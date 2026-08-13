begin;

-- Memory Source / Evidence Delta
-- Adds summary-first reads and immutable revision-level evidence links without
-- migrating legacy content or enabling any production runtime flag.

alter table public.memory_entries
  add column summary text,
  add constraint memory_entries_summary_check check (
    summary is null or length(btrim(summary)) between 1 and 2000
  );

alter table public.memory_revisions
  add column summary text,
  add constraint memory_revisions_summary_check check (
    summary is null or length(btrim(summary)) between 1 and 2000
  );

create table public.memory_sources (
  id bigint generated always as identity primary key,
  owner_id uuid not null references auth.users(id) on delete restrict,
  source_space_key text not null references public.memory_space_catalog(space_key) on update restrict,
  source_channel text not null check (length(btrim(source_channel)) between 1 and 80),
  source_kind text not null check (
    source_kind ~ '^[a-z][a-z0-9_]{0,79}$'
  ),
  locator jsonb not null default '{}'::jsonb check (jsonb_typeof(locator) = 'object'),
  quote_text text check (quote_text is null or length(btrim(quote_text)) between 1 and 10000),
  created_by_actor text not null check (created_by_actor in ('gpt', 'claude')),
  created_at timestamptz not null default now(),
  unique (id, owner_id),
  constraint memory_sources_private_space_check check (
    source_space_key in ('gpt', 'claude') and source_space_key = created_by_actor
  ),
  constraint memory_sources_shape_check check (
    (
      source_kind = 'lovehouse_message'
      and quote_text is null
      and coalesce(locator ->> 'message_id', '') ~ '^[1-9][0-9]{0,18}$'
      and not (locator ? 'start_message_id')
      and not (locator ? 'end_message_id')
    )
    or (
      source_kind in ('lovehouse_message_range', 'lovehouse_range')
      and quote_text is null
      and coalesce(locator ->> 'start_message_id', '') ~ '^[1-9][0-9]{0,18}$'
      and coalesce(locator ->> 'end_message_id', '') ~ '^[1-9][0-9]{0,18}$'
      and (locator ->> 'end_message_id')::bigint >= (locator ->> 'start_message_id')::bigint
      and (locator ->> 'end_message_id')::bigint - (locator ->> 'start_message_id')::bigint <= 49
      and not (locator ? 'message_id')
    )
    or (
      source_kind = 'manual_quote'
      and quote_text is not null
    )
    or (
      source_kind = 'manual_summary'
      and quote_text is null
    )
    or (
      source_kind not in (
        'lovehouse_message', 'lovehouse_message_range', 'lovehouse_range',
        'manual_quote', 'manual_summary'
      )
      and quote_text is null
    )
  )
);

create index memory_sources_owner_space_idx
  on public.memory_sources (owner_id, source_space_key, id);

create table public.memory_revision_sources (
  id bigint generated always as identity primary key,
  owner_id uuid not null references auth.users(id) on delete restrict,
  memory_id bigint not null references public.memory_entries(id) on delete restrict,
  revision_id bigint not null,
  source_id bigint not null,
  ordinal smallint not null check (ordinal between 1 and 8),
  created_at timestamptz not null default now(),
  unique (revision_id, source_id),
  unique (revision_id, ordinal),
  constraint memory_revision_sources_revision_fk
    foreign key (revision_id, memory_id, owner_id)
    references public.memory_revisions (id, memory_id, owner_id)
    on delete restrict,
  constraint memory_revision_sources_source_fk
    foreign key (source_id, owner_id)
    references public.memory_sources (id, owner_id)
    on delete restrict
);

create index memory_revision_sources_source_idx
  on public.memory_revision_sources (owner_id, source_id, revision_id);
create index memory_revision_sources_revision_idx
  on public.memory_revision_sources (owner_id, memory_id, revision_id, ordinal);

create or replace function public.memory_source_reject_change()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  raise exception '% is append-only', tg_table_name using errcode = '55000';
end;
$$;

-- Extend the existing revision contract. Null-summary revisions retain the
-- exact pre-delta hash, so already-approved Shared snapshots do not drift.
create or replace function public.memory_compute_revision_hash(p_revision_id bigint)
returns text
language sql
stable
set search_path = ''
as $$
  select public.memory_hash_jsonb(
    case when r.summary is null then
      pg_catalog.jsonb_build_object(
        'id', r.id,
        'owner_id', r.owner_id,
        'memory_id', r.memory_id,
        'revision_number', r.revision_number,
        'title', r.title,
        'content', r.content,
        'author', r.author,
        'memory_type', r.memory_type,
        'tags', r.tags,
        'emotion', r.emotion,
        'importance', r.importance,
        'retention', r.retention,
        'lifecycle_status', r.lifecycle_status,
        'editor_actor', r.editor_actor,
        'revision_reason', r.revision_reason,
        'created_at', r.created_at
      )
    else
      pg_catalog.jsonb_build_object(
        'id', r.id,
        'owner_id', r.owner_id,
        'memory_id', r.memory_id,
        'revision_number', r.revision_number,
        'title', r.title,
        'content', r.content,
        'summary', r.summary,
        'author', r.author,
        'memory_type', r.memory_type,
        'tags', r.tags,
        'emotion', r.emotion,
        'importance', r.importance,
        'retention', r.retention,
        'lifecycle_status', r.lifecycle_status,
        'editor_actor', r.editor_actor,
        'revision_reason', r.revision_reason,
        'created_at', r.created_at
      )
    end
  )
  from public.memory_revisions r
  where r.id = p_revision_id;
$$;

create or replace function public.memory_prepare_entry_write()
returns trigger
language plpgsql
set search_path = ''
as $$
declare
  source_owner uuid;
  source_space text;
  source_revision public.memory_revisions%rowtype;
  content_changed boolean;
  source_links_changed boolean := coalesce(
    current_setting('lovehouse.memory_source_change', true), 'false'
  ) = 'true';
