begin;

-- Unified Memory System Phase 4A: embedding lifecycle, versioned ranking and
-- hybrid recall. This migration does not read legacy tables, move legacy
-- content, enable the runtime flag, or add Phase 4B behavior.

create extension if not exists vector with schema extensions;

create table public.memory_ranking_profiles (
  profile_key text primary key check (profile_key ~ '^ranking_v[0-9]+$'),
  embedding_profile_key text not null check (length(btrim(embedding_profile_key)) between 1 and 100),
  embedding_dimensions integer not null check (embedding_dimensions between 1 and 2000),
  rrf_k integer not null check (rrf_k between 1 and 1000),
  keyword_weight double precision not null check (keyword_weight between 0 and 1),
  semantic_weight double precision not null check (semantic_weight between 0 and 1),
  importance_weight double precision not null check (importance_weight between 0 and 1),
  recency_weight double precision not null check (recency_weight between 0 and 1),
  decay_weight double precision not null check (decay_weight between 0 and 1),
  semantic_threshold double precision not null check (semantic_threshold between -1 and 1),
  candidate_multiplier integer not null check (candidate_multiplier between 1 and 10),
  created_at timestamptz not null default now(),
  constraint memory_ranking_profile_weight_sum check (
    abs(keyword_weight + semantic_weight + importance_weight + recency_weight + decay_weight - 1.0) < 0.000001
  )
);

insert into public.memory_ranking_profiles (
  profile_key, embedding_profile_key, embedding_dimensions, rrf_k,
  keyword_weight, semantic_weight, importance_weight, recency_weight,
  decay_weight, semantic_threshold, candidate_multiplier
) values (
  'ranking_v1', 'semantic-1536-v1', 1536, 60,
  0.31, 0.31, 0.18, 0.12, 0.08, 0.20, 4
);

create or replace function public.memory_behavior_reject_profile_change()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  raise exception 'Ranking profiles are immutable; insert a new version instead'
    using errcode = '55000';
end;
$$;

create trigger memory_ranking_profiles_immutable
  before update or delete on public.memory_ranking_profiles
  for each row execute function public.memory_behavior_reject_profile_change();

create table public.memory_embeddings (
  id bigint generated always as identity primary key,
  owner_id uuid not null references auth.users(id) on delete restrict,
  memory_id bigint not null references public.memory_entries(id) on delete restrict,
  revision_id bigint not null,
  revision_hash text not null check (revision_hash ~ '^[0-9a-f]{64}$'),
  embedding_profile_key text not null check (length(btrim(embedding_profile_key)) between 1 and 100),
  dimensions integer not null check (dimensions between 1 and 2000),
  input_hash text not null check (input_hash ~ '^[0-9a-f]{64}$'),
  embedding extensions.vector,
  status text not null default 'pending' check (status in ('pending', 'processing', 'ready', 'failed')),
  attempt_count integer not null default 0 check (attempt_count between 0 and 3),
  processing_actor text check (processing_actor is null or processing_actor in ('gpt', 'claude')),
  lease_expires_at timestamptz,
  next_attempt_at timestamptz not null default now(),
  error_code text,
  created_at timestamptz not null default now(),
  updated_at timestamptz not null default now(),
  constraint memory_embeddings_revision_fk
    foreign key (revision_id, memory_id, owner_id)
    references public.memory_revisions (id, memory_id, owner_id)
    on delete restrict,
  constraint memory_embeddings_unique_revision_profile
    unique (owner_id, memory_id, revision_id, embedding_profile_key),
  constraint memory_embeddings_lifecycle check (
    (status = 'pending' and embedding is null and processing_actor is null and lease_expires_at is null)
    or (status = 'processing' and embedding is null and processing_actor is not null and lease_expires_at is not null)
    or (status = 'ready' and embedding is not null and processing_actor is null and lease_expires_at is null and error_code is null)
    or (status = 'failed' and embedding is null and processing_actor is null and lease_expires_at is null and error_code is not null)
  )
);

