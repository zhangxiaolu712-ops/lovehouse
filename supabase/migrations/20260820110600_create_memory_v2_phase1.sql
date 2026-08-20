begin;

-- Memory V2 Phase 1 is a sidecar. It does not read or mutate any V1 table.
create extension if not exists pgcrypto with schema extensions;
create extension if not exists vector with schema extensions;

create table public.memory_v2_entries (
  id uuid primary key default extensions.gen_random_uuid(),
  owner_id uuid not null references auth.users(id) on delete restrict,
  space_key text not null check (space_key in ('gpt', 'claude', 'shared')),
  created_by_actor text not null check (created_by_actor in ('gpt', 'claude', 'owner')),
  current_revision_id uuid not null,
  status text not null default 'active' check (status in ('active', 'superseded', 'archived')),
  shared_status text check (shared_status is null or shared_status in ('approved', 'revoked')),
  origin_revision_id uuid,
  superseded_by_id uuid,
  last_recalled_at timestamptz,
  recall_count integer not null default 0 check (recall_count between 0 and 1000000),
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint memory_v2_entries_space_actor_check check (
    (space_key = 'gpt' and created_by_actor in ('gpt', 'owner'))
    or (space_key = 'claude' and created_by_actor in ('claude', 'owner'))
    or (space_key = 'shared' and created_by_actor = 'owner')
  ),
  constraint memory_v2_entries_shared_check check (
    (space_key = 'shared' and shared_status is not null and origin_revision_id is not null)
    or (space_key <> 'shared' and shared_status is null and origin_revision_id is null)
  ),
  constraint memory_v2_entries_superseded_check check (
    (status = 'superseded' and superseded_by_id is not null)
    or (status <> 'superseded' and superseded_by_id is null)
  )
);

create table public.memory_v2_revisions (
  id uuid primary key default extensions.gen_random_uuid(),
  memory_id uuid not null references public.memory_v2_entries(id) on delete restrict,
  revision_number integer not null check (revision_number >= 1),
  content text not null check (length(btrim(content)) between 1 and 50000),
  event_time timestamptz,
  human_importance smallint check (human_importance is null or human_importance between 0 and 5),
  ai_importance smallint check (ai_importance is null or ai_importance between 0 and 5),
  metadata jsonb not null default '{}'::jsonb check (jsonb_typeof(metadata) = 'object'),
  created_by_actor text not null check (created_by_actor in ('gpt', 'claude', 'owner')),
  reason text check (reason is null or length(reason) <= 1000),
  created_at timestamptz not null default now(),
  unique (memory_id, revision_number)
);

alter table public.memory_v2_entries
  add constraint memory_v2_entries_current_revision_fk
  foreign key (current_revision_id) references public.memory_v2_revisions(id)
  on delete restrict deferrable initially deferred,
  add constraint memory_v2_entries_origin_revision_fk
  foreign key (origin_revision_id) references public.memory_v2_revisions(id)
  on delete restrict deferrable initially deferred,
  add constraint memory_v2_entries_superseded_by_fk
  foreign key (superseded_by_id) references public.memory_v2_entries(id)
  on delete restrict;

create table public.memory_v2_sources (
  id uuid primary key default extensions.gen_random_uuid(),
  owner_id uuid not null references auth.users(id) on delete restrict,
  space_key text not null check (space_key in ('gpt', 'claude')),
  source_kind text not null check (length(btrim(source_kind)) between 1 and 80),
  locator jsonb not null default '{}'::jsonb check (jsonb_typeof(locator) = 'object'),
  quote_text text check (quote_text is null or length(quote_text) <= 20000),
  provenance jsonb not null default '{}'::jsonb check (jsonb_typeof(provenance) = 'object'),
  created_by_actor text not null check (created_by_actor in ('gpt', 'claude', 'owner')),
  created_at timestamptz not null default now(),
  constraint memory_v2_sources_actor_check check (
    (space_key = 'gpt' and created_by_actor in ('gpt', 'owner'))
    or (space_key = 'claude' and created_by_actor in ('claude', 'owner'))
  )
);

