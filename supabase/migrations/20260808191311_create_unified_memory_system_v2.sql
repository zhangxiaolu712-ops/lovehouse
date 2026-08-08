begin;

-- Unified Memory System V2
-- Structural migration only. It deliberately does not read from or write to
-- brain, memories, or any other legacy content table.

create extension if not exists pgcrypto with schema extensions;

create table public.memory_space_catalog (
  space_key text primary key,
  space_kind text not null check (space_kind in ('private', 'shared', 'legacy')),
  daily_recallable boolean not null,
  description text not null,
  created_at timestamptz not null default now()
);

insert into public.memory_space_catalog (
  space_key,
  space_kind,
  daily_recallable,
  description
)
values
  ('gpt', 'private', true, 'GPT private memory'),
  ('claude', 'private', true, 'Claude private memory'),
  ('shared', 'shared', false, 'Explicitly reviewed shared memory'),
  ('legacy_pending', 'legacy', false, 'Frozen legacy content awaiting curation');

create table public.memory_type_catalog (
  memory_type text primary key,
  display_name text not null,
  description text not null,
  created_at timestamptz not null default now()
);

insert into public.memory_type_catalog (memory_type, display_name, description)
values
  ('fact', '记事', 'Facts and events'),
  ('feeling', '感受', 'Feelings and emotional reflection'),
  ('diary', '日记', 'Diary entry'),
  ('article', '文章', 'Long-form writing'),
  ('small_moment', '小事记', 'Small daily moment'),
  ('memo', '备忘录', 'Reminder or memo'),
  ('self_inquiry', '问心', 'Self-inquiry and judgment'),
  ('quote', '语录', 'Quoted words'),
  ('summary', '总结', 'Summary'),
  ('reflection', '观点', 'Reflection or viewpoint');

create table public.memory_entries (
  id bigint generated always as identity primary key,
  owner_id uuid not null references auth.users(id) on delete restrict,
  space_key text not null references public.memory_space_catalog(space_key) on update restrict,
  memory_type text not null references public.memory_type_catalog(memory_type) on update restrict,
  tags text[] not null default '{}'::text[],
  title text,
  content text not null check (length(btrim(content)) between 1 and 50000),
  emotion jsonb not null default '{}'::jsonb check (jsonb_typeof(emotion) = 'object'),
  importance smallint not null default 1 check (importance between 1 and 5),
  retention text check (retention is null or retention in ('fixed', 'long', 'short', 'temporary')),
  lifecycle_status text not null default 'active'
    check (lifecycle_status in ('candidate', 'active', 'faded', 'awakened', 'archived')),
  decay_score double precision not null default 1 check (decay_score between 0 and 1),
  decay_updated_at timestamptz not null default now(),
  awaken_count integer not null default 0 check (awaken_count >= 0),
  last_awakened_at timestamptz,
  last_accessed_at timestamptz not null default now(),
  shared_status text check (shared_status is null or shared_status in ('candidate', 'approved', 'rejected', 'revoked')),
  author text,
  source_type text not null,
  source_model text,
  source_ref text,
  source_metadata jsonb not null default '{}'::jsonb check (jsonb_typeof(source_metadata) = 'object'),
  source_memory_id bigint references public.memory_entries(id) on delete restrict,
  source_revision_id bigint,
  source_revision_hash text check (
    source_revision_hash is null or source_revision_hash ~ '^[0-9a-f]{64}$'
  ),
  original_table text,
  original_id text,
  original_created_at timestamptz,
  legacy_source text,
  revision_number integer not null default 1 check (revision_number >= 1),
  created_by_actor text not null,
  updated_by_actor text,
  revision_reason text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint memory_entries_space_actor_check check (
    (space_key = 'gpt' and created_by_actor in ('gpt', 'owner', 'curator', 'system'))
    or (space_key = 'claude' and created_by_actor in ('claude', 'owner', 'curator', 'system'))
    or (space_key = 'shared' and created_by_actor = 'curator')
    or (space_key = 'legacy_pending' and created_by_actor in ('owner', 'curator', 'system'))
  ),
  constraint memory_entries_shared_state_check check (
    (space_key = 'shared' and shared_status is not null)
    or (space_key <> 'shared' and shared_status is null)
  ),
  constraint memory_entries_shared_source_check check (
    (
      space_key = 'shared'
      and source_memory_id is not null
      and source_revision_id is not null
      and source_revision_hash is not null
    )
    or (
      space_key <> 'shared'
      and source_memory_id is null
      and source_revision_id is null
      and source_revision_hash is null
    )
  ),
  constraint memory_entries_legacy_source_check check (
    (
      space_key = 'legacy_pending'
      and original_table is not null
      and original_id is not null
      and original_created_at is not null
      and legacy_source is not null
    )
    or (
      space_key <> 'legacy_pending'
      and original_table is null
      and original_id is null
      and original_created_at is null
      and legacy_source is null
    )
  ),
  constraint memory_entries_actor_tags_check check (
    not (tags && array['gpt', 'GPT', 'claude', 'Claude', 'cc', 'CC', 'codex', 'Codex']::text[])
  )
);