begin
  if tg_op = 'INSERT' then
    if new.updated_by_actor is not null or new.revision_reason is not null then
      raise exception 'Initial memory creation cannot preload revision metadata' using errcode = '23514';
    end if;
    if new.space_key = 'shared' then
      if not public.memory_internal_authority_is('curator') then
        raise exception 'Shared candidates require the trusted Curator RPC'
          using errcode = '42501';
      end if;
      if new.created_by_actor <> 'curator' then
        raise exception 'Only Curator may create a Shared candidate' using errcode = '42501';
      end if;
      if new.shared_status <> 'candidate' then
        raise exception 'Shared memory must enter as candidate' using errcode = '23514';
      end if;
      if new.source_memory_id is null or new.source_revision_id is null then
        raise exception 'Shared candidate requires a source memory and exact revision'
          using errcode = '23514';
      end if;
      select owner_id, space_key into source_owner, source_space
      from public.memory_entries where id = new.source_memory_id;
      if not found or source_owner is distinct from new.owner_id
        or source_space not in ('gpt', 'claude')
      then
        raise exception 'Shared candidate source must be a same-owner private memory'
          using errcode = '23514';
      end if;
      select * into source_revision
      from public.memory_revisions
      where id = new.source_revision_id
        and memory_id = new.source_memory_id
        and owner_id = new.owner_id;
      if not found then
        raise exception 'Shared candidate revision does not belong to its source memory and owner'
          using errcode = '23514';
      end if;
      new.title := source_revision.title;
      new.content := source_revision.content;
      new.summary := source_revision.summary;
      new.author := source_revision.author;
      new.memory_type := source_revision.memory_type;
      new.tags := source_revision.tags;
      new.emotion := source_revision.emotion;
      new.importance := source_revision.importance;
      new.retention := source_revision.retention;
      new.lifecycle_status := source_revision.lifecycle_status;
      new.source_revision_hash := public.memory_compute_revision_hash(new.source_revision_id);
    end if;
    new.revision_number := 1;
    new.updated_at := new.created_at;
    return new;
  end if;

  if new.owner_id is distinct from old.owner_id
    or new.space_key is distinct from old.space_key
    or new.created_by_actor is distinct from old.created_by_actor
    or new.source_type is distinct from old.source_type
    or new.source_model is distinct from old.source_model
    or new.source_ref is distinct from old.source_ref
    or new.source_metadata is distinct from old.source_metadata
    or new.source_memory_id is distinct from old.source_memory_id
    or new.source_revision_id is distinct from old.source_revision_id
    or new.source_revision_hash is distinct from old.source_revision_hash
    or new.original_table is distinct from old.original_table
    or new.original_id is distinct from old.original_id
    or new.original_created_at is distinct from old.original_created_at
    or new.legacy_source is distinct from old.legacy_source
    or new.created_at is distinct from old.created_at
  then
    raise exception 'Memory ownership, space and origin are immutable' using errcode = '55000';
  end if;

  if old.space_key = 'shared' then
    if not public.memory_internal_authority_is('owner') then
      raise exception 'Shared decisions require the authenticated Owner RPC'
        using errcode = '42501';
    end if;
    if row(
      new.memory_type, new.tags, new.title, new.content, new.summary, new.emotion,
      new.importance, new.retention, new.lifecycle_status, new.decay_score,
      new.decay_updated_at, new.awaken_count, new.last_awakened_at,
      new.last_accessed_at, new.author
    ) is distinct from row(
      old.memory_type, old.tags, old.title, old.content, old.summary, old.emotion,
      old.importance, old.retention, old.lifecycle_status, old.decay_score,
      old.decay_updated_at, old.awaken_count, old.last_awakened_at,
      old.last_accessed_at, old.author
    ) then
      raise exception 'Shared candidate and approved snapshots are immutable'
        using errcode = '55000';
    end if;
    if new.shared_status is not distinct from old.shared_status then
      raise exception 'Shared rows only permit an explicit Owner state transition'
        using errcode = '55000';
    end if;
    if new.updated_by_actor <> 'owner'
      or nullif(btrim(new.revision_reason), '') is null
      or new.revision_reason is not distinct from old.revision_reason
    then
      raise exception 'Only Owner may approve, reject or revoke Shared memory'
        using errcode = '42501';
    end if;
    if not (
      (old.shared_status = 'candidate' and new.shared_status in ('approved', 'rejected'))
      or (old.shared_status = 'approved' and new.shared_status = 'revoked')
    ) then
      raise exception 'Invalid Shared state transition: % -> %', old.shared_status, new.shared_status
        using errcode = '23514';
    end if;
    new.revision_number := 1;
    new.updated_at := now();
    return new;
  end if;

  if new.shared_status is distinct from old.shared_status then
    raise exception 'Private and Legacy memories cannot enter Shared by UPDATE'
      using errcode = '23514';
  end if;

  content_changed := row(
    new.title, new.content, new.summary, new.author, new.memory_type, new.tags,
    new.emotion, new.importance, new.retention, new.lifecycle_status
  ) is distinct from row(
    old.title, old.content, old.summary, old.author, old.memory_type, old.tags,
    old.emotion, old.importance, old.retention, old.lifecycle_status
  ) or source_links_changed;

  if content_changed then
    if nullif(btrim(new.updated_by_actor), '') is null
      or nullif(btrim(new.revision_reason), '') is null
      or new.revision_reason is not distinct from old.revision_reason
    then
      raise exception 'Memory content changes require updated_by_actor and revision_reason'
        using errcode = '23514';
    end if;
    new.revision_number := old.revision_number + 1;
  else
    new.revision_number := old.revision_number;
  end if;

  if not content_changed
    and new.shared_status is not distinct from old.shared_status
    and (
      new.updated_by_actor is distinct from old.updated_by_actor
      or new.revision_reason is distinct from old.revision_reason
    )
  then
    raise exception 'Revision metadata requires a tracked content or Shared state change'
      using errcode = '23514';
  end if;

  new.updated_at := now();
  return new;
