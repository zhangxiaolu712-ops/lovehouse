begin;

-- Unified Memory System Phase 3 runtime.
-- This migration adds only fixed-actor RPC doors. It does not read legacy
-- tables, migrate legacy content, or enable the Bridge runtime flag.

create or replace function public.memory_runtime_internal_audit(
  p_owner_id uuid,
  p_actor text,
  p_action text,
  p_memory_id bigint,
  p_space_key text,
  p_result text,
  p_reason_code text,
  p_request_id uuid,
  p_result_count integer,
  p_result_spaces text[]
)
returns void
language plpgsql
set search_path = ''
as $$
begin
  if p_actor not in ('gpt', 'claude') then
    raise exception 'Runtime audit actor must be fixed by a trusted wrapper'
      using errcode = '42501';
  end if;
  if p_result not in ('allowed', 'denied', 'error') then
    raise exception 'Invalid runtime audit result' using errcode = '23514';
  end if;

  insert into public.memory_audit_log (
    owner_id, actor, action, memory_id, space_key, result, reason_code,
    request_id, result_count, result_spaces, metadata
  ) values (
    p_owner_id, p_actor, left(p_action, 100), p_memory_id, p_space_key,
    p_result, p_reason_code, p_request_id, p_result_count,
    coalesce(p_result_spaces, '{}'::text[]), '{}'::jsonb
  );
end;
$$;

create or replace function public.memory_runtime_internal_get(
  p_actor text,
  p_owner_id uuid,
  p_request_id uuid,
  p_memory_id bigint
)
returns jsonb
language plpgsql
set search_path = ''
as $$
declare
  found_entry public.memory_entries%rowtype;
begin
  select * into found_entry
  from public.memory_entries
  where owner_id = p_owner_id and id = p_memory_id;

  if not found then
    perform public.memory_runtime_internal_audit(
      p_owner_id, p_actor, 'get', p_memory_id, null, 'allowed', null,
      p_request_id, 0, '{}'::text[]
    );
    return pg_catalog.jsonb_build_object('ok', true, 'memory', null);
  end if;

  if not (
    found_entry.space_key = p_actor
    or (found_entry.space_key = 'shared' and found_entry.shared_status = 'approved')
  ) then
    perform public.memory_runtime_internal_audit(
      p_owner_id, p_actor, 'get', p_memory_id, found_entry.space_key,
      'denied', 'MEMORY_ACCESS_DENIED', p_request_id, 0, '{}'::text[]
    );
    return pg_catalog.jsonb_build_object(
      'ok', false,
      'error_code', 'MEMORY_ACCESS_DENIED',
      'message', 'Memory is outside the fixed actor scope',
      'audit_persisted', true
    );
  end if;

  perform public.memory_runtime_internal_audit(
    p_owner_id, p_actor, 'get', p_memory_id, found_entry.space_key,
    'allowed', null, p_request_id, 1, array[found_entry.space_key]
  );
  return pg_catalog.jsonb_build_object('ok', true, 'memory', to_jsonb(found_entry));
end;
$$;

create or replace function public.memory_runtime_internal_list(
  p_actor text,
  p_owner_id uuid,
  p_request_id uuid,
  p_limit integer,
  p_cursor_id bigint,
  p_memory_type text,
  p_tags text[],
  p_retention text
)
returns jsonb
language plpgsql
set search_path = ''
as $$
declare
  safe_limit integer := least(greatest(coalesce(p_limit, 20), 1), 50);
  cursor_created_at timestamptz;
  cursor_memory_id bigint;
  items jsonb;
  spaces text[];
