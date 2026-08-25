begin;

-- Engineering Memory is a shared engineering-state space inside Memory V2.
-- It is intentionally absent from ordinary recall, Starter Pack and wake_up.
alter table public.memory_v2_entries
  add column subject_key text;

alter table public.memory_v2_entries
  drop constraint memory_v2_entries_space_key_check,
  drop constraint memory_v2_entries_created_by_actor_check,
  drop constraint memory_v2_entries_space_actor_check,
  add constraint memory_v2_entries_space_key_check
    check (space_key in ('gpt', 'claude', 'shared', 'engineering')),
  add constraint memory_v2_entries_created_by_actor_check
    check (created_by_actor in ('gpt', 'claude', 'codex', 'owner')),
  add constraint memory_v2_entries_space_actor_check check (
    (space_key = 'gpt' and created_by_actor in ('gpt', 'owner'))
    or (space_key = 'claude' and created_by_actor in ('claude', 'owner'))
    or (space_key = 'shared' and created_by_actor = 'owner')
    or (space_key = 'engineering' and created_by_actor in ('gpt', 'claude', 'codex', 'owner'))
  ),
  add constraint memory_v2_entries_subject_key_check check (
    (space_key = 'engineering'
      and subject_key is not null
      and length(subject_key) between 1 and 200
      and subject_key = btrim(subject_key))
    or (space_key <> 'engineering' and subject_key is null)
  );

create unique index memory_v2_entries_engineering_subject_unique
  on public.memory_v2_entries (owner_id, subject_key)
  where space_key = 'engineering';

alter table public.memory_v2_revisions
  drop constraint memory_v2_revisions_created_by_actor_check,
  add constraint memory_v2_revisions_created_by_actor_check
    check (created_by_actor in ('gpt', 'claude', 'codex', 'owner'));

alter table public.memory_v2_sources
  drop constraint memory_v2_sources_space_key_check,
  drop constraint memory_v2_sources_created_by_actor_check,
  drop constraint memory_v2_sources_actor_check,
  add constraint memory_v2_sources_space_key_check
    check (space_key in ('gpt', 'claude', 'engineering')),
  add constraint memory_v2_sources_created_by_actor_check
    check (created_by_actor in ('gpt', 'claude', 'codex', 'owner')),
  add constraint memory_v2_sources_actor_check check (
    (space_key = 'gpt' and created_by_actor in ('gpt', 'owner'))
    or (space_key = 'claude' and created_by_actor in ('claude', 'owner'))
    or (space_key = 'engineering' and created_by_actor in ('gpt', 'claude', 'codex', 'owner'))
  );

create or replace function public.memory_v2_engineering_upsert(
  p_owner_id uuid,
  p_actor text,
  p_subject_key text,
  p_content text,
  p_options jsonb default '{}'::jsonb
)
returns jsonb
language plpgsql
security invoker
set search_path = pg_catalog, public, extensions
as $$
declare
  entry_row public.memory_v2_entries%rowtype;
  previous_revision public.memory_v2_revisions%rowtype;
  memory_id uuid;
  new_revision_id uuid;
  revision_number_value integer;
  metadata_value jsonb;
  event_time_value timestamptz;
  human_importance_value smallint;
  ai_importance_value smallint;
  created_at_value timestamptz;
  normalized_subject text := btrim(coalesce(p_subject_key, ''));
  normalized_content text := btrim(coalesce(p_content, ''));