end;
$$;

create or replace function public.memory_source_validate_link()
returns trigger
language plpgsql
set search_path = ''
as $$
declare
  entry public.memory_entries%rowtype;
  source public.memory_sources%rowtype;
begin
  select * into strict entry
  from public.memory_entries
  where id = new.memory_id and owner_id = new.owner_id;

  select * into strict source
  from public.memory_sources
  where id = new.source_id and owner_id = new.owner_id;

  if entry.space_key in ('gpt', 'claude') then
    if source.source_space_key <> entry.space_key then
      raise exception 'Private memory revisions may only link same-namespace sources'
        using errcode = '42501';
    end if;
  elsif entry.space_key = 'shared' then
    if entry.source_revision_id is null or not exists (
      select 1
      from public.memory_revision_sources inherited
      where inherited.owner_id = new.owner_id
        and inherited.revision_id = entry.source_revision_id
        and inherited.source_id = new.source_id
    ) then
      raise exception 'Shared revisions may only inherit sources from their exact private source revision'
        using errcode = '42501';
    end if;
  else
    raise exception 'Legacy Pending cannot receive canonical source links'
      using errcode = '42501';
  end if;

  return new;
end;
$$;

create trigger memory_sources_append_only
  before update or delete on public.memory_sources
  for each row execute function public.memory_source_reject_change();
create trigger memory_revision_sources_validate
  before insert on public.memory_revision_sources
  for each row execute function public.memory_source_validate_link();
create trigger memory_revision_sources_append_only
  before update or delete on public.memory_revision_sources
  for each row execute function public.memory_source_reject_change();

create or replace function public.memory_source_inherit_revision_links()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  entry public.memory_entries%rowtype;
  prior_revision_id bigint;
  source_mode text := coalesce(current_setting('lovehouse.memory_source_mode', true), 'inherit');
begin
  select * into strict entry
  from public.memory_entries
  where id = new.memory_id and owner_id = new.owner_id;

  if entry.space_key = 'shared' then
    insert into public.memory_revision_sources (
      owner_id, memory_id, revision_id, source_id, ordinal, created_at
    )
    select new.owner_id, new.memory_id, new.id, links.source_id, links.ordinal, new.created_at
    from public.memory_revision_sources links
    where links.owner_id = new.owner_id
      and links.revision_id = entry.source_revision_id
    order by links.ordinal;
  elsif entry.space_key in ('gpt', 'claude')
    and new.revision_number > 1
    and source_mode <> 'replace'
  then
    select id into strict prior_revision_id
    from public.memory_revisions
    where owner_id = new.owner_id
      and memory_id = new.memory_id
      and revision_number = new.revision_number - 1;

    insert into public.memory_revision_sources (
      owner_id, memory_id, revision_id, source_id, ordinal, created_at
    )
    select new.owner_id, new.memory_id, new.id, links.source_id, links.ordinal, new.created_at
    from public.memory_revision_sources links
    where links.owner_id = new.owner_id
      and links.revision_id = prior_revision_id
    order by links.ordinal;
  end if;

  return new;
end;
$$;

create trigger memory_revisions_inherit_sources
  after insert on public.memory_revisions
  for each row execute function public.memory_source_inherit_revision_links();

create or replace function public.memory_source_materialize_links(
  p_actor text,
  p_owner_id uuid,
  p_memory_id bigint,
  p_revision_id bigint,
  p_sources jsonb
)
returns integer
language plpgsql
security definer
set search_path = ''
as $$
declare
  source_input record;
  source_row public.memory_sources%rowtype;
  requested_source_id bigint;
  source_count integer := 0;
begin
  if p_actor not in ('gpt', 'claude')
    or p_sources is null
    or jsonb_typeof(p_sources) <> 'array'
    or jsonb_array_length(p_sources) > 8
  then
    raise exception 'A fixed actor and an array of at most eight sources are required'
      using errcode = '23514';
  end if;

  for source_input in
    select value, ordinality::smallint as ordinal
    from jsonb_array_elements(p_sources) with ordinality
  loop
    if jsonb_typeof(source_input.value) <> 'object' then
      raise exception 'Each source must be an object' using errcode = '23514';
    end if;

    requested_source_id := null;
    if source_input.value ? 'source_id' then
      if coalesce(source_input.value ->> 'source_id', '') !~ '^[1-9][0-9]{0,18}$' then
        raise exception 'source_id must be a positive integer' using errcode = '23514';
      end if;
      requested_source_id := (source_input.value ->> 'source_id')::bigint;
      select * into source_row
      from public.memory_sources
      where id = requested_source_id
        and owner_id = p_owner_id
        and source_space_key = p_actor;
      if not found then
        raise exception 'Source is outside the fixed actor namespace'
          using errcode = '42501';
      end if;
    else
      insert into public.memory_sources (
        owner_id, source_space_key, source_channel, source_kind,
        locator, quote_text, created_by_actor
      ) values (
        p_owner_id,
        p_actor,
        nullif(btrim(source_input.value ->> 'source_channel'), ''),
        nullif(btrim(source_input.value ->> 'source_kind'), ''),
        case when jsonb_typeof(source_input.value -> 'locator') = 'object'
          then source_input.value -> 'locator' else '{}'::jsonb end,
        nullif(btrim(source_input.value ->> 'quote_text'), ''),
        p_actor
      ) returning * into source_row;
    end if;

    insert into public.memory_revision_sources (
      owner_id, memory_id, revision_id, source_id, ordinal
    ) values (
      p_owner_id, p_memory_id, p_revision_id, source_row.id, source_input.ordinal
    );
    source_count := source_count + 1;
  end loop;

  return source_count;