begin
  if p_cursor_id is not null then
    select e.created_at, e.id into cursor_created_at, cursor_memory_id
    from public.memory_entries e
    where e.owner_id = p_owner_id
      and e.id = p_cursor_id
      and (e.space_key = p_actor or (e.space_key = 'shared' and e.shared_status = 'approved'));
    if not found then
      perform public.memory_runtime_internal_audit(
        p_owner_id, p_actor, 'list', p_cursor_id, null, 'denied',
        'INVALID_MEMORY_CURSOR', p_request_id, 0, '{}'::text[]
      );
      return pg_catalog.jsonb_build_object(
        'ok', false, 'error_code', 'INVALID_MEMORY_CURSOR',
        'message', 'Cursor is outside the fixed actor scope',
        'audit_persisted', true
      );
    end if;
  end if;

  select
    coalesce(pg_catalog.jsonb_agg(to_jsonb(q) order by q.created_at desc, q.id desc), '[]'::jsonb),
    coalesce(pg_catalog.array_agg(distinct q.space_key) filter (where q.space_key is not null), '{}'::text[])
  into items, spaces
  from (
    select e.*
    from public.memory_entries e
    where e.owner_id = p_owner_id
      and (e.space_key = p_actor or (e.space_key = 'shared' and e.shared_status = 'approved'))
      and (p_memory_type is null or e.memory_type = p_memory_type)
      and (coalesce(p_tags, '{}'::text[]) <@ e.tags)
      and (p_retention is null or e.retention = p_retention)
      and (
        p_cursor_id is null
        or (e.created_at, e.id) < (cursor_created_at, cursor_memory_id)
      )
    order by e.created_at desc, e.id desc
    limit safe_limit
  ) q;

  perform public.memory_runtime_internal_audit(
    p_owner_id, p_actor, 'list', null, null, 'allowed', null,
    p_request_id, pg_catalog.jsonb_array_length(items), spaces
  );
  return pg_catalog.jsonb_build_object('ok', true, 'items', items);
end;
$$;

create or replace function public.memory_runtime_internal_recall(
  p_actor text,
  p_owner_id uuid,
  p_request_id uuid,
  p_query text,
  p_limit integer,
  p_cursor_id bigint,
  p_tags text[]
)
returns jsonb
language plpgsql
set search_path = ''
as $$
declare
  safe_limit integer := least(greatest(coalesce(p_limit, 5), 1), 10);
  normalized_query text := left(pg_catalog.btrim(coalesce(p_query, '')), 500);
  cursor_importance smallint;
  cursor_created_at timestamptz;
  cursor_memory_id bigint;
  items jsonb;
  spaces text[];
begin
  if normalized_query = '' then
    perform public.memory_runtime_internal_audit(
      p_owner_id, p_actor, 'recall', null, null, 'denied',
      'INVALID_MEMORY_QUERY', p_request_id, 0, '{}'::text[]
    );
    return pg_catalog.jsonb_build_object(
      'ok', false, 'error_code', 'INVALID_MEMORY_QUERY',
      'message', 'query is required', 'audit_persisted', true
    );
  end if;

  if p_cursor_id is not null then
    select e.importance, e.created_at, e.id
      into cursor_importance, cursor_created_at, cursor_memory_id
    from public.memory_entries e
    where e.owner_id = p_owner_id
      and e.id = p_cursor_id
      and (e.space_key = p_actor or (e.space_key = 'shared' and e.shared_status = 'approved'))
      and pg_catalog.strpos(pg_catalog.lower(e.content), pg_catalog.lower(normalized_query)) > 0
      and (coalesce(p_tags, '{}'::text[]) <@ e.tags);
    if not found then
      perform public.memory_runtime_internal_audit(
        p_owner_id, p_actor, 'recall', p_cursor_id, null, 'denied',
        'INVALID_MEMORY_CURSOR', p_request_id, 0, '{}'::text[]
      );
      return pg_catalog.jsonb_build_object(
        'ok', false, 'error_code', 'INVALID_MEMORY_CURSOR',
        'message', 'Cursor is outside this recall result',
        'audit_persisted', true
      );
    end if;
  end if;

  select
    coalesce(pg_catalog.jsonb_agg(
      to_jsonb(q) order by q.importance desc, q.created_at desc, q.id desc
    ), '[]'::jsonb),
    coalesce(pg_catalog.array_agg(distinct q.space_key) filter (where q.space_key is not null), '{}'::text[])
  into items, spaces
  from (
    select e.*
    from public.memory_entries e
    where e.owner_id = p_owner_id
      and (e.space_key = p_actor or (e.space_key = 'shared' and e.shared_status = 'approved'))
      and pg_catalog.strpos(pg_catalog.lower(e.content), pg_catalog.lower(normalized_query)) > 0
      and (coalesce(p_tags, '{}'::text[]) <@ e.tags)
      and (
        p_cursor_id is null
        or (e.importance, e.created_at, e.id)
          < (cursor_importance, cursor_created_at, cursor_memory_id)
      )
    order by e.importance desc, e.created_at desc, e.id desc
    limit safe_limit
  ) q;

  perform public.memory_runtime_internal_audit(
    p_owner_id, p_actor, 'recall', null, null, 'allowed', null,
    p_request_id, pg_catalog.jsonb_array_length(items), spaces
  );
  return pg_catalog.jsonb_build_object('ok', true, 'items', items);
