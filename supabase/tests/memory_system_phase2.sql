\set ON_ERROR_STOP on

begin;

insert into auth.users (id, email, raw_user_meta_data)
values
  ('10000000-0000-0000-0000-000000000001', 'memory-owner@example.invalid', '{}'::jsonb),
  ('20000000-0000-0000-0000-000000000002', 'other-owner@example.invalid', '{}'::jsonb)
on conflict (id) do nothing;

do $$
declare
  gpt_id bigint;
  claude_id bigint;
  legacy_id bigint;
  shared_id bigint;
  unapproved_shared_id bigint;
  candidate_id bigint;
  rejected boolean;
begin
  insert into public.memory_entries (
    owner_id, space_key, memory_type, tags, content, emotion, importance,
    retention, author, source_type, source_model, source_ref, created_by_actor
  ) values (
    '10000000-0000-0000-0000-000000000001', 'gpt', 'feeling',
    array['relationship', 'rose'], 'GPT private test memory',
    '{"label":"calm","intensity":0.6}'::jsonb, 4, 'long', 'GPT',
    'mcp', 'gpt', 'test:gpt:1', 'gpt'
  ) returning id into gpt_id;

  insert into public.memory_entries (
    owner_id, space_key, memory_type, tags, content, source_type,
    source_model, source_ref, created_by_actor
  ) values (
    '10000000-0000-0000-0000-000000000001', 'claude', 'diary',
    array['daily'], 'Claude private test memory', 'mcp', 'claude',
    'test:claude:1', 'claude'
  ) returning id into claude_id;

  insert into public.memory_entries (
    owner_id, space_key, memory_type, tags, content, author, source_type,
    source_model, source_ref, original_table, original_id,
    original_created_at, legacy_source, created_by_actor
  ) values (
    '10000000-0000-0000-0000-000000000001', 'legacy_pending', 'memo',
    array['legacy-test'], 'Frozen legacy test body', 'legacy-author',
    'legacy_import', 'CC', 'test:legacy:1', 'brain', '123',
    '2026-01-01T00:00:00Z', 'brain-v1', 'curator'
  ) returning id into legacy_id;

  rejected := false;
  begin
    insert into public.memory_entries (
      owner_id, space_key, memory_type, content, source_type,
      created_by_actor, shared_status, derived_from_memory_id
    ) values (
      '10000000-0000-0000-0000-000000000001', 'shared', 'fact',
      'Direct approved must fail', 'curation', 'owner', 'approved', gpt_id
    );
  exception when check_violation then
    rejected := true;
  end;
  if not rejected then
    raise exception 'Direct approved Shared insert was not rejected';
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

  insert into public.memory_entries (
    owner_id, space_key, memory_type, tags, content, source_type,
    source_model, source_ref, derived_from_memory_id, shared_status,
    created_by_actor
  ) values (
    '10000000-0000-0000-0000-000000000001', 'shared', 'feeling',
    array['relationship', 'rose'], 'Shared candidate test memory',
    'curation', 'curator', 'test:shared:1', gpt_id, 'candidate', 'curator'
  ) returning id into shared_id;

  insert into public.memory_entries (
    owner_id, space_key, memory_type, tags, content, source_type,
    source_model, source_ref, derived_from_memory_id, shared_status,
    created_by_actor
  ) values (
    '10000000-0000-0000-0000-000000000001', 'shared', 'diary',
    array['unapproved-test'], 'Unapproved Shared test memory',
    'curation', 'curator', 'test:shared:unapproved', claude_id,
    'candidate', 'curator'
  ) returning id into unapproved_shared_id;

  update public.memory_entries
    set shared_status = 'approved',
        updated_by_actor = 'owner',
        revision_reason = 'Owner approved this test candidate'
    where id = shared_id;

  rejected := false;
  begin
    update public.memory_entries
      set content = 'Silent overwrite must fail'
      where id = gpt_id;
  exception when check_violation then
    rejected := true;
  end;
  if not rejected then
    raise exception 'Silent content overwrite was not rejected';
  end if;

  update public.memory_entries
    set content = 'GPT private test memory, revised',
        updated_by_actor = 'gpt',
        revision_reason = 'Clarify the test wording'
    where id = gpt_id;

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

  if (select count(*) from public.memory_revisions where memory_id = gpt_id) <> 2 then
    raise exception 'Revision history is incomplete';
  end if;
  if not exists (
    select 1 from public.memory_revisions
    where memory_id = gpt_id and revision_number = 1
      and content = 'GPT private test memory'
  ) then
    raise exception 'Original revision is not traceable';
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
      and parent_memory_id = gpt_id
  ) then
    raise exception 'Shared approval provenance is incomplete';
  end if;
  if (select count(*) from public.memory_shared_transitions where memory_id = shared_id) <> 2 then
    raise exception 'Shared transition history is incomplete';
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
begin
  if (select count(*) from public.memory_entries) <> 5 then
    raise exception 'Owner RLS did not return all owner test memories';
  end if;
end;
$$;
reset role;

set local role service_role;
do $$
declare
  gpt_id bigint;
  claude_id bigint;
  legacy_id bigint;
  unapproved_shared_id bigint;
begin
  select id into gpt_id from public.memory_entries where source_ref = 'test:gpt:1';
  select id into claude_id from public.memory_entries where source_ref = 'test:claude:1';
  select id into legacy_id from public.memory_entries where source_ref = 'test:legacy:1';
  select id into unapproved_shared_id from public.memory_entries where source_ref = 'test:shared:unapproved';

  if (select count(*) from public.memory_entries) <> 5 then
    raise exception 'Service role bypass expectation changed; application filtering assumptions need review';
  end if;
  if (select count(*) from public.memory_recall_gpt(
    '10000000-0000-0000-0000-000000000001', 'test', 20, '{}'::text[]
  )) <> 2 then
    raise exception 'GPT fixed read door returned the wrong scope';
  end if;
  if (select count(*) from public.memory_recall_claude(
    '10000000-0000-0000-0000-000000000001', 'test', 20, '{}'::text[]
  )) <> 2 then
    raise exception 'Claude fixed read door returned the wrong scope';
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
    raise exception 'Legacy Pending escaped into daily reads';
  end if;
end;
$$;
reset role;

rollback;