create index memory_entries_owner_space_created_idx
  on public.memory_entries (owner_id, space_key, created_at desc);
create index memory_entries_owner_type_created_idx
  on public.memory_entries (owner_id, memory_type, created_at desc);
create index memory_entries_shared_status_idx
  on public.memory_entries (owner_id, shared_status, created_at desc)
  where space_key = 'shared';
create index memory_entries_tags_gin_idx on public.memory_entries using gin (tags);
create index memory_entries_source_memory_idx on public.memory_entries (source_memory_id)
  where source_memory_id is not null;
create index memory_entries_source_revision_idx on public.memory_entries (source_revision_id)
  where source_revision_id is not null;

create table public.memory_revisions (
  id bigint generated always as identity primary key,
  owner_id uuid not null references auth.users(id) on delete restrict,
  memory_id bigint not null references public.memory_entries(id) on delete restrict,
  revision_number integer not null check (revision_number >= 1),
  title text,
  content text not null,
  author text,
  memory_type text not null references public.memory_type_catalog(memory_type) on update restrict,
  tags text[] not null,
  emotion jsonb not null check (jsonb_typeof(emotion) = 'object'),
  importance smallint not null check (importance between 1 and 5),
  retention text,
  lifecycle_status text not null,
  editor_actor text not null,
  revision_reason text not null,
  created_at timestamptz not null default now(),
  unique (memory_id, revision_number),
  unique (id, memory_id, owner_id)
);

alter table public.memory_entries
  add constraint memory_entries_source_revision_fk
  foreign key (source_revision_id, source_memory_id, owner_id)
  references public.memory_revisions (id, memory_id, owner_id)
  on delete restrict;

create index memory_revisions_owner_memory_idx
  on public.memory_revisions (owner_id, memory_id, revision_number desc);

create table public.memory_provenance (
  id bigint generated always as identity primary key,
  owner_id uuid not null references auth.users(id) on delete restrict,
  memory_id bigint not null references public.memory_entries(id) on delete restrict,
  parent_memory_id bigint references public.memory_entries(id) on delete restrict,
  parent_revision_id bigint,
  event_type text not null check (
    event_type in (
      'created', 'legacy_staged', 'curated', 'dream_candidate', 'merged',
      'revised', 'shared_candidate', 'shared_approved', 'shared_rejected', 'shared_revoked'
    )
  ),
  actor text not null,
  source_type text,
  source_model text,
  source_ref text,
  original_table text,
  original_id text,
  original_created_at timestamptz,
  legacy_source text,
  reason text,
  details jsonb not null default '{}'::jsonb check (jsonb_typeof(details) = 'object'),
  created_at timestamptz not null default now(),
  constraint memory_provenance_parent_revision_fk
    foreign key (parent_revision_id, parent_memory_id, owner_id)
    references public.memory_revisions (id, memory_id, owner_id)
    on delete restrict
);

create index memory_provenance_owner_memory_idx
  on public.memory_provenance (owner_id, memory_id, created_at);
create index memory_provenance_parent_idx
  on public.memory_provenance (parent_memory_id)
  where parent_memory_id is not null;
create index memory_provenance_parent_revision_idx
  on public.memory_provenance (parent_revision_id, parent_memory_id, owner_id)
  where parent_revision_id is not null;

create table public.memory_shared_transitions (
  id bigint generated always as identity primary key,
  owner_id uuid not null references auth.users(id) on delete restrict,
  memory_id bigint not null references public.memory_entries(id) on delete restrict,
  from_status text check (from_status is null or from_status in ('candidate', 'approved', 'rejected', 'revoked')),
  to_status text not null check (to_status in ('candidate', 'approved', 'rejected', 'revoked')),
  actor text not null check (actor in ('owner', 'curator')),
  reason text not null check (length(btrim(reason)) > 0),
  source_memory_id bigint not null references public.memory_entries(id) on delete restrict,
  source_revision_id bigint not null,
  source_revision_hash text not null check (source_revision_hash ~ '^[0-9a-f]{64}$'),
  created_at timestamptz not null default now(),
  constraint memory_shared_transitions_source_revision_fk
    foreign key (source_revision_id, source_memory_id, owner_id)
    references public.memory_revisions (id, memory_id, owner_id)
    on delete restrict
);

create index memory_shared_transitions_owner_memory_idx
  on public.memory_shared_transitions (owner_id, memory_id, created_at);
create index memory_shared_transitions_source_revision_idx
  on public.memory_shared_transitions (source_revision_id, source_memory_id, owner_id);