end;
$$;

create or replace function public.memory_runtime_internal_remember(
  p_actor text,
  p_owner_id uuid,
  p_request_id uuid,
  p_memory jsonb
)
returns jsonb
language plpgsql
set search_path = ''
as $$
declare
  claim public.memory_mutation_idempotency%rowtype;
  saved public.memory_entries%rowtype;
  tags text[];
  emotion jsonb;
  importance smallint;
  request_material jsonb;
begin
  if p_request_id is null or p_memory is null or pg_catalog.jsonb_typeof(p_memory) <> 'object' then
    raise exception 'Trusted request id and normalized memory object are required'
      using errcode = '23514';
  end if;

  request_material := pg_catalog.jsonb_build_object('memory', p_memory);
  select * into strict claim
  from public.memory_claim_idempotency(
    p_owner_id, p_actor, 'remember', p_request_id, request_material
  );

  if claim.status = 'completed' then
    select * into strict saved from public.memory_entries where id = claim.resource_id;
    perform public.memory_runtime_internal_audit(
      p_owner_id, p_actor, 'remember_replay', saved.id, saved.space_key,
      'allowed', null, p_request_id, 1, array[saved.space_key]
    );
    return pg_catalog.jsonb_build_object(
      'ok', true, 'memory', to_jsonb(saved), 'replayed', true
    );
  end if;

  select coalesce(pg_catalog.array_agg(value), '{}'::text[])
    into tags
  from pg_catalog.jsonb_array_elements_text(
    case when pg_catalog.jsonb_typeof(p_memory -> 'tags') = 'array'
      then p_memory -> 'tags' else '[]'::jsonb end
  ) as tag_values(value);
  emotion := case when pg_catalog.jsonb_typeof(p_memory -> 'emotion') = 'object'
    then p_memory -> 'emotion' else '{}'::jsonb end;
  importance := case when coalesce(p_memory ->> 'importance', '') ~ '^[1-5]$'
    then (p_memory ->> 'importance')::smallint else 1 end;

  insert into public.memory_entries (
    owner_id, space_key, memory_type, tags, title, content, emotion,
    importance, retention, author, source_type, source_model, source_ref,
    source_metadata, created_by_actor
  ) values (
    p_owner_id,
    p_actor,
    coalesce(nullif(p_memory ->> 'memory_type', ''), 'fact'),
    tags,
    nullif(p_memory ->> 'title', ''),
    pg_catalog.btrim(p_memory ->> 'content'),
    emotion,
    importance,
    nullif(p_memory ->> 'retention', ''),
    nullif(p_memory ->> 'author', ''),
    'mcp_runtime',
    p_actor,
    nullif(p_memory ->> 'source_ref', ''),
    '{}'::jsonb,
    p_actor
  ) returning * into saved;

  perform public.memory_runtime_internal_audit(
    p_owner_id, p_actor, 'remember', saved.id, saved.space_key,
    'allowed', null, p_request_id, 1, array[saved.space_key]
  );
  update public.memory_mutation_idempotency
    set status = 'completed', resource_id = saved.id,
        response_metadata = pg_catalog.jsonb_build_object(
          'memory_id', saved.id, 'revision_number', saved.revision_number
        ),
        completed_at = now()
    where id = claim.id;

  return pg_catalog.jsonb_build_object(
    'ok', true, 'memory', to_jsonb(saved), 'replayed', false
  );
end;
$$;

create or replace function public.memory_runtime_internal_revise(
  p_actor text,
  p_owner_id uuid,
  p_request_id uuid,
  p_memory_id bigint,
  p_patch jsonb,
  p_reason text
)
returns jsonb
language plpgsql
set search_path = ''
as $$
declare
  claim public.memory_mutation_idempotency%rowtype;
  current_entry public.memory_entries%rowtype;
  revised public.memory_entries%rowtype;
  new_tags text[];
  new_emotion jsonb;
  new_importance smallint;
  new_memory_type text;
  new_title text;
  new_content text;
  new_author text;
  new_retention text;
  request_material jsonb;