create table public.memory_v2_revision_sources (
  revision_id uuid not null references public.memory_v2_revisions(id) on delete restrict,
  source_id uuid not null references public.memory_v2_sources(id) on delete restrict,
  ordinal smallint not null default 0 check (ordinal between 0 and 100),
  created_at timestamptz not null default now(),
  primary key (revision_id, source_id)
);

create table public.memory_v2_embeddings (
  revision_id uuid primary key references public.memory_v2_revisions(id) on delete cascade,
  model text not null check (length(btrim(model)) between 1 and 120),
  dimensions integer not null default 1536 check (dimensions = 1536),
  embedding extensions.vector(1536) not null,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now()
);

create index memory_v2_entries_owner_space_current_idx
  on public.memory_v2_entries (owner_id, space_key, status, created_at desc);
create index memory_v2_revisions_memory_idx
  on public.memory_v2_revisions (memory_id, revision_number desc);
create index memory_v2_sources_owner_space_idx
  on public.memory_v2_sources (owner_id, space_key, created_at desc);
create index memory_v2_revision_sources_source_idx
  on public.memory_v2_revision_sources (source_id, revision_id);
create index memory_v2_embeddings_cosine_idx
  on public.memory_v2_embeddings using hnsw (embedding extensions.vector_cosine_ops);

alter table public.memory_v2_entries enable row level security;
alter table public.memory_v2_entries force row level security;
alter table public.memory_v2_revisions enable row level security;
alter table public.memory_v2_revisions force row level security;
alter table public.memory_v2_sources enable row level security;
alter table public.memory_v2_sources force row level security;
alter table public.memory_v2_revision_sources enable row level security;
alter table public.memory_v2_revision_sources force row level security;
alter table public.memory_v2_embeddings enable row level security;
alter table public.memory_v2_embeddings force row level security;

revoke all on table public.memory_v2_entries from public, anon, authenticated;
revoke all on table public.memory_v2_revisions from public, anon, authenticated;
revoke all on table public.memory_v2_sources from public, anon, authenticated;
revoke all on table public.memory_v2_revision_sources from public, anon, authenticated;
revoke all on table public.memory_v2_embeddings from public, anon, authenticated;
grant select, insert, update on table public.memory_v2_entries to service_role;
grant select, insert on table public.memory_v2_revisions to service_role;
grant select, insert on table public.memory_v2_sources to service_role;
grant select, insert on table public.memory_v2_revision_sources to service_role;
grant select, insert, update, delete on table public.memory_v2_embeddings to service_role;

create or replace function public.memory_v2_materialize_sources(
  p_owner_id uuid,
  p_space_key text,
  p_actor text,
  p_revision_id uuid,
  p_sources jsonb
)
returns void
language plpgsql
security invoker
set search_path = pg_catalog, public, extensions
as $$
declare
  item jsonb;
  source_id uuid;
  source_row public.memory_v2_sources%rowtype;
  source_ordinal integer := 0;