create index memory_embeddings_revision_idx
  on public.memory_embeddings (revision_id, memory_id, owner_id);
create index memory_embeddings_work_queue_idx
  on public.memory_embeddings (status, next_attempt_at, created_at, id)
  where status in ('pending', 'failed', 'processing') and attempt_count < 3;
create index memory_embeddings_semantic_1536_hnsw_idx
  on public.memory_embeddings
  using hnsw ((embedding::extensions.vector(1536)) extensions.vector_cosine_ops)
  where status = 'ready' and embedding_profile_key = 'semantic-1536-v1' and dimensions = 1536;

create or replace function public.memory_behavior_embedding_input(
  p_title text,
  p_memory_type text,
  p_tags text[],
  p_content text
)
returns text
language sql
immutable
parallel safe
set search_path = ''
as $$
  select left(
    pg_catalog.concat_ws(
      E'\n',
      nullif(pg_catalog.btrim(coalesce(p_title, '')), ''),
      'type: ' || coalesce(p_memory_type, 'fact'),
      case when pg_catalog.cardinality(coalesce(p_tags, '{}'::text[])) > 0
        then 'tags: ' || pg_catalog.array_to_string(p_tags, ', ')
      end,
      pg_catalog.btrim(coalesce(p_content, ''))
    ),
    12000
  );
$$;

create or replace function public.memory_behavior_enqueue_revision()
returns trigger
language plpgsql
set search_path = ''
as $$
declare
  entry public.memory_entries%rowtype;
  embedding_input text;
  profile record;
begin
  select * into entry
  from public.memory_entries
  where id = new.memory_id and owner_id = new.owner_id;

  if not found or entry.revision_number <> new.revision_number then
    return new;
  end if;
  if entry.space_key not in ('gpt', 'claude') then
    return new;
  end if;

  -- A rapid revision may supersede queued work before a worker claims it.
  -- Ready historical vectors remain traceable, but stale unfinished work is
  -- terminal and can never consume provider calls.
  update public.memory_embeddings
  set status = 'failed', attempt_count = 3, embedding = null,
      processing_actor = null, lease_expires_at = null,
      error_code = 'EMBEDDING_REVISION_SUPERSEDED', updated_at = now()
  where owner_id = new.owner_id and memory_id = new.memory_id
    and revision_id <> new.id and status in ('pending', 'processing', 'failed');

  embedding_input := public.memory_behavior_embedding_input(new.title, new.memory_type, new.tags, new.content);
  for profile in
    select distinct embedding_profile_key, embedding_dimensions
    from public.memory_ranking_profiles
  loop
    insert into public.memory_embeddings (
      owner_id, memory_id, revision_id, revision_hash,
      embedding_profile_key, dimensions, input_hash
    ) values (
      new.owner_id, new.memory_id, new.id,
      public.memory_compute_revision_hash(new.id),
      profile.embedding_profile_key, profile.embedding_dimensions,
      pg_catalog.encode(extensions.digest(embedding_input, 'sha256'), 'hex')
    ) on conflict (owner_id, memory_id, revision_id, embedding_profile_key) do nothing;
  end loop;
  return new;
end;
$$;

create or replace function public.memory_behavior_enqueue_approved_shared()
returns trigger
language plpgsql
set search_path = ''
as $$
declare
  revision public.memory_revisions%rowtype;
  embedding_input text;
  profile record;
begin
  if new.space_key <> 'shared'
    or new.shared_status <> 'approved'
    or old.shared_status = 'approved' then
    return new;
  end if;

  select * into strict revision
  from public.memory_revisions
  where owner_id = new.owner_id
    and memory_id = new.id
    and revision_number = new.revision_number;
  embedding_input := public.memory_behavior_embedding_input(
    revision.title, revision.memory_type, revision.tags, revision.content
  );

  for profile in
    select distinct embedding_profile_key, embedding_dimensions
    from public.memory_ranking_profiles
  loop
    insert into public.memory_embeddings (
      owner_id, memory_id, revision_id, revision_hash,
      embedding_profile_key, dimensions, input_hash
    ) values (
      new.owner_id, new.id, revision.id,
      public.memory_compute_revision_hash(revision.id),
      profile.embedding_profile_key, profile.embedding_dimensions,
      pg_catalog.encode(extensions.digest(embedding_input, 'sha256'), 'hex')
    ) on conflict (owner_id, memory_id, revision_id, embedding_profile_key) do nothing;
  end loop;
  return new;