end;
$$;

create or replace function public.memory_capture_entry_history()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  provenance_event text;
  source_links_changed boolean := coalesce(
    current_setting('lovehouse.memory_source_change', true), 'false'
  ) = 'true';
begin
  if tg_op = 'INSERT' then
    insert into public.memory_revisions (
      owner_id, memory_id, revision_number, title, content, summary, author,
      memory_type, tags, emotion, importance, retention, lifecycle_status,
      editor_actor, revision_reason, created_at
    ) values (
      new.owner_id, new.id, 1, new.title, new.content, new.summary, new.author,
      new.memory_type, new.tags, new.emotion, new.importance, new.retention,
      new.lifecycle_status, new.created_by_actor, 'initial_create', new.created_at
    );

    provenance_event := case
      when new.space_key = 'legacy_pending' then 'legacy_staged'
      when new.space_key = 'shared' then 'shared_candidate'
      else 'created'
    end;

    insert into public.memory_provenance (
      owner_id, memory_id, parent_memory_id, parent_revision_id,
      event_type, actor, source_type, source_model, source_ref,
      original_table, original_id, original_created_at, legacy_source,
      reason, details, created_at
    ) values (
      new.owner_id, new.id, new.source_memory_id, new.source_revision_id,
      provenance_event, new.created_by_actor, new.source_type, new.source_model,
      new.source_ref, new.original_table, new.original_id,
      new.original_created_at, new.legacy_source, 'initial_create',
      case when new.space_key = 'shared'
        then new.source_metadata || pg_catalog.jsonb_build_object(
          'source_revision_id', new.source_revision_id,
          'source_revision_hash', new.source_revision_hash
        )
        else new.source_metadata
      end,
      new.created_at
    );

    if new.space_key = 'shared' then
      insert into public.memory_shared_transitions (
        owner_id, memory_id, from_status, to_status, actor, reason,
        source_memory_id, source_revision_id, source_revision_hash, created_at
      ) values (
        new.owner_id, new.id, null, 'candidate', new.created_by_actor,
        'shared_candidate_created', new.source_memory_id, new.source_revision_id,
        new.source_revision_hash, new.created_at
      );
    end if;
    return new;
  end if;

  if new.revision_number > old.revision_number then
    insert into public.memory_revisions (
      owner_id, memory_id, revision_number, title, content, summary, author,
      memory_type, tags, emotion, importance, retention, lifecycle_status,
      editor_actor, revision_reason, created_at
    ) values (
      new.owner_id, new.id, new.revision_number, new.title, new.content,
      new.summary, new.author, new.memory_type, new.tags, new.emotion,
      new.importance, new.retention, new.lifecycle_status,
      new.updated_by_actor, new.revision_reason, new.updated_at
    );

    insert into public.memory_provenance (
      owner_id, memory_id, parent_memory_id, parent_revision_id,
      event_type, actor, reason, details, created_at
    ) values (
      new.owner_id, new.id, new.source_memory_id, new.source_revision_id,
      'revised', new.updated_by_actor, new.revision_reason,
      pg_catalog.jsonb_build_object(
        'revision_number', new.revision_number,
        'source_links_changed', source_links_changed
      ),
      new.updated_at
    );
  end if;

  if new.shared_status is distinct from old.shared_status then
    insert into public.memory_shared_transitions (
      owner_id, memory_id, from_status, to_status, actor, reason,
      source_memory_id, source_revision_id, source_revision_hash, created_at
    ) values (
      new.owner_id, new.id, old.shared_status, new.shared_status,
      new.updated_by_actor, new.revision_reason, new.source_memory_id,
      new.source_revision_id, new.source_revision_hash, new.updated_at
    );

    insert into public.memory_provenance (
      owner_id, memory_id, parent_memory_id, parent_revision_id,
      event_type, actor, reason, details, created_at
    ) values (
      new.owner_id, new.id, new.source_memory_id, new.source_revision_id,
      case new.shared_status
        when 'candidate' then 'shared_candidate'
        when 'approved' then 'shared_approved'
        when 'rejected' then 'shared_rejected'
        when 'revoked' then 'shared_revoked'
      end,
      new.updated_by_actor, new.revision_reason,
      pg_catalog.jsonb_build_object(
        'from', old.shared_status,
        'to', new.shared_status,
        'source_revision_hash', new.source_revision_hash
      ),
      new.updated_at
    );
  end if;
  return new;
end;
$$;

create or replace function public.memory_validate_revision_insert()
returns trigger
language plpgsql
set search_path = ''
as $$
declare
  entry public.memory_entries%rowtype;
begin
  select * into strict entry
  from public.memory_entries
  where id = new.memory_id;

  if entry.space_key = 'shared' and (
    new.owner_id is distinct from entry.owner_id
    or new.revision_number <> 1
    or row(
      new.title, new.content, new.summary, new.author, new.memory_type, new.tags,
      new.emotion, new.importance, new.retention, new.lifecycle_status
    ) is distinct from row(
      entry.title, entry.content, entry.summary, entry.author,
      entry.memory_type, entry.tags, entry.emotion, entry.importance,
      entry.retention, entry.lifecycle_status
    )
    or new.editor_actor <> 'curator'
    or new.revision_reason <> 'initial_create'
  ) then
    raise exception 'Shared snapshots permit exactly one immutable initial revision'
      using errcode = '23514';
  end if;

  return new;
end;
$$;

