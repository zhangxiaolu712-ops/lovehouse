begin;

create or replace function public.memory_v2_timeline(
  p_owner_id uuid,
  p_actor text,
  p_query text default '',
  p_limit integer default 60
)
returns table (
  memory_id uuid,
  revision_id uuid,
  revision_number integer,
  content text,
  event_time timestamptz,
  metadata jsonb,
  created_at timestamptz,
  revision_created_at timestamptz,
  space_key text,
  status text,
  source_count integer
)
language sql
security invoker
set search_path = pg_catalog, public
as $$
  select e.id, r.id, r.revision_number, r.content, r.event_time, r.metadata,
    e.created_at, r.created_at, e.space_key, e.status,
    (select count(*)::integer from public.memory_v2_revision_sources links where links.revision_id = r.id)
  from public.memory_v2_entries e
  join public.memory_v2_revisions r on r.id = e.current_revision_id
  where e.owner_id = p_owner_id
    and e.space_key = p_actor
    and p_actor in ('gpt', 'claude')
    and e.status = 'active'
    and e.superseded_by_id is null
    and (btrim(coalesce(p_query, '')) = '' or r.content ilike '%' || btrim(p_query) || '%'
      or r.metadata::text ilike '%' || btrim(p_query) || '%')
  order by coalesce(r.event_time, e.created_at) desc, e.created_at desc
  limit least(greatest(coalesce(p_limit, 60), 1), 100)
$$;

create or replace function public.memory_v2_archive(
  p_owner_id uuid,
  p_actor text,
  p_memory_id uuid
)
returns jsonb
language plpgsql
security invoker
set search_path = pg_catalog, public
as $$
declare
  archived public.memory_v2_entries%rowtype;
begin
  if p_actor not in ('gpt', 'claude') then
    raise exception 'invalid memory actor' using errcode = '22023';
  end if;
  update public.memory_v2_entries
  set status = 'archived', updated_at = now()
  where id = p_memory_id and owner_id = p_owner_id and space_key = p_actor
    and status = 'active' and superseded_by_id is null
  returning * into archived;
  if not found then
    raise exception 'active memory is unavailable' using errcode = '42501';
  end if;
  return jsonb_build_object('memory_id', archived.id, 'revision_id', archived.current_revision_id, 'status', archived.status);
end;
$$;

revoke all on function public.memory_v2_timeline(uuid, text, text, integer) from public, anon, authenticated;
revoke all on function public.memory_v2_archive(uuid, text, uuid) from public, anon, authenticated;
grant execute on function public.memory_v2_timeline(uuid, text, text, integer) to service_role;
grant execute on function public.memory_v2_archive(uuid, text, uuid) to service_role;

create table public.engineering_project_checklist_items (
  owner_id uuid not null references auth.users(id) on delete restrict,
  item_key text not null check (length(item_key) between 1 and 160),
  section_index integer not null check (section_index between 0 and 200),
  is_custom boolean not null default false,
  item_text text check (item_text is null or length(btrim(item_text)) between 1 and 1000),
  status text not null check (status in ('done', 'partial', 'todo', 'idea', 'risk')),
  note text not null default '' check (length(note) <= 10000),
  completed_at date,
  updated_at timestamptz not null default now(),
  primary key (owner_id, item_key),
  constraint engineering_project_checklist_custom_text check (not is_custom or item_text is not null)
);

create table public.engineering_project_checklist_state (
  owner_id uuid primary key references auth.users(id) on delete restrict,
  local_v1_migrated_at timestamptz,
  updated_at timestamptz not null default now()
);

alter table public.engineering_project_checklist_items enable row level security;
alter table public.engineering_project_checklist_items force row level security;
alter table public.engineering_project_checklist_state enable row level security;
alter table public.engineering_project_checklist_state force row level security;
revoke all on table public.engineering_project_checklist_items from public, anon, authenticated;
revoke all on table public.engineering_project_checklist_state from public, anon, authenticated;
grant select, insert, update, delete on table public.engineering_project_checklist_items to service_role;
grant select, insert, update on table public.engineering_project_checklist_state to service_role;

