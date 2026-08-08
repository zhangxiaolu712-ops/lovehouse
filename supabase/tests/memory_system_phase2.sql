\set ON_ERROR_STOP on

begin;

insert into auth.users (id, email, raw_user_meta_data)
values
  ('10000000-0000-0000-0000-000000000001', 'memory-owner@example.invalid', '{}'::jsonb),
  ('20000000-0000-0000-0000-000000000002', 'other-owner@example.invalid', '{}'::jsonb)
on conflict (id) do nothing;

do $$
begin
  if pg_catalog.to_regclass('public.memory_spaces') is not null then
    raise exception 'Retired V1 namespace objects leaked into the V2 install';
  end if;
end;
$$;

do $$
declare
  gpt_id bigint;
  claude_id bigint;
  legacy_id bigint;
  shared_id bigint;
  unapproved_shared_id bigint;
  rejected_shared_id bigint;
  revoked_shared_id bigint;
  gpt_revision_1 bigint;
  gpt_revision_2 bigint;
  claude_revision_1 bigint;
  gpt_revision_1_hash text;
  candidate_id bigint;
  idempotency_first public.memory_mutation_idempotency%rowtype;
  idempotency_replay public.memory_mutation_idempotency%rowtype;
  database_hash text;
  rejected boolean;
begin
  perform pg_catalog.set_config(
    'request.jwt.claim.sub', '10000000-0000-0000-0000-000000000001', true
  );

  insert into public.memory_entries (
    owner_id, space_key, memory_type, tags, content, emotion, importance,
    retention, author, source_type, source_model, source_ref,
    created_by_actor, created_at
  ) values (
    '10000000-0000-0000-0000-000000000001', 'gpt', 'feeling',
    array['relationship', 'rose'], 'GPT private test memory',
    '{"label":"calm","intensity":0.6}'::jsonb, 4, 'long', 'GPT',
    'mcp', 'gpt', 'test:gpt:1', 'gpt', '2026-08-01T00:00:00Z'
  ) returning id into gpt_id;

  insert into public.memory_entries (
    owner_id, space_key, memory_type, tags, content, source_type,
    source_model, source_ref, created_by_actor, created_at
  ) values (
    '10000000-0000-0000-0000-000000000001', 'claude', 'diary',
    array['daily'], 'Claude private test memory', 'mcp', 'claude',
    'test:claude:1', 'claude', '2026-08-02T00:00:00Z'
  ) returning id into claude_id;

  select id into strict gpt_revision_1
  from public.memory_revisions
  where memory_id = gpt_id and revision_number = 1;
  select id into strict claude_revision_1
  from public.memory_revisions
  where memory_id = claude_id and revision_number = 1;
  gpt_revision_1_hash := public.memory_compute_revision_hash(gpt_revision_1);

  insert into public.memory_entries (
    owner_id, space_key, memory_type, tags, content, emotion, importance,
    author, source_type, source_model, source_ref, original_table, original_id,
    original_created_at, legacy_source, created_by_actor
  ) values (
    '10000000-0000-0000-0000-000000000001', 'legacy_pending', 'memo',
    array['legacy-test'], 'Legacy test sentinel legacy-only-orchid', '{}'::jsonb,
    5, 'legacy-author', 'legacy_import', 'CC', 'test:legacy:1', 'brain', '123',
    '2026-01-01T00:00:00Z', 'brain-v1', 'curator'
  ) returning id into legacy_id;

  rejected := false;
  begin
    insert into public.memory_entries (
      owner_id, space_key, source_type, source_ref, created_by_actor,
      shared_status, source_memory_id, source_revision_id
    ) values (
      '10000000-0000-0000-0000-000000000001', 'shared', 'curation',
      'test:shared:direct-candidate', 'curator', 'candidate', gpt_id, gpt_revision_1
    );
  exception when insufficient_privilege then
    rejected := true;
  end;
  if not rejected then
    raise exception 'Direct Shared candidate insert bypassed the trusted Curator RPC';
  end if;

  rejected := false;
  begin
    perform public.memory_curator_create_shared_candidate(
      '10000000-0000-0000-0000-000000000001',
      gpt_id,
      claude_revision_1,
      'Mismatched revision must fail'
    );
  exception when check_violation then
    rejected := true;
  end;
  if not rejected then
    raise exception 'Mismatched source memory/revision was not rejected';
  end if;

  rejected := false;
  begin
    insert into public.memory_entries (
      owner_id, space_key, memory_type, tags, content, source_type,
      source_model, created_by_actor
    ) values (
      '10000000-0000-0000-0000-000000000001', 'gpt', 'fact',
      array['Claude'], 'Actor names cannot become tags', 'mcp', 'gpt', 'gpt'
    );
  exception when check_violation then
    rejected := true;
  end;
  if not rejected then
    raise exception 'Actor tag constraint did not reject Claude';
  end if;

  rejected := false;
  begin
    insert into public.memory_entries (
      owner_id, space_key, memory_type, content, source_type,
      created_by_actor, original_table, original_id, original_created_at
    ) values (
      '10000000-0000-0000-0000-000000000001', 'legacy_pending', 'fact',
      'Missing legacy source must fail', 'legacy_import', 'curator',
      'memories', '9', now()
    );
  exception when check_violation then
    rejected := true;
  end;
  if not rejected then
    raise exception 'Legacy source constraint did not fail closed';
  end if;

  select id into shared_id
  from public.memory_curator_create_shared_candidate(
    '10000000-0000-0000-0000-000000000001', gpt_id, gpt_revision_1,
    'Candidate that the Owner will approve'
  );

  select id into unapproved_shared_id
  from public.memory_curator_create_shared_candidate(
    '10000000-0000-0000-0000-000000000001', claude_id, claude_revision_1,
    'Candidate that remains unapproved'
  );

  select id into rejected_shared_id
  from public.memory_curator_create_shared_candidate(
    '10000000-0000-0000-0000-000000000001', claude_id, claude_revision_1,
    'Candidate that the Owner will reject'
  );

  select id into revoked_shared_id
  from public.memory_curator_create_shared_candidate(
    '10000000-0000-0000-0000-000000000001', gpt_id, gpt_revision_1,
    'Candidate that the Owner will later revoke'
  );

  if not exists (
    select 1 from public.memory_entries
    where id = shared_id
      and content = 'GPT private test memory'
      and memory_type = 'feeling'
      and tags = array['relationship', 'rose']
      and source_revision_hash = gpt_revision_1_hash
      and created_by_actor = 'curator'
  ) then
    raise exception 'Shared candidate was not snapshotted from the selected revision';
  end if;

  rejected := false;
  begin
    update public.memory_entries
      set content = 'Candidate body must be immutable'
      where id = unapproved_shared_id;
  exception when insufficient_privilege or object_not_in_prerequisite_state then
    rejected := true;
  end;
  if not rejected then
    raise exception 'Shared candidate body was mutable';
  end if;

  rejected := false;
  begin
    update public.memory_entries
      set shared_status = 'approved',
          updated_by_actor = 'curator',
          revision_reason = 'Curator cannot approve'
      where id = unapproved_shared_id;
  exception when insufficient_privilege then
    rejected := true;
  end;
  if not rejected then
    raise exception 'Curator was able to approve Shared';
  end if;

  rejected := false;
  begin
    update public.memory_entries
      set shared_status = 'rejected',
          updated_by_actor = 'curator',
          revision_reason = 'Curator cannot reject'
      where id = unapproved_shared_id;
  exception when insufficient_privilege then
    rejected := true;
  end;
  if not rejected then
    raise exception 'Curator was able to reject Shared';
  end if;

  rejected := false;
  begin
    update public.memory_entries
      set shared_status = 'approved',
          updated_by_actor = 'gpt',
          revision_reason = 'GPT cannot impersonate Owner'
      where id = unapproved_shared_id;
  exception when insufficient_privilege then
    rejected := true;
  end;
  if not rejected then
    raise exception 'GPT actor was able to approve Shared';
  end if;

  rejected := false;
  begin
    update public.memory_entries
      set shared_status = 'approved',
          updated_by_actor = 'claude',
          revision_reason = 'Claude cannot impersonate Owner'
      where id = unapproved_shared_id;
  exception when insufficient_privilege then
    rejected := true;
  end;
  if not rejected then
    raise exception 'Claude actor was able to approve Shared';
  end if;

  rejected := false;
  begin
    update public.memory_entries
      set shared_status = 'approved',
          updated_by_actor = 'system',
          revision_reason = 'System cannot approve'
      where id = unapproved_shared_id;
  exception when insufficient_privilege then
    rejected := true;
  end;
  if not rejected then
    raise exception 'System was able to approve Shared';
  end if;

  rejected := false;
  begin
    update public.memory_entries
      set shared_status = 'approved',
          updated_by_actor = 'owner',
          revision_reason = 'Forged Owner field without trusted RPC'
      where id = unapproved_shared_id;
  exception when insufficient_privilege then
    rejected := true;
  end;
  if not rejected then
    raise exception 'Direct UPDATE forged the Owner authority';
  end if;

  perform pg_catalog.set_config(
    'request.jwt.claim.sub', '20000000-0000-0000-0000-000000000002', true
  );
  rejected := false;
  begin
    perform public.memory_owner_transition_shared(
      unapproved_shared_id, 'approved', 'Other owner must not approve'
    );
  exception when insufficient_privilege then
    rejected := true;
  end;
  perform pg_catalog.set_config(
    'request.jwt.claim.sub', '10000000-0000-0000-0000-000000000001', true
  );
  if not rejected then
    raise exception 'A different authenticated Owner crossed owner isolation';
  end if;

  rejected := false;
  begin
    perform public.memory_owner_transition_shared(
      unapproved_shared_id, 'revoked', 'Candidate cannot jump to revoked'
    );
  exception when check_violation then
    rejected := true;
  end;
  if not rejected then
    raise exception 'candidate -> revoked was accepted';
  end if;

  perform public.memory_owner_transition_shared(
    rejected_shared_id, 'rejected', 'Owner rejected this candidate'
  );

  rejected := false;
  begin
    perform public.memory_owner_transition_shared(
      rejected_shared_id, 'approved', 'Rejected cannot be reopened'
    );
  exception when check_violation then
    rejected := true;
  end;
  if not rejected then
    raise exception 'rejected -> approved was accepted';
  end if;

  rejected := false;
  begin
    perform public.memory_owner_transition_shared(
      rejected_shared_id, 'candidate', 'Rejected cannot return to candidate'
    );
  exception when check_violation then
    rejected := true;
  end;
  if not rejected then
    raise exception 'rejected -> candidate was accepted';
  end if;

  perform public.memory_owner_transition_shared(
    revoked_shared_id, 'approved', 'Owner approved revocation test'
  );
  perform public.memory_owner_transition_shared(
    revoked_shared_id, 'revoked', 'Owner revoked this approved snapshot'
  );

  rejected := false;
  begin
    perform public.memory_owner_transition_shared(
      revoked_shared_id, 'approved', 'Revoked cannot be restored'
    );
  exception when check_violation then
    rejected := true;
  end;
  if not rejected then
    raise exception 'revoked -> approved was accepted';
  end if;

  perform public.memory_owner_transition_shared(
    shared_id, 'approved', 'Owner approved exact GPT revision 1'
  );

  rejected := false;
  begin
    update public.memory_entries
      set shared_status = 'revoked',
          updated_by_actor = 'curator',
          revision_reason = 'Curator cannot revoke'
      where id = shared_id;
  exception when insufficient_privilege then
    rejected := true;
  end;
  if not rejected then
    raise exception 'Curator was able to revoke Shared';
  end if;

  rejected := false;
  begin
    insert into public.memory_revisions (
      owner_id, memory_id, revision_number, title, content, author, memory_type,
      tags, emotion, importance, retention, lifecycle_status, editor_actor,
      revision_reason
    ) values (
      '10000000-0000-0000-0000-000000000001', shared_id, 2,
      'Swapped shared title', 'Swapped shared body',
      'attacker', 'feeling', array['shared'], '{}'::jsonb, 1, 'long', 'active',
      'owner', 'Attempted revision swap'
    );
  exception when check_violation then
    rejected := true;
  end;
  if not rejected then
    raise exception 'Approved Shared accepted a replacement revision';
  end if;

  rejected := false;
  begin
    update public.memory_entries
      set content = 'Approved Shared overwrite must fail',
          updated_by_actor = 'owner',
          revision_reason = 'Attempted Shared overwrite'
      where id = shared_id;
  exception when insufficient_privilege or object_not_in_prerequisite_state then
    rejected := true;
  end;
  if not rejected then
    raise exception 'Approved Shared body was mutable';
  end if;

  rejected := false;
  begin
    update public.memory_entries
      set content = 'Silent overwrite must fail'
      where id = gpt_id;
  exception when check_violation then
    rejected := true;
  end;
  if not rejected then
    raise exception 'Silent private content overwrite was not rejected';
  end if;

  update public.memory_entries
    set content = 'GPT private test memory, revised',
        updated_by_actor = 'gpt',
        revision_reason = 'Clarify the test wording'
    where id = gpt_id;

  select id into strict gpt_revision_2
  from public.memory_revisions
  where memory_id = gpt_id and revision_number = 2;

  if not exists (
    select 1 from public.memory_entries
    where id = shared_id
      and content = 'GPT private test memory'
      and source_revision_id = gpt_revision_1
      and source_revision_hash = gpt_revision_1_hash
  ) then
    raise exception 'Shared candidate drifted after the source gained a new revision';
  end if;
  if gpt_revision_2 = gpt_revision_1 then
    raise exception 'Private revision did not advance';
  end if;
  if (select count(*) from public.memory_revisions where memory_id = shared_id) <> 1 then
    raise exception 'Shared status/body handling created an illicit revision';
  end if;

  insert into public.memory_audit_log (
    owner_id, actor, action, memory_id, space_key, result,
    result_count, result_spaces, metadata
  ) values (
    '10000000-0000-0000-0000-000000000001', 'gpt', 'recall', gpt_id,
    'gpt', 'allowed', 2, array['gpt', 'shared'], '{"request":"test-only"}'::jsonb
  );
  insert into public.memory_audit_log (
    owner_id, actor, action, memory_id, space_key, result, reason_code
  ) values (
    '10000000-0000-0000-0000-000000000001', 'gpt', 'read', claude_id,
    'claude', 'denied', 'MEMORY_ACCESS_DENIED'
  );

  insert into public.memory_ingest_candidates (
    owner_id, proposed_space_key, proposed_memory_type, proposed_tags,
    content, source_window_id, source_model, source_type, source_ref
  ) values (
    '10000000-0000-0000-0000-000000000001', 'claude', 'summary',
    array['dreaming-test'], 'Dreaming candidate test content',
    'window-test-1', 'claude', 'dreaming', 'dream-run:test'
  ) returning id into candidate_id;

  select * into strict idempotency_first
  from public.memory_claim_idempotency(
    '10000000-0000-0000-0000-000000000001',
    'gpt',
    'write_private',
    '30000000-0000-0000-0000-000000000003',
    '{"content":"same","tags":["a"]}'::jsonb
  );
  update public.memory_mutation_idempotency
    set status = 'completed',
        resource_id = gpt_id,
        response_metadata = jsonb_build_object('memory_id', gpt_id),
        completed_at = now()
    where id = idempotency_first.id;

  select * into strict idempotency_replay
  from public.memory_claim_idempotency(
    '10000000-0000-0000-0000-000000000001',
    'gpt',
    'write_private',
    '30000000-0000-0000-0000-000000000003',
    '{"tags":["a"],"content":"same"}'::jsonb
  );
  if idempotency_replay.id <> idempotency_first.id
    or idempotency_replay.status <> 'completed'
    or idempotency_replay.resource_id <> gpt_id
  then
    raise exception 'Same idempotency request did not replay its original resource';
  end if;

  rejected := false;
  begin
    perform * from public.memory_claim_idempotency(
      '10000000-0000-0000-0000-000000000001',
      'gpt',
      'write_private',
      '30000000-0000-0000-0000-000000000003',
      '{"content":"different"}'::jsonb
    );
  exception when unique_violation then
    rejected := true;
  end;
  if not rejected then
    raise exception 'Same idempotency key with different request hash was accepted';
  end if;

  insert into public.memory_mutation_idempotency (
    owner_id, actor, operation, request_id, request_material, request_hash
  ) values (
    '10000000-0000-0000-0000-000000000001', 'claude', 'write_private',
    '40000000-0000-0000-0000-000000000004', '{"content":"database hashes me"}'::jsonb,
    repeat('0', 64)
  ) returning request_hash into database_hash;
  if database_hash = repeat('0', 64) or length(database_hash) <> 64 then
    raise exception 'Client-controlled request_hash was trusted';
  end if;
  if exists (
    select 1 from public.memory_mutation_idempotency where request_material is not null
  ) then
    raise exception 'Transient idempotency request material was retained';
  end if;

  rejected := false;
  begin
    update public.memory_mutation_idempotency
      set request_hash = repeat('f', 64)
      where id = idempotency_first.id;
  exception when object_not_in_prerequisite_state then
    rejected := true;
  end;
  if not rejected then
    raise exception 'Database-computed request_hash was mutable';
  end if;

  if (select count(*) from public.memory_revisions where memory_id = gpt_id) <> 2 then
    raise exception 'Private revision history is incomplete';
  end if;
  if not exists (
    select 1 from public.memory_revisions
    where id = gpt_revision_1 and memory_id = gpt_id
      and revision_number = 1 and content = 'GPT private test memory'
  ) then
    raise exception 'Original private revision is not traceable';
  end if;
  if not exists (
    select 1 from public.memory_provenance
    where memory_id = legacy_id and event_type = 'legacy_staged'
      and original_table = 'brain' and original_id = '123'
  ) then
    raise exception 'Legacy provenance is incomplete';
  end if;
  if not exists (
    select 1 from public.memory_provenance
    where memory_id = shared_id and event_type = 'shared_approved'
      and parent_memory_id = gpt_id and parent_revision_id = gpt_revision_1
      and details->>'source_revision_hash' = gpt_revision_1_hash
  ) then
    raise exception 'Shared approval provenance does not bind the exact private revision';
  end if;
  if not exists (
    select 1 from public.memory_shared_transitions
    where memory_id = shared_id and from_status is null and to_status = 'candidate'
      and actor = 'curator' and source_memory_id = gpt_id
      and source_revision_id = gpt_revision_1
      and source_revision_hash = gpt_revision_1_hash
  ) then
    raise exception 'Shared candidate transition lacks its exact revision source';
  end if;
  if exists (
    select 1 from public.memory_shared_transitions
    where from_status is not null and actor <> 'owner'
  ) then
    raise exception 'A non-Owner performed a Shared decision transition';
  end if;
  if (select count(*) from public.memory_shared_transitions where memory_id = shared_id) <> 2 then
    raise exception 'Approved Shared transition history is incomplete';
  end if;
  if (select count(*) from public.memory_shared_transitions where memory_id = revoked_shared_id) <> 3 then
    raise exception 'Revoked Shared transition history is incomplete';
  end if;
  if not exists (
    select 1 from public.memory_ingest_candidates
    where id = candidate_id and status = 'pending' and converted_memory_id is null
  ) then
    raise exception 'Dreaming candidate lifecycle is invalid';
  end if;
  if exists (
    select 1 from public.memory_entries
    where space_key = 'legacy_pending' and id <> legacy_id
  ) then
    raise exception 'Unexpected Legacy Pending test rows found';
  end if;
  if has_table_privilege('authenticated', 'public.memory_mutation_idempotency', 'SELECT') then
    raise exception 'Authenticated role can read internal idempotency claims';
  end if;
  if not has_function_privilege(
    'service_role',
    'public.memory_curator_create_shared_candidate(uuid,bigint,bigint,text)',
    'EXECUTE'
  ) or has_function_privilege(
    'authenticated',
    'public.memory_curator_create_shared_candidate(uuid,bigint,bigint,text)',
    'EXECUTE'
  ) then
    raise exception 'Curator RPC privilege boundary is incorrect';
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
    raise exception 'Owner RPC privilege boundary is incorrect';
  end if;
  if has_function_privilege(
    'anon',
    'public.memory_owner_transition_shared(bigint,text,text)',
    'EXECUTE'
  ) or has_function_privilege(
    'anon',
    'public.memory_curator_create_shared_candidate(uuid,bigint,bigint,text)',
    'EXECUTE'
  ) then
    raise exception 'Anonymous role can reach a trusted authority RPC';
  end if;

  rejected := false;
  begin
    update public.memory_audit_log set action = 'tampered' where memory_id = gpt_id;
  exception when object_not_in_prerequisite_state then
    rejected := true;
  end;
  if not rejected then
    raise exception 'Audit append-only protection failed';
  end if;