end;
$$;

create trigger memory_revisions_enqueue_embedding
  after insert on public.memory_revisions
  for each row execute function public.memory_behavior_enqueue_revision();
create trigger memory_entries_enqueue_approved_shared
  after update of shared_status on public.memory_entries
  for each row execute function public.memory_behavior_enqueue_approved_shared();

-- Existing canonical rows are queued from their exact current revision only.
-- Legacy Pending and unapproved Shared are deliberately absent.
insert into public.memory_embeddings (
  owner_id, memory_id, revision_id, revision_hash,
  embedding_profile_key, dimensions, input_hash
)
select
  e.owner_id, e.id, r.id, public.memory_compute_revision_hash(r.id),
  p.embedding_profile_key, p.embedding_dimensions,
  pg_catalog.encode(extensions.digest(
    public.memory_behavior_embedding_input(r.title, r.memory_type, r.tags, r.content),
    'sha256'
  ), 'hex')
from public.memory_entries e
join public.memory_revisions r
  on r.owner_id = e.owner_id and r.memory_id = e.id and r.revision_number = e.revision_number
cross join (
  select distinct embedding_profile_key, embedding_dimensions
  from public.memory_ranking_profiles
) p
where e.space_key in ('gpt', 'claude')
   or (e.space_key = 'shared' and e.shared_status = 'approved')
on conflict (owner_id, memory_id, revision_id, embedding_profile_key) do nothing;

create or replace function public.memory_behavior_internal_audit(
  p_owner_id uuid,
  p_actor text,
  p_action text,
  p_memory_id bigint,
  p_space_key text,
  p_result text,
  p_reason_code text,
  p_request_id uuid,
  p_result_count integer,
  p_result_spaces text[],
  p_metadata jsonb
)
returns void
language plpgsql
set search_path = ''
as $$
begin
  if p_actor not in ('gpt', 'claude') then
    raise exception 'Behavior actor must be fixed by a trusted wrapper' using errcode = '42501';
  end if;
  insert into public.memory_audit_log (
    owner_id, actor, action, memory_id, space_key, result, reason_code,
    request_id, result_count, result_spaces, metadata
  ) values (
    p_owner_id, p_actor, left(p_action, 100), p_memory_id, p_space_key,
    p_result, p_reason_code, p_request_id, p_result_count,
    coalesce(p_result_spaces, '{}'::text[]), coalesce(p_metadata, '{}'::jsonb)
  );
end;
$$;

