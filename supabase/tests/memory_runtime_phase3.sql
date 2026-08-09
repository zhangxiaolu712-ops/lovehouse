\set ON_ERROR_STOP on

begin;

insert into auth.users (id, email, raw_user_meta_data)
values
  ('10000000-0000-0000-0000-000000000001', 'runtime-owner@example.invalid', '{}'::jsonb),
  ('20000000-0000-0000-0000-000000000002', 'runtime-other@example.invalid', '{}'::jsonb)
on conflict (id) do nothing;

create or replace function public.memory_runtime_test_reject_audit()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if new.action = 'remember'
    and new.request_id = '30000000-0000-4000-8000-000000000013'::uuid
  then
    raise exception 'forced audit failure';
  end if;
  return new;
end;
$$;

create trigger memory_runtime_test_reject_audit
  before insert on public.memory_audit_log
  for each row execute function public.memory_runtime_test_reject_audit();

do $$
declare
  v_owner_id constant uuid := '10000000-0000-0000-0000-000000000001';
  gpt_request constant uuid := '30000000-0000-4000-8000-000000000001';
  claude_request constant uuid := '30000000-0000-4000-8000-000000000002';
  result jsonb;
  gpt_id bigint;
  claude_id bigint;
  gpt_revision_1 bigint;
  gpt_revision_2 bigint;
  candidate_id bigint;
  legacy_id bigint;
  before_count bigint;
  after_count bigint;
  rejected boolean;