begin
  if p_sources is null then return; end if;
  if jsonb_typeof(p_sources) <> 'array' or jsonb_array_length(p_sources) > 20 then
    raise exception 'sources must be an array with at most 20 items' using errcode = '22023';
  end if;

  for item in select value from jsonb_array_elements(p_sources)
  loop
    if jsonb_typeof(item) <> 'object' then
      raise exception 'source must be an object' using errcode = '22023';
    end if;

    if nullif(item ->> 'source_id', '') is not null then
      source_id := (item ->> 'source_id')::uuid;
      select * into source_row from public.memory_v2_sources where id = source_id;
      if not found or source_row.owner_id <> p_owner_id or source_row.space_key <> p_space_key then
        raise exception 'source is outside the private namespace' using errcode = '42501';
      end if;
    else
      if length(btrim(coalesce(item ->> 'source_kind', ''))) not between 1 and 80 then
        raise exception 'source_kind is required' using errcode = '22023';
      end if;
      if jsonb_typeof(coalesce(item -> 'locator', '{}'::jsonb)) <> 'object'
        or jsonb_typeof(coalesce(item -> 'provenance', '{}'::jsonb)) <> 'object' then
        raise exception 'source locator and provenance must be objects' using errcode = '22023';
      end if;

      insert into public.memory_v2_sources (
        owner_id, space_key, source_kind, locator, quote_text, provenance, created_by_actor
      ) values (
        p_owner_id,
        p_space_key,
        btrim(item ->> 'source_kind'),
        coalesce(item -> 'locator', '{}'::jsonb),
        nullif(item ->> 'quote_text', ''),
        coalesce(item -> 'provenance', '{}'::jsonb),
        p_actor
      ) returning id into source_id;
    end if;

    insert into public.memory_v2_revision_sources (revision_id, source_id, ordinal)
    values (p_revision_id, source_id, source_ordinal)
    on conflict (revision_id, source_id) do nothing;
    source_ordinal := source_ordinal + 1;
  end loop;
end;
$$;

create or replace function public.memory_v2_approve_shared(
  p_owner_id uuid,
  p_source_memory_id uuid
)
returns jsonb
language plpgsql
security invoker
set search_path = pg_catalog, public, extensions
as $$
declare
  source_entry public.memory_v2_entries%rowtype;
  source_revision public.memory_v2_revisions%rowtype;
  shared_memory_id uuid := extensions.gen_random_uuid();
  shared_revision_id uuid := extensions.gen_random_uuid();
  created_at_value timestamptz;
begin
  select * into source_entry
  from public.memory_v2_entries
  where id = p_source_memory_id and owner_id = p_owner_id
    and space_key in ('gpt', 'claude') and status = 'active'
    and superseded_by_id is null
  for share;
  if not found then
    raise exception 'private source memory is unavailable' using errcode = '42501';
  end if;

  select * into strict source_revision
  from public.memory_v2_revisions where id = source_entry.current_revision_id;

  insert into public.memory_v2_entries (
    id, owner_id, space_key, created_by_actor, current_revision_id,
    shared_status, origin_revision_id
  ) values (
    shared_memory_id, p_owner_id, 'shared', 'owner', shared_revision_id,
    'approved', source_revision.id
  ) returning created_at into created_at_value;

  insert into public.memory_v2_revisions (
    id, memory_id, revision_number, content, event_time,
    human_importance, ai_importance, metadata, created_by_actor, reason
  ) values (
    shared_revision_id, shared_memory_id, 1, source_revision.content,
    source_revision.event_time, source_revision.human_importance,
    source_revision.ai_importance, source_revision.metadata, 'owner',
    'approved_shared_snapshot'
  );

  insert into public.memory_v2_revision_sources (revision_id, source_id, ordinal)
  select shared_revision_id, source_id, ordinal
  from public.memory_v2_revision_sources
  where revision_id = source_revision.id;

  return jsonb_build_object(
    'memory_id', shared_memory_id,
    'revision_id', shared_revision_id,
    'space_key', 'shared',
    'shared_status', 'approved',
    'origin_revision_id', source_revision.id,
    'created_at', created_at_value
  );
end;
$$;