create or replace function public.memory_behavior_internal_claim_embeddings(
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
  safe_limit integer := least(greatest(coalesce(p_limit, 4), 1), 8);
  items jsonb;
  item_count integer;
begin
  if p_actor not in ('gpt', 'claude') then
    raise exception 'Invalid fixed actor' using errcode = '42501';
  end if;

  with claimable as (
    select me.id
    from public.memory_embeddings me
    join public.memory_entries e
      on e.id = me.memory_id and e.owner_id = me.owner_id
    join public.memory_revisions r
      on r.id = me.revision_id and r.memory_id = me.memory_id and r.owner_id = me.owner_id
    where me.owner_id = p_owner_id
      and r.revision_number = e.revision_number
      and (e.space_key = p_actor or (e.space_key = 'shared' and e.shared_status = 'approved'))
      and me.attempt_count < 3
      and (
        (me.status in ('pending', 'failed') and me.next_attempt_at <= now())
        or (me.status = 'processing' and me.lease_expires_at <= now())
      )
    order by me.next_attempt_at, me.created_at, me.id
    for update of me skip locked
    limit safe_limit
  ), claimed as (
    update public.memory_embeddings me
    set status = 'processing', processing_actor = p_actor,
        lease_expires_at = now() + interval '2 minutes',
        attempt_count = me.attempt_count + 1, error_code = null, updated_at = now()
    from claimable c
    where me.id = c.id
    returning me.*
  )
  select coalesce(pg_catalog.jsonb_agg(pg_catalog.jsonb_build_object(
    'id', c.id,
    'memory_id', c.memory_id,
    'revision_id', c.revision_id,
    'revision_hash', c.revision_hash,
    'embedding_profile', c.embedding_profile_key,
    'dimensions', c.dimensions,
    'input_hash', c.input_hash,
    'input', public.memory_behavior_embedding_input(r.title, r.memory_type, r.tags, r.content)
  ) order by c.id), '[]'::jsonb)
  into items
  from claimed c
  join public.memory_revisions r on r.id = c.revision_id;

  item_count := pg_catalog.jsonb_array_length(items);
  perform public.memory_behavior_internal_audit(
    p_owner_id, p_actor, 'embedding_claim', null, null, 'allowed', null,
    p_request_id, item_count, '{}'::text[],
    pg_catalog.jsonb_build_object('batch_limit', safe_limit)
  );
  return pg_catalog.jsonb_build_object('ok', true, 'items', items);
end;
$$;

create or replace function public.memory_behavior_internal_complete_embedding(
  p_actor text,
  p_owner_id uuid,
  p_request_id uuid,
  p_embedding_id bigint,
  p_embedding real[]
)
returns jsonb
language plpgsql
set search_path = ''
as $$
declare
  target public.memory_embeddings%rowtype;
  actual_dimensions integer;
begin
  select * into target from public.memory_embeddings
  where id = p_embedding_id and owner_id = p_owner_id for update;
  if not found or target.status <> 'processing' or target.processing_actor <> p_actor
    or target.lease_expires_at <= now() then
    perform public.memory_behavior_internal_audit(
      p_owner_id, p_actor, 'embedding_complete', null, null, 'denied',
      'EMBEDDING_CLAIM_INVALID', p_request_id, 0, '{}'::text[], '{}'::jsonb
    );
    return pg_catalog.jsonb_build_object(
      'ok', false, 'error_code', 'EMBEDDING_CLAIM_INVALID',
      'message', 'Embedding claim is absent, expired, or belongs to another actor',
      'audit_persisted', true
    );
  end if;

  actual_dimensions := pg_catalog.cardinality(p_embedding);
  if actual_dimensions is distinct from target.dimensions
    or exists (
      select 1 from pg_catalog.unnest(p_embedding) as vector_values(component)
      where component::text in ('NaN', 'Infinity', '-Infinity')
    ) then
    perform public.memory_behavior_internal_audit(
      p_owner_id, p_actor, 'embedding_complete', target.memory_id, null, 'denied',
      'MEMORY_VECTOR_INVALID', p_request_id, 0, '{}'::text[],
      pg_catalog.jsonb_build_object('expected_dimensions', target.dimensions, 'actual_dimensions', actual_dimensions)
    );
    return pg_catalog.jsonb_build_object(
      'ok', false, 'error_code', 'MEMORY_VECTOR_INVALID',
      'message', 'Embedding dimensions or values are invalid', 'audit_persisted', true
    );
  end if;

  update public.memory_embeddings
  set embedding = p_embedding::extensions.vector,
      status = 'ready', processing_actor = null, lease_expires_at = null,
      error_code = null, updated_at = now()
  where id = target.id;
  perform public.memory_behavior_internal_audit(
    p_owner_id, p_actor, 'embedding_complete', target.memory_id, null,
    'allowed', null, p_request_id, 1, '{}'::text[],
    pg_catalog.jsonb_build_object('embedding_profile', target.embedding_profile_key)
  );
  return pg_catalog.jsonb_build_object('ok', true, 'embedding_id', target.id);
end;
$$;

create or replace function public.memory_behavior_internal_fail_embedding(
  p_actor text,
  p_owner_id uuid,
  p_request_id uuid,
  p_embedding_id bigint,
  p_reason_code text
)
returns jsonb
language plpgsql
set search_path = ''
as $$
declare
  target public.memory_embeddings%rowtype;
begin
  select * into target from public.memory_embeddings
  where id = p_embedding_id and owner_id = p_owner_id for update;
  if not found or target.status <> 'processing' or target.processing_actor <> p_actor then
    perform public.memory_behavior_internal_audit(
      p_owner_id, p_actor, 'embedding_fail', null, null, 'denied',
      'EMBEDDING_CLAIM_INVALID', p_request_id, 0, '{}'::text[], '{}'::jsonb
    );
    return pg_catalog.jsonb_build_object(
      'ok', false, 'error_code', 'EMBEDDING_CLAIM_INVALID',
      'message', 'Embedding claim is absent or belongs to another actor',
      'audit_persisted', true
    );
  end if;
  update public.memory_embeddings
  set status = 'failed', embedding = null, processing_actor = null,
      lease_expires_at = null, error_code = left(coalesce(nullif(btrim(p_reason_code), ''), 'EMBEDDING_FAILED'), 100),
      next_attempt_at = now() + pg_catalog.make_interval(
        mins => least(60, pg_catalog.power(2, greatest(target.attempt_count - 1, 0))::integer)
      ),
      updated_at = now()
  where id = target.id;
  perform public.memory_behavior_internal_audit(
    p_owner_id, p_actor, 'embedding_fail', target.memory_id, null,
    'error', 'EMBEDDING_PROVIDER_FAILED', p_request_id, 0, '{}'::text[],
    pg_catalog.jsonb_build_object('attempt_count', target.attempt_count)
  );
  return pg_catalog.jsonb_build_object('ok', true, 'embedding_id', target.id, 'retryable', target.attempt_count < 3);
end;
$$;

create or replace function public.memory_behavior_internal_recall(
  p_actor text,
  p_owner_id uuid,
  p_request_id uuid,
  p_query text,
  p_query_embedding real[],
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
  profile public.memory_ranking_profiles%rowtype;
  normalized_query text := left(pg_catalog.btrim(coalesce(p_query, '')), 500);
  safe_limit integer := least(greatest(coalesce(p_limit, 5), 1), 10);
  candidate_limit integer;
  all_items jsonb;
  all_ids bigint[];
  spaces text[];
  cursor_position integer := 0;
  items jsonb;
begin
  if p_actor not in ('gpt', 'claude') then
    raise exception 'Invalid fixed actor' using errcode = '42501';
  end if;
  select * into profile from public.memory_ranking_profiles where profile_key = p_ranking_profile;
  if not found or normalized_query = '' or pg_catalog.cardinality(p_query_embedding) is distinct from profile.embedding_dimensions
    or exists (
      select 1 from pg_catalog.unnest(p_query_embedding) as vector_values(component)
      where component::text in ('NaN', 'Infinity', '-Infinity')
    ) then
    perform public.memory_behavior_internal_audit(
      p_owner_id, p_actor, 'hybrid_recall', null, null, 'denied',
      'MEMORY_VECTOR_INVALID', p_request_id, 0, '{}'::text[],
      pg_catalog.jsonb_build_object('ranking_profile', p_ranking_profile)
    );
    return pg_catalog.jsonb_build_object(
      'ok', false, 'error_code', 'MEMORY_VECTOR_INVALID',
      'message', 'Query, ranking profile, or embedding is invalid',
      'audit_persisted', true
    );
  end if;

  candidate_limit := least(40, safe_limit * profile.candidate_multiplier);
  with eligible as materialized (
    select e.*, me.embedding
    from public.memory_entries e
    join public.memory_revisions r
      on r.owner_id = e.owner_id and r.memory_id = e.id and r.revision_number = e.revision_number
    left join public.memory_embeddings me
      on me.owner_id = e.owner_id and me.memory_id = e.id and me.revision_id = r.id
      and me.embedding_profile_key = profile.embedding_profile_key
      and me.dimensions = profile.embedding_dimensions and me.status = 'ready'
    where e.owner_id = p_owner_id
      and (e.space_key = p_actor or (e.space_key = 'shared' and e.shared_status = 'approved'))
      and coalesce(p_tags, '{}'::text[]) <@ e.tags
  ), keyword_candidates as (
    select id, row_number() over (
      order by
        case when pg_catalog.strpos(pg_catalog.lower(coalesce(title, '')), pg_catalog.lower(normalized_query)) > 0 then 0 else 1 end,
        importance desc, created_at desc, id desc
    )::integer keyword_rank
    from eligible
    where pg_catalog.strpos(pg_catalog.lower(content), pg_catalog.lower(normalized_query)) > 0
       or pg_catalog.strpos(pg_catalog.lower(coalesce(title, '')), pg_catalog.lower(normalized_query)) > 0
    limit candidate_limit
  ), semantic_candidates as (
    select id, similarity,
      row_number() over (order by similarity desc, created_at desc, id desc)::integer semantic_rank
    from (
      select id, created_at,
        1 - (embedding <=> p_query_embedding::extensions.vector) as similarity
      from eligible
      where embedding is not null
      order by embedding <=> p_query_embedding::extensions.vector
      limit candidate_limit
    ) nearest
    where similarity >= profile.semantic_threshold
  ), scored as (
    select e.*,
      (
        (profile.rrf_k + 1) * (
          case when k.keyword_rank is null then 0 else profile.keyword_weight / (profile.rrf_k + k.keyword_rank) end
          + case when s.semantic_rank is null then 0 else profile.semantic_weight / (profile.rrf_k + s.semantic_rank) end
        )
        + profile.importance_weight * ((e.importance - 1)::double precision / 4)
        + profile.recency_weight * (1 / (1 + greatest(0, extract(epoch from (now() - e.created_at)) / 86400) / 90))
        + profile.decay_weight * e.decay_score
      ) as final_score
    from eligible e
    left join keyword_candidates k on k.id = e.id
    left join semantic_candidates s on s.id = e.id
    where k.id is not null or s.id is not null
  ), ranked as (
    select * from scored
    order by final_score desc, created_at desc, id desc
    limit candidate_limit
  )
  select
    coalesce(pg_catalog.jsonb_agg(to_jsonb(r) - 'embedding' - 'final_score' order by r.final_score desc, r.created_at desc, r.id desc), '[]'::jsonb),
    coalesce(pg_catalog.array_agg(r.id order by r.final_score desc, r.created_at desc, r.id desc), '{}'::bigint[]),
    coalesce(pg_catalog.array_agg(distinct r.space_key), '{}'::text[])
  into all_items, all_ids, spaces
  from ranked r;

  if p_cursor_id is not null then
    cursor_position := coalesce(pg_catalog.array_position(all_ids, p_cursor_id), 0);
    if cursor_position = 0 then
      perform public.memory_behavior_internal_audit(
        p_owner_id, p_actor, 'hybrid_recall', p_cursor_id, null, 'denied',
        'INVALID_MEMORY_CURSOR', p_request_id, 0, '{}'::text[],
        pg_catalog.jsonb_build_object('ranking_profile', profile.profile_key)
      );
      return pg_catalog.jsonb_build_object(
        'ok', false, 'error_code', 'INVALID_MEMORY_CURSOR',
        'message', 'Cursor is outside this hybrid recall result', 'audit_persisted', true
      );
    end if;
  end if;

  select coalesce(pg_catalog.jsonb_agg(value order by ordinal), '[]'::jsonb)
  into items
  from pg_catalog.jsonb_array_elements(all_items) with ordinality result(value, ordinal)
  where ordinal > cursor_position and ordinal <= cursor_position + safe_limit;

  perform public.memory_behavior_internal_audit(
    p_owner_id, p_actor, 'hybrid_recall', null, null, 'allowed', null,
    p_request_id, pg_catalog.jsonb_array_length(items), spaces,
    pg_catalog.jsonb_build_object(
      'mode', 'hybrid', 'ranking_profile', profile.profile_key,
      'embedding_profile', profile.embedding_profile_key
    )
  );
  return pg_catalog.jsonb_build_object('ok', true, 'items', items);
end;
$$;

-- Fixed actor doors. The caller never supplies actor, owner authority, space,
-- revision or ranking weights through an AI-facing tool.
create or replace function public.memory_behavior_recall_gpt(
  p_owner_id uuid, p_request_id uuid, p_query text, p_query_embedding real[],
  p_ranking_profile text, p_limit integer, p_cursor_id bigint, p_tags text[]
)
returns jsonb language sql security definer set search_path = '' as $$
  select public.memory_behavior_internal_recall(
    'gpt', p_owner_id, p_request_id, p_query, p_query_embedding,
    p_ranking_profile, p_limit, p_cursor_id, p_tags
  )
$$;
create or replace function public.memory_behavior_recall_claude(
  p_owner_id uuid, p_request_id uuid, p_query text, p_query_embedding real[],
  p_ranking_profile text, p_limit integer, p_cursor_id bigint, p_tags text[]
)
returns jsonb language sql security definer set search_path = '' as $$
  select public.memory_behavior_internal_recall(
    'claude', p_owner_id, p_request_id, p_query, p_query_embedding,
    p_ranking_profile, p_limit, p_cursor_id, p_tags
  )
$$;
create or replace function public.memory_behavior_claim_embeddings_gpt(
  p_owner_id uuid, p_request_id uuid, p_limit integer
)
returns jsonb language sql security definer set search_path = '' as $$
  select public.memory_behavior_internal_claim_embeddings('gpt', p_owner_id, p_request_id, p_limit)
$$;
create or replace function public.memory_behavior_claim_embeddings_claude(
  p_owner_id uuid, p_request_id uuid, p_limit integer
)
returns jsonb language sql security definer set search_path = '' as $$
  select public.memory_behavior_internal_claim_embeddings('claude', p_owner_id, p_request_id, p_limit)
$$;
create or replace function public.memory_behavior_complete_embedding_gpt(
  p_owner_id uuid, p_request_id uuid, p_embedding_id bigint, p_embedding real[]
)
returns jsonb language sql security definer set search_path = '' as $$
  select public.memory_behavior_internal_complete_embedding(
    'gpt', p_owner_id, p_request_id, p_embedding_id, p_embedding
  )
$$;
create or replace function public.memory_behavior_complete_embedding_claude(
  p_owner_id uuid, p_request_id uuid, p_embedding_id bigint, p_embedding real[]
)
returns jsonb language sql security definer set search_path = '' as $$
  select public.memory_behavior_internal_complete_embedding(
    'claude', p_owner_id, p_request_id, p_embedding_id, p_embedding
  )
$$;
create or replace function public.memory_behavior_fail_embedding_gpt(
  p_owner_id uuid, p_request_id uuid, p_embedding_id bigint, p_reason_code text
)
returns jsonb language sql security definer set search_path = '' as $$
  select public.memory_behavior_internal_fail_embedding(
    'gpt', p_owner_id, p_request_id, p_embedding_id, p_reason_code
  )
$$;
create or replace function public.memory_behavior_fail_embedding_claude(
  p_owner_id uuid, p_request_id uuid, p_embedding_id bigint, p_reason_code text
)
returns jsonb language sql security definer set search_path = '' as $$
  select public.memory_behavior_internal_fail_embedding(
    'claude', p_owner_id, p_request_id, p_embedding_id, p_reason_code
  )
$$;

alter table public.memory_ranking_profiles enable row level security;
alter table public.memory_ranking_profiles force row level security;
alter table public.memory_embeddings enable row level security;
alter table public.memory_embeddings force row level security;

revoke all on table public.memory_ranking_profiles from public, anon, authenticated, service_role;
revoke all on table public.memory_embeddings from public, anon, authenticated, service_role;
revoke all on sequence public.memory_embeddings_id_seq from public, anon, authenticated, service_role;

revoke execute on function public.memory_behavior_reject_profile_change() from public, anon, authenticated, service_role;
revoke execute on function public.memory_behavior_embedding_input(text, text, text[], text) from public, anon, authenticated, service_role;
revoke execute on function public.memory_behavior_enqueue_revision() from public, anon, authenticated, service_role;
revoke execute on function public.memory_behavior_enqueue_approved_shared() from public, anon, authenticated, service_role;
revoke execute on function public.memory_behavior_internal_audit(uuid, text, text, bigint, text, text, text, uuid, integer, text[], jsonb) from public, anon, authenticated, service_role;
revoke execute on function public.memory_behavior_internal_claim_embeddings(text, uuid, uuid, integer) from public, anon, authenticated, service_role;
revoke execute on function public.memory_behavior_internal_complete_embedding(text, uuid, uuid, bigint, real[]) from public, anon, authenticated, service_role;
revoke execute on function public.memory_behavior_internal_fail_embedding(text, uuid, uuid, bigint, text) from public, anon, authenticated, service_role;
revoke execute on function public.memory_behavior_internal_recall(text, uuid, uuid, text, real[], text, integer, bigint, text[]) from public, anon, authenticated, service_role;

revoke execute on function public.memory_behavior_recall_gpt(uuid, uuid, text, real[], text, integer, bigint, text[]) from public, anon, authenticated, service_role;
revoke execute on function public.memory_behavior_recall_claude(uuid, uuid, text, real[], text, integer, bigint, text[]) from public, anon, authenticated, service_role;
revoke execute on function public.memory_behavior_claim_embeddings_gpt(uuid, uuid, integer) from public, anon, authenticated, service_role;
revoke execute on function public.memory_behavior_claim_embeddings_claude(uuid, uuid, integer) from public, anon, authenticated, service_role;
revoke execute on function public.memory_behavior_complete_embedding_gpt(uuid, uuid, bigint, real[]) from public, anon, authenticated, service_role;
revoke execute on function public.memory_behavior_complete_embedding_claude(uuid, uuid, bigint, real[]) from public, anon, authenticated, service_role;
revoke execute on function public.memory_behavior_fail_embedding_gpt(uuid, uuid, bigint, text) from public, anon, authenticated, service_role;
revoke execute on function public.memory_behavior_fail_embedding_claude(uuid, uuid, bigint, text) from public, anon, authenticated, service_role;

grant execute on function public.memory_behavior_recall_gpt(uuid, uuid, text, real[], text, integer, bigint, text[]) to service_role;
grant execute on function public.memory_behavior_recall_claude(uuid, uuid, text, real[], text, integer, bigint, text[]) to service_role;
grant execute on function public.memory_behavior_claim_embeddings_gpt(uuid, uuid, integer) to service_role;
grant execute on function public.memory_behavior_claim_embeddings_claude(uuid, uuid, integer) to service_role;
grant execute on function public.memory_behavior_complete_embedding_gpt(uuid, uuid, bigint, real[]) to service_role;
grant execute on function public.memory_behavior_complete_embedding_claude(uuid, uuid, bigint, real[]) to service_role;
grant execute on function public.memory_behavior_fail_embedding_gpt(uuid, uuid, bigint, text) to service_role;
grant execute on function public.memory_behavior_fail_embedding_claude(uuid, uuid, bigint, text) to service_role;

comment on table public.memory_ranking_profiles is
  'Immutable, versioned experimental ranking parameters. New behavior requires a new profile row.';
comment on table public.memory_embeddings is
  'Derived vectors for exact canonical revisions. Never a second memory store.';
comment on function public.memory_behavior_internal_recall(text, uuid, uuid, text, real[], text, integer, bigint, text[]) is
  'SQL-level hybrid recall over actor private plus approved Shared only; Legacy is absent from the eligible relation.';

commit;