create table public.memory_audit_log (
  id bigint generated always as identity primary key,
  owner_id uuid not null references auth.users(id) on delete restrict,
  actor text not null,
  action text not null,
  memory_id bigint,
  space_key text references public.memory_space_catalog(space_key) on update restrict,
  result text not null check (result in ('allowed', 'denied', 'error')),
  reason_code text,
  request_id uuid,
  result_count integer check (result_count is null or result_count >= 0),
  result_spaces text[] not null default '{}'::text[],
  metadata jsonb not null default '{}'::jsonb check (jsonb_typeof(metadata) = 'object'),
  occurred_at timestamptz not null default now(),
  constraint memory_audit_result_reason_check check (
    (result = 'allowed') or reason_code is not null
  )
);

create index memory_audit_owner_time_idx
  on public.memory_audit_log (owner_id, occurred_at desc);
create index memory_audit_denied_idx
  on public.memory_audit_log (owner_id, actor, occurred_at desc)
  where result <> 'allowed';

create table public.memory_mutation_idempotency (
  id bigint generated always as identity primary key,
  owner_id uuid not null references auth.users(id) on delete restrict,
  actor text not null check (actor in ('gpt', 'claude', 'curator', 'owner', 'system')),
  operation text not null check (length(btrim(operation)) between 1 and 100),
  request_id uuid not null,
  -- Insert-only transient input. A BEFORE trigger hashes canonical jsonb and
  -- clears this field, so request bodies are never retained in this table.
  request_material jsonb,
  request_hash text not null check (request_hash ~ '^[0-9a-f]{64}$'),
  status text not null default 'started' check (status in ('started', 'completed')),
  resource_id bigint references public.memory_entries(id) on delete restrict,
  response_metadata jsonb not null default '{}'::jsonb
    check (jsonb_typeof(response_metadata) = 'object'),
  created_at timestamptz not null default now(),
  completed_at timestamptz,
  constraint memory_idempotency_material_not_stored check (request_material is null),
  constraint memory_idempotency_completion_check check (
    (status = 'started' and completed_at is null)
    or (status = 'completed' and completed_at is not null)
  ),
  unique (owner_id, actor, operation, request_id)
);

create index memory_idempotency_owner_created_idx
  on public.memory_mutation_idempotency (owner_id, created_at desc);
create index memory_idempotency_resource_idx
  on public.memory_mutation_idempotency (resource_id)
  where resource_id is not null;

create table public.memory_ingest_candidates (
  id bigint generated always as identity primary key,
  owner_id uuid not null references auth.users(id) on delete restrict,
  proposed_space_key text not null references public.memory_space_catalog(space_key) on update restrict,
  proposed_memory_type text not null references public.memory_type_catalog(memory_type) on update restrict,
  proposed_tags text[] not null default '{}'::text[],
  content text not null check (length(btrim(content)) between 1 and 50000),
  emotion jsonb not null default '{}'::jsonb check (jsonb_typeof(emotion) = 'object'),
  importance smallint not null default 1 check (importance between 1 and 5),
  source_window_id text,
  source_model text,
  source_type text not null,
  source_ref text,
  source_metadata jsonb not null default '{}'::jsonb check (jsonb_typeof(source_metadata) = 'object'),
  dream_run_ref text,
  status text not null default 'pending'
    check (status in ('pending', 'approved', 'rejected', 'merged')),
  reviewer_actor text,
  review_reason text,
  reviewed_at timestamptz,
  converted_memory_id bigint references public.memory_entries(id) on delete restrict,
  duplicate_of_candidate_id bigint references public.memory_ingest_candidates(id) on delete restrict,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint memory_ingest_candidate_review_check check (
    (status = 'pending' and reviewer_actor is null and reviewed_at is null)
    or (status <> 'pending' and reviewer_actor is not null and review_reason is not null and reviewed_at is not null)
  ),
  constraint memory_ingest_candidate_merge_check check (
    (status = 'merged' and converted_memory_id is not null)
    or (status <> 'merged' and converted_memory_id is null)
  )
);

create index memory_ingest_candidates_owner_status_idx
  on public.memory_ingest_candidates (owner_id, status, created_at);
create index memory_ingest_candidates_duplicate_idx
  on public.memory_ingest_candidates (duplicate_of_candidate_id)
  where duplicate_of_candidate_id is not null;

create or replace function public.memory_hash_jsonb(p_value jsonb)
returns text
language sql
immutable
parallel safe
set search_path = ''
as $$
  select pg_catalog.encode(
    extensions.digest(
      pg_catalog.convert_to(coalesce(p_value, 'null'::jsonb)::text, 'UTF8'),
      'sha256'
    ),
    'hex'
  );
$$;

