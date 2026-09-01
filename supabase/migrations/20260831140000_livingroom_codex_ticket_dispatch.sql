create table public.livingroom_tasks (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references auth.users(id) on delete cascade,
  source_message_id bigint not null unique references public.livingroom(id) on delete restrict,
  thread_id uuid not null unique default gen_random_uuid(),
  target_agent text not null,
  target_runtime text not null,
  target_endpoint text not null,
  request_summary text not null check (char_length(request_summary) <= 1000),
  status text not null default 'queued'
    check (status in ('queued', 'running', 'waiting_approval', 'requires_local_user', 'completed', 'failed')),
  runtime_session_id text,
  final_result_summary text check (final_result_summary is null or char_length(final_result_summary) <= 1000),
  last_error_summary text check (last_error_summary is null or char_length(last_error_summary) <= 1000),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  completed_at timestamptz
);

create table public.livingroom_approvals (
  id uuid primary key default gen_random_uuid(),
  owner_id uuid not null references auth.users(id) on delete cascade,
  task_id uuid not null references public.livingroom_tasks(id) on delete cascade,
  request_summary text not null check (char_length(request_summary) <= 1000),
  risk_level text not null check (risk_level in ('low', 'medium', 'high')),
  action_summary text not null check (char_length(action_summary) between 1 and 160),
  impact_summary text not null check (char_length(impact_summary) between 1 and 160),
  status text not null default 'pending'
    check (status in ('pending', 'approved', 'rejected', 'expired')),
  expires_at timestamptz not null default (now() + interval '24 hours'),
  decided_at timestamptz,
  created_at timestamptz not null default now()
);

create unique index livingroom_approvals_one_pending
  on public.livingroom_approvals(task_id) where status = 'pending';
create index livingroom_tasks_dispatch
  on public.livingroom_tasks(target_agent, target_runtime, target_endpoint, status, created_at);

alter table public.livingroom_tasks enable row level security;
alter table public.livingroom_approvals enable row level security;

create policy livingroom_tasks_owner_read on public.livingroom_tasks
  for select to authenticated using ((select auth.uid()) = owner_id);
create policy livingroom_approvals_owner_read on public.livingroom_approvals
  for select to authenticated using ((select auth.uid()) = owner_id);

revoke all on public.livingroom_tasks, public.livingroom_approvals from anon, authenticated;
grant select on public.livingroom_tasks, public.livingroom_approvals to authenticated;
grant select, insert, update, delete on public.livingroom_tasks, public.livingroom_approvals to service_role;

create or replace function public.livingroom_request_approval(
  p_owner_id uuid,
  p_task_id uuid,
  p_request_summary text,
  p_runtime_session_id text,
  p_expires_at timestamptz default null,
  p_risk_level text default 'high',
  p_action_summary text default '需要审批',
  p_impact_summary text default '影响当前任务'
) returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
  requested public.livingroom_approvals%rowtype;
begin
  if p_request_summary is null or btrim(p_request_summary) = '' or char_length(p_request_summary) > 1000 then
    raise exception 'invalid approval request' using errcode = '22023';
  end if;
  if p_expires_at is not null and p_expires_at <= now() then
    raise exception 'approval expiry must be in the future' using errcode = '22023';
  end if;
  if p_risk_level not in ('low', 'medium', 'high') then p_risk_level := 'high'; end if;

  update public.livingroom_tasks
     set status = 'waiting_approval', runtime_session_id = p_runtime_session_id, updated_at = now()
   where id = p_task_id and owner_id = p_owner_id and status = 'running';
  if not found then raise exception 'task is not running' using errcode = 'P0001'; end if;

  insert into public.livingroom_approvals(owner_id, task_id, request_summary, risk_level, action_summary, impact_summary, expires_at)
  values (p_owner_id, p_task_id, btrim(p_request_summary), p_risk_level,
    left(coalesce(nullif(btrim(p_action_summary), ''), '需要审批'), 160),
    left(coalesce(nullif(btrim(p_impact_summary), ''), '影响当前任务'), 160),
    coalesce(p_expires_at, now() + interval '24 hours'))
  returning * into requested;
  return to_jsonb(requested);