create or replace function public.memory_v2_recall_lexical(
  p_owner_id uuid,
  p_actor text,
  p_query text,
  p_limit integer default 30
)
returns jsonb
language sql
stable
security invoker
set search_path = pg_catalog, public, extensions
as $$
  with visible as (
    select
      e.id as memory_id,
      r.id as revision_id,
      r.revision_number,
      r.content,
      r.event_time,
      r.human_importance,
      r.ai_importance,
      r.metadata,
      r.created_at,
      e.space_key,
      e.last_recalled_at,
      e.recall_count,
      (select count(*)::integer from public.memory_v2_revision_sources links
        where links.revision_id = r.id) as source_count,
      case
        when btrim(coalesce(p_query, '')) = '' then 1.0::double precision
        else greatest(
          case when strpos(lower(r.content), lower(btrim(p_query))) > 0
            then 0.75::double precision else 0.0::double precision end,
          ts_rank_cd(
            to_tsvector('simple', r.content),
            plainto_tsquery('simple', btrim(p_query))
          )::double precision
        )
      end as relevance
    from public.memory_v2_entries e
    join public.memory_v2_revisions r on r.id = e.current_revision_id
    where e.owner_id = p_owner_id
      and p_actor in ('gpt', 'claude')
      and e.status = 'active'
      and e.superseded_by_id is null
      and (
        e.space_key = p_actor
        or (e.space_key = 'shared' and e.shared_status = 'approved')
      )
  ), limited as (
    select * from visible
    where btrim(coalesce(p_query, '')) = '' or relevance > 0
    order by relevance desc, created_at desc, memory_id
    limit least(greatest(coalesce(p_limit, 30), 1), 50)
  )
  select coalesce(jsonb_agg(to_jsonb(limited) order by relevance desc, created_at desc), '[]'::jsonb)
  from limited;
$$;