begin
  select * into current_entry
  from public.memory_entries
  where owner_id = p_owner_id and id = p_memory_id
  for update;

  if not found or current_entry.space_key <> p_actor then
    perform public.memory_runtime_internal_audit(
      p_owner_id, p_actor, 'revise', p_memory_id,
      case when current_entry.id is null then null else current_entry.space_key end,
      'denied', 'MEMORY_ACCESS_DENIED', p_request_id, 0, '{}'::text[]
    );
    return pg_catalog.jsonb_build_object(
      'ok', false, 'error_code', 'MEMORY_ACCESS_DENIED',
      'message', 'Only the fixed actor private memory can be revised',
      'audit_persisted', true
    );
  end if;

  if p_request_id is null or p_patch is null or pg_catalog.jsonb_typeof(p_patch) <> 'object'
    or nullif(pg_catalog.btrim(p_reason), '') is null
  then
    raise exception 'Trusted request id, patch and revision reason are required'
      using errcode = '23514';
  end if;

  request_material := pg_catalog.jsonb_build_object(
    'memory_id', p_memory_id, 'patch', p_patch, 'reason', pg_catalog.btrim(p_reason)
  );
  select * into strict claim
  from public.memory_claim_idempotency(
    p_owner_id, p_actor, 'revise', p_request_id, request_material
  );
  if claim.status = 'completed' then
    select * into strict revised from public.memory_entries where id = claim.resource_id;
    perform public.memory_runtime_internal_audit(
      p_owner_id, p_actor, 'revise_replay', revised.id, revised.space_key,
      'allowed', null, p_request_id, 1, array[revised.space_key]
    );
    return pg_catalog.jsonb_build_object(
      'ok', true, 'memory', to_jsonb(revised), 'replayed', true
    );
  end if;

  new_tags := current_entry.tags;
  if p_patch ? 'tags' and pg_catalog.jsonb_typeof(p_patch -> 'tags') = 'array' then
    select coalesce(pg_catalog.array_agg(value), '{}'::text[])
      into new_tags
    from pg_catalog.jsonb_array_elements_text(p_patch -> 'tags') as tag_values(value);
  end if;
  new_emotion := case when pg_catalog.jsonb_typeof(p_patch -> 'emotion') = 'object'
    then p_patch -> 'emotion' else current_entry.emotion end;
  new_importance := case when coalesce(p_patch ->> 'importance', '') ~ '^[1-5]$'
    then (p_patch ->> 'importance')::smallint else current_entry.importance end;
  new_memory_type := coalesce(nullif(p_patch ->> 'memory_type', ''), current_entry.memory_type);
  new_title := case when p_patch ? 'title' then nullif(p_patch ->> 'title', '') else current_entry.title end;
  new_content := coalesce(nullif(pg_catalog.btrim(p_patch ->> 'content'), ''), current_entry.content);
  new_author := case when p_patch ? 'author' then nullif(p_patch ->> 'author', '') else current_entry.author end;
  new_retention := case when p_patch ? 'retention'
    then nullif(p_patch ->> 'retention', '') else current_entry.retention end;

  if row(
    new_title, new_content, new_author, new_memory_type, new_tags,
    new_emotion, new_importance, new_retention
  ) is not distinct from row(
    current_entry.title, current_entry.content, current_entry.author,
    current_entry.memory_type, current_entry.tags, current_entry.emotion,
    current_entry.importance, current_entry.retention
  ) then
    delete from public.memory_mutation_idempotency where id = claim.id;
    perform public.memory_runtime_internal_audit(
      p_owner_id, p_actor, 'revise', p_memory_id, current_entry.space_key,
      'denied', 'NO_MEMORY_CHANGE', p_request_id, 0, '{}'::text[]
    );
    return pg_catalog.jsonb_build_object(
      'ok', false, 'error_code', 'NO_MEMORY_CHANGE',
      'message', 'Revision must change memory content or semantics',
      'audit_persisted', true
    );
  end if;

  update public.memory_entries
    set title = new_title,
        content = new_content,
        author = new_author,
        memory_type = new_memory_type,
        tags = new_tags,
        emotion = new_emotion,
        importance = new_importance,
        retention = new_retention,
        updated_by_actor = p_actor,
        revision_reason = pg_catalog.btrim(p_reason)
    where id = p_memory_id
    returning * into revised;

  perform public.memory_runtime_internal_audit(
    p_owner_id, p_actor, 'revise', revised.id, revised.space_key,
    'allowed', null, p_request_id, 1, array[revised.space_key]
  );
  update public.memory_mutation_idempotency
    set status = 'completed', resource_id = revised.id,
        response_metadata = pg_catalog.jsonb_build_object(
          'memory_id', revised.id, 'revision_number', revised.revision_number
        ),
        completed_at = now()
    where id = claim.id;

  return pg_catalog.jsonb_build_object(
    'ok', true, 'memory', to_jsonb(revised), 'replayed', false
  );
