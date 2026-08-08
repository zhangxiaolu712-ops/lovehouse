\set ON_ERROR_STOP on

begin;

insert into auth.users (id, email, raw_user_meta_data)
values
  ('41000000-0000-0000-0000-000000000001', 'phase4a-owner@example.invalid', '{}'::jsonb),
  ('42000000-0000-0000-0000-000000000002', 'phase4a-other@example.invalid', '{}'::jsonb)
on conflict (id) do nothing;

do $$
declare
  v_owner constant uuid := '41000000-0000-0000-0000-000000000001';
  gpt_id bigint;
  gpt_second_id bigint;
  claude_id bigint;
  shared_id bigint;
  legacy_id bigint;
  old_revision_id bigint;
  new_revision_id bigint;
  embedding_id bigint;
  claimed jsonb;
  result jsonb;
  vector_a real[] := array[1::real] || pg_catalog.array_fill(0::real, array[1535]);
  vector_b real[] := array[0::real, 1::real] || pg_catalog.array_fill(0::real, array[1534]);
  item jsonb;
  rejected boolean;
begin
  result := public.memory_runtime_remember_gpt(
    v_owner, '41000000-0000-4000-8000-000000000001',
    '{"content":"private north star","memory_type":"feeling","tags":["phase4a"],"importance":5}'::jsonb
  );
  gpt_id := (result->'memory'->>'id')::bigint;
  result := public.memory_runtime_remember_gpt(
    v_owner, '41000000-0000-4000-8000-000000000002',
    '{"content":"private quiet garden","memory_type":"diary","tags":["phase4a"],"importance":1}'::jsonb
  );
  gpt_second_id := (result->'memory'->>'id')::bigint;
  result := public.memory_runtime_remember_claude(
    v_owner, '41000000-0000-4000-8000-000000000003',
    '{"content":"claude hidden observatory","memory_type":"memo","tags":["phase4a"],"importance":5}'::jsonb
  );
  claude_id := (result->'memory'->>'id')::bigint;

  select id into strict old_revision_id from public.memory_revisions
  where memory_id = gpt_id and revision_number = 1;
  if not exists (
    select 1 from public.memory_embeddings
    where memory_id = gpt_id and revision_id = old_revision_id and status = 'pending'
  ) or not exists (
    select 1 from public.memory_embeddings
    where memory_id = claude_id and status = 'pending'
  ) then
    raise exception 'Private current revisions were not queued';
  end if;

  insert into public.memory_entries (
    owner_id, space_key, memory_type, tags, content, source_type,
    original_table, original_id, original_created_at, legacy_source, created_by_actor
  ) values (
    v_owner, 'legacy_pending', 'memo', array['phase4a'],
    'legacy-semantic-sentinel', 'legacy_import', 'brain', 'phase4a-legacy',
    '2026-01-01T00:00:00Z', 'phase4a-test', 'curator'
  ) returning id into legacy_id;
  if exists (select 1 from public.memory_embeddings where memory_id = legacy_id) then
    raise exception 'Legacy Pending entered the embedding lifecycle';
  end if;

  result := public.memory_runtime_propose_shared_gpt(
    v_owner, '41000000-0000-4000-8000-000000000004', gpt_id, 'phase4a candidate'
  );
  shared_id := (result->'memory'->>'id')::bigint;
  if exists (select 1 from public.memory_embeddings where memory_id = shared_id) then
    raise exception 'Unapproved Shared entered the embedding lifecycle';
  end if;
  perform pg_catalog.set_config('request.jwt.claim.sub', v_owner::text, true);
  perform public.memory_owner_transition_shared(shared_id, 'approved', 'approve stable snapshot');
  if not exists (
    select 1 from public.memory_embeddings where memory_id = shared_id and status = 'pending'
  ) then
    raise exception 'Approved Shared was not queued';
  end if;

  -- GPT claims only GPT private plus approved Shared, never Claude or Legacy.
  claimed := public.memory_behavior_claim_embeddings_gpt(
    v_owner, '41000000-0000-4000-8000-000000000005', 99
  );
  if pg_catalog.jsonb_array_length(claimed->'items') > 8
    or exists (
      select 1 from pg_catalog.jsonb_array_elements(claimed->'items') x(value)
      where (value->>'memory_id')::bigint in (claude_id, legacy_id)
    )
  then
    raise exception 'Embedding claim limit or fixed actor isolation failed';
  end if;
  for item in select value from pg_catalog.jsonb_array_elements(claimed->'items') x(value)
  loop
    perform public.memory_behavior_complete_embedding_gpt(
      v_owner, extensions.gen_random_uuid(), (item->>'id')::bigint,
      vector_a
    );
  end loop;

  claimed := public.memory_behavior_claim_embeddings_claude(
    v_owner, '41000000-0000-4000-8000-000000000006', 8
  );
  if not exists (
    select 1 from pg_catalog.jsonb_array_elements(claimed->'items') x(value)
    where (value->>'memory_id')::bigint = claude_id
  ) or exists (
    select 1 from pg_catalog.jsonb_array_elements(claimed->'items') x(value)
    where (value->>'memory_id')::bigint in (gpt_id, gpt_second_id, legacy_id)
  ) then
    raise exception 'Claude embedding claim crossed the private boundary';
  end if;
  for item in select value from pg_catalog.jsonb_array_elements(claimed->'items') x(value)
  loop
    perform public.memory_behavior_complete_embedding_claude(
      v_owner, extensions.gen_random_uuid(), (item->>'id')::bigint, vector_a
    );
  end loop;

  -- A semantic-only query finds GPT private and approved Shared, while Claude
  -- private and Legacy never enter the SQL eligible relation.
  result := public.memory_behavior_recall_gpt(
    v_owner, '41000000-0000-4000-8000-000000000007',
    'words absent from every memory', vector_a, 'ranking_v1', 10, null, array['phase4a']
  );
  if not exists (
    select 1 from pg_catalog.jsonb_array_elements(result->'items') x(value)
    where (value->>'id')::bigint = gpt_id
  ) or exists (
    select 1 from pg_catalog.jsonb_array_elements(result->'items') x(value)
    where (value->>'id')::bigint in (claude_id, legacy_id)
  ) then
    raise exception 'Hybrid recall private/Shared/Legacy isolation failed';
  end if;
  if (result->'items'->0->>'id')::bigint = gpt_second_id then
    raise exception 'Versioned ranking did not honor importance for equal semantic relevance';
  end if;
  if not exists (
    select 1 from public.memory_audit_log
    where request_id = '41000000-0000-4000-8000-000000000007'
      and action = 'hybrid_recall'
      and metadata->>'ranking_profile' = 'ranking_v1'
      and metadata->>'mode' = 'hybrid'
  ) then
    raise exception 'Hybrid ranking version was not persisted in audit';
  end if;

  -- Exact revision lifecycle: revising queues a new vector and the old ready
  -- vector cannot satisfy current-revision hybrid recall.
  perform public.memory_runtime_revise_gpt(
    v_owner, '41000000-0000-4000-8000-000000000008', gpt_id,
    '{"content":"private north star revised"}'::jsonb, 'phase4a exact revision test'
  );
  select id into strict new_revision_id from public.memory_revisions
  where memory_id = gpt_id and revision_number = 2;
  if new_revision_id = old_revision_id or not exists (
    select 1 from public.memory_embeddings
    where memory_id = gpt_id and revision_id = new_revision_id and status = 'pending'
  ) then
    raise exception 'New revision did not receive its own pending embedding';
  end if;
  result := public.memory_behavior_recall_gpt(
    v_owner, '41000000-0000-4000-8000-000000000009',
    'semantic only again', vector_a, 'ranking_v1', 10, null, array['phase4a']
  );
  if exists (
    select 1 from pg_catalog.jsonb_array_elements(result->'items') x(value)
    where (value->>'id')::bigint = gpt_id
  ) then
    raise exception 'Old revision embedding leaked into current recall';
  end if;

  -- Profiles are append-only experimental versions, not mutable constants.
  rejected := false;
  begin
    update public.memory_ranking_profiles set importance_weight = 0.17
    where profile_key = 'ranking_v1';
  exception when object_not_in_prerequisite_state then
    rejected := true;
  end;
  if not rejected then
    raise exception 'ranking_v1 was mutable';
  end if;
end;
$$;

do $$
begin
  if has_table_privilege('service_role', 'public.memory_embeddings', 'SELECT')
    or has_table_privilege('service_role', 'public.memory_ranking_profiles', 'SELECT')
    or has_table_privilege('authenticated', 'public.memory_embeddings', 'SELECT')
  then
    raise exception 'Raw Phase 4A tables are exposed';
  end if;
  if not has_function_privilege(
    'service_role',
    'public.memory_behavior_recall_gpt(uuid,uuid,text,real[],text,integer,bigint,text[])',
    'EXECUTE'
  ) or has_function_privilege(
    'authenticated',
    'public.memory_behavior_recall_gpt(uuid,uuid,text,real[],text,integer,bigint,text[])',
    'EXECUTE'
  ) or has_function_privilege(
    'service_role',
    'public.memory_behavior_internal_recall(text,uuid,uuid,text,real[],text,integer,bigint,text[])',
    'EXECUTE'
  ) then
    raise exception 'Fixed actor hybrid RPC privilege boundary is incorrect';
  end if;
end;
$$;

rollback;