begin
  if p_actor not in ('gpt', 'claude', 'codex', 'owner') then
    raise exception 'trusted engineering actor is required' using errcode = '42501';
  end if;
  if length(normalized_subject) not between 1 and 200 then
    raise exception 'a bounded subject_key is required' using errcode = '22023';
  end if;
  if length(normalized_content) not between 1 and 50000 then
    raise exception 'content is required' using errcode = '22023';
  end if;
  if p_options is null or jsonb_typeof(p_options) <> 'object' then
    raise exception 'options must be an object' using errcode = '22023';
  end if;
  if p_options ?| array['owner_id', 'owner', 'actor', 'space', 'space_key', 'shared_status', 'created_by_actor', 'permissions']
    or (p_options ? 'metadata' and (p_options -> 'metadata') ?| array['owner_id', 'owner', 'actor', 'space', 'space_key', 'shared_status', 'created_by_actor', 'permissions']) then
    raise exception 'authority fields are server controlled' using errcode = '42501';
  end if;
  if p_options ? 'metadata' and jsonb_typeof(p_options -> 'metadata') <> 'object' then
    raise exception 'metadata must be an object' using errcode = '22023';
  end if;

  -- Serialize concurrent creates/revisions for the same owner + subject without
  -- adding a mapping table or process-memory lock.
  perform pg_advisory_xact_lock(
    hashtextextended(p_owner_id::text || ':engineering:' || normalized_subject, 0)
  );

  select * into entry_row
  from public.memory_v2_entries
  where owner_id = p_owner_id
    and space_key = 'engineering'
    and subject_key = normalized_subject
  for update;

  if not found then
    memory_id := extensions.gen_random_uuid();
    new_revision_id := extensions.gen_random_uuid();
    metadata_value := coalesce(p_options -> 'metadata', '{}'::jsonb);
    event_time_value := nullif(p_options ->> 'event_time', '')::timestamptz;
    human_importance_value := nullif(p_options ->> 'human_importance', '')::smallint;
    ai_importance_value := nullif(p_options ->> 'ai_importance', '')::smallint;

    insert into public.memory_v2_entries (
      id, owner_id, space_key, subject_key, created_by_actor, current_revision_id
    ) values (
      memory_id, p_owner_id, 'engineering', normalized_subject, p_actor, new_revision_id
    ) returning created_at into created_at_value;

    insert into public.memory_v2_revisions (
      id, memory_id, revision_number, content, event_time,
      human_importance, ai_importance, metadata, created_by_actor, reason
    ) values (
      new_revision_id, memory_id, 1, normalized_content, event_time_value,
      human_importance_value, ai_importance_value, metadata_value, p_actor,
      nullif(p_options ->> 'reason', '')
    );

    perform public.memory_v2_materialize_sources(
      p_owner_id, 'engineering', p_actor, new_revision_id, p_options -> 'sources'
    );

    return jsonb_build_object(
      'action', 'created',
      'memory_id', memory_id,
      'revision_id', new_revision_id,
      'revision_number', 1,
      'space_key', 'engineering',
      'subject_key', normalized_subject,
      'created_at', created_at_value
    );
  end if;

  if entry_row.status <> 'active' then
    raise exception 'engineering subject is archived' using errcode = '42501';
  end if;

  select * into strict previous_revision
  from public.memory_v2_revisions
  where id = entry_row.current_revision_id;

  metadata_value := case when p_options ? 'metadata'
    then p_options -> 'metadata' else previous_revision.metadata end;
  event_time_value := case when p_options ? 'event_time'
    then nullif(p_options ->> 'event_time', '')::timestamptz else previous_revision.event_time end;
  human_importance_value := case when p_options ? 'human_importance'
    then nullif(p_options ->> 'human_importance', '')::smallint else previous_revision.human_importance end;
  ai_importance_value := case when p_options ? 'ai_importance'
    then nullif(p_options ->> 'ai_importance', '')::smallint else previous_revision.ai_importance end;

  if previous_revision.content = normalized_content
    and previous_revision.metadata = metadata_value
    and previous_revision.event_time is not distinct from event_time_value
    and previous_revision.human_importance is not distinct from human_importance_value
    and previous_revision.ai_importance is not distinct from ai_importance_value
    and not (p_options ? 'sources') then
    return jsonb_build_object(
      'action', 'noop',
      'memory_id', entry_row.id,
      'revision_id', previous_revision.id,
      'revision_number', previous_revision.revision_number,
      'space_key', 'engineering',
      'subject_key', normalized_subject,
      'created_at', previous_revision.created_at
    );
  end if;

  memory_id := entry_row.id;
  new_revision_id := extensions.gen_random_uuid();
  revision_number_value := previous_revision.revision_number + 1;

  insert into public.memory_v2_revisions (
    id, memory_id, revision_number, content, event_time,
    human_importance, ai_importance, metadata, created_by_actor, reason
  ) values (
    new_revision_id, memory_id, revision_number_value, normalized_content, event_time_value,
    human_importance_value, ai_importance_value, metadata_value, p_actor,
    nullif(p_options ->> 'reason', '')
  ) returning created_at into created_at_value;

  if p_options ? 'sources' then
    perform public.memory_v2_materialize_sources(
      p_owner_id, 'engineering', p_actor, new_revision_id, p_options -> 'sources'
    );
  else
    insert into public.memory_v2_revision_sources (revision_id, source_id, ordinal)
    select new_revision_id, links.source_id, links.ordinal
    from public.memory_v2_revision_sources links
    where links.revision_id = previous_revision.id;
  end if;

  update public.memory_v2_entries
  set current_revision_id = new_revision_id, updated_at = now()
  where id = memory_id;

  return jsonb_build_object(
    'action', 'revised',
    'memory_id', memory_id,
    'revision_id', new_revision_id,
    'revision_number', revision_number_value,
    'space_key', 'engineering',
    'subject_key', normalized_subject,
    'created_at', created_at_value
  );