end;
$$;

create or replace function public.memory_runtime_internal_propose_shared(
  p_actor text,
  p_owner_id uuid,
  p_request_id uuid,
  p_memory_id bigint,
  p_reason text
)
returns jsonb
language plpgsql
set search_path = ''
as $$
declare
  claim public.memory_mutation_idempotency%rowtype;
  source_entry public.memory_entries%rowtype;
  source_revision_id bigint;
  candidate public.memory_entries%rowtype;
  request_material jsonb;
begin
  select * into source_entry
  from public.memory_entries
  where owner_id = p_owner_id and id = p_memory_id
  for update;

  if not found or source_entry.space_key <> p_actor then
    perform public.memory_runtime_internal_audit(
      p_owner_id, p_actor, 'propose_shared', p_memory_id,
      case when source_entry.id is null then null else source_entry.space_key end,
      'denied', 'MEMORY_ACCESS_DENIED', p_request_id, 0, '{}'::text[]
    );
    return pg_catalog.jsonb_build_object(
      'ok', false, 'error_code', 'MEMORY_ACCESS_DENIED',
      'message', 'Only the fixed actor private memory can be proposed',
      'audit_persisted', true
    );
  end if;
  if p_request_id is null or nullif(pg_catalog.btrim(p_reason), '') is null then
    raise exception 'Trusted request id and candidate reason are required'
      using errcode = '23514';
  end if;

  request_material := pg_catalog.jsonb_build_object(
    'memory_id', p_memory_id, 'reason', pg_catalog.btrim(p_reason),
    'source_revision_number', source_entry.revision_number
  );
  select * into strict claim
  from public.memory_claim_idempotency(
    p_owner_id, p_actor, 'propose_shared', p_request_id, request_material
  );
  if claim.status = 'completed' then
    select * into strict candidate from public.memory_entries where id = claim.resource_id;
    perform public.memory_runtime_internal_audit(
      p_owner_id, p_actor, 'propose_shared_replay', candidate.id, candidate.space_key,
      'allowed', null, p_request_id, 1, array[candidate.space_key]
    );
    return pg_catalog.jsonb_build_object(
      'ok', true, 'memory', to_jsonb(candidate), 'replayed', true
    );
  end if;

  select id into strict source_revision_id
  from public.memory_revisions
  where owner_id = p_owner_id
    and memory_id = source_entry.id
    and revision_number = source_entry.revision_number;

  candidate := public.memory_curator_create_shared_candidate(
    p_owner_id, source_entry.id, source_revision_id, pg_catalog.btrim(p_reason)
  );

  perform public.memory_runtime_internal_audit(
    p_owner_id, p_actor, 'propose_shared', candidate.id, candidate.space_key,
    'allowed', null, p_request_id, 1, array[candidate.space_key]
  );
  update public.memory_mutation_idempotency
    set status = 'completed', resource_id = candidate.id,
        response_metadata = pg_catalog.jsonb_build_object(
          'memory_id', candidate.id,
          'source_memory_id', source_entry.id,
          'source_revision_number', source_entry.revision_number
        ),
        completed_at = now()
    where id = claim.id;

  return pg_catalog.jsonb_build_object(
    'ok', true, 'memory', to_jsonb(candidate), 'replayed', false
  );
end;
$$;

create or replace function public.memory_runtime_internal_external_audit(
  p_actor text,
  p_owner_id uuid,
  p_request_id uuid,
  p_action text,
  p_memory_id bigint,
  p_space_key text,
  p_result text,
  p_reason_code text,
  p_result_count integer,
  p_result_spaces text[]
)
returns jsonb
language plpgsql
set search_path = ''
as $$
begin
  perform public.memory_runtime_internal_audit(
    p_owner_id, p_actor, p_action, p_memory_id, p_space_key, p_result,
    p_reason_code, p_request_id, p_result_count, p_result_spaces
  );
  return pg_catalog.jsonb_build_object('ok', true);