create or replace function public.memory_compute_revision_hash(p_revision_id bigint)
returns text
language sql
stable
set search_path = ''
as $$
  select public.memory_hash_jsonb(
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
  )
  from public.memory_revisions r
  where r.id = p_revision_id;
$$;

create or replace function public.memory_prepare_idempotency()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if tg_op = 'INSERT' then
    if new.request_material is null or pg_catalog.jsonb_typeof(new.request_material) <> 'object' then
      raise exception 'Canonical request material is required for database-computed idempotency hash'
        using errcode = '23514';
    end if;
    new.request_hash := public.memory_hash_jsonb(new.request_material);
    new.request_material := null;
    new.status := 'started';
    new.resource_id := null;
    new.response_metadata := '{}'::jsonb;
    new.completed_at := null;
    return new;
  end if;

  if new.owner_id is distinct from old.owner_id
    or new.actor is distinct from old.actor
    or new.operation is distinct from old.operation
    or new.request_id is distinct from old.request_id
    or new.request_hash is distinct from old.request_hash
    or new.created_at is distinct from old.created_at
    or new.request_material is not null
  then
    raise exception 'Idempotency identity and database-computed hash are immutable'
      using errcode = '55000';
  end if;

  if old.status <> 'started' or new.status <> 'completed' or new.completed_at is null then
    raise exception 'Idempotency rows only allow started -> completed'
      using errcode = '23514';
  end if;
  return new;
end;
$$;

create trigger memory_idempotency_prepare
  before insert or update on public.memory_mutation_idempotency
  for each row execute function public.memory_prepare_idempotency();

create or replace function public.memory_claim_idempotency(
  p_owner_id uuid,
  p_actor text,
  p_operation text,
  p_request_id uuid,
  p_request_material jsonb
)
returns setof public.memory_mutation_idempotency
language plpgsql
set search_path = ''
as $$
declare
  expected_hash text;
  claimed public.memory_mutation_idempotency%rowtype;
begin
  if p_request_material is null or pg_catalog.jsonb_typeof(p_request_material) <> 'object' then
    raise exception 'Canonical request material is required' using errcode = '23514';
  end if;
  expected_hash := public.memory_hash_jsonb(p_request_material);

  insert into public.memory_mutation_idempotency (
    owner_id, actor, operation, request_id, request_material, request_hash
  ) values (
    p_owner_id, p_actor, p_operation, p_request_id, p_request_material, expected_hash
  )
  on conflict (owner_id, actor, operation, request_id) do nothing;

  select * into strict claimed
  from public.memory_mutation_idempotency
  where owner_id = p_owner_id
    and actor = p_actor
    and operation = p_operation
    and request_id = p_request_id;

  if claimed.request_hash <> expected_hash then
    raise exception 'Idempotency key was reused with different request material'
      using errcode = '23505';
  end if;

  return next claimed;
end;
$$;

-- Trusted administrative doors. They are deliberately separate from the
-- GPT/Claude read/write RPCs and never accept actor, space, status owner, or
-- client-computed hash fields from an AI tool call.
create or replace function public.memory_curator_create_shared_candidate(
  p_owner_id uuid,
  p_source_memory_id bigint,
  p_source_revision_id bigint,
  p_reason text
)
returns public.memory_entries
language plpgsql
security definer
set search_path = ''
as $$
declare
  previous_authority text;
  candidate public.memory_entries%rowtype;
begin
  if p_owner_id is null
    or nullif(pg_catalog.btrim(p_reason), '') is null
  then
    raise exception 'Curator candidate requires owner and reason'
      using errcode = '23514';
  end if;

  previous_authority := pg_catalog.current_setting('lovehouse.memory_authority', true);
  perform pg_catalog.set_config('lovehouse.memory_authority', 'curator', true);
  begin
    insert into public.memory_entries (
      owner_id, space_key, source_type, source_model, source_ref,
      source_metadata, source_memory_id, source_revision_id,
      shared_status, created_by_actor
    ) values (
      p_owner_id, 'shared', 'curation', 'curator',
      pg_catalog.format('memory:%s:revision:%s', p_source_memory_id, p_source_revision_id),
      pg_catalog.jsonb_build_object('candidate_reason', pg_catalog.btrim(p_reason)),
      p_source_memory_id, p_source_revision_id, 'candidate', 'curator'
    ) returning * into candidate;
  exception when others then
    perform pg_catalog.set_config(
      'lovehouse.memory_authority', pg_catalog.coalesce(previous_authority, ''), true
    );
    raise;
  end;
  perform pg_catalog.set_config(
    'lovehouse.memory_authority', pg_catalog.coalesce(previous_authority, ''), true
  );
  return candidate;
end;
$$;

