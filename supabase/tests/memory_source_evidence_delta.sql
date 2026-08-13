\set ON_ERROR_STOP on

begin;

insert into auth.users (id, email, raw_user_meta_data)
values ('71000000-0000-0000-0000-000000000001', 'source-owner@example.invalid', '{}'::jsonb)
on conflict (id) do nothing;

do $$
declare
  owner_id constant uuid := '71000000-0000-0000-0000-000000000001';
  result jsonb;
  item jsonb;
  gpt_id bigint;
  second_id bigint;
  claude_id bigint;
  candidate_id bigint;
  revision_1 bigint;
  revision_2 bigint;
  revision_3 bigint;
  quote_source bigint;
  summary_source bigint;
  message_source bigint;
  range_source bigint;
begin
  result := public.memory_runtime_remember_gpt(owner_id, '71000000-0000-4000-8000-000000000001',
    jsonb_build_object(
      'content', repeat('full canonical memory ', 40),
      'summary', 'A bounded authored summary',
      'memory_type', 'reflection',
      'tags', jsonb_build_array('free-tag'),
      'sources', jsonb_build_array(
        jsonb_build_object('source_channel','chatgpt_app','source_kind','manual_quote','locator',jsonb_build_object('reference','selected user text'),'quote_text','exact manual quote'),
        jsonb_build_object('source_channel','chatgpt_app','source_kind','manual_summary','locator',jsonb_build_object('reference','user supplied summary')),
        jsonb_build_object('source_channel','lovehouse','source_kind','lovehouse_message','locator',jsonb_build_object('message_id',101)),
        jsonb_build_object('source_channel','lovehouse','source_kind','lovehouse_message_range','locator',jsonb_build_object('start_message_id',102,'end_message_id',110))
      )
    ));
  gpt_id := (result#>>'{memory,id}')::bigint;
  select id into strict revision_1 from public.memory_revisions where memory_id = gpt_id and revision_number = 1;
  select source_id into strict quote_source from public.memory_revision_sources where revision_id = revision_1 and ordinal = 1;
  select source_id into strict summary_source from public.memory_revision_sources where revision_id = revision_1 and ordinal = 2;
  select source_id into strict message_source from public.memory_revision_sources where revision_id = revision_1 and ordinal = 3;
  select source_id into strict range_source from public.memory_revision_sources where revision_id = revision_1 and ordinal = 4;

  if (select count(*) from public.memory_revision_sources where revision_id = revision_1) <> 4 then
    raise exception 'one revision did not retain all four sources';
  end if;
  if not exists (select 1 from public.memory_entries where id=gpt_id and summary='A bounded authored summary' and content like 'full canonical%')
    or not exists (select 1 from public.memory_revisions where id=revision_1 and summary='A bounded authored summary' and content like 'full canonical%') then
    raise exception 'summary replaced or lost canonical content';
  end if;

  result := public.memory_runtime_recall_gpt(owner_id, '71000000-0000-4000-8000-000000000002', 'canonical', 10, null, '{}'::text[]);
  select value into strict item from jsonb_array_elements(result->'items') x(value) where (value->>'memory_id')::bigint = gpt_id;
  if item ? 'content' or item ? 'quote_text' or item ? 'locator'
    or item->>'summary' <> 'A bounded authored summary'
    or item->>'summary_origin' <> 'stored'
    or (item->>'source_count')::integer <> 4
    or jsonb_path_exists(item, '$.sources[*].quote_text') then
    raise exception 'recall is not summary-first: %', item;
  end if;

  result := public.memory_runtime_list_gpt(owner_id, '71000000-0000-4000-8000-000000000003', 50, null, null, '{}'::text[], null);
  if exists (select 1 from jsonb_array_elements(result->'items') x(value) where value ? 'content' or value ? 'quote_text') then
    raise exception 'list returned full content or quote';
  end if;
  result := public.memory_runtime_memory_box_gpt(owner_id, '71000000-0000-4000-8000-000000000004', 4);
  if exists (select 1 from jsonb_array_elements(result->'items') x(value) where value ? 'content' or value ? 'quote_text') then
    raise exception 'Memory Box returned full content or quote';
  end if;

  result := public.memory_runtime_remember_gpt(owner_id, '71000000-0000-4000-8000-000000000005',
    jsonb_build_object('content',repeat('legacy compatible body ',30),'sources',jsonb_build_array(jsonb_build_object('source_id',quote_source))));
  second_id := (result#>>'{memory,id}')::bigint;
  result := public.memory_runtime_recall_gpt(owner_id, '71000000-0000-4000-8000-000000000006', 'legacy compatible', 10, null, '{}'::text[]);
  select value into strict item from jsonb_array_elements(result->'items') x(value) where (value->>'memory_id')::bigint = second_id;
  if item->>'summary_origin' <> 'excerpt_fallback' or length(item->>'summary') > 320 or item ? 'content' then
    raise exception 'missing summary fallback is unsafe: %', item;
  end if;
  if (select count(distinct memory_id) from public.memory_revision_sources where source_id = quote_source) <> 2 then
    raise exception 'one source cannot be reused by multiple memories';
  end if;

  result := public.memory_runtime_remember_claude(owner_id, '71000000-0000-4000-8000-000000000007',
    jsonb_build_object('content','Claude private source','sources',jsonb_build_array(jsonb_build_object('source_channel','claude_app','source_kind','manual_quote','locator',jsonb_build_object('reference','selection'),'quote_text','Claude quote'))));
  claude_id := (result#>>'{memory,id}')::bigint;

  result := public.memory_runtime_expand_source_gpt(owner_id, '71000000-0000-4000-8000-000000000008', quote_source);
  if result#>>'{source,quote_text}' <> 'exact manual quote' then raise exception 'manual quote did not expand exactly'; end if;
  result := public.memory_runtime_expand_source_gpt(owner_id, '71000000-0000-4000-8000-000000000009', summary_source);
  if result#>'{source,quote_text}' <> 'null'::jsonb or result#>>'{source,source_kind}' <> 'manual_summary' then raise exception 'manual summary forged evidence'; end if;
  result := public.memory_runtime_expand_source_gpt(owner_id, '71000000-0000-4000-8000-000000000010', message_source);
  if (result#>>'{source,locator,message_id}')::bigint <> 101 then raise exception 'message locator lost'; end if;
  result := public.memory_runtime_expand_source_gpt(owner_id, '71000000-0000-4000-8000-000000000011', range_source);
  if (result#>>'{source,locator,end_message_id}')::bigint <> 110 then raise exception 'range locator lost'; end if;

  result := public.memory_runtime_expand_source_claude(owner_id, '71000000-0000-4000-8000-000000000012', quote_source);
  if result->>'error_code' <> 'MEMORY_SOURCE_ACCESS_DENIED' then raise exception 'Claude read GPT private source'; end if;
  select source_id into strict summary_source from public.memory_revision_sources rs join public.memory_revisions r on r.id=rs.revision_id where r.memory_id=claude_id limit 1;
  result := public.memory_runtime_expand_source_gpt(owner_id, '71000000-0000-4000-8000-000000000013', summary_source);
  if result->>'error_code' <> 'MEMORY_SOURCE_ACCESS_DENIED' then raise exception 'GPT read Claude private source'; end if;

  result := public.memory_runtime_revise_gpt(owner_id, '71000000-0000-4000-8000-000000000014', gpt_id,
    '{"content":"revision two inherits sources"}'::jsonb, 'content clarification');
  select id into strict revision_2 from public.memory_revisions where memory_id=gpt_id and revision_number=2;
  if (select count(*) from public.memory_revision_sources where revision_id=revision_2) <> 4 then raise exception 'revise did not inherit sources'; end if;

  result := public.memory_runtime_revise_gpt(owner_id, '71000000-0000-4000-8000-000000000015', gpt_id,
    jsonb_build_object('sources',jsonb_build_array(jsonb_build_object('source_id',quote_source))), 'replace source provenance');
  select id into strict revision_3 from public.memory_revisions where memory_id=gpt_id and revision_number=3;
  if (select count(*) from public.memory_revision_sources where revision_id=revision_3) <> 1
    or (select count(*) from public.memory_revision_sources where revision_id=revision_2) <> 4 then
    raise exception 'source replacement destroyed or failed to version old links';
  end if;

  result := public.memory_runtime_propose_shared_gpt(owner_id, '71000000-0000-4000-8000-000000000016', gpt_id, 'share exact source revision');
  candidate_id := (result#>>'{memory,id}')::bigint;
  perform set_config('request.jwt.claim.sub', owner_id::text, true);
  perform public.memory_owner_transition_shared(candidate_id, 'approved', 'approved for source test');
  result := public.memory_runtime_expand_source_claude(owner_id, '71000000-0000-4000-8000-000000000017', quote_source);
  if result->>'ok' <> 'true' then raise exception 'approved Shared source not readable by peer'; end if;

  begin
    update public.memory_revision_sources set ordinal=2 where revision_id=revision_3;
    raise exception 'revision source link was mutable';
  exception when sqlstate '55000' then null;
  end;
end;
$$;

create or replace function public.memory_source_test_reject_audit() returns trigger language plpgsql set search_path='' as $$
begin
  if new.action='expand_source' and new.request_id='71000000-0000-4000-8000-000000000099'::uuid then raise exception 'forced audit failure'; end if;
  return new;
end $$;
create trigger memory_source_test_reject_audit before insert on public.memory_audit_log for each row execute function public.memory_source_test_reject_audit();
do $$
declare result jsonb; source_id bigint;
begin
  select id into source_id from public.memory_sources where owner_id='71000000-0000-0000-0000-000000000001' and created_by_actor='gpt' limit 1;
  begin
    perform public.memory_runtime_expand_source_gpt('71000000-0000-0000-0000-000000000001','71000000-0000-4000-8000-000000000099',source_id);
    raise exception 'audit failure did not fail closed';
  exception when raise_exception then
    if sqlerrm='audit failure did not fail closed' then raise; end if;
  end;
end $$;
drop trigger memory_source_test_reject_audit on public.memory_audit_log;
drop function public.memory_source_test_reject_audit();

do $$
begin
  if not (select relrowsecurity and relforcerowsecurity from pg_class where oid='public.memory_sources'::regclass)
    or not (select relrowsecurity and relforcerowsecurity from pg_class where oid='public.memory_revision_sources'::regclass) then
    raise exception 'new source tables do not FORCE RLS';
  end if;
  if has_table_privilege('anon','public.memory_sources','SELECT')
    or has_table_privilege('authenticated','public.memory_sources','SELECT')
    or has_table_privilege('service_role','public.memory_sources','SELECT') then
    raise exception 'Data API role has direct source table access';
  end if;
  if not has_function_privilege('service_role','public.memory_runtime_expand_source_gpt(uuid,uuid,bigint)','EXECUTE')
    or has_function_privilege('anon','public.memory_runtime_expand_source_gpt(uuid,uuid,bigint)','EXECUTE') then
    raise exception 'expand_source RPC grants are incorrect';
  end if;
end $$;

rollback;