end;
$$;

-- Fixed GPT wrappers. Actor is not an argument.
create or replace function public.memory_runtime_get_gpt(p_owner_id uuid, p_request_id uuid, p_memory_id bigint)
returns jsonb language sql security definer set search_path = ''
as $$ select public.memory_runtime_internal_get('gpt', p_owner_id, p_request_id, p_memory_id); $$;
create or replace function public.memory_runtime_list_gpt(p_owner_id uuid, p_request_id uuid, p_limit integer, p_cursor_id bigint, p_memory_type text, p_tags text[], p_retention text)
returns jsonb language sql security definer set search_path = ''
as $$ select public.memory_runtime_internal_list('gpt', p_owner_id, p_request_id, p_limit, p_cursor_id, p_memory_type, p_tags, p_retention); $$;
create or replace function public.memory_runtime_recall_gpt(p_owner_id uuid, p_request_id uuid, p_query text, p_limit integer, p_cursor_id bigint, p_tags text[])
returns jsonb language sql security definer set search_path = ''
as $$ select public.memory_runtime_internal_recall('gpt', p_owner_id, p_request_id, p_query, p_limit, p_cursor_id, p_tags); $$;
create or replace function public.memory_runtime_remember_gpt(p_owner_id uuid, p_request_id uuid, p_memory jsonb)
returns jsonb language sql security definer set search_path = ''
as $$ select public.memory_runtime_internal_remember('gpt', p_owner_id, p_request_id, p_memory); $$;
create or replace function public.memory_runtime_revise_gpt(p_owner_id uuid, p_request_id uuid, p_memory_id bigint, p_patch jsonb, p_reason text)
returns jsonb language sql security definer set search_path = ''
as $$ select public.memory_runtime_internal_revise('gpt', p_owner_id, p_request_id, p_memory_id, p_patch, p_reason); $$;
create or replace function public.memory_runtime_propose_shared_gpt(p_owner_id uuid, p_request_id uuid, p_memory_id bigint, p_reason text)
returns jsonb language sql security definer set search_path = ''
as $$ select public.memory_runtime_internal_propose_shared('gpt', p_owner_id, p_request_id, p_memory_id, p_reason); $$;
create or replace function public.memory_runtime_audit_gpt(p_owner_id uuid, p_request_id uuid, p_action text, p_memory_id bigint, p_space_key text, p_result text, p_reason_code text, p_result_count integer, p_result_spaces text[])
returns jsonb language sql security definer set search_path = ''
as $$ select public.memory_runtime_internal_external_audit('gpt', p_owner_id, p_request_id, p_action, p_memory_id, p_space_key, p_result, p_reason_code, p_result_count, p_result_spaces); $$;

-- Fixed Claude wrappers. Actor is not an argument.
create or replace function public.memory_runtime_get_claude(p_owner_id uuid, p_request_id uuid, p_memory_id bigint)
returns jsonb language sql security definer set search_path = ''
as $$ select public.memory_runtime_internal_get('claude', p_owner_id, p_request_id, p_memory_id); $$;
create or replace function public.memory_runtime_list_claude(p_owner_id uuid, p_request_id uuid, p_limit integer, p_cursor_id bigint, p_memory_type text, p_tags text[], p_retention text)
returns jsonb language sql security definer set search_path = ''
as $$ select public.memory_runtime_internal_list('claude', p_owner_id, p_request_id, p_limit, p_cursor_id, p_memory_type, p_tags, p_retention); $$;
create or replace function public.memory_runtime_recall_claude(p_owner_id uuid, p_request_id uuid, p_query text, p_limit integer, p_cursor_id bigint, p_tags text[])
returns jsonb language sql security definer set search_path = ''
as $$ select public.memory_runtime_internal_recall('claude', p_owner_id, p_request_id, p_query, p_limit, p_cursor_id, p_tags); $$;
create or replace function public.memory_runtime_remember_claude(p_owner_id uuid, p_request_id uuid, p_memory jsonb)
returns jsonb language sql security definer set search_path = ''
as $$ select public.memory_runtime_internal_remember('claude', p_owner_id, p_request_id, p_memory); $$;
create or replace function public.memory_runtime_revise_claude(p_owner_id uuid, p_request_id uuid, p_memory_id bigint, p_patch jsonb, p_reason text)
returns jsonb language sql security definer set search_path = ''
as $$ select public.memory_runtime_internal_revise('claude', p_owner_id, p_request_id, p_memory_id, p_patch, p_reason); $$;
create or replace function public.memory_runtime_propose_shared_claude(p_owner_id uuid, p_request_id uuid, p_memory_id bigint, p_reason text)
returns jsonb language sql security definer set search_path = ''
as $$ select public.memory_runtime_internal_propose_shared('claude', p_owner_id, p_request_id, p_memory_id, p_reason); $$;
create or replace function public.memory_runtime_audit_claude(p_owner_id uuid, p_request_id uuid, p_action text, p_memory_id bigint, p_space_key text, p_result text, p_reason_code text, p_result_count integer, p_result_spaces text[])
returns jsonb language sql security definer set search_path = ''
as $$ select public.memory_runtime_internal_external_audit('claude', p_owner_id, p_request_id, p_action, p_memory_id, p_space_key, p_result, p_reason_code, p_result_count, p_result_spaces); $$;