end;
$$;

create or replace function public.memory_v2_engineering_recall(
  p_owner_id uuid,
  p_actor text,
  p_query text default '',
  p_limit integer default 30,
  p_include_archived boolean default false
)
returns jsonb
language plpgsql
stable
security invoker
set search_path = pg_catalog, public, extensions
as $$
declare
  result jsonb;
begin
  if p_actor not in ('gpt', 'claude', 'codex', 'owner') then
    raise exception 'trusted engineering actor is required' using errcode = '42501';
  end if;
  if p_include_archived and p_actor <> 'owner' then
    raise exception 'only owner may list archived engineering subjects' using errcode = '42501';
  end if;

  with visible as (
    select
      e.id as memory_id,
      e.subject_key,
      e.status,
      e.created_by_actor,
      e.created_at,
      e.updated_at,
      r.id as revision_id,
      r.revision_number,
      r.content,
      r.metadata,
      r.created_by_actor as last_modified_actor,
      r.created_at as revision_created_at,
      (select count(*)::integer from public.memory_v2_revision_sources links
        where links.revision_id = r.id) as source_count,
      case
        when btrim(coalesce(p_query, '')) = '' then 1.0::double precision
        when lower(e.subject_key) = lower(btrim(p_query)) then 1.0::double precision
        else greatest(
          case when strpos(lower(e.subject_key), lower(btrim(p_query))) > 0 then 0.9 else 0.0 end,
          case when strpos(lower(r.content), lower(btrim(p_query))) > 0 then 0.8 else 0.0 end,
          ts_rank_cd(
            to_tsvector('simple', e.subject_key || ' ' || r.content),
            plainto_tsquery('simple', btrim(p_query))
          )::double precision,
          case when char_length(btrim(p_query)) >= 3
            then extensions.word_similarity(
              lower(btrim(p_query)), lower(e.subject_key || ' ' || r.content)
            )::double precision
            else 0.0::double precision end
        )
      end as relevance
    from public.memory_v2_entries e
    join public.memory_v2_revisions r on r.id = e.current_revision_id
    where e.owner_id = p_owner_id
      and e.space_key = 'engineering'
      and (e.status = 'active' or (p_include_archived and e.status = 'archived'))
  ), limited as (
    select * from visible
    where btrim(coalesce(p_query, '')) = '' or relevance > 0
    order by relevance desc, updated_at desc, subject_key
    limit least(greatest(coalesce(p_limit, 30), 1), 50)
  )
  select coalesce(jsonb_agg(to_jsonb(limited) order by relevance desc, updated_at desc), '[]'::jsonb)
  into result
  from limited;

  return result;
