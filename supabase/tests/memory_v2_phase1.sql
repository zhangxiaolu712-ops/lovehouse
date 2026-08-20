\set ON_ERROR_STOP on

begin;

insert into auth.users (id, email, raw_user_meta_data)
values
  ('30000000-0000-0000-0000-000000000003', 'memory-v2-owner@example.invalid', '{}'::jsonb),
  ('40000000-0000-0000-0000-000000000004', 'memory-v2-other@example.invalid', '{}'::jsonb)
on conflict (id) do nothing;

do $$
declare
  table_count integer;
  insecure_count integer;
begin
  select count(*) into table_count
  from pg_class
  where relnamespace = 'public'::regnamespace
    and relkind = 'r'
    and relname in (
      'memory_v2_entries', 'memory_v2_revisions', 'memory_v2_sources',
      'memory_v2_revision_sources', 'memory_v2_embeddings'
    );
  if table_count <> 5 then
    raise exception 'Memory V2 Phase 1 must create exactly five tables, found %', table_count;
  end if;

  select count(*) into insecure_count
  from pg_class
  where relnamespace = 'public'::regnamespace
    and relname like 'memory_v2_%'
    and relkind = 'r'
    and (not relrowsecurity or not relforcerowsecurity);
  if insecure_count <> 0 then
    raise exception 'Every Memory V2 table must FORCE RLS';
  end if;

  if has_table_privilege('anon', 'public.memory_v2_entries', 'select')
    or has_table_privilege('authenticated', 'public.memory_v2_entries', 'select')
    or has_function_privilege('anon', 'public.memory_v2_remember(uuid,text,text,jsonb)', 'execute')
    or has_function_privilege('authenticated', 'public.memory_v2_recall_lexical(uuid,text,text,integer)', 'execute') then
    raise exception 'anon/authenticated gained direct Memory V2 access';
  end if;
end;
$$;

do $$
declare
  owner_id constant uuid := '30000000-0000-0000-0000-000000000003';
  gpt_saved jsonb;
  claude_saved jsonb;
  gpt_source_saved jsonb;
  revised jsonb;
  shared_saved jsonb;
  old_saved jsonb;
  new_saved jsonb;
  chinese_saved jsonb;
  chinese_unrelated jsonb;
  result jsonb;
  expanded jsonb;
  quote_source_id uuid;
  summary_source_id uuid;
  denied boolean;