-- Phase 3 removes every direct service-role path to canonical memory tables
-- and Phase 2 RPCs. The Bridge receives only fixed-actor runtime RPC grants.
revoke all on table public.memory_entries from service_role;
revoke all on table public.memory_revisions from service_role;
revoke all on table public.memory_provenance from service_role;
revoke all on table public.memory_shared_transitions from service_role;
revoke all on table public.memory_audit_log from service_role;
revoke all on table public.memory_mutation_idempotency from service_role;
revoke all on table public.memory_ingest_candidates from service_role;
revoke all on sequence public.memory_entries_id_seq from service_role;
revoke all on sequence public.memory_revisions_id_seq from service_role;
revoke all on sequence public.memory_provenance_id_seq from service_role;
revoke all on sequence public.memory_shared_transitions_id_seq from service_role;
revoke all on sequence public.memory_audit_log_id_seq from service_role;
revoke all on sequence public.memory_mutation_idempotency_id_seq from service_role;
revoke all on sequence public.memory_ingest_candidates_id_seq from service_role;

revoke execute on function public.memory_get_gpt(uuid, bigint) from service_role;
revoke execute on function public.memory_get_claude(uuid, bigint) from service_role;
revoke execute on function public.memory_list_gpt(uuid, integer, text, text[], text) from service_role;
revoke execute on function public.memory_list_claude(uuid, integer, text, text[], text) from service_role;
revoke execute on function public.memory_recall_gpt(uuid, text, integer, text[]) from service_role;
revoke execute on function public.memory_recall_claude(uuid, text, integer, text[]) from service_role;
revoke execute on function public.memory_hash_jsonb(jsonb) from service_role;
revoke execute on function public.memory_compute_revision_hash(bigint) from service_role;
revoke execute on function public.memory_claim_idempotency(uuid, text, text, uuid, jsonb) from service_role;
revoke execute on function public.memory_curator_create_shared_candidate(uuid, bigint, bigint, text) from service_role;

revoke execute on function public.memory_runtime_internal_audit(uuid, text, text, bigint, text, text, text, uuid, integer, text[]) from public, anon, authenticated, service_role;
revoke execute on function public.memory_runtime_internal_get(text, uuid, uuid, bigint) from public, anon, authenticated, service_role;
revoke execute on function public.memory_runtime_internal_list(text, uuid, uuid, integer, bigint, text, text[], text) from public, anon, authenticated, service_role;
revoke execute on function public.memory_runtime_internal_recall(text, uuid, uuid, text, integer, bigint, text[]) from public, anon, authenticated, service_role;
revoke execute on function public.memory_runtime_internal_remember(text, uuid, uuid, jsonb) from public, anon, authenticated, service_role;
revoke execute on function public.memory_runtime_internal_revise(text, uuid, uuid, bigint, jsonb, text) from public, anon, authenticated, service_role;
revoke execute on function public.memory_runtime_internal_propose_shared(text, uuid, uuid, bigint, text) from public, anon, authenticated, service_role;
revoke execute on function public.memory_runtime_internal_external_audit(text, uuid, uuid, text, bigint, text, text, text, integer, text[]) from public, anon, authenticated, service_role;