end;
$$;

create or replace function public.memory_v2_engineering_open(
  p_owner_id uuid,
  p_actor text,
  p_subject_key text
)
returns jsonb
language plpgsql
stable
security invoker
set search_path = pg_catalog, public
as $$
declare
  entry_row public.memory_v2_entries%rowtype;
  revisions_value jsonb;
begin
  if p_actor not in ('gpt', 'claude', 'codex', 'owner') then
    raise exception 'trusted engineering actor is required' using errcode = '42501';
  end if;

  select * into entry_row
  from public.memory_v2_entries
  where owner_id = p_owner_id
    and space_key = 'engineering'
    and subject_key = btrim(coalesce(p_subject_key, ''))
    and (status = 'active' or (status = 'archived' and p_actor = 'owner'));
  if not found then
    raise exception 'engineering subject is unavailable' using errcode = '42501';
  end if;

  select coalesce(jsonb_agg(
    to_jsonb(r) || jsonb_build_object(
      'sources', coalesce((
        select jsonb_agg(descriptor order by ordinal, source_id)
        from (
          select
            links.ordinal,
            source.id as source_id,
            jsonb_build_object(
              'source_id', source.id,
              'source_kind', source.source_kind,
              'locator', source.locator,
              'provenance', source.provenance,
              'ordinal', links.ordinal
            ) as descriptor
          from public.memory_v2_revision_sources links
          join public.memory_v2_sources source on source.id = links.source_id
          where links.revision_id = r.id
            and source.owner_id = p_owner_id
            and source.space_key = 'engineering'
          order by links.ordinal, source.id
          limit 101
        ) bounded_sources
      ), '[]'::jsonb)
    ) order by r.revision_number
  ), '[]'::jsonb)
  into revisions_value
  from public.memory_v2_revisions r
  where r.memory_id = entry_row.id;

  return jsonb_build_object(
    'entry', jsonb_build_object(
      'memory_id', entry_row.id,
      'space_key', entry_row.space_key,
      'subject_key', entry_row.subject_key,
      'status', entry_row.status,
      'current_revision_id', entry_row.current_revision_id,
      'created_by_actor', entry_row.created_by_actor,
      'created_at', entry_row.created_at,
      'updated_at', entry_row.updated_at
    ),
    'revisions', revisions_value
  );
end;
$$;

create or replace function public.memory_v2_engineering_expand_source(
  p_owner_id uuid,
  p_actor text,
  p_source_id uuid
)
returns jsonb
language plpgsql
stable
security invoker
set search_path = pg_catalog, public
as $$
declare
  source_row public.memory_v2_sources%rowtype;
begin
  if p_actor not in ('gpt', 'claude', 'codex', 'owner') then
    raise exception 'trusted engineering actor is required' using errcode = '42501';
  end if;

  select source.* into source_row
  from public.memory_v2_sources source
  where source.id = p_source_id
    and source.owner_id = p_owner_id
    and source.space_key = 'engineering'
    and exists (
      select 1
      from public.memory_v2_revision_sources links
      join public.memory_v2_revisions revision on revision.id = links.revision_id
      join public.memory_v2_entries entry on entry.id = revision.memory_id
      where links.source_id = source.id
        and entry.owner_id = p_owner_id
        and entry.space_key = 'engineering'
        and (entry.status = 'active' or (entry.status = 'archived' and p_actor = 'owner'))
    );
  if not found then
    raise exception 'engineering source is unavailable' using errcode = '42501';
  end if;

  return jsonb_build_object(
    'source_id', source_row.id,
    'source_kind', source_row.source_kind,
    'available', source_row.quote_text is not null,
    'quote_text', source_row.quote_text,
    'locator', source_row.locator,
    'provenance', source_row.provenance,
    'created_at', source_row.created_at
  );