create or replace function public.engineering_project_checklist_load(p_owner_id uuid)
returns jsonb
language sql
security invoker
set search_path = pg_catalog, public
as $$
  select jsonb_build_object(
    'local_v1_migrated', coalesce((select local_v1_migrated_at is not null from public.engineering_project_checklist_state where owner_id = p_owner_id), false),
    'items', coalesce((select jsonb_agg(jsonb_build_object(
      'id', item_key, 'sectionIndex', section_index, 'custom', is_custom, 'text', item_text,
      'status', status, 'note', note, 'completedAt', coalesce(to_char(completed_at, 'YYYY-MM-DD'), '')
    ) order by updated_at, item_key) from public.engineering_project_checklist_items where owner_id = p_owner_id), '[]'::jsonb)
  )
$$;

create or replace function public.engineering_project_checklist_save(p_owner_id uuid, p_item jsonb)
returns jsonb
language plpgsql
security invoker
set search_path = pg_catalog, public
as $$
declare saved public.engineering_project_checklist_items%rowtype;
begin
  insert into public.engineering_project_checklist_items(owner_id, item_key, section_index, is_custom, item_text, status, note, completed_at)
  values (p_owner_id, p_item->>'id', (p_item->>'sectionIndex')::integer, coalesce((p_item->>'custom')::boolean, false),
    nullif(btrim(p_item->>'text'), ''), p_item->>'status', coalesce(p_item->>'note', ''), nullif(p_item->>'completedAt', '')::date)
  on conflict (owner_id, item_key) do update set
    section_index = excluded.section_index, is_custom = excluded.is_custom, item_text = excluded.item_text,
    status = excluded.status, note = excluded.note, completed_at = excluded.completed_at, updated_at = now()
  returning * into saved;
  return jsonb_build_object('id', saved.item_key, 'status', saved.status, 'updated_at', saved.updated_at);
end;
$$;

create or replace function public.engineering_project_checklist_delete(p_owner_id uuid, p_item_key text)
returns boolean
language plpgsql
security invoker
set search_path = pg_catalog, public
as $$
begin
  delete from public.engineering_project_checklist_items
  where owner_id = p_owner_id and item_key = p_item_key and is_custom = true;
  return found;
end;
$$;

create or replace function public.engineering_project_checklist_migrate_local_v1(p_owner_id uuid, p_items jsonb)
returns jsonb
language plpgsql
security invoker
set search_path = pg_catalog, public
as $$
declare item jsonb; migrated integer := 0;
begin
  insert into public.engineering_project_checklist_state(owner_id) values (p_owner_id)
  on conflict (owner_id) do nothing;
  if (select local_v1_migrated_at is not null from public.engineering_project_checklist_state where owner_id = p_owner_id for update) then
    return jsonb_build_object('migrated', false, 'reason', 'already_migrated', 'count', 0);
  end if;
  if jsonb_typeof(coalesce(p_items, '[]'::jsonb)) <> 'array' or jsonb_array_length(coalesce(p_items, '[]'::jsonb)) > 2000 then
    raise exception 'p_items must be a bounded array' using errcode = '22023';
  end if;
  for item in select value from jsonb_array_elements(coalesce(p_items, '[]'::jsonb)) loop
    perform public.engineering_project_checklist_save(p_owner_id, item);
    migrated := migrated + 1;
  end loop;
  update public.engineering_project_checklist_state set local_v1_migrated_at = now(), updated_at = now() where owner_id = p_owner_id;
  return jsonb_build_object('migrated', true, 'count', migrated);
end;
$$;

revoke all on function public.engineering_project_checklist_load(uuid) from public, anon, authenticated;
revoke all on function public.engineering_project_checklist_save(uuid, jsonb) from public, anon, authenticated;
revoke all on function public.engineering_project_checklist_delete(uuid, text) from public, anon, authenticated;
revoke all on function public.engineering_project_checklist_migrate_local_v1(uuid, jsonb) from public, anon, authenticated;
grant execute on function public.engineering_project_checklist_load(uuid) to service_role;
grant execute on function public.engineering_project_checklist_save(uuid, jsonb) to service_role;
grant execute on function public.engineering_project_checklist_delete(uuid, text) to service_role;
grant execute on function public.engineering_project_checklist_migrate_local_v1(uuid, jsonb) to service_role;

comment on table public.engineering_project_checklist_items is 'Engineering-domain canonical state for the B612 project checklist.';
comment on table public.engineering_project_checklist_state is 'Engineering-domain migration state; localStorage v1 imports at most once per owner.';

commit;
