\set ON_ERROR_STOP on

begin;

insert into auth.users (id, email, raw_user_meta_data)
values
  ('30000000-0000-0000-0000-000000000003', 'engineering-owner@example.invalid', '{}'::jsonb),
  ('40000000-0000-0000-0000-000000000004', 'engineering-other@example.invalid', '{}'::jsonb)
on conflict (id) do nothing;

do $$
declare
  owner_id constant uuid := '30000000-0000-0000-0000-000000000003';
  other_owner_id constant uuid := '40000000-0000-0000-0000-000000000004';
  created jsonb;
  unchanged jsonb;
  revised jsonb;
  codex_created jsonb;
  owner_created jsonb;
  private_memory jsonb;
  claude_private jsonb;
  shared_memory jsonb;
  opened jsonb;
  recalled jsonb;
  starter jsonb;
  expanded jsonb;
  archived jsonb;
  restored jsonb;
  source_id uuid;
  denied boolean;
  revision_count integer;
begin
  if not exists (
    select 1 from information_schema.columns
    where table_schema = 'public' and table_name = 'memory_v2_entries'
      and column_name = 'subject_key'
  ) then
    raise exception 'Engineering subject_key column is missing';
  end if;
  if not (select relrowsecurity and relforcerowsecurity
    from pg_class where oid = 'public.memory_v2_entries'::regclass) then
    raise exception 'Memory V2 entries no longer FORCE RLS';
  end if;
  if has_table_privilege('anon', 'public.memory_v2_entries', 'select')
    or has_table_privilege('authenticated', 'public.memory_v2_sources', 'select')
    or has_function_privilege('anon', 'public.memory_v2_engineering_upsert(uuid,text,text,text,jsonb)', 'execute')
    or has_function_privilege('authenticated', 'public.memory_v2_engineering_recall(uuid,text,text,integer,boolean)', 'execute') then
    raise exception 'Engineering Memory leaked direct Data API privileges';
  end if;

  created := public.memory_v2_engineering_upsert(
    owner_id,
    'gpt',
    'memory.active_system',
    'Memory V2 is the active memory runtime.',
    jsonb_build_object(
      'reason', 'initial verified state',
      'metadata', jsonb_build_object(
        'category', 'memory',
        'component', 'memory-v2',
        'change_type', 'foundation',
        'environment', 'test',
        'source_commit', 'abc123'
      ),
      'sources', jsonb_build_array(
        jsonb_build_object(
          'source_kind', 'git_commit',
          'locator', jsonb_build_object('commit', 'abc123'),
          'quote_text', 'verified engineering evidence',
          'provenance', jsonb_build_object('verified_by', 'test')
        )
      )
    )
  );
  if created ->> 'action' <> 'created'
    or created ->> 'space_key' <> 'engineering'
    or created ->> 'subject_key' <> 'memory.active_system' then
    raise exception 'GPT engineering create failed';
  end if;

  unchanged := public.memory_v2_engineering_upsert(
    owner_id, 'claude', 'memory.active_system',
    'Memory V2 is the active memory runtime.',
    jsonb_build_object(
      'reason', 'same state observed again',
      'metadata', jsonb_build_object(
        'category', 'memory',
        'component', 'memory-v2',
        'change_type', 'foundation',
        'environment', 'test',
        'source_commit', 'abc123'
      )
    )
  );
  select count(*) into revision_count
  from public.memory_v2_revisions
  where memory_id = (created ->> 'memory_id')::uuid;
  if unchanged ->> 'action' <> 'noop' or revision_count <> 1 then
    raise exception 'identical Engineering content created an empty revision';
  end if;

  revised := public.memory_v2_engineering_upsert(
    owner_id,
    'claude',
    'memory.active_system',
    'Memory V2 is active; Engineering Memory remains explicit-only.',
    jsonb_build_object(
      'reason', 'clarify isolation',
      'metadata', jsonb_build_object(
        'category', 'future-module-not-in-enum',
        'component', 'memory-v2',
        'change_type', 'clarification'
      )
    )
  );
  if revised ->> 'action' <> 'revised'
    or revised ->> 'memory_id' <> created ->> 'memory_id'
    or (revised ->> 'revision_number')::integer <> 2 then
    raise exception 'same subject_key did not append to the existing Entry';
  end if;

  codex_created := public.memory_v2_engineering_upsert(
    owner_id, 'codex', 'runtime.codex', 'Codex runtime uses a sidecar.',
    jsonb_build_object('metadata', jsonb_build_object('category', 'runtime'))
  );
  owner_created := public.memory_v2_engineering_upsert(
    owner_id, 'owner', 'infra.pm2', 'PM2 process identity is verified at deploy time.',
    jsonb_build_object('metadata', jsonb_build_object('category', 'infra'))
  );
  if codex_created ->> 'action' <> 'created' or owner_created ->> 'action' <> 'created' then
    raise exception 'Codex or Owner could not create Engineering Memory';
  end if;

  recalled := public.memory_v2_engineering_recall(owner_id, 'codex', 'Memory V2', 20, false);
  if not exists (
    select 1 from jsonb_array_elements(recalled) item
    where item ->> 'subject_key' = 'memory.active_system'
      and item ->> 'content' = 'Memory V2 is active; Engineering Memory remains explicit-only.'
      and item -> 'metadata' ->> 'category' = 'future-module-not-in-enum'
  ) then
    raise exception 'Engineering recall did not return the current revision or unknown category';
  end if;

  -- Ordinary Memory paths must remain blind to Engineering Memory.
  recalled := public.memory_v2_recall_lexical(owner_id, 'gpt', 'Memory V2', 50);
  if exists (select 1 from jsonb_array_elements(recalled) item
    where item ->> 'memory_id' = created ->> 'memory_id') then
    raise exception 'GPT ordinary recall exposed Engineering Memory';
  end if;
  recalled := public.memory_v2_recall_lexical(owner_id, 'claude', 'Memory V2', 50);
  if exists (select 1 from jsonb_array_elements(recalled) item
    where item ->> 'memory_id' = created ->> 'memory_id') then
    raise exception 'Claude ordinary recall exposed Engineering Memory';
  end if;
  starter := public.memory_v2_starter_pack_candidates(owner_id, 'gpt');
  if exists (select 1 from jsonb_array_elements(starter) item
    where item ->> 'space_key' = 'engineering') then
    raise exception 'GPT Starter Pack exposed Engineering Memory';
  end if;
  starter := public.memory_v2_starter_pack_candidates(owner_id, 'claude');
  if exists (select 1 from jsonb_array_elements(starter) item
    where item ->> 'space_key' = 'engineering') then
    raise exception 'Claude Starter Pack exposed Engineering Memory';
  end if;

  private_memory := public.memory_v2_remember(owner_id, 'gpt', 'GPT private sentinel', '{}'::jsonb);
  claude_private := public.memory_v2_remember(owner_id, 'claude', 'Claude private sentinel', '{}'::jsonb);
  begin
    perform public.memory_v2_remember(owner_id, 'codex', 'forbidden private write', '{}'::jsonb);
    raise exception 'Codex wrote ordinary private Memory';
  exception when insufficient_privilege then null;
  end;
  recalled := public.memory_v2_recall_lexical(owner_id, 'codex', 'sentinel', 20);
  if jsonb_array_length(recalled) <> 0 then
    raise exception 'Codex read private Memory through ordinary recall';
  end if;
  if jsonb_array_length(public.memory_v2_history(
    owner_id, 'gpt', (created ->> 'memory_id')::uuid
  )) <> 0 then
    raise exception 'ordinary history exposed Engineering Memory';
  end if;

  opened := public.memory_v2_engineering_open(owner_id, 'gpt', 'memory.active_system');
  if opened -> 'entry' ->> 'current_revision_id' <> revised ->> 'revision_id'
    or jsonb_array_length(opened -> 'revisions') <> 2
    or opened::text like '%verified engineering evidence%'
    or opened::text like '%quote_text%' then
    raise exception 'Engineering history/current/source descriptor contract failed';
  end if;
  if (opened -> 'revisions' -> 0 -> 'sources' -> 0 ->> 'source_kind') <> 'git_commit'
    or jsonb_array_length(opened -> 'revisions' -> 1 -> 'sources') <> 1 then
    raise exception 'Engineering source inheritance or descriptors failed';
  end if;
  source_id := (opened -> 'revisions' -> 0 -> 'sources' -> 0 ->> 'source_id')::uuid;
  expanded := public.memory_v2_engineering_expand_source(owner_id, 'codex', source_id);
  if expanded ->> 'quote_text' <> 'verified engineering evidence'
    or expanded ->> 'source_kind' <> 'git_commit' then
    raise exception 'Engineering source explicit expansion failed';
  end if;
  begin
    perform public.memory_v2_expand_source(owner_id, 'gpt', source_id);
    raise exception 'ordinary source expansion exposed Engineering evidence';
  exception when insufficient_privilege then null;
  end;

  begin
    perform public.memory_v2_engineering_open(other_owner_id, 'owner', 'memory.active_system');
    raise exception 'cross-owner Engineering read succeeded';
  exception when insufficient_privilege then null;
  end;

  begin
    perform public.memory_v2_engineering_archive(owner_id, 'gpt', 'memory.active_system');
    raise exception 'non-owner archived Engineering Memory';
  exception when insufficient_privilege then null;
  end;
  archived := public.memory_v2_engineering_archive(owner_id, 'owner', 'memory.active_system');
  if archived ->> 'action' <> 'archived' then
    raise exception 'Owner archive failed';
  end if;
  recalled := public.memory_v2_engineering_recall(owner_id, 'owner', 'memory.active_system', 20, false);
  if exists (select 1 from jsonb_array_elements(recalled) item
    where item ->> 'subject_key' = 'memory.active_system') then
    raise exception 'archived Engineering subject remained in the default list';
  end if;
  recalled := public.memory_v2_engineering_recall(owner_id, 'owner', 'memory.active_system', 20, true);
  if not exists (select 1 from jsonb_array_elements(recalled) item
    where item ->> 'status' = 'archived') then
    raise exception 'Owner could not list archived Engineering subject';
  end if;
  begin
    perform public.memory_v2_engineering_recall(owner_id, 'claude', '', 20, true);
    raise exception 'non-owner actor listed archived Engineering subjects';
  exception when insufficient_privilege then null;
  end;
  restored := public.memory_v2_engineering_restore(owner_id, 'owner', 'memory.active_system');
  if restored ->> 'action' <> 'restored'
    or not exists (select 1 from jsonb_array_elements(public.memory_v2_engineering_recall(
      owner_id, 'gpt', 'memory.active_system', 20, false
    )) item where item ->> 'subject_key' = 'memory.active_system') then
    raise exception 'Owner restore failed';
  end if;

  -- Existing approved Shared behavior remains unchanged and is not part of Engineering recall.
  shared_memory := public.memory_v2_approve_shared(owner_id, (private_memory ->> 'memory_id')::uuid);
  if not exists (select 1 from jsonb_array_elements(
      public.memory_v2_recall_lexical(owner_id, 'gpt', 'sentinel', 20)
    ) item where item ->> 'memory_id' = shared_memory ->> 'memory_id')
    or not exists (select 1 from jsonb_array_elements(
      public.memory_v2_recall_lexical(owner_id, 'claude', 'sentinel', 20)
    ) item where item ->> 'memory_id' = shared_memory ->> 'memory_id') then
    raise exception 'approved Shared visibility changed';
  end if;
  recalled := public.memory_v2_engineering_recall(owner_id, 'gpt', 'sentinel', 20, false);
  if exists (select 1 from jsonb_array_elements(recalled) item
    where item ->> 'memory_id' = shared_memory ->> 'memory_id') then
    raise exception 'approved Shared leaked into Engineering recall';
  end if;

  -- subject_key is unique for the owner even when bypassing the RPC.
  begin
    insert into public.memory_v2_entries (
      owner_id, space_key, subject_key, created_by_actor, current_revision_id
    ) values (
      owner_id, 'engineering', 'runtime.codex', 'owner', extensions.gen_random_uuid()
    );
    raise exception 'duplicate Engineering subject_key was accepted';
  exception when unique_violation then null;
  end;
end;
$$;

rollback;