end;
$$;

create or replace function public.memory_v2_engineering_archive(
  p_owner_id uuid,
  p_actor text,
  p_subject_key text
)
returns jsonb
language plpgsql
security invoker
set search_path = pg_catalog, public
as $$
declare
  entry_row public.memory_v2_entries%rowtype;
begin
  if p_actor <> 'owner' then
    raise exception 'owner is required to archive engineering subjects' using errcode = '42501';
  end if;
  select * into entry_row
  from public.memory_v2_entries
  where owner_id = p_owner_id
    and space_key = 'engineering'
    and subject_key = btrim(coalesce(p_subject_key, ''))
  for update;
  if not found then
    raise exception 'engineering subject is unavailable' using errcode = '42501';
  end if;
  if entry_row.status = 'active' then
    update public.memory_v2_entries
    set status = 'archived', updated_at = now()
    where id = entry_row.id;
  end if;
  return jsonb_build_object(
    'action', case when entry_row.status = 'active' then 'archived' else 'noop' end,
    'memory_id', entry_row.id,
    'subject_key', entry_row.subject_key,
    'status', 'archived'
  );
end;
$$;

create or replace function public.memory_v2_engineering_restore(
  p_owner_id uuid,
  p_actor text,
  p_subject_key text
)
returns jsonb
language plpgsql
security invoker
set search_path = pg_catalog, public
as $$
declare
  entry_row public.memory_v2_entries%rowtype;
begin
  if p_actor <> 'owner' then
    raise exception 'owner is required to restore engineering subjects' using errcode = '42501';
  end if;
  select * into entry_row
  from public.memory_v2_entries
  where owner_id = p_owner_id
    and space_key = 'engineering'
    and subject_key = btrim(coalesce(p_subject_key, ''))
  for update;
  if not found then
    raise exception 'engineering subject is unavailable' using errcode = '42501';
  end if;
  if entry_row.status = 'archived' then
    update public.memory_v2_entries
    set status = 'active', updated_at = now()
    where id = entry_row.id;
  end if;
  return jsonb_build_object(
    'action', case when entry_row.status = 'archived' then 'restored' else 'noop' end,
    'memory_id', entry_row.id,
    'subject_key', entry_row.subject_key,
    'status', 'active'
  );
end;
$$;

revoke all on function public.memory_v2_engineering_upsert(uuid, text, text, text, jsonb) from public, anon, authenticated;
revoke all on function public.memory_v2_engineering_recall(uuid, text, text, integer, boolean) from public, anon, authenticated;
revoke all on function public.memory_v2_engineering_open(uuid, text, text) from public, anon, authenticated;
revoke all on function public.memory_v2_engineering_expand_source(uuid, text, uuid) from public, anon, authenticated;
revoke all on function public.memory_v2_engineering_archive(uuid, text, text) from public, anon, authenticated;
revoke all on function public.memory_v2_engineering_restore(uuid, text, text) from public, anon, authenticated;

grant execute on function public.memory_v2_engineering_upsert(uuid, text, text, text, jsonb) to service_role;
grant execute on function public.memory_v2_engineering_recall(uuid, text, text, integer, boolean) to service_role;
grant execute on function public.memory_v2_engineering_open(uuid, text, text) to service_role;
grant execute on function public.memory_v2_engineering_expand_source(uuid, text, uuid) to service_role;
grant execute on function public.memory_v2_engineering_archive(uuid, text, text) to service_role;
grant execute on function public.memory_v2_engineering_restore(uuid, text, text) to service_role;

comment on column public.memory_v2_entries.subject_key is
  'Stable identity for Engineering Memory subjects; NULL for ordinary Memory V2 entries.';

commit;
