\set ON_ERROR_STOP on

begin;

insert into auth.users (id, email, raw_user_meta_data)
values
  ('51000000-0000-0000-0000-000000000001', 'phase4b-owner@example.invalid', '{}'::jsonb),
  ('52000000-0000-0000-0000-000000000002', 'phase4b-other@example.invalid', '{}'::jsonb)
on conflict (id) do nothing;

do $$
declare
  v_owner constant uuid := '51000000-0000-0000-0000-000000000001';
  other_owner constant uuid := '52000000-0000-0000-0000-000000000002';
  result jsonb;
  claimed jsonb;
  gpt_id bigint;
  claude_id bigint;
  shared_id bigint;
  legacy_id bigint;
  extra_id bigint;
  job_id bigint;
  failed_job_id bigint;
  gpt_revision_id bigint;
  gpt_revision_hash text;
  memory_count_before integer;
  revision_count_before integer;
  candidate_count_before integer;
  rejected boolean;
  anchor_record_id bigint;
  candidate_id bigint;
  i integer;
begin
  result := public.memory_runtime_remember_gpt(
    v_owner, '51000000-0000-4000-8000-000000000001',
    '{"content":"GPT core rose memory","memory_type":"feeling","tags":["phase4b"],"importance":5}'::jsonb
  );
  gpt_id := (result->'memory'->>'id')::bigint;
  result := public.memory_runtime_remember_claude(
    v_owner, '51000000-0000-4000-8000-000000000002',
    '{"content":"Claude private observatory","memory_type":"memo","tags":["phase4b"],"importance":5}'::jsonb
  );
  claude_id := (result->'memory'->>'id')::bigint;

  select id, public.memory_compute_revision_hash(id)
    into strict gpt_revision_id, gpt_revision_hash
  from public.memory_revisions
  where owner_id = v_owner and memory_id = gpt_id and revision_number = 1;

  result := public.memory_runtime_propose_shared_gpt(
    v_owner, '51000000-0000-4000-8000-000000000003', gpt_id,
    'Phase 4B approved Shared source'
  );
  shared_id := (result->'memory'->>'id')::bigint;
  perform pg_catalog.set_config('request.jwt.claim.sub', v_owner::text, true);
  perform public.memory_owner_transition_shared(
    shared_id, 'approved', 'Approve deterministic Phase 4B test snapshot'
  );

  insert into public.memory_entries (
    owner_id, space_key, memory_type, tags, content, source_type,
    original_table, original_id, original_created_at, legacy_source, created_by_actor
  ) values (
    v_owner, 'legacy_pending', 'memo', array['phase4b'],
    'legacy-dream-sentinel', 'legacy_import', 'brain', 'phase4b-legacy',
    '2026-01-01T00:00:00Z', 'phase4b-test', 'curator'
  ) returning id into legacy_id;

  -- Anchor uses a fixed actor door, binds an exact revision and never mutates
  -- the source memory. Cross-private anchoring is denied and audited.
  result := public.memory_behavior_set_anchor_gpt(
    v_owner, '51000000-0000-4000-8000-000000000004',
    gpt_id, true, 'Core memory'
  );
  anchor_record_id := (result->'anchor'->>'id')::bigint;
  if result->>'ok' <> 'true' or not exists (
    select 1 from public.memory_anchor_records
    where id = anchor_record_id and memory_id = gpt_id
      and pinned_revision_id = gpt_revision_id
      and pinned_revision_hash = gpt_revision_hash
      and released_at is null
  ) then
    raise exception 'Anchor did not bind the exact private revision';
  end if;
  result := public.memory_behavior_set_anchor_gpt(
    v_owner, '51000000-0000-4000-8000-000000000005',
    claude_id, true, 'Cross-space attempt'
  );
  if result->>'error_code' <> 'MEMORY_ACCESS_DENIED'
    or not (result->>'audit_persisted')::boolean then
    raise exception 'Cross-private Anchor access was not denied and audited';
  end if;
  if (select count(*) from public.memory_revisions where memory_id = gpt_id) <> 1 then
    raise exception 'Anchoring changed the source revision history';
  end if;

  -- The active Anchor limit is enforced in the database. These low-importance
  -- rows also prove a Dream batch remains bounded even with many eligible rows.
  for i in 1..12 loop
    result := public.memory_runtime_remember_gpt(
      v_owner, extensions.gen_random_uuid(),
      pg_catalog.jsonb_build_object(
        'content', 'Phase 4B bounded source ' || i,
        'memory_type', 'fact', 'tags', array['phase4b'], 'importance', 1
      )
    );
    extra_id := (result->'memory'->>'id')::bigint;
    if i <= 11 then
      result := public.memory_behavior_set_anchor_gpt(
        v_owner, extensions.gen_random_uuid(), extra_id, true, 'Bounded anchor ' || i
      );
      if result->>'ok' <> 'true' then
        raise exception 'Anchor below the limit was rejected';
      end if;
    else
      result := public.memory_behavior_set_anchor_gpt(
        v_owner, extensions.gen_random_uuid(), extra_id, true, 'Thirteenth anchor'
      );
      if result->>'error_code' <> 'MEMORY_ANCHOR_LIMIT_REACHED' then
        raise exception 'Database Anchor limit was not enforced';
      end if;
    end if;
  end loop;

  select count(*) into memory_count_before from public.memory_entries;
  select count(*) into revision_count_before from public.memory_revisions;
  select count(*) into candidate_count_before from public.memory_ingest_candidates;

  result := public.memory_behavior_enqueue_dream_gpt(
    v_owner, '51000000-0000-4000-8000-000000000006',
    'GPT perspective with every source actor preserved', 99
  );
  job_id := (result->'job'->>'id')::bigint;
  if job_id is null or (result->'job'->>'source_count')::integer not between 1 and 4 then
    raise exception 'Dream enqueue did not create a bounded job';
  end if;
  if exists (
    select 1 from public.memory_dream_job_sources
    where dream_job_id = job_id
      and (dream_actor <> 'gpt' or source_memory_id in (claude_id, legacy_id))
  ) or exists (
    select 1 from public.memory_dream_job_sources
    where dream_job_id = job_id and source_space_key = 'legacy_pending'
  ) then
    raise exception 'Dream source selection crossed actor or Legacy boundaries';
  end if;
  if not exists (
    select 1 from public.memory_dream_job_sources
    where dream_job_id = job_id and source_memory_id = gpt_id
      and source_revision_id = gpt_revision_id
      and source_revision_hash = gpt_revision_hash
      and dream_actor = 'gpt' and source_actor = 'gpt'
  ) then
    raise exception 'Anchored exact source revision was not selected first-class';
  end if;

  claimed := public.memory_behavior_claim_dream_gpt(
    v_owner, '51000000-0000-4000-8000-000000000007',
    'deepseek-compatible', 'deepseek-curator-v1'
  );
  if (claimed->'job'->>'id')::bigint <> job_id
    or pg_catalog.jsonb_array_length(claimed->'job'->'sources') not between 1 and 4
    or exists (
      select 1 from pg_catalog.jsonb_array_elements(claimed->'job'->'sources') source(value)
      where length(value->>'content') > 6000
        or value->>'dream_actor' <> 'gpt'
        or value->>'source_actor' not in ('gpt', 'claude')
    )
  then
    raise exception 'Dream claim did not return a bounded traceable snapshot';
  end if;

  result := public.memory_behavior_complete_dream_gpt(
    v_owner, '51000000-0000-4000-8000-000000000008', job_id,
    'deepseek-compatible', 'deepseek-curator-v1',
    '[
      {
        "proposal_kind":"derived_memory",
        "content":"A derived pending summary that preserves its source.",
        "memory_type":"summary",
        "tags":["phase4b","derived"],
        "importance":3,
        "source_ordinals":[1],
        "actor":"claude",
        "owner_id":"52000000-0000-0000-0000-000000000002",
        "space_key":"claude"
      },
      {
        "proposal_kind":"revision_suggestion",
        "content":"A pending revision suggestion; the source remains unchanged.",
        "memory_type":"reflection",
        "source_ordinals":[1],
        "target_source_ordinal":1
      },
      {
        "proposal_kind":"shared_candidate",
        "content":"A pending Shared recommendation, never auto-approved.",
        "memory_type":"summary",
        "source_ordinals":[1]
      }
    ]'::jsonb
  );
  if result->>'ok' <> 'true'
    or pg_catalog.jsonb_array_length(result->'candidate_ids') <> 3 then
    raise exception 'Dream completion did not create exactly three candidates';
  end if;
  if (select count(*) from public.memory_entries) <> memory_count_before
    or (select count(*) from public.memory_revisions) <> revision_count_before
  then
    raise exception 'Dream completion modified canonical memory or revision history';
  end if;
  if (select count(*) from public.memory_ingest_candidates) <> candidate_count_before + 3
    or exists (
      select 1 from public.memory_ingest_candidates
      where dream_job_id = job_id
        and (status <> 'pending' or reviewer_actor is not null or converted_memory_id is not null)
    )
  then
    raise exception 'Dream outputs bypassed pending candidate review';
  end if;
  if not exists (
    select 1 from public.memory_ingest_candidates
    where dream_job_id = job_id and proposal_kind = 'derived_memory'
      and proposed_space_key = 'gpt'
  ) or not exists (
    select 1 from public.memory_ingest_candidates
    where dream_job_id = job_id and proposal_kind = 'revision_suggestion'
      and target_memory_id = gpt_id and target_revision_id = gpt_revision_id
      and target_revision_hash = gpt_revision_hash
  ) or not exists (
    select 1 from public.memory_ingest_candidates
    where dream_job_id = job_id and proposal_kind = 'shared_candidate'
      and proposed_space_key = 'shared' and status = 'pending'
  ) then
    raise exception 'Dream proposal kinds did not preserve their review-only contract';
  end if;
  if exists (
    select 1 from public.memory_ingest_candidates
    where dream_job_id = job_id
      and (source_metadata ? 'owner_id' or source_metadata ? 'space_key')
  ) then
    raise exception 'Curator authority-shaped fields reached candidate authority metadata';
  end if;
  if (select count(*) from public.memory_ingest_candidate_sources where dream_job_id = job_id) <> 3
    or exists (
      select 1 from public.memory_ingest_candidate_sources
      where dream_job_id = job_id
        and (dream_actor <> 'gpt' or source_actor <> 'gpt'
          or source_memory_id <> gpt_id or source_revision_id <> gpt_revision_id
          or source_revision_hash <> gpt_revision_hash
          or curator_provider <> 'deepseek-compatible'
          or curator_model <> 'deepseek-curator-v1')
    )
  then
    raise exception 'Candidate provenance lost actor/provider/exact revision identity';
  end if;

  -- A new revision is a new immutable Dream input. A different provider can
  -- process it without any schema change.
  perform public.memory_runtime_revise_gpt(
    v_owner, '51000000-0000-4000-8000-000000000009', gpt_id,
    '{"content":"GPT core rose memory revised after first Dream"}'::jsonb,
    'Create an exact new source revision for provider replacement test'
  );
  result := public.memory_behavior_enqueue_dream_gpt(
    v_owner, '51000000-0000-4000-8000-000000000010',
    'Same schema, replacement Curator provider', 1
  );
  if result->'job' is null then
    raise exception 'A new revision did not become eligible for a new Dream';
  end if;
  claimed := public.memory_behavior_claim_dream_gpt(
    v_owner, '51000000-0000-4000-8000-000000000011',
    'openai-compatible', 'gpt-curator-v2'
  );
  if claimed->'job'->>'curator_provider' <> 'openai-compatible'
    or claimed->'job'->>'curator_model' <> 'gpt-curator-v2' then
    raise exception 'Curator provider replacement was not configuration-only';
  end if;

  -- Invalid Curator output rolls back every candidate insert. The worker may
  -- then fail/retry the queue item, while originals remain byte-for-byte.
  result := public.memory_runtime_remember_gpt(
    v_owner, '51000000-0000-4000-8000-000000000012',
    '{"content":"Failure isolation source","memory_type":"fact","tags":["phase4b"],"importance":5}'::jsonb
  );
  extra_id := (result->'memory'->>'id')::bigint;
  result := public.memory_behavior_enqueue_dream_gpt(
    v_owner, '51000000-0000-4000-8000-000000000013',
    'Failure isolation perspective', 4
  );
  -- The previous replacement-provider job is currently processing; fail it so
  -- the next claim remains deterministic and no source is silently discarded.
  perform public.memory_behavior_fail_dream_gpt(
    v_owner, extensions.gen_random_uuid(),
    (claimed->'job'->>'id')::bigint,
    'openai-compatible', 'gpt-curator-v2', 'TEST_PROVIDER_INTERRUPTED'
  );
  claimed := public.memory_behavior_claim_dream_gpt(
    v_owner, '51000000-0000-4000-8000-000000000014',
    'fault-injection', 'invalid-output-model'
  );
  failed_job_id := (claimed->'job'->>'id')::bigint;
  candidate_count_before := (select count(*) from public.memory_ingest_candidates);
  memory_count_before := (select count(*) from public.memory_entries);
  revision_count_before := (select count(*) from public.memory_revisions);
  rejected := false;
  begin
    perform public.memory_behavior_complete_dream_gpt(
      v_owner, '51000000-0000-4000-8000-000000000015', failed_job_id,
      'fault-injection', 'invalid-output-model',
      '[
        {"content":"first insert would be valid","source_ordinals":[1]},
        {"content":"","source_ordinals":[1]}
      ]'::jsonb
    );
  exception when others then
    rejected := true;
  end;
  if not rejected
    or (select count(*) from public.memory_ingest_candidates) <> candidate_count_before
    or (select count(*) from public.memory_entries) <> memory_count_before
    or (select count(*) from public.memory_revisions) <> revision_count_before
  then
    raise exception 'Failed Dream output polluted candidates or original memory';
  end if;
  perform public.memory_behavior_fail_dream_gpt(
    v_owner, '51000000-0000-4000-8000-000000000016', failed_job_id,
    'fault-injection', 'invalid-output-model', 'MEMORY_DREAM_OUTPUT_INVALID'
  );

  -- Anchor release leaves history and does not touch the original memory.
  result := public.memory_behavior_set_anchor_gpt(
    v_owner, '51000000-0000-4000-8000-000000000017',
    gpt_id, false, 'No longer needs active pin'
  );
  if result->>'ok' <> 'true' or not exists (
    select 1 from public.memory_anchor_records
    where id = anchor_record_id and released_at is not null
      and released_reason = 'No longer needs active pin'
  ) then
    raise exception 'Anchor release history is incomplete';
  end if;
  rejected := false;
  begin
    update public.memory_anchor_records set reason = 'silent rewrite' where id = anchor_record_id;
  exception when object_not_in_prerequisite_state then
    rejected := true;
  end;
  if not rejected then
    raise exception 'Anchor history was mutable';
  end if;

  -- Other owners and direct service-role table access remain isolated.
  result := public.memory_behavior_enqueue_dream_gpt(
    other_owner, '52000000-0000-4000-8000-000000000018',
    'Other owner has no sources', 4
  );
  if result->'job' is not null then
    raise exception 'Dream queue crossed owner boundaries';
  end if;
  if has_table_privilege('service_role', 'public.memory_dream_jobs', 'SELECT')
    or has_table_privilege('service_role', 'public.memory_dream_job_sources', 'INSERT')
    or has_table_privilege('service_role', 'public.memory_ingest_candidate_sources', 'SELECT')
    or has_table_privilege('service_role', 'public.memory_anchor_records', 'UPDATE')
  then
    raise exception 'Bridge received direct Phase 4B table access';
  end if;
  if has_function_privilege(
    'service_role',
    'public.memory_behavior_internal_complete_dream(text,uuid,uuid,bigint,text,text,jsonb)',
    'EXECUTE'
  ) or has_function_privilege(
    'authenticated',
    'public.memory_behavior_complete_dream_gpt(uuid,uuid,bigint,text,text,jsonb)',
    'EXECUTE'
  ) or not has_function_privilege(
    'service_role',
    'public.memory_behavior_complete_dream_gpt(uuid,uuid,bigint,text,text,jsonb)',
    'EXECUTE'
  ) then
    raise exception 'Dream fixed-actor RPC privilege boundary is incorrect';
  end if;
  if exists (
    select 1 from public.memory_dream_job_sources where source_memory_id = legacy_id
  ) or exists (
    select 1 from public.memory_ingest_candidate_sources where source_memory_id = legacy_id
  ) then
    raise exception 'Legacy Pending entered Dream SQL candidate sets';
  end if;
end;
$$;

rollback;