end;
$$;

set local role authenticated;
select set_config('request.jwt.claim.sub', '20000000-0000-0000-0000-000000000002', true);
do $$
begin
  if (select count(*) from public.memory_entries) <> 0 then
    raise exception 'RLS exposed another owner memory';
  end if;
end;
$$;

select set_config('request.jwt.claim.sub', '10000000-0000-0000-0000-000000000001', true);
do $$
declare
  rejected boolean := false;
  candidate_id bigint;
begin
  if (select count(*) from public.memory_entries) <> 7 then
    raise exception 'Owner RLS did not return all owner test memories';
  end if;
  begin
    perform * from public.memory_claim_idempotency(
      '10000000-0000-0000-0000-000000000001', 'gpt', 'forged',
      '50000000-0000-0000-0000-000000000005', '{}'::jsonb
    );
  exception when insufficient_privilege then
    rejected := true;
  end;
  if not rejected then
    raise exception 'Authenticated client could call the internal idempotency function';
  end if;

  select id into strict candidate_id
  from public.memory_entries
  where space_key = 'shared' and shared_status = 'candidate'
  order by id
  limit 1;
  perform public.memory_owner_transition_shared(
    candidate_id, 'rejected', 'Authenticated Owner rejected the remaining candidate'
  );
end;
$$;
reset role;

