\set ON_ERROR_STOP on

begin;

insert into auth.users (id, email, raw_user_meta_data)
values
  ('61000000-0000-0000-0000-000000000001', 'memory-box-owner@example.invalid', '{}'::jsonb),
  ('62000000-0000-0000-0000-000000000002', 'memory-box-other@example.invalid', '{}'::jsonb)
on conflict (id) do nothing;

do $$
declare
  v_owner constant uuid := '61000000-0000-0000-0000-000000000001';
  other_owner constant uuid := '62000000-0000-0000-0000-000000000002';
  result jsonb;
  gpt_result jsonb;
  gpt_source_id bigint;
  gpt_extra_id bigint;
  claude_id bigint;
  shared_id bigint;
  pending_shared_id bigint;
  source_revision_id bigint;
  source_revision_hash text;
  item jsonb;
  item_count integer;
  distinct_item_count integer;
begin
  result := public.memory_runtime_remember_gpt(
    v_owner, '61000000-0000-4000-8000-000000000001',
    '{"content":"GPT memory-box source revision one","memory_type":"reflection","tags":["box-v1"],"importance":4}'::jsonb
  );
  gpt_source_id := (result->'memory'->>'id')::bigint;

  result := public.memory_runtime_revise_gpt(
    v_owner, '61000000-0000-4000-8000-000000000002', gpt_source_id,
    '{"content":"GPT memory-box current revision two"}'::jsonb,
    'Bind Memory Box to the current effective revision'
  );
  select r.id, public.memory_compute_revision_hash(r.id)
    into strict source_revision_id, source_revision_hash
  from public.memory_revisions r
  where r.owner_id = v_owner
    and r.memory_id = gpt_source_id
    and r.revision_number = (result->'memory'->>'revision_number')::integer;

  result := public.memory_runtime_propose_shared_gpt(
    v_owner, '61000000-0000-4000-8000-000000000003', gpt_source_id,
    'Approved Shared source for Memory Box V1'
  );
  shared_id := (result->'memory'->>'id')::bigint;
  perform pg_catalog.set_config('request.jwt.claim.sub', v_owner::text, true);
  perform public.memory_owner_transition_shared(
    shared_id, 'approved', 'Approve exact private revision for Memory Box test'
  );

  result := public.memory_runtime_remember_gpt(
    v_owner, '61000000-0000-4000-8000-000000000004',
    '{"content":"GPT second private memory","memory_type":"fact","tags":["box-v1"]}'::jsonb
  );
  gpt_extra_id := (result->'memory'->>'id')::bigint;

  result := public.memory_runtime_remember_claude(
    v_owner, '61000000-0000-4000-8000-000000000005',
    '{"content":"Claude private Memory Box memory","memory_type":"memo","tags":["box-v1"]}'::jsonb
  );
  claude_id := (result->'memory'->>'id')::bigint;

  result := public.memory_runtime_propose_shared_gpt(
    v_owner, '61000000-0000-4000-8000-000000000006', gpt_extra_id,
    'Must remain an unapproved Shared candidate'
  );
  pending_shared_id := (result->'memory'->>'id')::bigint;

  insert into public.memory_entries (
    owner_id, space_key, memory_type, tags, content, source_type,
    original_table, original_id, original_created_at, legacy_source,
    created_by_actor
  ) values (
    v_owner, 'legacy_pending', 'memo', array['box-v1'],
    'legacy-memory-box-sentinel', 'legacy_import', 'brain', 'box-v1-legacy',
    '2026-01-01T00:00:00Z', 'memory-box-test', 'curator'
  );

  perform public.memory_runtime_remember_gpt(
    other_owner, '61000000-0000-4000-8000-000000000007',
    '{"content":"other-owner-memory-box-sentinel","memory_type":"fact"}'::jsonb
  );

  -- Limit defaults to three, and every result is already inside GPT's legal
  -- SQL candidate set before randomization.
  result := public.memory_runtime_memory_box_gpt(
    v_owner, '61000000-0000-4000-8000-000000000008', null
  );
  if result->>'ok' <> 'true' or result->>'actor' <> 'gpt'
    or result->>'mode' <> 'random_history'
    or pg_catalog.jsonb_array_length(result->'items') <> 3 then
    raise exception 'GPT Memory Box response contract/default limit failed: %', result;
  end if;
  gpt_result := result;
  if exists (
    select 1 from pg_catalog.jsonb_array_elements(result->'items') picked(value)
    where value->>'space_key' not in ('gpt', 'shared')
      or (value->>'space_key' = 'shared' and value->>'shared_status' <> 'approved')
      or (value->>'memory_id')::bigint in (claude_id, pending_shared_id)
      or value->>'content' in ('legacy-memory-box-sentinel', 'other-owner-memory-box-sentinel')
  ) then
    raise exception 'GPT Memory Box leaked an illegal candidate: %', result;
  end if;
  select count(*), count(distinct (value->>'memory_id')::bigint)
    into item_count, distinct_item_count
  from pg_catalog.jsonb_array_elements(result->'items') picked(value);
  if item_count <> distinct_item_count then
    raise exception 'Memory Box repeated a memory inside one draw';
  end if;

  -- Claude can see only Claude private plus approved Shared. A high requested
  -- limit is clamped to four but cannot manufacture additional candidates.
  result := public.memory_runtime_memory_box_claude(
    v_owner, '61000000-0000-4000-8000-000000000009', 99
  );
  if pg_catalog.jsonb_array_length(result->'items') <> 2 then
    raise exception 'Claude Memory Box legal-set count failed: %', result;
  end if;
  if exists (
    select 1 from pg_catalog.jsonb_array_elements(result->'items') picked(value)
    where value->>'space_key' not in ('claude', 'shared')
      or (value->>'space_key' = 'shared' and value->>'shared_status' <> 'approved')
  ) then
    raise exception 'Claude Memory Box leaked an illegal candidate: %', result;
  end if;

  -- Revision identity and content are emitted from one exact current revision.
  for item in
    select value from pg_catalog.jsonb_array_elements(gpt_result->'items') x(value)
    union all
    select value from pg_catalog.jsonb_array_elements(result->'items') x(value)
  loop
    if not exists (
      select 1
      from public.memory_entries e
      join public.memory_revisions r
        on r.owner_id = e.owner_id
        and r.memory_id = e.id
        and r.revision_number = e.revision_number
      where e.owner_id = v_owner
        and e.id = (item->>'memory_id')::bigint
        and r.id = (item->>'revision_id')::bigint
        and r.revision_number = (item->>'revision_number')::integer
        and public.memory_compute_revision_hash(r.id) = item->>'revision_hash'
        and r.content = item->>'content'
    ) then
      raise exception 'Memory Box returned a mixed or stale revision: %', item;
    end if;
  end loop;

  -- Approved Shared retains its exact private-revision source link and does
  -- not pretend the reviewing actor or the reader authored the source.
  select value into strict item
  from pg_catalog.jsonb_array_elements(result->'items') picked(value)
  where (value->>'memory_id')::bigint = shared_id;
  if (item#>>'{provenance,source_link,memory_id}')::bigint <> gpt_source_id
    or (item#>>'{provenance,source_link,revision_id}')::bigint <> source_revision_id
    or item#>>'{provenance,source_link,revision_hash}' <> source_revision_hash
    or item#>>'{provenance,source_link,space_key}' <> 'gpt'
    or item#>>'{provenance,perspective_actor}' <> 'claude'
    or pg_catalog.jsonb_array_length(item#>'{provenance,events}') < 2 then
    raise exception 'Shared provenance did not preserve the real source chain: %', item;
  end if;

  if not exists (
    select 1 from public.memory_audit_log
    where owner_id = v_owner and actor = 'gpt' and action = 'memory_box'
      and request_id = '61000000-0000-4000-8000-000000000008'
      and result = 'allowed' and result_count = 3
  ) or not exists (
    select 1 from public.memory_audit_log
    where owner_id = v_owner and actor = 'claude' and action = 'memory_box'
      and request_id = '61000000-0000-4000-8000-000000000009'
      and result = 'allowed' and result_count = 2
  ) then
    raise exception 'Memory Box read audit was not persisted';
  end if;

  -- The internal actor-bearing function is unreachable to API roles. Only
  -- fixed-actor wrappers are granted to service_role.
  if pg_catalog.has_function_privilege(
    'service_role',
    'public.memory_runtime_internal_memory_box(text,uuid,uuid,integer)',
    'EXECUTE'
  ) then
    raise exception 'service_role can invoke the actor-bearing internal door';
  end if;
  if not pg_catalog.has_function_privilege(
    'service_role',
    'public.memory_runtime_memory_box_gpt(uuid,uuid,integer)',
    'EXECUTE'
  ) or not pg_catalog.has_function_privilege(
    'service_role',
    'public.memory_runtime_memory_box_claude(uuid,uuid,integer)',
    'EXECUTE'
  ) then
    raise exception 'service_role is missing a fixed actor Memory Box door';
  end if;
  if pg_catalog.has_function_privilege(
    'anon', 'public.memory_runtime_memory_box_gpt(uuid,uuid,integer)', 'EXECUTE'
  ) or pg_catalog.has_function_privilege(
    'authenticated', 'public.memory_runtime_memory_box_claude(uuid,uuid,integer)', 'EXECUTE'
  ) then
    raise exception 'A client role can execute a Memory Box door';
  end if;
end;
$$;

rollback;