end;
$$;

revoke all on function public.livingroom_request_approval(uuid, uuid, text, text, timestamptz, text, text, text) from public, anon, authenticated;
grant execute on function public.livingroom_request_approval(uuid, uuid, text, text, timestamptz, text, text, text) to service_role;

create or replace function public.livingroom_require_local_user(
  p_owner_id uuid, p_task_id uuid, p_request_summary text, p_runtime_session_id text
) returns jsonb language plpgsql security definer set search_path = pg_catalog, public as $$
declare changed public.livingroom_tasks%rowtype;
begin
  update public.livingroom_tasks set status = 'requires_local_user',
    runtime_session_id = p_runtime_session_id, updated_at = now()
  where id = p_task_id and owner_id = p_owner_id and status = 'running'
  returning * into changed;
  if changed.id is null then raise exception 'task is not running' using errcode = 'P0001'; end if;
  return to_jsonb(changed);
end; $$;
revoke all on function public.livingroom_require_local_user(uuid, uuid, text, text) from public, anon, authenticated;
grant execute on function public.livingroom_require_local_user(uuid, uuid, text, text) to service_role;

create or replace function public.livingroom_resume_local_user(
  p_owner_id uuid, p_task_id uuid
) returns jsonb language plpgsql security definer set search_path = pg_catalog, public as $$
declare changed public.livingroom_tasks%rowtype;
begin
  update public.livingroom_tasks set status = 'queued', updated_at = now()
  where id = p_task_id and owner_id = p_owner_id and status = 'requires_local_user'
  returning * into changed;
  return case when changed.id is null then null else to_jsonb(changed) end;
end; $$;
revoke all on function public.livingroom_resume_local_user(uuid, uuid) from public, anon, authenticated;
grant execute on function public.livingroom_resume_local_user(uuid, uuid) to service_role;

create or replace function public.livingroom_decide_approval(
  p_owner_id uuid,
  p_approval_id uuid,
  p_decision text
) returns jsonb
language plpgsql
security definer
set search_path = pg_catalog, public
as $$
declare
  decided public.livingroom_approvals%rowtype;
  next_task_status text;
begin
  if p_decision not in ('approved', 'rejected', 'expired') then
    raise exception 'invalid approval decision' using errcode = '22023';
  end if;

  if p_decision = 'approved' then
    update public.livingroom_approvals
       set status = 'approved', decided_at = now()
     where id = p_approval_id and owner_id = p_owner_id and status = 'pending'
       and (expires_at is null or expires_at > now())
     returning * into decided;
    if decided.id is null then
      update public.livingroom_approvals
         set status = 'expired', decided_at = now()
       where id = p_approval_id and owner_id = p_owner_id and status = 'pending'
         and expires_at <= now()
       returning * into decided;
      if decided.id is not null then
        update public.livingroom_tasks
           set status = 'failed', last_error_summary = 'approval expired', updated_at = now()
         where id = decided.task_id and owner_id = p_owner_id and status = 'waiting_approval';
      end if;
      return null;
    end if;
    next_task_status := 'queued';
  else
    update public.livingroom_approvals
       set status = p_decision, decided_at = now()
     where id = p_approval_id and owner_id = p_owner_id and status = 'pending'
     returning * into decided;
    if decided.id is null then return null; end if;
    next_task_status := 'failed';
  end if;

  update public.livingroom_tasks
     set status = next_task_status,
         last_error_summary = case when next_task_status = 'queued' then null else 'approval ' || p_decision end,
         updated_at = now()
   where id = decided.task_id and owner_id = p_owner_id and status = 'waiting_approval';
  if not found then raise exception 'task is not waiting for approval' using errcode = 'P0001'; end if;
  return to_jsonb(decided);
end;
$$;

revoke all on function public.livingroom_decide_approval(uuid, uuid, text) from public, anon, authenticated;
grant execute on function public.livingroom_decide_approval(uuid, uuid, text) to service_role;