create or replace function public.memory_v2_recall_semantic(
  p_owner_id uuid,
  p_actor text,
  p_query_embedding real[],
  p_model text,
  p_limit integer default 30
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
  if p_actor not in ('gpt', 'claude') then
    raise exception 'fixed actor is required' using errcode = '42501';
  end if;
  if cardinality(p_query_embedding) <> 1536
    or exists (
      select 1 from unnest(p_query_embedding) component
      where component::text in ('NaN', 'Infinity', '-Infinity')
    ) then
    raise exception 'a finite 1536-dimension embedding is required' using errcode = '22023';
  end if;
  if length(btrim(coalesce(p_model, ''))) not between 1 and 120 then
    raise exception 'embedding model is required' using errcode = '22023';
  end if;

  with visible as (
    select
      e.id as memory_id,
      r.id as revision_id,
      r.revision_number,
      r.content,
      r.event_time,
      r.human_importance,
      r.ai_importance,
      r.metadata,
      r.created_at,
      e.space_key,
      e.last_recalled_at,
      e.recall_count,
      (select count(*)::integer from public.memory_v2_revision_sources links
        where links.revision_id = r.id) as source_count,
      greatest(
        0.0::double precision,
        1.0 - (emb.embedding OPERATOR(extensions.<=>) p_query_embedding::extensions.vector)
      ) as relevance
    from public.memory_v2_entries e
    join public.memory_v2_revisions r on r.id = e.current_revision_id
    join public.memory_v2_embeddings emb on emb.revision_id = r.id and emb.model = p_model
    where e.owner_id = p_owner_id
      and e.status = 'active'
      and e.superseded_by_id is null
      and (
        e.space_key = p_actor
        or (e.space_key = 'shared' and e.shared_status = 'approved')
      )
    order by emb.embedding OPERATOR(extensions.<=>) p_query_embedding::extensions.vector
    limit least(greatest(coalesce(p_limit, 30), 1), 50)
  )
  select coalesce(jsonb_agg(to_jsonb(visible) order by relevance desc, created_at desc), '[]'::jsonb)
  into result from visible;
  return result;
end;
$$;

create or replace function public.memory_v2_store_embedding(
  p_owner_id uuid,
  p_actor text,
  p_revision_id uuid,
  p_model text,
  p_embedding real[]
)
returns void
language plpgsql
security invoker
set search_path = pg_catalog, public, extensions
as $$
begin
  if p_actor not in ('gpt', 'claude') then
    raise exception 'fixed actor is required' using errcode = '42501';
  end if;
  if cardinality(p_embedding) <> 1536
    or exists (
      select 1 from unnest(p_embedding) component
      where component::text in ('NaN', 'Infinity', '-Infinity')
    ) then
    raise exception 'a finite 1536-dimension embedding is required' using errcode = '22023';
  end if;
  if not exists (
    select 1
    from public.memory_v2_revisions r
    join public.memory_v2_entries e on e.id = r.memory_id
    where r.id = p_revision_id and e.owner_id = p_owner_id and e.space_key = p_actor
  ) then
    raise exception 'revision is outside the private namespace' using errcode = '42501';
  end if;

  insert into public.memory_v2_embeddings (revision_id, model, embedding)
  values (p_revision_id, btrim(p_model), p_embedding::extensions.vector)
  on conflict (revision_id) do update
  set model = excluded.model, embedding = excluded.embedding, updated_at = now();
end;
$$;

create or replace function public.memory_v2_record_recall(
  p_owner_id uuid,
  p_actor text,
  p_memory_ids uuid[],
  p_recalled_at timestamptz
)
returns integer
language plpgsql
security invoker
set search_path = pg_catalog, public
as $$
declare
  changed integer;
begin
  if p_actor not in ('gpt', 'claude') then return 0; end if;
  update public.memory_v2_entries
  set recall_count = least(recall_count + 1, 1000000),
      last_recalled_at = greatest(coalesce(last_recalled_at, '-infinity'::timestamptz), p_recalled_at),
      updated_at = now()
  where id = any(coalesce(p_memory_ids, '{}'::uuid[]))
    and owner_id = p_owner_id
    and status = 'active'
    and superseded_by_id is null
    and (
      space_key = p_actor
      or (space_key = 'shared' and shared_status = 'approved')
    );
  get diagnostics changed = row_count;
  return changed;
end;
$$;

create or replace function public.memory_v2_history(
  p_owner_id uuid,
  p_actor text,
  p_memory_id uuid
)
returns jsonb
language sql
stable
security invoker
set search_path = pg_catalog, public
as $$
  select coalesce(jsonb_agg(to_jsonb(r) order by r.revision_number), '[]'::jsonb)
  from public.memory_v2_revisions r
  join public.memory_v2_entries e on e.id = r.memory_id
  where e.id = p_memory_id and e.owner_id = p_owner_id
    and p_actor in ('gpt', 'claude')
    and (
      e.space_key = p_actor
      or (e.space_key = 'shared' and e.shared_status = 'approved')
    );
$$;

create or replace function public.memory_v2_expand_source(
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
  if p_actor not in ('gpt', 'claude') then
    raise exception 'fixed actor is required' using errcode = '42501';
  end if;
  select s.* into source_row
  from public.memory_v2_sources s
  where s.id = p_source_id and s.owner_id = p_owner_id
    and exists (
      select 1
      from public.memory_v2_revision_sources links
      join public.memory_v2_revisions r on r.id = links.revision_id
      join public.memory_v2_entries e on e.id = r.memory_id
      where links.source_id = s.id
        and (
          e.space_key = p_actor
          or (e.space_key = 'shared' and e.shared_status = 'approved')
        )
    );
  if not found then
    raise exception 'source is unavailable' using errcode = '42501';
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

create or replace function public.memory_v2_remember(
  p_owner_id uuid,
  p_actor text,
  p_content text,
  p_options jsonb default '{}'::jsonb
)
returns jsonb
language plpgsql
security invoker
set search_path = pg_catalog, public, extensions
as $$
declare
  memory_id uuid := extensions.gen_random_uuid();
  new_revision_id uuid := extensions.gen_random_uuid();
  created_at_value timestamptz;
  event_time_value timestamptz;
  human_importance_value smallint;
  ai_importance_value smallint;
  metadata_value jsonb;
  supersedes_id uuid;
begin
  if p_actor not in ('gpt', 'claude') then
    raise exception 'fixed actor is required' using errcode = '42501';
  end if;
  if length(btrim(coalesce(p_content, ''))) not between 1 and 50000 then
    raise exception 'content is required' using errcode = '22023';
  end if;
  if p_options is null or jsonb_typeof(p_options) <> 'object' then
    raise exception 'options must be an object' using errcode = '22023';
  end if;
  if p_options ?| array['owner_id', 'actor', 'space_key', 'shared_status', 'created_by_actor'] then
    raise exception 'authority fields are server controlled' using errcode = '42501';
  end if;

  event_time_value := nullif(p_options ->> 'event_time', '')::timestamptz;
  human_importance_value := nullif(p_options ->> 'human_importance', '')::smallint;
  ai_importance_value := nullif(p_options ->> 'ai_importance', '')::smallint;
  metadata_value := coalesce(p_options -> 'metadata', '{}'::jsonb);
  supersedes_id := nullif(p_options ->> 'supersedes_memory_id', '')::uuid;
  if jsonb_typeof(metadata_value) <> 'object' then
    raise exception 'metadata must be an object' using errcode = '22023';
  end if;

  insert into public.memory_v2_entries (
    id, owner_id, space_key, created_by_actor, current_revision_id
  ) values (
    memory_id, p_owner_id, p_actor, p_actor, new_revision_id
  ) returning created_at into created_at_value;

  insert into public.memory_v2_revisions (
    id, memory_id, revision_number, content, event_time,
    human_importance, ai_importance, metadata, created_by_actor
  ) values (
    new_revision_id, memory_id, 1, btrim(p_content), event_time_value,
    human_importance_value, ai_importance_value, metadata_value, p_actor
  );

  perform public.memory_v2_materialize_sources(
    p_owner_id, p_actor, p_actor, new_revision_id, p_options -> 'sources'
  );

  if supersedes_id is not null then
    update public.memory_v2_entries
    set status = 'superseded', superseded_by_id = memory_id, updated_at = now()
    where id = supersedes_id
      and owner_id = p_owner_id
      and space_key = p_actor
      and status = 'active'
      and superseded_by_id is null;
    if not found then
      raise exception 'superseded memory is unavailable' using errcode = '42501';
    end if;
  end if;

  return jsonb_build_object(
    'memory_id', memory_id,
    'revision_id', new_revision_id,
    'revision_number', 1,
    'space_key', p_actor,
    'created_at', created_at_value
  );
end;
$$;

create or replace function public.memory_v2_revise(
  p_owner_id uuid,
  p_actor text,
  p_memory_id uuid,
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
  new_revision_id uuid := extensions.gen_random_uuid();
  revision_number_value integer;
  metadata_value jsonb;
  event_time_value timestamptz;
  human_importance_value smallint;
  ai_importance_value smallint;
  created_at_value timestamptz;
begin
  if p_actor not in ('gpt', 'claude') then
    raise exception 'fixed actor is required' using errcode = '42501';
  end if;
  if length(btrim(coalesce(p_content, ''))) not between 1 and 50000 then
    raise exception 'content is required' using errcode = '22023';
  end if;
  if p_options is null or jsonb_typeof(p_options) <> 'object' then
    raise exception 'options must be an object' using errcode = '22023';
  end if;
  if p_options ?| array['owner_id', 'actor', 'space_key', 'shared_status', 'created_by_actor'] then
    raise exception 'authority fields are server controlled' using errcode = '42501';
  end if;

  select * into entry_row
  from public.memory_v2_entries
  where id = p_memory_id and owner_id = p_owner_id and space_key = p_actor
    and status = 'active' and superseded_by_id is null
  for update;
  if not found then
    raise exception 'memory is unavailable' using errcode = '42501';
  end if;

  select * into strict previous_revision
  from public.memory_v2_revisions where id = entry_row.current_revision_id;
  revision_number_value := previous_revision.revision_number + 1;
  metadata_value := case when p_options ? 'metadata'
    then p_options -> 'metadata' else previous_revision.metadata end;
  event_time_value := case when p_options ? 'event_time'
    then nullif(p_options ->> 'event_time', '')::timestamptz else previous_revision.event_time end;
  human_importance_value := case when p_options ? 'human_importance'
    then nullif(p_options ->> 'human_importance', '')::smallint else previous_revision.human_importance end;
  ai_importance_value := case when p_options ? 'ai_importance'
    then nullif(p_options ->> 'ai_importance', '')::smallint else previous_revision.ai_importance end;

  if jsonb_typeof(metadata_value) <> 'object' then
    raise exception 'metadata must be an object' using errcode = '22023';
  end if;

  insert into public.memory_v2_revisions (
    id, memory_id, revision_number, content, event_time,
    human_importance, ai_importance, metadata, created_by_actor, reason
  ) values (
    new_revision_id, p_memory_id, revision_number_value, btrim(p_content), event_time_value,
    human_importance_value, ai_importance_value, metadata_value, p_actor,
    nullif(p_options ->> 'reason', '')
  ) returning created_at into created_at_value;

  if p_options ? 'sources' then
    perform public.memory_v2_materialize_sources(
      p_owner_id, p_actor, p_actor, new_revision_id, p_options -> 'sources'
    );
  else
    insert into public.memory_v2_revision_sources (revision_id, source_id, ordinal)
    select new_revision_id, links.source_id, links.ordinal
    from public.memory_v2_revision_sources links
    where links.revision_id = previous_revision.id;
  end if;

  update public.memory_v2_entries
  set current_revision_id = new_revision_id, updated_at = now()
  where id = p_memory_id;

  return jsonb_build_object(
    'memory_id', p_memory_id,
    'revision_id', new_revision_id,
    'revision_number', revision_number_value,
    'space_key', p_actor,
    'created_at', created_at_value
  );
end;
$$;

revoke all on function public.memory_v2_materialize_sources(uuid, text, text, uuid, jsonb) from public, anon, authenticated;
revoke all on function public.memory_v2_remember(uuid, text, text, jsonb) from public, anon, authenticated;
revoke all on function public.memory_v2_revise(uuid, text, uuid, text, jsonb) from public, anon, authenticated;
revoke all on function public.memory_v2_approve_shared(uuid, uuid) from public, anon, authenticated;
revoke all on function public.memory_v2_recall_lexical(uuid, text, text, integer) from public, anon, authenticated;
revoke all on function public.memory_v2_recall_semantic(uuid, text, real[], text, integer) from public, anon, authenticated;
revoke all on function public.memory_v2_store_embedding(uuid, text, uuid, text, real[]) from public, anon, authenticated;
revoke all on function public.memory_v2_record_recall(uuid, text, uuid[], timestamptz) from public, anon, authenticated;
revoke all on function public.memory_v2_history(uuid, text, uuid) from public, anon, authenticated;
revoke all on function public.memory_v2_expand_source(uuid, text, uuid) from public, anon, authenticated;

grant execute on function public.memory_v2_remember(uuid, text, text, jsonb) to service_role;
grant execute on function public.memory_v2_materialize_sources(uuid, text, text, uuid, jsonb) to service_role;
grant execute on function public.memory_v2_revise(uuid, text, uuid, text, jsonb) to service_role;
grant execute on function public.memory_v2_approve_shared(uuid, uuid) to service_role;
grant execute on function public.memory_v2_recall_lexical(uuid, text, text, integer) to service_role;
grant execute on function public.memory_v2_recall_semantic(uuid, text, real[], text, integer) to service_role;
grant execute on function public.memory_v2_store_embedding(uuid, text, uuid, text, real[]) to service_role;
grant execute on function public.memory_v2_record_recall(uuid, text, uuid[], timestamptz) to service_role;
grant execute on function public.memory_v2_history(uuid, text, uuid) to service_role;
grant execute on function public.memory_v2_expand_source(uuid, text, uuid) to service_role;

comment on table public.memory_v2_entries is 'Memory V2 identity/currentness sidecar; V1 remains untouched.';
comment on table public.memory_v2_revisions is 'Append-only Memory V2 content revisions.';
comment on table public.memory_v2_sources is 'Optional reusable Memory V2 evidence snapshots/descriptors.';
comment on table public.memory_v2_embeddings is 'Rebuildable semantic sidecar; never required for remember or lexical recall.';

commit;