create or replace function public.memory_owner_transition_shared(
  p_memory_id bigint,
  p_to_status text,
  p_reason text
)
returns public.memory_entries
language plpgsql
security definer
set search_path = ''
as $$
declare
  caller_owner uuid;
  previous_authority text;
  transitioned public.memory_entries%rowtype;
begin
  caller_owner := auth.uid();
  if caller_owner is null then
    raise exception 'Authenticated Owner is required' using errcode = '42501';
  end if;
  if p_to_status is null
    or p_to_status not in ('approved', 'rejected', 'revoked')
    or nullif(pg_catalog.btrim(p_reason), '') is null
  then
    raise exception 'Owner transition requires an allowed state and reason'
      using errcode = '23514';
  end if;

  previous_authority := pg_catalog.current_setting('lovehouse.memory_authority', true);
  perform pg_catalog.set_config('lovehouse.memory_authority', 'owner', true);
  begin
    update public.memory_entries
      set shared_status = p_to_status,
          updated_by_actor = 'owner',
          revision_reason = pg_catalog.btrim(p_reason)
      where id = p_memory_id
        and owner_id = caller_owner
        and space_key = 'shared'
      returning * into transitioned;
    if not found then
      raise exception 'Owner cannot access this Shared memory' using errcode = '42501';
    end if;
  exception when others then
    perform pg_catalog.set_config(
      'lovehouse.memory_authority', pg_catalog.coalesce(previous_authority, ''), true
    );
    raise;
  end;
  perform pg_catalog.set_config(
    'lovehouse.memory_authority', pg_catalog.coalesce(previous_authority, ''), true
  );
  return transitioned;
end;
$$;

create or replace function public.memory_internal_authority_is(p_expected text)
returns boolean
language sql
stable
set search_path = ''
as $$
  select pg_catalog.coalesce(
    p_expected in ('curator', 'owner')
    and pg_catalog.current_setting('lovehouse.memory_authority', true) = p_expected
    and current_user = pg_catalog.pg_get_userbyid(c.relowner),
    false
  )
  from pg_catalog.pg_class c
  where c.oid = 'public.memory_entries'::pg_catalog.regclass;
$$;

-- Fixed-actor database read doors. The Bridge calls these RPCs instead of
-- querying memory_entries directly, so a forgotten application WHERE clause
-- cannot expose the other actor, unapproved Shared, or Legacy Pending rows.
create or replace function public.memory_get_gpt(p_owner_id uuid, p_memory_id bigint)
returns setof public.memory_entries
language sql
stable
set search_path = ''
as $$
  select e.* from public.memory_entries e
  where e.owner_id = p_owner_id and e.id = p_memory_id
    and (e.space_key = 'gpt' or (e.space_key = 'shared' and e.shared_status = 'approved'))
  limit 1;
$$;

create or replace function public.memory_get_claude(p_owner_id uuid, p_memory_id bigint)
returns setof public.memory_entries
language sql
stable
set search_path = ''
as $$
  select e.* from public.memory_entries e
  where e.owner_id = p_owner_id and e.id = p_memory_id
    and (e.space_key = 'claude' or (e.space_key = 'shared' and e.shared_status = 'approved'))
  limit 1;
$$;

create or replace function public.memory_list_gpt(
  p_owner_id uuid,
  p_limit integer default 100,
  p_memory_type text default null,
  p_tags text[] default '{}',
  p_retention text default null
)
returns setof public.memory_entries
language sql
stable
set search_path = ''
as $$
  select e.* from public.memory_entries e
  where e.owner_id = p_owner_id
    and (e.space_key = 'gpt' or (e.space_key = 'shared' and e.shared_status = 'approved'))
    and (p_memory_type is null or e.memory_type = p_memory_type)
    and (coalesce(p_tags, '{}'::text[]) <@ e.tags)
    and (p_retention is null or e.retention = p_retention)
  order by e.created_at desc
  limit least(greatest(coalesce(p_limit, 100), 1), 200);
$$;

create or replace function public.memory_list_claude(
  p_owner_id uuid,
  p_limit integer default 100,
  p_memory_type text default null,
  p_tags text[] default '{}',
  p_retention text default null
)
returns setof public.memory_entries
language sql
stable
set search_path = ''
as $$
  select e.* from public.memory_entries e
  where e.owner_id = p_owner_id
    and (e.space_key = 'claude' or (e.space_key = 'shared' and e.shared_status = 'approved'))
    and (p_memory_type is null or e.memory_type = p_memory_type)
    and (coalesce(p_tags, '{}'::text[]) <@ e.tags)
    and (p_retention is null or e.retention = p_retention)
  order by e.created_at desc
  limit least(greatest(coalesce(p_limit, 100), 1), 200);
$$;