begin
  gpt_saved := public.memory_v2_remember(owner_id, 'gpt', 'GPT only orchid memory', '{}'::jsonb);
  claude_saved := public.memory_v2_remember(owner_id, 'claude', 'Claude only cedar memory', '{}'::jsonb);

  if gpt_saved ->> 'space_key' <> 'gpt' or claude_saved ->> 'space_key' <> 'claude' then
    raise exception 'remember(content) did not use the fixed actor namespace';
  end if;

  result := public.memory_v2_recall_lexical(owner_id, 'gpt', 'memory', 20);
  if not exists (select 1 from jsonb_array_elements(result) item where item ->> 'memory_id' = gpt_saved ->> 'memory_id')
    or exists (select 1 from jsonb_array_elements(result) item where item ->> 'memory_id' = claude_saved ->> 'memory_id') then
    raise exception 'GPT private isolation failed';
  end if;

  result := public.memory_v2_recall_lexical(owner_id, 'claude', 'memory', 20);
  if not exists (select 1 from jsonb_array_elements(result) item where item ->> 'memory_id' = claude_saved ->> 'memory_id')
    or exists (select 1 from jsonb_array_elements(result) item where item ->> 'memory_id' = gpt_saved ->> 'memory_id') then
    raise exception 'Claude private isolation failed';
  end if;

  gpt_source_saved := public.memory_v2_remember(
    owner_id,
    'gpt',
    'Evidence-backed rose memory',
    jsonb_build_object(
      'sources', jsonb_build_array(
        jsonb_build_object(
          'source_kind', 'manual_quote',
          'quote_text', 'rose source quote',
          'provenance', jsonb_build_object('channel', 'manual')
        ),
        jsonb_build_object(
          'source_kind', 'manual_summary',
          'provenance', jsonb_build_object('channel', 'official_app')
        )
      )
    )
  );

  select links.source_id into strict quote_source_id
  from public.memory_v2_revision_sources links
  join public.memory_v2_sources source on source.id = links.source_id
  where links.revision_id = (gpt_source_saved ->> 'revision_id')::uuid
    and source.source_kind = 'manual_quote';

  expanded := public.memory_v2_expand_source(owner_id, 'gpt', quote_source_id);
  if expanded ->> 'available' <> 'true' or expanded ->> 'quote_text' <> 'rose source quote' then
    raise exception 'manual quote did not expand';
  end if;

  select links.source_id into strict summary_source_id
  from public.memory_v2_revision_sources links
  join public.memory_v2_sources source on source.id = links.source_id
  where links.revision_id = (gpt_source_saved ->> 'revision_id')::uuid
    and source.source_kind = 'manual_summary';
  expanded := public.memory_v2_expand_source(owner_id, 'gpt', summary_source_id);
  if expanded ->> 'available' <> 'false' or expanded -> 'quote_text' <> 'null'::jsonb then
    raise exception 'manual summary fabricated evidence';
  end if;

  denied := false;
  begin
    perform public.memory_v2_expand_source(owner_id, 'claude', quote_source_id);
  exception when insufficient_privilege then
    denied := true;
  end;
  if not denied then
    raise exception 'Claude expanded a GPT private source';
  end if;

  revised := public.memory_v2_revise(
    owner_id,
    'gpt',
    (gpt_source_saved ->> 'memory_id')::uuid,
    'Evidence-backed rose memory revision two',
    jsonb_build_object('reason', 'understanding changed')
  );
  if revised ->> 'revision_number' <> '2' then
    raise exception 'revision number did not advance';
  end if;
  if (select count(*) from public.memory_v2_revisions
      where memory_id = (gpt_source_saved ->> 'memory_id')::uuid) <> 2 then
    raise exception 'old revision was overwritten';
  end if;
  if not exists (
    select 1 from public.memory_v2_revision_sources
    where revision_id = (revised ->> 'revision_id')::uuid and source_id = quote_source_id
  ) then
    raise exception 'revision did not inherit source links';
  end if;

  shared_saved := public.memory_v2_approve_shared(owner_id, (gpt_source_saved ->> 'memory_id')::uuid);
  result := public.memory_v2_recall_lexical(owner_id, 'claude', 'rose', 20);
  if not exists (
    select 1 from jsonb_array_elements(result) item
    where item ->> 'memory_id' = shared_saved ->> 'memory_id'
      and item ->> 'space_key' = 'shared'
  ) then
    raise exception 'approved Shared was not visible to Claude';
  end if;
  expanded := public.memory_v2_expand_source(owner_id, 'claude', quote_source_id);
  if expanded ->> 'quote_text' <> 'rose source quote' then
    raise exception 'approved Shared did not carry its exact source';
  end if;

  old_saved := public.memory_v2_remember(owner_id, 'gpt', '偏好 A old fact', '{}'::jsonb);
  new_saved := public.memory_v2_remember(
    owner_id,
    'gpt',
    '偏好 B new fact',
    jsonb_build_object('supersedes_memory_id', old_saved ->> 'memory_id')
  );
  result := public.memory_v2_recall_lexical(owner_id, 'gpt', '偏好', 20);
  if exists (select 1 from jsonb_array_elements(result) item where item ->> 'memory_id' = old_saved ->> 'memory_id')
    or not exists (select 1 from jsonb_array_elements(result) item where item ->> 'memory_id' = new_saved ->> 'memory_id') then
    raise exception 'superseded currentness filter failed';
  end if;
  if jsonb_array_length(public.memory_v2_history(owner_id, 'gpt', (old_saved ->> 'memory_id')::uuid)) <> 1 then
    raise exception 'superseded history was lost';
  end if;

  perform public.memory_v2_store_embedding(
    owner_id,
    'gpt',
    (new_saved ->> 'revision_id')::uuid,
    'qwen3-embedding:4b',
    array_fill(0.01::real, array[1536])
  );
  result := public.memory_v2_recall_semantic(
    owner_id,
    'gpt',
    array_fill(0.01::real, array[1536]),
    'qwen3-embedding:4b',
    10
  );
  if not exists (select 1 from jsonb_array_elements(result) item where item ->> 'memory_id' = new_saved ->> 'memory_id') then
    raise exception 'semantic sidecar did not return the indexed revision';
  end if;

  result := public.memory_v2_recall_lexical(owner_id, 'gpt', 'orchid', 10);
  if jsonb_array_length(result) = 0 then
    raise exception 'lexical recall depended on the embedding sidecar';
  end if;

  chinese_saved := public.memory_v2_remember(
    owner_id, 'gpt', '我以前很喜欢苹果茉莉绿奶茶', '{}'::jsonb
  );
  chinese_unrelated := public.memory_v2_remember(
    owner_id, 'gpt', '昨天下午我们一起去公园散步看晚霞', '{}'::jsonb
  );
  result := public.memory_v2_recall_lexical(owner_id, 'gpt', '苹果奶茶', 10);
  if not exists (
    select 1 from jsonb_array_elements(result) item
    where item ->> 'memory_id' = chinese_saved ->> 'memory_id'
  ) then
    raise exception 'Chinese lexical fallback did not recall the split phrase';
  end if;
  if exists (
    select 1 from jsonb_array_elements(result) item
    where item ->> 'memory_id' = chinese_unrelated ->> 'memory_id'
  ) then
    raise exception 'Chinese lexical fallback overmatched unrelated content';
  end if;
end;
$$;

rollback;
