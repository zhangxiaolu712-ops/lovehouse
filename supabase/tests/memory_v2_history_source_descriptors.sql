\set ON_ERROR_STOP on

begin;

insert into auth.users (id, email, raw_user_meta_data)
values ('61000000-0000-0000-0000-000000000061', 'memory-v2-history-owner@example.invalid', '{}'::jsonb)
on conflict (id) do nothing;

do $$
declare
  owner_id constant uuid := '61000000-0000-0000-0000-000000000061';
  gpt_empty jsonb;
  gpt_one jsonb;
  gpt_multi_v1 jsonb;
  gpt_multi_v2 jsonb;
  claude_one jsonb;
  shared_one jsonb;
  history_result jsonb;
  revision_one jsonb;
  revision_two jsonb;
  descriptor jsonb;
  source_id uuid;
  expanded jsonb;
  function_security_definer boolean;
  function_volatility "char";
  function_config text[];
begin
  select p.prosecdef, p.provolatile, p.proconfig
  into strict function_security_definer, function_volatility, function_config
  from pg_catalog.pg_proc p
  join pg_catalog.pg_namespace n on n.oid = p.pronamespace
  where n.nspname = 'public'
    and p.proname = 'memory_v2_history'
    and pg_catalog.pg_get_function_identity_arguments(p.oid) = 'p_owner_id uuid, p_actor text, p_memory_id uuid'
    and pg_catalog.pg_get_function_result(p.oid) = 'jsonb';

  if function_security_definer or function_volatility <> 's'
    or not ('search_path=pg_catalog, public' = any(coalesce(function_config, '{}'::text[]))) then
    raise exception 'memory_v2_history security, volatility or search_path changed';
  end if;
  if has_function_privilege('anon', 'public.memory_v2_history(uuid,text,uuid)', 'execute')
    or has_function_privilege('authenticated', 'public.memory_v2_history(uuid,text,uuid)', 'execute')
    or not has_function_privilege('service_role', 'public.memory_v2_history(uuid,text,uuid)', 'execute') then
    raise exception 'memory_v2_history grants widened or service_role grant was lost';
  end if;

  gpt_empty := public.memory_v2_remember(owner_id, 'gpt', 'History delta without a source', '{}'::jsonb);
  history_result := public.memory_v2_history(owner_id, 'gpt', (gpt_empty ->> 'memory_id')::uuid);
  revision_one := history_result -> 0;
  if jsonb_array_length(history_result) <> 1
    or jsonb_typeof(revision_one -> 'sources') <> 'array'
    or jsonb_array_length(revision_one -> 'sources') <> 0 then
    raise exception 'revision without a source did not return sources=[]';
  end if;

  gpt_one := public.memory_v2_remember(
    owner_id,
    'gpt',
    'History delta with one quoted source',
    jsonb_build_object(
      'event_time', '2026-08-20T12:00:00+08:00',
      'human_importance', 4,
      'ai_importance', 3,
      'metadata', jsonb_build_object('test_case', 'one_source'),
      'sources', jsonb_build_array(jsonb_build_object(
        'source_kind', 'manual_quote',
        'locator', jsonb_build_object('reference', 'history-delta-one'),
        'quote_text', 'HISTORY_DELTA_PRIVATE_QUOTE_ONE',
        'provenance', jsonb_build_object('source_channel', 'manual')
      ))
    )
  );
  history_result := public.memory_v2_history(owner_id, 'gpt', (gpt_one ->> 'memory_id')::uuid);
  revision_one := history_result -> 0;
  descriptor := revision_one -> 'sources' -> 0;

  if not (revision_one ?& array[
    'id', 'memory_id', 'revision_number', 'content', 'event_time',
    'human_importance', 'ai_importance', 'metadata', 'created_by_actor',
    'reason', 'created_at', 'sources'
  ]) then
    raise exception 'an original history field was lost';
  end if;
  if jsonb_array_length(revision_one -> 'sources') <> 1
    or descriptor ->> 'source_kind' <> 'manual_quote'
    or descriptor -> 'locator' <> jsonb_build_object('reference', 'history-delta-one')
    or descriptor -> 'provenance' <> jsonb_build_object('source_channel', 'manual')
    or descriptor ->> 'ordinal' <> '0'
    or not (descriptor ?& array['source_id', 'source_kind', 'locator', 'provenance', 'ordinal']) then
    raise exception 'one-source descriptor shape is incorrect';
  end if;
  if history_result::text like '%quote_text%'
    or history_result::text like '%HISTORY_DELTA_PRIVATE_QUOTE_ONE%' then
    raise exception 'history leaked quote_text or its value';
  end if;

  source_id := (descriptor ->> 'source_id')::uuid;
  expanded := public.memory_v2_expand_source(owner_id, 'gpt', source_id);
  if expanded ->> 'quote_text' <> 'HISTORY_DELTA_PRIVATE_QUOTE_ONE' then
    raise exception 'history source_id could not be explicitly expanded';
  end if;

  gpt_multi_v1 := public.memory_v2_remember(
    owner_id,
    'gpt',
    'Multi-source revision one',
    jsonb_build_object('sources', jsonb_build_array(
      jsonb_build_object(
        'source_kind', 'manual_summary',
        'locator', jsonb_build_object('reference', 'v1-first'),
        'provenance', jsonb_build_object('source_channel', 'manual')
      ),
      jsonb_build_object(
        'source_kind', 'manual_quote',
        'locator', jsonb_build_object('reference', 'v1-second'),
        'quote_text', 'HISTORY_DELTA_PRIVATE_QUOTE_TWO',
        'provenance', jsonb_build_object('source_channel', 'manual')
      )
    ))
  );
  gpt_multi_v2 := public.memory_v2_revise(
    owner_id,
    'gpt',
    (gpt_multi_v1 ->> 'memory_id')::uuid,
    'Multi-source revision two',
    jsonb_build_object(
      'reason', 'replace evidence set',
      'sources', jsonb_build_array(jsonb_build_object(
        'source_kind', 'manual_summary',
        'locator', jsonb_build_object('reference', 'v2-only'),
        'provenance', jsonb_build_object('source_channel', 'official_app')
      ))
    )
  );
  history_result := public.memory_v2_history(owner_id, 'gpt', (gpt_multi_v1 ->> 'memory_id')::uuid);
  revision_one := history_result -> 0;
  revision_two := history_result -> 1;
  if jsonb_array_length(history_result) <> 2
    or revision_one ->> 'revision_number' <> '1'
    or revision_two ->> 'revision_number' <> '2'
    or jsonb_array_length(revision_one -> 'sources') <> 2
    or revision_one -> 'sources' -> 0 ->> 'ordinal' <> '0'
    or revision_one -> 'sources' -> 1 ->> 'ordinal' <> '1'
    or jsonb_array_length(revision_two -> 'sources') <> 1
    or revision_two -> 'sources' -> 0 -> 'locator' <> jsonb_build_object('reference', 'v2-only')
    or (revision_one -> 'sources' -> 0 ->> 'source_id')
      = (revision_two -> 'sources' -> 0 ->> 'source_id') then
    raise exception 'multi-revision source ownership or ordinal order is incorrect';
  end if;
  if history_result::text like '%quote_text%'
    or history_result::text like '%HISTORY_DELTA_PRIVATE_QUOTE_TWO%' then
    raise exception 'multi-revision history leaked quote_text';
  end if;

  claude_one := public.memory_v2_remember(
    owner_id,
    'claude',
    'Claude private history',
    jsonb_build_object('sources', jsonb_build_array(jsonb_build_object(
      'source_kind', 'manual_summary',
      'locator', jsonb_build_object('reference', 'claude-only'),
      'provenance', jsonb_build_object('source_channel', 'manual')
    )))
  );
  if public.memory_v2_history(owner_id, 'gpt', (claude_one ->> 'memory_id')::uuid) <> '[]'::jsonb
    or public.memory_v2_history(owner_id, 'claude', (gpt_one ->> 'memory_id')::uuid) <> '[]'::jsonb then
    raise exception 'cross-private history became visible';
  end if;

  shared_one := public.memory_v2_approve_shared(owner_id, (gpt_one ->> 'memory_id')::uuid);
  history_result := public.memory_v2_history(owner_id, 'gpt', (shared_one ->> 'memory_id')::uuid);
  if jsonb_array_length(history_result) <> 1
    or jsonb_array_length(history_result -> 0 -> 'sources') <> 1 then
    raise exception 'GPT could not read approved Shared source descriptor';
  end if;
  history_result := public.memory_v2_history(owner_id, 'claude', (shared_one ->> 'memory_id')::uuid);
  if jsonb_array_length(history_result) <> 1
    or jsonb_array_length(history_result -> 0 -> 'sources') <> 1
    or history_result::text like '%quote_text%'
    or history_result::text like '%HISTORY_DELTA_PRIVATE_QUOTE_ONE%' then
    raise exception 'Claude approved Shared descriptor failed or leaked quote_text';
  end if;
  source_id := (history_result -> 0 -> 'sources' -> 0 ->> 'source_id')::uuid;
  expanded := public.memory_v2_expand_source(owner_id, 'claude', source_id);
  if expanded ->> 'quote_text' <> 'HISTORY_DELTA_PRIVATE_QUOTE_ONE' then
    raise exception 'approved Shared history source_id could not be explicitly expanded';
  end if;
end;
$$;

rollback;