set local role service_role;
do $$
declare
  gpt_id bigint;
  claude_id bigint;
  legacy_id bigint;
  shared_id bigint;
  unapproved_shared_id bigint;
  curator_candidate_id bigint;
  history_probe_id bigint;
  history_probe_revision bigint;
  gpt_results bigint[];
  claude_results bigint[];
  rejected boolean;
begin
  select id into strict gpt_id from public.memory_entries where source_ref = 'test:gpt:1';
  select id into strict claude_id from public.memory_entries where source_ref = 'test:claude:1';
  select id into strict legacy_id from public.memory_entries where source_ref = 'test:legacy:1';
  select id into strict shared_id
  from public.memory_entries
  where source_memory_id = gpt_id and shared_status = 'approved';
  select id into strict unapproved_shared_id
  from public.memory_entries
  where source_memory_id = claude_id and shared_status <> 'approved'
  order by id
  limit 1;

  if (select count(*) from public.memory_entries) <> 7 then
    raise exception 'Service role bypass expectation changed; application filtering assumptions need review';
  end if;

  select array_agg(id) into gpt_results
  from public.memory_recall_gpt(
    '10000000-0000-0000-0000-000000000001', 'test', 20, '{}'::text[]
  );
  if gpt_results is distinct from array[shared_id, gpt_id]::bigint[] then
    raise exception 'GPT recall count/order was influenced by forbidden spaces: %', gpt_results;
  end if;

  select array_agg(id) into claude_results
  from public.memory_recall_claude(
    '10000000-0000-0000-0000-000000000001', 'test', 20, '{}'::text[]
  );
  if claude_results is distinct from array[shared_id, claude_id]::bigint[] then
    raise exception 'Claude recall count/order was influenced by forbidden spaces: %', claude_results;
  end if;

  if exists (select 1 from public.memory_recall_gpt(
    '10000000-0000-0000-0000-000000000001', 'legacy-only-orchid', 20, '{}'::text[]
  )) or exists (select 1 from public.memory_recall_claude(
    '10000000-0000-0000-0000-000000000001', 'legacy-only-orchid', 20, '{}'::text[]
  )) then
    raise exception 'Legacy-only keyword leaked through ordinary recall';
  end if;

  if exists (select 1 from public.memory_get_gpt(
    '10000000-0000-0000-0000-000000000001', claude_id
  )) then
    raise exception 'GPT fixed read door exposed Claude Memory';
  end if;
  if exists (select 1 from public.memory_get_claude(
    '10000000-0000-0000-0000-000000000001', gpt_id
  )) then
    raise exception 'Claude fixed read door exposed GPT Memory';
  end if;
  if exists (select 1 from public.memory_get_gpt(
    '10000000-0000-0000-0000-000000000001', unapproved_shared_id
  )) or exists (select 1 from public.memory_get_claude(
    '10000000-0000-0000-0000-000000000001', unapproved_shared_id
  )) then
    raise exception 'An unapproved Shared memory escaped its fixed read door';
  end if;
  if exists (select 1 from public.memory_get_gpt(
    '10000000-0000-0000-0000-000000000001', legacy_id
  )) or exists (select 1 from public.memory_get_claude(
    '10000000-0000-0000-0000-000000000001', legacy_id
  )) then
    raise exception 'Legacy Pending escaped into ordinary get';
  end if;

  rejected := false;
  begin
    perform public.memory_owner_transition_shared(
      unapproved_shared_id, 'approved', 'Service role must not impersonate Owner'
    );
  exception when insufficient_privilege then
    rejected := true;
  end;
  if not rejected then
    raise exception 'Service role reached the authenticated Owner RPC';
  end if;

  rejected := false;
  begin
    insert into public.memory_entries (
      owner_id, space_key, source_type, created_by_actor, shared_status,
      source_memory_id, source_revision_id
    ) values (
      '10000000-0000-0000-0000-000000000001', 'shared', 'curation',
      'curator', 'candidate', gpt_id,
      (select id from public.memory_revisions
       where memory_id = gpt_id and revision_number = 1)
    );
  exception when insufficient_privilege then
    rejected := true;
  end;
  if not rejected then
    raise exception 'Service role bypassed the trusted Curator RPC with a raw INSERT';
  end if;

  select id into curator_candidate_id
  from public.memory_curator_create_shared_candidate(
    '10000000-0000-0000-0000-000000000001',
    gpt_id,
    (select id from public.memory_revisions
     where memory_id = gpt_id and revision_number = 2),
    'Service-only Curator entry verification'
  );
  if not exists (
    select 1 from public.memory_entries
    where id = curator_candidate_id
      and created_by_actor = 'curator'
      and shared_status = 'candidate'
      and source_revision_hash is not null
  ) then
    raise exception 'Trusted Curator RPC did not create a fixed candidate snapshot';
  end if;

  insert into public.memory_entries (
    owner_id, space_key, memory_type, content, source_type, source_model,
    source_ref, created_by_actor
  ) values (
    '10000000-0000-0000-0000-000000000001', 'gpt', 'fact',
    'Private history trigger probe', 'mcp', 'gpt',
    'test:private-history-probe', 'gpt'
  ) returning id into history_probe_id;

  update public.memory_entries
    set content = 'Private history trigger probe revised',
        updated_by_actor = 'gpt',
        revision_reason = 'Verify trigger-only history writes'
    where id = history_probe_id;

  if (select count(*) from public.memory_revisions where memory_id = history_probe_id) <> 2 then
    raise exception 'Legitimate private entry update did not create revision history';
  end if;
  select max(revision_number) into history_probe_revision
  from public.memory_revisions where memory_id = history_probe_id;

  rejected := false;
  begin
    insert into public.memory_revisions (
      owner_id, memory_id, revision_number, content, memory_type, tags,
      emotion, importance, lifecycle_status, editor_actor, revision_reason
    ) values (
      '10000000-0000-0000-0000-000000000001', history_probe_id,
      history_probe_revision + 1, 'Forged private revision', 'fact',
      '{}'::text[], '{}'::jsonb, 1, 'active', 'gpt', 'Direct forgery'
    );
  exception when insufficient_privilege then
    rejected := true;
  end;
  if not rejected then
    raise exception 'Service role directly forged a private revision';
  end if;
end;
$$;
reset role;

rollback;