begin
  result := public.memory_runtime_remember_gpt(
    v_owner_id,
    gpt_request,
    '{"content":"runtime rose memory","memory_type":"diary","tags":["rose","runtime"],"importance":4,"retention":"long","author":"gpt"}'::jsonb
  );
  if not (result->>'ok')::boolean or (result->>'replayed')::boolean then
    raise exception 'GPT remember did not create a new memory';
  end if;
  gpt_id := (result->'memory'->>'id')::bigint;

  result := public.memory_runtime_remember_claude(
    v_owner_id,
    claude_request,
    '{"content":"runtime rose memory for Claude","memory_type":"diary","tags":["rose"],"importance":3,"author":"claude"}'::jsonb
  );
  claude_id := (result->'memory'->>'id')::bigint;

  if not exists (
    select 1 from public.memory_entries
    where id = gpt_id and owner_id = v_owner_id and space_key = 'gpt'
      and created_by_actor = 'gpt' and source_type = 'mcp_runtime' and author = 'gpt'
  ) or not exists (
    select 1 from public.memory_entries
    where id = claude_id and owner_id = v_owner_id and space_key = 'claude'
      and created_by_actor = 'claude' and source_type = 'mcp_runtime' and author = 'claude'
  ) then
    raise exception 'Fixed actor private write boundary failed';
  end if;

  select id into strict gpt_revision_1
  from public.memory_revisions
  where memory_id = gpt_id and revision_number = 1;
  if not exists (
    select 1 from public.memory_provenance
    where memory_id = gpt_id and event_type = 'created' and actor = 'gpt'
  ) then
    raise exception 'Remember provenance was not persisted';
  end if;
  if not exists (
    select 1 from public.memory_audit_log audit
    where audit.memory_id = gpt_id and audit.actor = 'gpt' and audit.action = 'remember'
      and audit.request_id = gpt_request and audit.result = 'allowed'
  ) then
    raise exception 'Remember audit was not persisted';
  end if;

  -- Same trusted request and same normalized payload safely replays.
  result := public.memory_runtime_remember_gpt(
    v_owner_id,
    gpt_request,
    '{"tags":["rose","runtime"],"retention":"long","importance":4,"memory_type":"diary","content":"runtime rose memory","author":"gpt"}'::jsonb
  );
  if not (result->>'replayed')::boolean
    or (result->'memory'->>'id')::bigint <> gpt_id
  then
    raise exception 'Remember idempotent replay failed';
  end if;
  if (select count(*) from public.memory_entries where id = gpt_id) <> 1
    or (select count(*) from public.memory_revisions where memory_id = gpt_id) <> 1
  then
    raise exception 'Remember replay duplicated memory history';
  end if;

  rejected := false;
  begin
    perform public.memory_runtime_remember_gpt(
      v_owner_id,
      gpt_request,
      '{"content":"different payload"}'::jsonb
    );
  exception when unique_violation then
    rejected := true;
  end;
  if not rejected then
    raise exception 'Same request id with a different payload did not conflict';
  end if;

  -- Fixed read doors deny the other private space and persist the denial.
  result := public.memory_runtime_get_gpt(
    v_owner_id, '30000000-0000-4000-8000-000000000003', claude_id
  );
  if (result->>'error_code') <> 'MEMORY_ACCESS_DENIED'
    or not (result->>'audit_persisted')::boolean
  then
    raise exception 'GPT crossed into Claude private memory';
  end if;
  result := public.memory_runtime_get_claude(
    v_owner_id, '30000000-0000-4000-8000-000000000004', gpt_id
  );
  if (result->>'error_code') <> 'MEMORY_ACCESS_DENIED' then
    raise exception 'Claude crossed into GPT private memory';
  end if;

  -- Revision is a real versioned mutation with provenance/audit in one RPC.
  result := public.memory_runtime_revise_gpt(
    v_owner_id,
    '30000000-0000-4000-8000-000000000005',
    gpt_id,
    '{"content":"runtime rose memory revised","importance":5}'::jsonb,
    'Clarify the durable wording'
  );
  if (result->'memory'->>'revision_number')::integer <> 2 then
    raise exception 'Private revision did not advance';
  end if;
  select id into strict gpt_revision_2
  from public.memory_revisions
  where memory_id = gpt_id and revision_number = 2;
  if gpt_revision_1 = gpt_revision_2 or not exists (
    select 1 from public.memory_provenance
    where memory_id = gpt_id and event_type = 'revised'
      and reason = 'Clarify the durable wording'
  ) or not exists (
    select 1 from public.memory_audit_log audit
    where audit.memory_id = gpt_id and audit.action = 'revise' and audit.result = 'allowed'
  ) then
    raise exception 'Revision history, provenance or audit is incomplete';
  end if;
  if not exists (
    select 1 from public.memory_entries
    where id = gpt_id and memory_type = 'diary' and author = 'gpt'
  ) or not exists (
    select 1 from public.memory_revisions
    where id = gpt_revision_2 and memory_id = gpt_id and author = 'gpt'
  ) then
    raise exception 'Diary revision did not inherit its fixed actor author';
  end if;

  -- Cross-private revise fails without creating a revision.
  before_count := (select count(*) from public.memory_revisions where memory_id = claude_id);
  result := public.memory_runtime_revise_gpt(
    v_owner_id,
    '30000000-0000-4000-8000-000000000006',
    claude_id,
    '{"content":"forged cross-space revision"}'::jsonb,
    'Must fail'
  );
  after_count := (select count(*) from public.memory_revisions where memory_id = claude_id);
  if (result->>'error_code') <> 'MEMORY_ACCESS_DENIED' or before_count <> after_count then
    raise exception 'Cross-private revision was not fail closed';
  end if;

  -- Curator proposal resolves and freezes the current exact revision internally.
  result := public.memory_runtime_propose_shared_gpt(
    v_owner_id,
    '30000000-0000-4000-8000-000000000007',
    gpt_id,
    'Useful for both assistants'
  );
  candidate_id := (result->'memory'->>'id')::bigint;
  if not exists (
    select 1 from public.memory_entries
    where id = candidate_id and space_key = 'shared' and shared_status = 'candidate'
      and source_memory_id = gpt_id and source_revision_id = gpt_revision_2
      and source_revision_hash = public.memory_compute_revision_hash(gpt_revision_2)
      and content = 'runtime rose memory revised' and author = 'gpt'
  ) then
    raise exception 'Shared candidate was not bound to the exact current revision';
  end if;

  perform pg_catalog.set_config('request.jwt.claim.sub', v_owner_id::text, true);
  perform public.memory_owner_transition_shared(
    candidate_id, 'approved', 'Owner approves the exact snapshot'
  );
  result := public.memory_runtime_get_claude(
    v_owner_id, '30000000-0000-4000-8000-000000000008', candidate_id
  );
  if (result->'memory'->>'id')::bigint <> candidate_id
    or result->'memory'->>'author' <> 'gpt'
  then
    raise exception 'Approved Shared was not readable by Claude with its source author';
  end if;

  -- A later private revision never drifts the approved Shared snapshot.
  perform public.memory_runtime_revise_gpt(
    v_owner_id,
    '30000000-0000-4000-8000-000000000009',
    gpt_id,
    '{"content":"runtime rose memory revision three"}'::jsonb,
    'New private wording after sharing'
  );
  if not exists (
    select 1 from public.memory_entries
    where id = candidate_id and source_revision_id = gpt_revision_2
      and source_revision_hash = public.memory_compute_revision_hash(gpt_revision_2)
      and content = 'runtime rose memory revised' and author = 'gpt'
  ) then
    raise exception 'Approved Shared snapshot drifted with its private source';
  end if;

  -- Legacy SQL-level isolation: sentinel never affects content, count or order.
  insert into public.memory_entries (
    owner_id, space_key, memory_type, tags, content, source_type, source_model,
    original_table, original_id, original_created_at, legacy_source,
    created_by_actor
  ) values (
    v_owner_id, 'legacy_pending', 'memo', array['runtime'],
    'legacy-only-orchid runtime rose memory', 'legacy_import', 'legacy',
    'brain', 'phase3-runtime-legacy', '2026-01-01T00:00:00Z', 'phase2-test',
    'curator'
  ) returning id into legacy_id;

  result := public.memory_runtime_recall_gpt(
    v_owner_id, '30000000-0000-4000-8000-000000000010',
    'legacy-only-orchid', 10, null, '{}'::text[]
  );
  if pg_catalog.jsonb_array_length(result->'items') <> 0 then
    raise exception 'Legacy sentinel leaked into normal recall';
  end if;
  result := public.memory_runtime_recall_gpt(
    v_owner_id, '30000000-0000-4000-8000-000000000011',
    'runtime rose memory', 999, null, '{}'::text[]
  );
  if pg_catalog.jsonb_array_length(result->'items') > 10
    or exists (
      select 1 from pg_catalog.jsonb_array_elements(result->'items') as items(item)
      where (item->>'id')::bigint = legacy_id
    )
  then
    raise exception 'Recall hard limit or Legacy SQL isolation failed';
  end if;

  result := public.memory_runtime_list_gpt(
    v_owner_id, '30000000-0000-4000-8000-000000000012',
    999, null, null, '{}'::text[], null
  );
  if pg_catalog.jsonb_array_length(result->'items') > 50
    or exists (
      select 1 from pg_catalog.jsonb_array_elements(result->'items') as items(item)
      where (item->>'id')::bigint = legacy_id
    )
  then
    raise exception 'List hard limit or Legacy SQL isolation failed';
  end if;

  -- If persistent audit cannot be inserted, remember/history/idempotency roll back.
  rejected := false;
  begin
    perform public.memory_runtime_remember_gpt(
      v_owner_id,
      '30000000-0000-4000-8000-000000000013',
      '{"content":"must rollback with audit"}'::jsonb
    );
  exception when others then
    rejected := true;
  end;
  if not rejected
    or exists (select 1 from public.memory_entries where content = 'must rollback with audit')
    or exists (
      select 1 from public.memory_mutation_idempotency
      where request_id = '30000000-0000-4000-8000-000000000013'
    )
  then
    raise exception 'Audit failure did not roll back the complete mutation';
  end if;