create or replace function public.memory_source_summary_item(p_entry public.memory_entries)
returns jsonb
language sql
stable
set search_path = ''
as $$
  select pg_catalog.jsonb_build_object(
    'id', p_entry.id,
    'memory_id', p_entry.id,
    'revision_id', revision.id,
    'revision_number', p_entry.revision_number,
    'revision_hash', public.memory_compute_revision_hash(revision.id),
    'space_key', p_entry.space_key,
    'shared_status', p_entry.shared_status,
    'title', p_entry.title,
    'summary', coalesce(p_entry.summary, left(p_entry.content, 320)),
    'summary_origin', case when p_entry.summary is null
      then 'excerpt_fallback' else 'stored' end,
    'memory_type', p_entry.memory_type,
    'tags', p_entry.tags,
    'emotion', p_entry.emotion,
    'importance', p_entry.importance,
    'retention', p_entry.retention,
    'author', p_entry.author,
    'created_at', p_entry.created_at,
    'updated_at', p_entry.updated_at,
    'has_source', coalesce(source_links.source_count, 0) > 0,
    'source_count', coalesce(source_links.source_count, 0),
    'sources', coalesce(source_links.sources, '[]'::jsonb)
  )
  from public.memory_revisions revision
  left join lateral (
    select
      count(*)::integer as source_count,
      pg_catalog.jsonb_agg(
        pg_catalog.jsonb_build_object(
          'source_id', source.id,
          'source_channel', source.source_channel,
          'source_kind', source.source_kind,
          'can_expand', source.source_kind <> 'manual_summary'
        ) order by links.ordinal
      ) as sources
    from public.memory_revision_sources links
    join public.memory_sources source
      on source.owner_id = links.owner_id and source.id = links.source_id
    where links.owner_id = p_entry.owner_id
      and links.memory_id = p_entry.id
      and links.revision_id = revision.id
  ) source_links on true
  where revision.owner_id = p_entry.owner_id
    and revision.memory_id = p_entry.id
    and revision.revision_number = p_entry.revision_number;
$$;

create or replace function public.memory_source_summarize_items(p_items jsonb)
returns jsonb
language sql
stable
set search_path = ''
as $$
  select coalesce(
    pg_catalog.jsonb_agg(public.memory_source_summary_item(entry) order by item.ordinality),
    '[]'::jsonb
  )
  from pg_catalog.jsonb_array_elements(coalesce(p_items, '[]'::jsonb))
    with ordinality as item(value, ordinality)
  join public.memory_entries entry
    on entry.id = coalesce(
      nullif(item.value ->> 'id', '')::bigint,
      nullif(item.value ->> 'memory_id', '')::bigint
    );
$$;