create or replace function public.memory_recall_gpt(
  p_owner_id uuid,
  p_query text,
  p_limit integer default 20,
  p_tags text[] default '{}'
)
returns setof public.memory_entries
language sql
stable
set search_path = ''
as $$
  select e.* from public.memory_entries e
  where e.owner_id = p_owner_id
    and (e.space_key = 'gpt' or (e.space_key = 'shared' and e.shared_status = 'approved'))
    and nullif(btrim(p_query), '') is not null
    and e.content ilike ('%' || p_query || '%')
    and (coalesce(p_tags, '{}'::text[]) <@ e.tags)
  order by e.importance desc, e.created_at desc
  limit least(greatest(coalesce(p_limit, 20), 1), 50);
$$;

create or replace function public.memory_recall_claude(
  p_owner_id uuid,
  p_query text,
  p_limit integer default 20,
  p_tags text[] default '{}'
)
returns setof public.memory_entries
language sql
stable
set search_path = ''
as $$
  select e.* from public.memory_entries e
  where e.owner_id = p_owner_id
    and (e.space_key = 'claude' or (e.space_key = 'shared' and e.shared_status = 'approved'))
    and nullif(btrim(p_query), '') is not null
    and e.content ilike ('%' || p_query || '%')
    and (coalesce(p_tags, '{}'::text[]) <@ e.tags)
  order by e.importance desc, e.created_at desc
  limit least(greatest(coalesce(p_limit, 20), 1), 50);
$$;

create or replace function public.memory_reject_append_only_change()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  raise exception '% is append-only', tg_table_name using errcode = '55000';
end;
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
      select owner_id, space_key
        into source_owner, source_space
        from public.memory_entries
        where id = new.source_memory_id;
      if not found
        or source_owner is distinct from new.owner_id
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

      -- The candidate is an immutable snapshot of the selected private
      -- revision. Client-provided body/hash values are never trusted.
      new.title := source_revision.title;
      new.content := source_revision.content;
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
      new.memory_type, new.tags, new.title, new.content, new.emotion,
      new.importance, new.retention, new.lifecycle_status, new.decay_score,
      new.decay_updated_at, new.awaken_count, new.last_awakened_at,
      new.last_accessed_at, new.author
    ) is distinct from row(
      old.memory_type, old.tags, old.title, old.content, old.emotion,
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
    new.title,
    new.content,
    new.author,
    new.memory_type,
    new.tags,
    new.emotion,
    new.importance,
    new.retention,
    new.lifecycle_status
  ) is distinct from row(
    old.title,
    old.content,
    old.author,
    old.memory_type,
    old.tags,
    old.emotion,
    old.importance,
    old.retention,
    old.lifecycle_status
  );

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

create or replace function public.memory_capture_entry_history()
returns trigger
language plpgsql
security definer
set search_path = ''
as $$
declare
  provenance_event text;