revoke execute on function public.memory_runtime_get_gpt(uuid, uuid, bigint) from public, anon, authenticated, service_role;
revoke execute on function public.memory_runtime_list_gpt(uuid, uuid, integer, bigint, text, text[], text) from public, anon, authenticated, service_role;
revoke execute on function public.memory_runtime_recall_gpt(uuid, uuid, text, integer, bigint, text[]) from public, anon, authenticated, service_role;
revoke execute on function public.memory_runtime_remember_gpt(uuid, uuid, jsonb) from public, anon, authenticated, service_role;
revoke execute on function public.memory_runtime_revise_gpt(uuid, uuid, bigint, jsonb, text) from public, anon, authenticated, service_role;
revoke execute on function public.memory_runtime_propose_shared_gpt(uuid, uuid, bigint, text) from public, anon, authenticated, service_role;
revoke execute on function public.memory_runtime_audit_gpt(uuid, uuid, text, bigint, text, text, text, integer, text[]) from public, anon, authenticated, service_role;
revoke execute on function public.memory_runtime_get_claude(uuid, uuid, bigint) from public, anon, authenticated, service_role;
revoke execute on function public.memory_runtime_list_claude(uuid, uuid, integer, bigint, text, text[], text) from public, anon, authenticated, service_role;
revoke execute on function public.memory_runtime_recall_claude(uuid, uuid, text, integer, bigint, text[]) from public, anon, authenticated, service_role;
revoke execute on function public.memory_runtime_remember_claude(uuid, uuid, jsonb) from public, anon, authenticated, service_role;
revoke execute on function public.memory_runtime_revise_claude(uuid, uuid, bigint, jsonb, text) from public, anon, authenticated, service_role;
revoke execute on function public.memory_runtime_propose_shared_claude(uuid, uuid, bigint, text) from public, anon, authenticated, service_role;
revoke execute on function public.memory_runtime_audit_claude(uuid, uuid, text, bigint, text, text, text, integer, text[]) from public, anon, authenticated, service_role;

grant execute on function public.memory_runtime_get_gpt(uuid, uuid, bigint) to service_role;
grant execute on function public.memory_runtime_list_gpt(uuid, uuid, integer, bigint, text, text[], text) to service_role;
grant execute on function public.memory_runtime_recall_gpt(uuid, uuid, text, integer, bigint, text[]) to service_role;
grant execute on function public.memory_runtime_remember_gpt(uuid, uuid, jsonb) to service_role;
grant execute on function public.memory_runtime_revise_gpt(uuid, uuid, bigint, jsonb, text) to service_role;
grant execute on function public.memory_runtime_propose_shared_gpt(uuid, uuid, bigint, text) to service_role;
grant execute on function public.memory_runtime_audit_gpt(uuid, uuid, text, bigint, text, text, text, integer, text[]) to service_role;
grant execute on function public.memory_runtime_get_claude(uuid, uuid, bigint) to service_role;
grant execute on function public.memory_runtime_list_claude(uuid, uuid, integer, bigint, text, text[], text) to service_role;
grant execute on function public.memory_runtime_recall_claude(uuid, uuid, text, integer, bigint, text[]) to service_role;
grant execute on function public.memory_runtime_remember_claude(uuid, uuid, jsonb) to service_role;
grant execute on function public.memory_runtime_revise_claude(uuid, uuid, bigint, jsonb, text) to service_role;
grant execute on function public.memory_runtime_propose_shared_claude(uuid, uuid, bigint, text) to service_role;
grant execute on function public.memory_runtime_audit_claude(uuid, uuid, text, bigint, text, text, text, integer, text[]) to service_role;

comment on function public.memory_runtime_remember_gpt(uuid, uuid, jsonb) is
  'Fixed GPT transactional remember door. owner/request id are Bridge-internal.';
comment on function public.memory_runtime_remember_claude(uuid, uuid, jsonb) is
  'Fixed Claude transactional remember door. owner/request id are Bridge-internal.';
comment on function public.memory_runtime_propose_shared_gpt(uuid, uuid, bigint, text) is
  'GPT Curator proposal door; resolves the current private revision inside the transaction.';
comment on function public.memory_runtime_propose_shared_claude(uuid, uuid, bigint, text) is
  'Claude Curator proposal door; resolves the current private revision inside the transaction.';

commit;