create or replace function public.memory_source_runtime_internal_remember(
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
  saved_revision_id bigint;
  tags text[];
  emotion jsonb;
  importance smallint;
  request_material jsonb;
  sources jsonb := coalesce(p_memory -> 'sources', '[]'::jsonb);
begin
  if p_actor not in ('gpt', 'claude')
    or p_request_id is null
    or p_memory is null
    or jsonb_typeof(p_memory) <> 'object'
  then
    raise exception 'Trusted actor, request id and normalized memory object are required'
      using errcode = '23514';
  end if;
  if jsonb_typeof(sources) <> 'array' or jsonb_array_length(sources) > 8 then
    raise exception 'sources must be an array with at most eight items'
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

  select coalesce(pg_catalog.array_agg(value), '{}'::text[]) into tags
  from pg_catalog.jsonb_array_elements_text(
    case when pg_catalog.jsonb_typeof(p_memory -> 'tags') = 'array'
      then p_memory -> 'tags' else '[]'::jsonb end
  ) as tag_values(value);
  emotion := case when pg_catalog.jsonb_typeof(p_memory -> 'emotion') = 'object'
    then p_memory -> 'emotion' else '{}'::jsonb end;
  importance := case when coalesce(p_memory ->> 'importance', '') ~ '^[1-5]$'
    then (p_memory ->> 'importance')::smallint else 1 end;

  insert into public.memory_entries (
    owner_id, space_key, memory_type, tags, title, content, summary, emotion,
    importance, retention, author, source_type, source_model, source_ref,
    source_metadata, created_by_actor
  ) values (
    p_owner_id,
    p_actor,
    coalesce(nullif(p_memory ->> 'memory_type', ''), 'fact'),
    tags,
    nullif(p_memory ->> 'title', ''),
    pg_catalog.btrim(p_memory ->> 'content'),
    nullif(pg_catalog.btrim(p_memory ->> 'summary'), ''),
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

  select id into strict saved_revision_id
  from public.memory_revisions
  where owner_id = p_owner_id and memory_id = saved.id and revision_number = 1;
  perform public.memory_source_materialize_links(
    p_actor, p_owner_id, saved.id, saved_revision_id, sources
  );

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

create or replace function public.memory_source_runtime_internal_revise(
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
  revised_revision_id bigint;
  new_tags text[];
  new_emotion jsonb;
  new_importance smallint;
  new_memory_type text;
  new_title text;
  new_content text;
  new_summary text;
  new_author text;
  new_retention text;
  request_material jsonb;
  sources_changed boolean;
  sources jsonb;
begin
  if p_actor not in ('gpt', 'claude') then
    raise exception 'A fixed actor is required' using errcode = '42501';
  end if;
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
  if p_request_id is null or p_patch is null or jsonb_typeof(p_patch) <> 'object'
    or nullif(pg_catalog.btrim(p_reason), '') is null
  then
    raise exception 'Trusted request id, patch and revision reason are required'
      using errcode = '23514';
  end if;

  sources_changed := p_patch ? 'sources';
  sources := coalesce(p_patch -> 'sources', '[]'::jsonb);
  if sources_changed and (
    jsonb_typeof(sources) <> 'array' or jsonb_array_length(sources) > 8
  ) then
    raise exception 'sources must be an array with at most eight items'
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
  if p_patch ? 'tags' and jsonb_typeof(p_patch -> 'tags') = 'array' then
    select coalesce(pg_catalog.array_agg(value), '{}'::text[]) into new_tags
    from pg_catalog.jsonb_array_elements_text(p_patch -> 'tags') as tag_values(value);
  end if;
  new_emotion := case when jsonb_typeof(p_patch -> 'emotion') = 'object'
    then p_patch -> 'emotion' else current_entry.emotion end;
  new_importance := case when coalesce(p_patch ->> 'importance', '') ~ '^[1-5]$'
    then (p_patch ->> 'importance')::smallint else current_entry.importance end;
  new_memory_type := coalesce(nullif(p_patch ->> 'memory_type', ''), current_entry.memory_type);
  new_title := case when p_patch ? 'title'
    then nullif(p_patch ->> 'title', '') else current_entry.title end;
  new_content := coalesce(nullif(pg_catalog.btrim(p_patch ->> 'content'), ''), current_entry.content);
  new_summary := case when p_patch ? 'summary'
    then nullif(pg_catalog.btrim(p_patch ->> 'summary'), '') else current_entry.summary end;
  new_author := case when p_patch ? 'author'
    then nullif(p_patch ->> 'author', '') else current_entry.author end;
  new_retention := case when p_patch ? 'retention'
    then nullif(p_patch ->> 'retention', '') else current_entry.retention end;

  if not sources_changed and row(
    new_title, new_content, new_summary, new_author, new_memory_type,
    new_tags, new_emotion, new_importance, new_retention
  ) is not distinct from row(
    current_entry.title, current_entry.content, current_entry.summary,
    current_entry.author, current_entry.memory_type, current_entry.tags,
    current_entry.emotion, current_entry.importance, current_entry.retention
  ) then
    delete from public.memory_mutation_idempotency where id = claim.id;
    perform public.memory_runtime_internal_audit(
      p_owner_id, p_actor, 'revise', p_memory_id, current_entry.space_key,
      'denied', 'NO_MEMORY_CHANGE', p_request_id, 0, '{}'::text[]
    );
    return pg_catalog.jsonb_build_object(
      'ok', false, 'error_code', 'NO_MEMORY_CHANGE',
      'message', 'Revision must change memory content, summary, semantics or sources',
      'audit_persisted', true
    );
  end if;

  if sources_changed then
    perform pg_catalog.set_config('lovehouse.memory_source_mode', 'replace', true);
    perform pg_catalog.set_config('lovehouse.memory_source_change', 'true', true);
  end if;

  update public.memory_entries
    set title = new_title,
        content = new_content,
        summary = new_summary,
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

  select id into strict revised_revision_id
  from public.memory_revisions
  where owner_id = p_owner_id
    and memory_id = revised.id
    and revision_number = revised.revision_number;
  if sources_changed then
    perform public.memory_source_materialize_links(
      p_actor, p_owner_id, revised.id, revised_revision_id, sources
    );
  end if;

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

create or replace function public.memory_source_runtime_internal_list(
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
  result jsonb;
begin
  result := public.memory_runtime_internal_list(
    p_actor, p_owner_id, p_request_id, p_limit, p_cursor_id,
    p_memory_type, p_tags, p_retention
  );
  if not coalesce((result ->> 'ok')::boolean, false) then return result; end if;
  return result || pg_catalog.jsonb_build_object(
    'items', public.memory_source_summarize_items(result -> 'items')
  );
end;
$$;

create or replace function public.memory_source_runtime_internal_recall(
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
  result jsonb;
begin
  result := public.memory_runtime_internal_recall(
    p_actor, p_owner_id, p_request_id, p_query, p_limit, p_cursor_id, p_tags
  );
  if not coalesce((result ->> 'ok')::boolean, false) then return result; end if;
  return result || pg_catalog.jsonb_build_object(
    'items', public.memory_source_summarize_items(result -> 'items')
  );
end;
$$;

create or replace function public.memory_source_runtime_internal_memory_box(
  p_actor text,
  p_owner_id uuid,
  p_request_id uuid,
  p_limit integer
)
returns jsonb
language plpgsql
set search_path = ''
as $$
declare
  result jsonb;
begin
  result := public.memory_runtime_internal_memory_box(
    p_actor, p_owner_id, p_request_id, p_limit
  );
  if not coalesce((result ->> 'ok')::boolean, false) then return result; end if;
  return result || pg_catalog.jsonb_build_object(
    'items', public.memory_source_summarize_items(result -> 'items')
  );
end;
$$;

create or replace function public.memory_source_behavior_internal_recall(
  p_actor text,
  p_owner_id uuid,
  p_request_id uuid,
  p_query text,
  p_query_embedding real[],
  p_query_embedding_profile text,
  p_query_embedding_model text,
  p_ranking_profile text,
  p_limit integer,
  p_cursor_id bigint,
  p_tags text[]
)
returns jsonb
language plpgsql
set search_path = ''
as $$
declare
  result jsonb;
begin
  result := public.memory_behavior_internal_recall(
    p_actor, p_owner_id, p_request_id, p_query, p_query_embedding,
    p_query_embedding_profile, p_query_embedding_model, p_ranking_profile,
    p_limit, p_cursor_id, p_tags
  );
  if not coalesce((result ->> 'ok')::boolean, false) then return result; end if;
  return result || pg_catalog.jsonb_build_object(
    'items', public.memory_source_summarize_items(result -> 'items')
  );
end;
$$;

create or replace function public.memory_source_runtime_internal_expand(
  p_actor text,
  p_owner_id uuid,
  p_request_id uuid,
  p_source_id bigint
)
returns jsonb
language plpgsql
set search_path = ''
as $$
declare
  source public.memory_sources%rowtype;
  shared_visible boolean;
begin
  if p_actor not in ('gpt', 'claude') or p_request_id is null or p_source_id is null then
    raise exception 'Fixed actor, request id and source id are required'
      using errcode = '23514';
  end if;

  select * into source
  from public.memory_sources
  where owner_id = p_owner_id and id = p_source_id;

  if found then
    select exists (
      select 1
      from public.memory_revision_sources links
      join public.memory_revisions revision
        on revision.owner_id = links.owner_id
        and revision.memory_id = links.memory_id
        and revision.id = links.revision_id
      join public.memory_entries entry
        on entry.owner_id = revision.owner_id and entry.id = revision.memory_id
      where links.owner_id = p_owner_id
        and links.source_id = p_source_id
        and entry.space_key = 'shared'
        and entry.shared_status = 'approved'
    ) into shared_visible;
  end if;

  if not found or not (source.source_space_key = p_actor or shared_visible) then
    perform public.memory_runtime_internal_audit(
      p_owner_id, p_actor, 'expand_source', null, null,
      'denied', 'MEMORY_SOURCE_ACCESS_DENIED', p_request_id, 0, '{}'::text[]
    );
    return pg_catalog.jsonb_build_object(
      'ok', false,
      'error_code', 'MEMORY_SOURCE_ACCESS_DENIED',
      'message', 'Source is outside the fixed actor scope',
      'audit_persisted', true
    );
  end if;

  perform public.memory_runtime_internal_audit(
    p_owner_id, p_actor, 'expand_source', null, source.source_space_key,
    'allowed', null, p_request_id, 1, array[source.source_space_key]
  );
  return pg_catalog.jsonb_build_object(
    'ok', true,
    'source', pg_catalog.jsonb_build_object(
      'source_id', source.id,
      'source_channel', source.source_channel,
      'source_kind', source.source_kind,
      'source_space_key', source.source_space_key,
      'locator', source.locator,
      'quote_text', source.quote_text,
      'created_by_actor', source.created_by_actor,
      'created_at', source.created_at
    )
  );
end;
$$;

-- Replace only the fixed actor doors. Existing callers keep the same RPC names
-- and signatures; internal legacy functions remain revoked and rollbackable.
create or replace function public.memory_runtime_list_gpt(
  p_owner_id uuid, p_request_id uuid, p_limit integer, p_cursor_id bigint,
  p_memory_type text, p_tags text[], p_retention text
)
returns jsonb language sql security definer set search_path = '' as $$
  select public.memory_source_runtime_internal_list(
    'gpt', p_owner_id, p_request_id, p_limit, p_cursor_id,
    p_memory_type, p_tags, p_retention
  )
$$;
create or replace function public.memory_runtime_list_claude(
  p_owner_id uuid, p_request_id uuid, p_limit integer, p_cursor_id bigint,
  p_memory_type text, p_tags text[], p_retention text
)
returns jsonb language sql security definer set search_path = '' as $$
  select public.memory_source_runtime_internal_list(
    'claude', p_owner_id, p_request_id, p_limit, p_cursor_id,
    p_memory_type, p_tags, p_retention
  )
$$;
create or replace function public.memory_runtime_recall_gpt(
  p_owner_id uuid, p_request_id uuid, p_query text, p_limit integer,
  p_cursor_id bigint, p_tags text[]
)
returns jsonb language sql security definer set search_path = '' as $$
  select public.memory_source_runtime_internal_recall(
    'gpt', p_owner_id, p_request_id, p_query, p_limit, p_cursor_id, p_tags
  )
$$;
create or replace function public.memory_runtime_recall_claude(
  p_owner_id uuid, p_request_id uuid, p_query text, p_limit integer,
  p_cursor_id bigint, p_tags text[]
)
returns jsonb language sql security definer set search_path = '' as $$
  select public.memory_source_runtime_internal_recall(
    'claude', p_owner_id, p_request_id, p_query, p_limit, p_cursor_id, p_tags
  )
$$;
create or replace function public.memory_runtime_remember_gpt(
  p_owner_id uuid, p_request_id uuid, p_memory jsonb
)
returns jsonb language sql security definer set search_path = '' as $$
  select public.memory_source_runtime_internal_remember(
    'gpt', p_owner_id, p_request_id, p_memory
  )
$$;
create or replace function public.memory_runtime_remember_claude(
  p_owner_id uuid, p_request_id uuid, p_memory jsonb
)
returns jsonb language sql security definer set search_path = '' as $$
  select public.memory_source_runtime_internal_remember(
    'claude', p_owner_id, p_request_id, p_memory
  )
$$;
create or replace function public.memory_runtime_revise_gpt(
  p_owner_id uuid, p_request_id uuid, p_memory_id bigint,
  p_patch jsonb, p_reason text
)
returns jsonb language sql security definer set search_path = '' as $$
  select public.memory_source_runtime_internal_revise(
    'gpt', p_owner_id, p_request_id, p_memory_id, p_patch, p_reason
  )
$$;
create or replace function public.memory_runtime_revise_claude(
  p_owner_id uuid, p_request_id uuid, p_memory_id bigint,
  p_patch jsonb, p_reason text
)
returns jsonb language sql security definer set search_path = '' as $$
  select public.memory_source_runtime_internal_revise(
    'claude', p_owner_id, p_request_id, p_memory_id, p_patch, p_reason
  )
$$;
create or replace function public.memory_runtime_memory_box_gpt(
  p_owner_id uuid, p_request_id uuid, p_limit integer
)
returns jsonb language sql security definer set search_path = '' as $$
  select public.memory_source_runtime_internal_memory_box(
    'gpt', p_owner_id, p_request_id, p_limit
  )
$$;
create or replace function public.memory_runtime_memory_box_claude(
  p_owner_id uuid, p_request_id uuid, p_limit integer
)
returns jsonb language sql security definer set search_path = '' as $$
  select public.memory_source_runtime_internal_memory_box(
    'claude', p_owner_id, p_request_id, p_limit
  )
$$;
create or replace function public.memory_behavior_recall_gpt(
  p_owner_id uuid, p_request_id uuid, p_query text, p_query_embedding real[],
  p_query_embedding_profile text, p_query_embedding_model text,
  p_ranking_profile text, p_limit integer, p_cursor_id bigint, p_tags text[]
)
returns jsonb language sql security definer set search_path = '' as $$
  select public.memory_source_behavior_internal_recall(
    'gpt', p_owner_id, p_request_id, p_query, p_query_embedding,
    p_query_embedding_profile, p_query_embedding_model, p_ranking_profile,
    p_limit, p_cursor_id, p_tags
  )
$$;
create or replace function public.memory_behavior_recall_claude(
  p_owner_id uuid, p_request_id uuid, p_query text, p_query_embedding real[],
  p_query_embedding_profile text, p_query_embedding_model text,
  p_ranking_profile text, p_limit integer, p_cursor_id bigint, p_tags text[]
)
returns jsonb language sql security definer set search_path = '' as $$
  select public.memory_source_behavior_internal_recall(
    'claude', p_owner_id, p_request_id, p_query, p_query_embedding,
    p_query_embedding_profile, p_query_embedding_model, p_ranking_profile,
    p_limit, p_cursor_id, p_tags
  )
$$;
create or replace function public.memory_runtime_expand_source_gpt(
  p_owner_id uuid, p_request_id uuid, p_source_id bigint
)
returns jsonb language sql security definer set search_path = '' as $$
  select public.memory_source_runtime_internal_expand(
    'gpt', p_owner_id, p_request_id, p_source_id
  )
$$;
create or replace function public.memory_runtime_expand_source_claude(
  p_owner_id uuid, p_request_id uuid, p_source_id bigint
)
returns jsonb language sql security definer set search_path = '' as $$
  select public.memory_source_runtime_internal_expand(
    'claude', p_owner_id, p_request_id, p_source_id
  )
$$;

alter table public.memory_sources enable row level security;
alter table public.memory_sources force row level security;
alter table public.memory_revision_sources enable row level security;
alter table public.memory_revision_sources force row level security;

revoke all on table public.memory_sources from public, anon, authenticated, service_role;
revoke all on table public.memory_revision_sources from public, anon, authenticated, service_role;
revoke all on sequence public.memory_sources_id_seq from public, anon, authenticated, service_role;
revoke all on sequence public.memory_revision_sources_id_seq from public, anon, authenticated, service_role;

revoke execute on function public.memory_source_reject_change() from public, anon, authenticated, service_role;
revoke execute on function public.memory_source_validate_link() from public, anon, authenticated, service_role;
revoke execute on function public.memory_source_inherit_revision_links() from public, anon, authenticated, service_role;
revoke execute on function public.memory_source_materialize_links(text, uuid, bigint, bigint, jsonb) from public, anon, authenticated, service_role;
revoke execute on function public.memory_source_summary_item(public.memory_entries) from public, anon, authenticated, service_role;
revoke execute on function public.memory_source_summarize_items(jsonb) from public, anon, authenticated, service_role;
revoke execute on function public.memory_source_runtime_internal_remember(text, uuid, uuid, jsonb) from public, anon, authenticated, service_role;
revoke execute on function public.memory_source_runtime_internal_revise(text, uuid, uuid, bigint, jsonb, text) from public, anon, authenticated, service_role;
revoke execute on function public.memory_source_runtime_internal_list(text, uuid, uuid, integer, bigint, text, text[], text) from public, anon, authenticated, service_role;
revoke execute on function public.memory_source_runtime_internal_recall(text, uuid, uuid, text, integer, bigint, text[]) from public, anon, authenticated, service_role;
revoke execute on function public.memory_source_runtime_internal_memory_box(text, uuid, uuid, integer) from public, anon, authenticated, service_role;
revoke execute on function public.memory_source_behavior_internal_recall(text, uuid, uuid, text, real[], text, text, text, integer, bigint, text[]) from public, anon, authenticated, service_role;
revoke execute on function public.memory_source_runtime_internal_expand(text, uuid, uuid, bigint) from public, anon, authenticated, service_role;
revoke execute on function public.memory_runtime_expand_source_gpt(uuid, uuid, bigint) from public, anon, authenticated, service_role;
revoke execute on function public.memory_runtime_expand_source_claude(uuid, uuid, bigint) from public, anon, authenticated, service_role;

grant execute on function public.memory_runtime_expand_source_gpt(uuid, uuid, bigint) to service_role;
grant execute on function public.memory_runtime_expand_source_claude(uuid, uuid, bigint) to service_role;

comment on column public.memory_entries.summary is
  'Bounded authored summary. Null means summary-first reads return an explicitly marked excerpt fallback.';
comment on column public.memory_revisions.summary is
  'Revision-bound summary snapshot; canonical content remains the full Memory body.';
comment on table public.memory_sources is
  'Immutable reusable source/evidence identities scoped to one owner and one AI private namespace.';
comment on table public.memory_revision_sources is
  'Append-only many-to-many links between exact Memory revisions and immutable sources.';
comment on function public.memory_runtime_expand_source_gpt(uuid, uuid, bigint) is
  'Fixed GPT source descriptor/quote expansion door. LoveHouse message bodies are resolved by the fenced Bridge path.';
comment on function public.memory_runtime_expand_source_claude(uuid, uuid, bigint) is
  'Fixed Claude source descriptor/quote expansion door. LoveHouse message bodies are resolved by the fenced Bridge path.';

commit;