begin
  if tg_op = 'INSERT' then
    insert into public.memory_revisions (
      owner_id, memory_id, revision_number, title, content, author, memory_type, tags,
      emotion, importance, retention, lifecycle_status, editor_actor,
      revision_reason, created_at
    ) values (
      new.owner_id, new.id, 1, new.title, new.content, new.author, new.memory_type, new.tags,
      new.emotion, new.importance, new.retention, new.lifecycle_status,
      new.created_by_actor, 'initial_create', new.created_at
    );

    provenance_event := case
      when new.space_key = 'legacy_pending' then 'legacy_staged'
      when new.space_key = 'shared' then 'shared_candidate'
      else 'created'
    end;

    insert into public.memory_provenance (
      owner_id, memory_id, parent_memory_id, parent_revision_id,
      event_type, actor, source_type,
      source_model, source_ref, original_table, original_id,
      original_created_at, legacy_source, reason, details, created_at
    ) values (
      new.owner_id, new.id, new.source_memory_id, new.source_revision_id, provenance_event,
      new.created_by_actor, new.source_type, new.source_model, new.source_ref,
      new.original_table, new.original_id, new.original_created_at,
      new.legacy_source, 'initial_create',
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
      owner_id, memory_id, revision_number, title, content, author, memory_type, tags,
      emotion, importance, retention, lifecycle_status, editor_actor,
      revision_reason, created_at
    ) values (
      new.owner_id, new.id, new.revision_number, new.title, new.content, new.author,
      new.memory_type, new.tags, new.emotion, new.importance, new.retention,
      new.lifecycle_status, new.updated_by_actor, new.revision_reason, new.updated_at
    );

    insert into public.memory_provenance (
      owner_id, memory_id, parent_memory_id, parent_revision_id,
      event_type, actor, reason, details, created_at
    ) values (
      new.owner_id, new.id, new.source_memory_id, new.source_revision_id, 'revised',
      new.updated_by_actor, new.revision_reason,
      jsonb_build_object('revision_number', new.revision_number), new.updated_at
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
      new.owner_id,
      new.id,
      new.source_memory_id,
      new.source_revision_id,
      case new.shared_status
        when 'candidate' then 'shared_candidate'
        when 'approved' then 'shared_approved'
        when 'rejected' then 'shared_rejected'
        when 'revoked' then 'shared_revoked'
      end,
      new.updated_by_actor,
      new.revision_reason,
      jsonb_build_object(
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
      new.title, new.content, new.author, new.memory_type, new.tags,
      new.emotion, new.importance, new.retention, new.lifecycle_status
    ) is distinct from row(
      entry.title, entry.content, entry.author, entry.memory_type, entry.tags,
      entry.emotion, entry.importance, entry.retention, entry.lifecycle_status
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

create trigger memory_entries_prepare_write
  before insert or update on public.memory_entries
  for each row execute function public.memory_prepare_entry_write();

create trigger memory_entries_capture_history
  after insert or update on public.memory_entries
  for each row execute function public.memory_capture_entry_history();

create trigger memory_revisions_validate_insert
  before insert on public.memory_revisions
  for each row execute function public.memory_validate_revision_insert();

create trigger memory_revisions_append_only
  before update or delete on public.memory_revisions
  for each row execute function public.memory_reject_append_only_change();
create trigger memory_provenance_append_only
  before update or delete on public.memory_provenance
  for each row execute function public.memory_reject_append_only_change();
create trigger memory_shared_transitions_append_only
  before update or delete on public.memory_shared_transitions
  for each row execute function public.memory_reject_append_only_change();
create trigger memory_audit_log_append_only
  before update or delete on public.memory_audit_log
  for each row execute function public.memory_reject_append_only_change();

alter table public.memory_space_catalog enable row level security;
alter table public.memory_type_catalog enable row level security;
alter table public.memory_entries enable row level security;
alter table public.memory_revisions enable row level security;
alter table public.memory_provenance enable row level security;
alter table public.memory_shared_transitions enable row level security;
alter table public.memory_audit_log enable row level security;
alter table public.memory_mutation_idempotency enable row level security;
alter table public.memory_ingest_candidates enable row level security;

create policy memory_space_catalog_authenticated_read
  on public.memory_space_catalog for select to authenticated using (true);
create policy memory_type_catalog_authenticated_read
  on public.memory_type_catalog for select to authenticated using (true);
create policy memory_entries_owner_read
  on public.memory_entries for select to authenticated
  using ((select auth.uid()) = owner_id);
create policy memory_revisions_owner_read
  on public.memory_revisions for select to authenticated
  using ((select auth.uid()) = owner_id);
create policy memory_provenance_owner_read
  on public.memory_provenance for select to authenticated
  using ((select auth.uid()) = owner_id);
create policy memory_shared_transitions_owner_read
  on public.memory_shared_transitions for select to authenticated
  using ((select auth.uid()) = owner_id);
create policy memory_audit_owner_read
  on public.memory_audit_log for select to authenticated
  using ((select auth.uid()) = owner_id);
create policy memory_ingest_candidates_owner_read
  on public.memory_ingest_candidates for select to authenticated
  using ((select auth.uid()) = owner_id);

revoke all on table public.memory_space_catalog from anon, authenticated;
revoke all on table public.memory_type_catalog from anon, authenticated;
revoke all on table public.memory_entries from anon, authenticated;
revoke all on table public.memory_revisions from anon, authenticated;
revoke all on table public.memory_provenance from anon, authenticated;
revoke all on table public.memory_shared_transitions from anon, authenticated;
revoke all on table public.memory_audit_log from anon, authenticated;
revoke all on table public.memory_mutation_idempotency from anon, authenticated;
revoke all on table public.memory_ingest_candidates from anon, authenticated;

grant select on table public.memory_space_catalog to authenticated, service_role;
grant select on table public.memory_type_catalog to authenticated, service_role;
grant select on table public.memory_entries to authenticated;
grant select on table public.memory_revisions to authenticated;
grant select on table public.memory_provenance to authenticated;
grant select on table public.memory_shared_transitions to authenticated;
grant select on table public.memory_audit_log to authenticated;
grant select on table public.memory_ingest_candidates to authenticated;

grant select, insert, update, delete on table public.memory_entries to service_role;
revoke all on table public.memory_revisions from service_role;
grant select on table public.memory_revisions to service_role;
grant select, insert on table public.memory_provenance to service_role;
grant select, insert on table public.memory_shared_transitions to service_role;
grant select, insert on table public.memory_audit_log to service_role;
grant select, insert, update on table public.memory_mutation_idempotency to service_role;
grant select, insert, update, delete on table public.memory_ingest_candidates to service_role;
grant usage, select on sequence public.memory_entries_id_seq to service_role;
revoke all on sequence public.memory_revisions_id_seq from anon, authenticated, service_role;
grant usage, select on sequence public.memory_provenance_id_seq to service_role;
grant usage, select on sequence public.memory_shared_transitions_id_seq to service_role;
grant usage, select on sequence public.memory_audit_log_id_seq to service_role;
grant usage, select on sequence public.memory_mutation_idempotency_id_seq to service_role;
grant usage, select on sequence public.memory_ingest_candidates_id_seq to service_role;

revoke execute on function public.memory_reject_append_only_change() from public, anon, authenticated;
revoke execute on function public.memory_prepare_entry_write() from public, anon, authenticated;
revoke execute on function public.memory_capture_entry_history() from public, anon, authenticated;
revoke execute on function public.memory_validate_revision_insert() from public, anon, authenticated;
revoke execute on function public.memory_hash_jsonb(jsonb) from public, anon, authenticated;
revoke execute on function public.memory_compute_revision_hash(bigint) from public, anon, authenticated;
revoke execute on function public.memory_prepare_idempotency() from public, anon, authenticated;
revoke execute on function public.memory_claim_idempotency(uuid, text, text, uuid, jsonb) from public, anon, authenticated;
revoke execute on function public.memory_curator_create_shared_candidate(uuid, bigint, bigint, text) from public, anon, authenticated, service_role;
revoke execute on function public.memory_owner_transition_shared(bigint, text, text) from public, anon, authenticated, service_role;
revoke execute on function public.memory_internal_authority_is(text) from public, anon, authenticated, service_role;
revoke execute on function public.memory_get_gpt(uuid, bigint) from public, anon, authenticated;
revoke execute on function public.memory_get_claude(uuid, bigint) from public, anon, authenticated;
revoke execute on function public.memory_list_gpt(uuid, integer, text, text[], text) from public, anon, authenticated;
revoke execute on function public.memory_list_claude(uuid, integer, text, text[], text) from public, anon, authenticated;
revoke execute on function public.memory_recall_gpt(uuid, text, integer, text[]) from public, anon, authenticated;
revoke execute on function public.memory_recall_claude(uuid, text, integer, text[]) from public, anon, authenticated;

grant execute on function public.memory_get_gpt(uuid, bigint) to service_role;
grant execute on function public.memory_get_claude(uuid, bigint) to service_role;
grant execute on function public.memory_list_gpt(uuid, integer, text, text[], text) to service_role;
grant execute on function public.memory_list_claude(uuid, integer, text, text[], text) to service_role;
grant execute on function public.memory_recall_gpt(uuid, text, integer, text[]) to service_role;
grant execute on function public.memory_recall_claude(uuid, text, integer, text[]) to service_role;
grant execute on function public.memory_hash_jsonb(jsonb) to service_role;
grant execute on function public.memory_compute_revision_hash(bigint) to service_role;
grant execute on function public.memory_claim_idempotency(uuid, text, text, uuid, jsonb) to service_role;
grant execute on function public.memory_curator_create_shared_candidate(uuid, bigint, bigint, text) to service_role;
grant execute on function public.memory_owner_transition_shared(bigint, text, text) to authenticated;

comment on table public.memory_entries is
  'Canonical LoveHouse Memory System entries. No legacy body is migrated by this migration.';
comment on column public.memory_entries.space_key is
  'Ownership dimension: gpt, claude, shared, or legacy_pending.';
comment on column public.memory_entries.memory_type is
  'Content-kind dimension, independent from space and tags.';
comment on column public.memory_entries.tags is
  'Topic dimension. AI actor names are forbidden as tags.';
comment on table public.memory_revisions is
  'Append-only content snapshots created only by the trusted memory_entries history trigger.';
comment on table public.memory_provenance is
  'Append-only source and curation chain explaining how a memory reached its current form.';
comment on table public.memory_shared_transitions is
  'Append-only Shared candidate/approval/rejection/revocation state history.';
comment on table public.memory_audit_log is
  'Append-only access audit metadata. Memory content must never be stored here.';
comment on table public.memory_mutation_idempotency is
  'Database idempotency claims. Hashes are computed from transient canonical jsonb; request bodies are not retained.';
comment on table public.memory_ingest_candidates is
  'Dreaming/curation candidates. Candidates are not daily-recallable memories.';

-- Migration self-checks. Any failure rolls back the entire transaction.
do $$
begin
  if (select count(*) from public.memory_space_catalog) <> 4 then
    raise exception 'Memory System V2 requires exactly four phase-two spaces';
  end if;
  if exists (
    select 1 from public.memory_space_catalog
    where space_key = 'legacy_pending' and daily_recallable
  ) then
    raise exception 'Legacy Pending must never be daily recallable';
  end if;
  if exists (
    select 1 from public.memory_space_catalog
    where space_key = 'shared' and daily_recallable
  ) then
    raise exception 'Shared catalog must require explicit approved filtering';
  end if;
end;
$$;

commit;