end;
$$;

drop trigger memory_runtime_test_reject_audit on public.memory_audit_log;
drop function public.memory_runtime_test_reject_audit();

do $$
begin
  if has_table_privilege('service_role', 'public.memory_entries', 'SELECT')
    or has_table_privilege('service_role', 'public.memory_entries', 'INSERT')
    or has_table_privilege('service_role', 'public.memory_audit_log', 'INSERT')
  then
    raise exception 'Bridge service role still has a raw canonical table path';
  end if;
  if has_function_privilege('service_role', 'public.memory_get_gpt(uuid,bigint)', 'EXECUTE')
    or has_function_privilege('service_role', 'public.memory_claim_idempotency(uuid,text,text,uuid,jsonb)', 'EXECUTE')
    or has_function_privilege('service_role', 'public.memory_curator_create_shared_candidate(uuid,bigint,bigint,text)', 'EXECUTE')
  then
    raise exception 'Bridge service role still has a Phase 2 bypass RPC';
  end if;
  if not has_function_privilege('service_role', 'public.memory_runtime_remember_gpt(uuid,uuid,jsonb)', 'EXECUTE')
    or not has_function_privilege('service_role', 'public.memory_runtime_remember_claude(uuid,uuid,jsonb)', 'EXECUTE')
    or has_function_privilege('authenticated', 'public.memory_runtime_remember_gpt(uuid,uuid,jsonb)', 'EXECUTE')
    or has_function_privilege('anon', 'public.memory_runtime_remember_claude(uuid,uuid,jsonb)', 'EXECUTE')
  then
    raise exception 'Fixed actor Runtime RPC privilege boundary is incorrect';
  end if;
  if has_function_privilege(
    'service_role',
    'public.memory_runtime_internal_remember(text,uuid,uuid,jsonb)',
    'EXECUTE'
  ) then
    raise exception 'Service role can bypass the fixed actor wrapper';
  end if;
  if not has_function_privilege(
    'authenticated',
    'public.memory_owner_transition_shared(bigint,text,text)',
    'EXECUTE'
  ) or has_function_privilege(
    'service_role',
    'public.memory_owner_transition_shared(bigint,text,text)',
    'EXECUTE'
  ) then
    raise exception 'Owner decision door was weakened by Phase 3';
  end if;
end;
$$;

rollback;
