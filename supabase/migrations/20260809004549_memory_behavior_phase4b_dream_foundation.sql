begin;

-- Unified Memory System Phase 4B: Anchor records, a bounded Dream queue and
-- candidate-only Curator outputs. This migration never reads legacy content,
-- never mutates an existing memory/revision, and does not expose new MCP tools.

create table public.memory_anchor_records (
  id bigint generated always as identity primary key,
  owner_id uuid not null references auth.users(id) on delete restrict,
  actor text not null check (actor in ('gpt', 'claude')),
  memory_id bigint not null references public.memory_entries(id) on delete restrict,
  pinned_revision_id bigint not null,
  pinned_revision_hash text not null check (pinned_revision_hash ~ '^[0-9a-f]{64}$'),
  reason text not null check (length(btrim(reason)) between 1 and 1000),
  pinned_at timestamptz not null default now(),
  released_at timestamptz,
  released_reason text,
  constraint memory_anchor_revision_fk
    foreign key (pinned_revision_id, memory_id, owner_id)
    references public.memory_revisions (id, memory_id, owner_id)
    on delete restrict,
  constraint memory_anchor_release_check check (
    (released_at is null and released_reason is null)
    or (released_at is not null and length(btrim(released_reason)) between 1 and 1000)
  )
);

create unique index memory_anchor_active_unique_idx
  on public.memory_anchor_records (owner_id, actor, memory_id)
  where released_at is null;
create index memory_anchor_owner_actor_idx
  on public.memory_anchor_records (owner_id, actor, pinned_at desc);

create table public.memory_dream_jobs (
  id bigint generated always as identity primary key,
  owner_id uuid not null references auth.users(id) on delete restrict,
  actor text not null check (actor in ('gpt', 'claude')),
  perspective text not null check (length(btrim(perspective)) between 1 and 500),
  request_id uuid not null,
  request_hash text not null check (request_hash ~ '^[0-9a-f]{64}$'),
  status text not null default 'pending'
    check (status in ('pending', 'processing', 'completed', 'failed')),
  attempt_count integer not null default 0 check (attempt_count between 0 and 3),
  available_at timestamptz not null default now(),
  lease_expires_at timestamptz,
  curator_provider text check (
    curator_provider is null or length(btrim(curator_provider)) between 1 and 100
  ),
  curator_model text check (
    curator_model is null or length(btrim(curator_model)) between 1 and 150
  ),
  output_hash text check (output_hash is null or output_hash ~ '^[0-9a-f]{64}$'),
  output_count integer check (output_count is null or output_count between 0 and 3),
  last_error_code text,
  created_at timestamptz not null default now(),
  started_at timestamptz,
  completed_at timestamptz,
  updated_at timestamptz not null default now(),
  unique (owner_id, actor, request_id),
  constraint memory_dream_job_lifecycle_check check (
    (
      status = 'pending'
      and lease_expires_at is null and completed_at is null
      and output_hash is null and output_count is null
    ) or (
      status = 'processing'
      and lease_expires_at is not null and curator_provider is not null
      and curator_model is not null and completed_at is null
      and output_hash is null and output_count is null
    ) or (
      status = 'completed'
      and lease_expires_at is null and curator_provider is not null
      and curator_model is not null and completed_at is not null
      and output_hash is not null and output_count is not null
      and last_error_code is null
    ) or (
      status = 'failed'
      and lease_expires_at is null and curator_provider is not null
      and curator_model is not null and completed_at is not null
      and output_hash is null and output_count is null
      and last_error_code is not null
    )
  )
);

create index memory_dream_jobs_queue_idx
  on public.memory_dream_jobs (owner_id, actor, status, available_at, created_at, id)
  where status in ('pending', 'processing') and attempt_count < 3;

create table public.memory_dream_job_sources (
  id bigint generated always as identity primary key,
  owner_id uuid not null references auth.users(id) on delete restrict,
  dream_job_id bigint not null references public.memory_dream_jobs(id) on delete restrict,
  ordinal smallint not null check (ordinal between 1 and 4),
  source_memory_id bigint not null references public.memory_entries(id) on delete restrict,
  source_revision_id bigint not null,
  source_revision_hash text not null check (source_revision_hash ~ '^[0-9a-f]{64}$'),
  source_space_key text not null check (source_space_key in ('gpt', 'claude', 'shared')),
  dream_actor text not null check (dream_actor in ('gpt', 'claude')),
  source_actor text not null check (source_actor in ('gpt', 'claude')),
  created_at timestamptz not null default now(),
  unique (dream_job_id, ordinal),
  unique (dream_job_id, source_revision_id),
  unique (owner_id, dream_actor, source_revision_id),
  constraint memory_dream_source_revision_fk
    foreign key (source_revision_id, source_memory_id, owner_id)
    references public.memory_revisions (id, memory_id, owner_id)
    on delete restrict
);

create index memory_dream_sources_exact_revision_idx
  on public.memory_dream_job_sources (
    owner_id, source_memory_id, source_revision_id, source_revision_hash
  );

alter table public.memory_ingest_candidates
  add column dream_job_id bigint references public.memory_dream_jobs(id) on delete restrict,
  add column dream_output_key text,
  add column proposal_kind text not null default 'derived_memory'
    check (proposal_kind in ('derived_memory', 'revision_suggestion', 'shared_candidate')),
  add column target_memory_id bigint references public.memory_entries(id) on delete restrict,
  add column target_revision_id bigint,
  add column target_revision_hash text check (
    target_revision_hash is null or target_revision_hash ~ '^[0-9a-f]{64}$'
  ),
  add column curator_provider text,
  add column curator_model text,
  add column perspective text,
  add constraint memory_ingest_candidate_target_revision_fk
    foreign key (target_revision_id, target_memory_id, owner_id)
    references public.memory_revisions (id, memory_id, owner_id)
    on delete restrict,
  add constraint memory_ingest_candidate_dream_identity_check check (
    (
      dream_job_id is null and dream_output_key is null
      and curator_provider is null and curator_model is null and perspective is null
    ) or (
      dream_job_id is not null and length(btrim(dream_output_key)) between 1 and 100
      and length(btrim(curator_provider)) between 1 and 100
      and length(btrim(curator_model)) between 1 and 150
      and length(btrim(perspective)) between 1 and 500
    )
  ),
  add constraint memory_ingest_candidate_revision_target_check check (
    (
      proposal_kind = 'revision_suggestion'
      and target_memory_id is not null
      and target_revision_id is not null
      and target_revision_hash is not null
    ) or (
      proposal_kind <> 'revision_suggestion'
      and target_memory_id is null
      and target_revision_id is null
      and target_revision_hash is null
    )
  );

create unique index memory_ingest_candidate_dream_output_idx
  on public.memory_ingest_candidates (dream_job_id, dream_output_key)
  where dream_job_id is not null;

create table public.memory_ingest_candidate_sources (
  id bigint generated always as identity primary key,
  owner_id uuid not null references auth.users(id) on delete restrict,
  candidate_id bigint not null references public.memory_ingest_candidates(id) on delete restrict,
  dream_job_id bigint not null references public.memory_dream_jobs(id) on delete restrict,
  source_ordinal smallint not null check (source_ordinal between 1 and 4),
  source_memory_id bigint not null references public.memory_entries(id) on delete restrict,
  source_revision_id bigint not null,
  source_revision_hash text not null check (source_revision_hash ~ '^[0-9a-f]{64}$'),
  source_space_key text not null check (source_space_key in ('gpt', 'claude', 'shared')),
  dream_actor text not null check (dream_actor in ('gpt', 'claude')),
  source_actor text not null check (source_actor in ('gpt', 'claude')),
  perspective text not null check (length(btrim(perspective)) between 1 and 500),
  curator_provider text not null check (length(btrim(curator_provider)) between 1 and 100),
  curator_model text not null check (length(btrim(curator_model)) between 1 and 150),
  created_at timestamptz not null default now(),
  unique (candidate_id, source_ordinal),
  constraint memory_candidate_source_revision_fk
    foreign key (source_revision_id, source_memory_id, owner_id)
    references public.memory_revisions (id, memory_id, owner_id)
    on delete restrict
);

create index memory_candidate_sources_revision_idx
  on public.memory_ingest_candidate_sources (
    owner_id, source_memory_id, source_revision_id, source_revision_hash
  );

create index memory_entries_dream_recent_idx
  on public.memory_entries (owner_id, space_key, updated_at desc, id desc)
  where space_key in ('gpt', 'claude', 'shared');

-- Anchor history is append-only except for one release transition. Dream
-- source/candidate provenance is fully append-only.
create or replace function public.memory_behavior_guard_anchor_update()
returns trigger
language plpgsql
set search_path = ''
as $$
begin
  if new.owner_id is distinct from old.owner_id
    or new.actor is distinct from old.actor
    or new.memory_id is distinct from old.memory_id
    or new.pinned_revision_id is distinct from old.pinned_revision_id
    or new.pinned_revision_hash is distinct from old.pinned_revision_hash
    or new.reason is distinct from old.reason
    or new.pinned_at is distinct from old.pinned_at
  then
    raise exception 'Anchor identity and pinned revision are immutable'
      using errcode = '55000';
  end if;
  if old.released_at is not null
    or new.released_at is null
    or nullif(pg_catalog.btrim(new.released_reason), '') is null
  then
    raise exception 'Anchor records only allow one active -> released transition'
      using errcode = '55000';
  end if;
  return new;
end;
$$;

create trigger memory_anchor_guard_update
  before update on public.memory_anchor_records
  for each row execute function public.memory_behavior_guard_anchor_update();
create trigger memory_anchor_reject_delete
  before delete on public.memory_anchor_records
  for each row execute function public.memory_reject_append_only_change();
create trigger memory_dream_sources_append_only
  before update or delete on public.memory_dream_job_sources
  for each row execute function public.memory_reject_append_only_change();
create trigger memory_candidate_sources_append_only
  before update or delete on public.memory_ingest_candidate_sources
  for each row execute function public.memory_reject_append_only_change();

create or replace function public.memory_behavior_internal_set_anchor(
  p_actor text,
  p_owner_id uuid,
  p_request_id uuid,
  p_memory_id bigint,
  p_pinned boolean,
  p_reason text
)
returns jsonb
language plpgsql
set search_path = ''
as $$
declare
  entry public.memory_entries%rowtype;
  revision_id bigint;
  revision_hash text;
  anchor public.memory_anchor_records%rowtype;
begin
  if p_actor not in ('gpt', 'claude') then
    raise exception 'Invalid fixed actor' using errcode = '42501';
  end if;
  if p_request_id is null or nullif(pg_catalog.btrim(p_reason), '') is null then
    raise exception 'Trusted request id and anchor reason are required'
      using errcode = '23514';
  end if;

  select * into entry
  from public.memory_entries
  where owner_id = p_owner_id and id = p_memory_id;
  if not found or not (
    entry.space_key = p_actor
    or (entry.space_key = 'shared' and entry.shared_status = 'approved')
  ) then
    perform public.memory_behavior_internal_audit(
      p_owner_id, p_actor, 'anchor_change', p_memory_id,
      case when entry.id is null then null else entry.space_key end,
      'denied', 'MEMORY_ACCESS_DENIED', p_request_id, 0, '{}'::text[],
      pg_catalog.jsonb_build_object('requested_state', p_pinned)
    );
    return pg_catalog.jsonb_build_object(
      'ok', false, 'error_code', 'MEMORY_ACCESS_DENIED',
      'message', 'Only actor-private or approved Shared memory can be anchored',
      'audit_persisted', true
    );
  end if;

  if p_pinned then
    select * into anchor
    from public.memory_anchor_records
    where owner_id = p_owner_id and actor = p_actor
      and memory_id = p_memory_id and released_at is null;
    if found then
      return pg_catalog.jsonb_build_object('ok', true, 'anchor', to_jsonb(anchor), 'replayed', true);
    end if;
    if (
      select count(*) from public.memory_anchor_records
      where owner_id = p_owner_id and actor = p_actor and released_at is null
    ) >= 12 then
      perform public.memory_behavior_internal_audit(
        p_owner_id, p_actor, 'anchor_change', p_memory_id, entry.space_key,
        'denied', 'MEMORY_ANCHOR_LIMIT_REACHED', p_request_id, 0, '{}'::text[],
        pg_catalog.jsonb_build_object('limit', 12)
      );
      return pg_catalog.jsonb_build_object(
        'ok', false, 'error_code', 'MEMORY_ANCHOR_LIMIT_REACHED',
        'message', 'At most 12 active anchors are allowed per actor',
        'audit_persisted', true
      );
    end if;
    select id, public.memory_compute_revision_hash(id)
      into strict revision_id, revision_hash
    from public.memory_revisions
    where owner_id = p_owner_id and memory_id = entry.id
      and revision_number = entry.revision_number;
    insert into public.memory_anchor_records (
      owner_id, actor, memory_id, pinned_revision_id, pinned_revision_hash, reason
    ) values (
      p_owner_id, p_actor, entry.id, revision_id, revision_hash, pg_catalog.btrim(p_reason)
    ) returning * into anchor;
  else
    update public.memory_anchor_records
      set released_at = now(), released_reason = pg_catalog.btrim(p_reason)
      where owner_id = p_owner_id and actor = p_actor
        and memory_id = p_memory_id and released_at is null
      returning * into anchor;
    if not found then
      perform public.memory_behavior_internal_audit(
        p_owner_id, p_actor, 'anchor_change', p_memory_id, entry.space_key,
        'denied', 'MEMORY_ANCHOR_NOT_FOUND', p_request_id, 0, '{}'::text[],
        pg_catalog.jsonb_build_object('requested_state', false)
      );
      return pg_catalog.jsonb_build_object(
        'ok', false, 'error_code', 'MEMORY_ANCHOR_NOT_FOUND',
        'message', 'No active anchor exists for this memory',
        'audit_persisted', true
      );
    end if;
  end if;

  perform public.memory_behavior_internal_audit(
    p_owner_id, p_actor, 'anchor_change', p_memory_id, entry.space_key,
    'allowed', null, p_request_id, 1, array[entry.space_key],
    pg_catalog.jsonb_build_object(
      'pinned', p_pinned,
      'pinned_revision_id', anchor.pinned_revision_id,
      'pinned_revision_hash', anchor.pinned_revision_hash
    )
  );
  return pg_catalog.jsonb_build_object(
    'ok', true, 'anchor', to_jsonb(anchor), 'replayed', false
  );
end;
$$;

create or replace function public.memory_behavior_internal_list_anchors(
  p_actor text,
  p_owner_id uuid
)
returns jsonb
language plpgsql
set search_path = ''
as $$
declare
  items jsonb;
begin
  if p_actor not in ('gpt', 'claude') then
    raise exception 'Invalid fixed actor' using errcode = '42501';
  end if;
  select coalesce(pg_catalog.jsonb_agg(to_jsonb(a) order by a.pinned_at desc), '[]'::jsonb)
    into items
  from public.memory_anchor_records a
  where a.owner_id = p_owner_id and a.actor = p_actor and a.released_at is null;
  return pg_catalog.jsonb_build_object('ok', true, 'items', items);
end;
$$;

create or replace function public.memory_behavior_internal_enqueue_dream(
  p_actor text,
  p_owner_id uuid,
  p_request_id uuid,
  p_perspective text,
  p_limit integer
)
returns jsonb
language plpgsql
set search_path = ''
as $$
declare
  safe_limit integer := least(greatest(coalesce(p_limit, 4), 1), 4);
  normalized_perspective text := left(pg_catalog.btrim(coalesce(p_perspective, '')), 500);
  expected_hash text;
  existing public.memory_dream_jobs%rowtype;
  job public.memory_dream_jobs%rowtype;
  source_count integer;
begin
  if p_actor not in ('gpt', 'claude') then
    raise exception 'Invalid fixed actor' using errcode = '42501';
  end if;
  if p_request_id is null or normalized_perspective = '' then
    raise exception 'Trusted request id and Dream perspective are required'
      using errcode = '23514';
  end if;
  expected_hash := public.memory_hash_jsonb(pg_catalog.jsonb_build_object(
    'perspective', normalized_perspective, 'limit', safe_limit
  ));

  select * into existing
  from public.memory_dream_jobs
  where owner_id = p_owner_id and actor = p_actor and request_id = p_request_id;
  if found then
    if existing.request_hash <> expected_hash then
      perform public.memory_behavior_internal_audit(
        p_owner_id, p_actor, 'dream_enqueue', null, null,
        'denied', 'MEMORY_DREAM_REQUEST_CONFLICT', p_request_id, 0, '{}'::text[],
        pg_catalog.jsonb_build_object('job_id', existing.id)
      );
      return pg_catalog.jsonb_build_object(
        'ok', false, 'error_code', 'MEMORY_DREAM_REQUEST_CONFLICT',
        'message', 'Dream request id was reused with different material',
        'audit_persisted', true
      );
    end if;
    return pg_catalog.jsonb_build_object('ok', true, 'job', to_jsonb(existing), 'replayed', true);
  end if;

  insert into public.memory_dream_jobs (
    owner_id, actor, perspective, request_id, request_hash
  ) values (
    p_owner_id, p_actor, normalized_perspective, p_request_id, expected_hash
  ) returning * into job;

  insert into public.memory_dream_job_sources (
    owner_id, dream_job_id, ordinal, source_memory_id, source_revision_id,
    source_revision_hash, source_space_key, dream_actor, source_actor
  )
  select
    p_owner_id, job.id, selected.ordinal, selected.memory_id,
    selected.revision_id, selected.revision_hash, selected.space_key,
    p_actor, selected.source_actor
  from (
    select
      e.id as memory_id,
      r.id as revision_id,
      public.memory_compute_revision_hash(r.id) as revision_hash,
      e.space_key,
      case
        when e.space_key in ('gpt', 'claude') then e.space_key
        else source_entry.space_key
      end as source_actor,
      row_number() over (
        order by (a.id is not null) desc, e.importance desc, e.updated_at desc, e.id desc
      )::smallint as ordinal
    from public.memory_entries e
    join public.memory_revisions r
      on r.owner_id = e.owner_id and r.memory_id = e.id
      and r.revision_number = e.revision_number
    left join public.memory_entries source_entry
      on e.space_key = 'shared' and source_entry.owner_id = e.owner_id
      and source_entry.id = e.source_memory_id
    left join public.memory_anchor_records a
      on a.owner_id = e.owner_id and a.actor = p_actor
      and a.memory_id = e.id and a.released_at is null
    where e.owner_id = p_owner_id
      and (e.space_key = p_actor or (e.space_key = 'shared' and e.shared_status = 'approved'))
      and e.lifecycle_status in ('active', 'awakened')
      and (a.id is not null or e.updated_at >= now() - interval '180 days')
      and not exists (
        select 1 from public.memory_dream_job_sources prior
        where prior.owner_id = p_owner_id and prior.dream_actor = p_actor
          and prior.source_revision_id = r.id
      )
    order by (a.id is not null) desc, e.importance desc, e.updated_at desc, e.id desc
    limit safe_limit
  ) selected;

  get diagnostics source_count = row_count;
  if source_count = 0 then
    delete from public.memory_dream_jobs where id = job.id;
    perform public.memory_behavior_internal_audit(
      p_owner_id, p_actor, 'dream_enqueue', null, null,
      'allowed', null, p_request_id, 0, '{}'::text[],
      pg_catalog.jsonb_build_object('reason', 'no_eligible_recent_revision')
    );
    return pg_catalog.jsonb_build_object('ok', true, 'job', null, 'replayed', false);
  end if;

  perform public.memory_behavior_internal_audit(
    p_owner_id, p_actor, 'dream_enqueue', null, null,
    'allowed', null, p_request_id, source_count, '{}'::text[],
    pg_catalog.jsonb_build_object(
      'job_id', job.id, 'source_count', source_count,
      'perspective', normalized_perspective
    )
  );
  return pg_catalog.jsonb_build_object(
    'ok', true, 'job', to_jsonb(job) || pg_catalog.jsonb_build_object('source_count', source_count),
    'replayed', false
  );
end;
$$;

create or replace function public.memory_behavior_internal_claim_dream(
  p_actor text,
  p_owner_id uuid,
  p_request_id uuid,
  p_curator_provider text,
  p_curator_model text
)
returns jsonb
language plpgsql
set search_path = ''
as $$
declare
  provider_key text := left(pg_catalog.btrim(coalesce(p_curator_provider, '')), 100);
  model_key text := left(pg_catalog.btrim(coalesce(p_curator_model, '')), 150);
  job public.memory_dream_jobs%rowtype;
  sources jsonb;
begin
  if p_actor not in ('gpt', 'claude') then
    raise exception 'Invalid fixed actor' using errcode = '42501';
  end if;
  if p_request_id is null or provider_key = '' or model_key = '' then
    raise exception 'Trusted request id and Curator identity are required'
      using errcode = '23514';
  end if;

  select * into job
  from public.memory_dream_jobs
  where owner_id = p_owner_id and actor = p_actor
    and attempt_count < 3
    and (
      (status = 'pending' and available_at <= now())
      or (status = 'processing' and lease_expires_at <= now())
    )
  order by available_at, created_at, id
  for update skip locked
  limit 1;
  if not found then
    return pg_catalog.jsonb_build_object('ok', true, 'job', null);
  end if;

  update public.memory_dream_jobs
    set status = 'processing',
        attempt_count = attempt_count + 1,
        lease_expires_at = now() + interval '5 minutes',
        curator_provider = provider_key,
        curator_model = model_key,
        started_at = coalesce(started_at, now()),
        last_error_code = null,
        updated_at = now()
    where id = job.id
    returning * into job;

  select coalesce(pg_catalog.jsonb_agg(
    pg_catalog.jsonb_build_object(
      'ordinal', s.ordinal,
      'memory_id', s.source_memory_id,
      'revision_id', s.source_revision_id,
      'revision_hash', s.source_revision_hash,
      'source_space', s.source_space_key,
      'dream_actor', s.dream_actor,
      'source_actor', s.source_actor,
      'title', r.title,
      'memory_type', r.memory_type,
      'tags', r.tags,
      'emotion', r.emotion,
      'importance', r.importance,
      'content', left(r.content, 6000)
    ) order by s.ordinal
  ), '[]'::jsonb) into sources
  from public.memory_dream_job_sources s
  join public.memory_revisions r
    on r.owner_id = s.owner_id and r.memory_id = s.source_memory_id
    and r.id = s.source_revision_id
  where s.owner_id = p_owner_id and s.dream_job_id = job.id;

  perform public.memory_behavior_internal_audit(
    p_owner_id, p_actor, 'dream_claim', null, null,
    'allowed', null, p_request_id, pg_catalog.jsonb_array_length(sources), '{}'::text[],
    pg_catalog.jsonb_build_object(
      'job_id', job.id, 'attempt', job.attempt_count,
      'curator_provider', provider_key, 'curator_model', model_key
    )
  );
  return pg_catalog.jsonb_build_object(
    'ok', true,
    'job', pg_catalog.jsonb_build_object(
      'id', job.id,
      'actor', job.actor,
      'perspective', job.perspective,
      'attempt_count', job.attempt_count,
      'curator_provider', job.curator_provider,
      'curator_model', job.curator_model,
      'sources', sources
    )
  );
end;
$$;

create or replace function public.memory_behavior_internal_complete_dream(
  p_actor text,
  p_owner_id uuid,
  p_request_id uuid,
  p_job_id bigint,
  p_curator_provider text,
  p_curator_model text,
  p_outputs jsonb
)
returns jsonb
language plpgsql
set search_path = ''
as $$
declare
  provider_key text := left(pg_catalog.btrim(coalesce(p_curator_provider, '')), 100);
  model_key text := left(pg_catalog.btrim(coalesce(p_curator_model, '')), 150);
  outputs_hash text;
  job public.memory_dream_jobs%rowtype;
  output_record record;
  proposal_kind text;
  proposed_space text;
  proposed_type text;
  proposed_tags text[];
  proposed_emotion jsonb;
  proposed_importance smallint;
  proposed_content text;
  selected_ordinals smallint[];
  target_ordinal smallint;
  target_source public.memory_dream_job_sources%rowtype;
  candidate public.memory_ingest_candidates%rowtype;
  candidate_ids bigint[] := '{}'::bigint[];
  source_count integer;
begin
  if p_actor not in ('gpt', 'claude') then
    raise exception 'Invalid fixed actor' using errcode = '42501';
  end if;
  if p_request_id is null or provider_key = '' or model_key = ''
    or pg_catalog.jsonb_typeof(p_outputs) <> 'array'
    or pg_catalog.jsonb_array_length(p_outputs) not between 1 and 3
  then
    raise exception 'A trusted request, Curator identity and 1-3 candidate outputs are required'
      using errcode = '23514';
  end if;
  outputs_hash := public.memory_hash_jsonb(p_outputs);

  select * into job
  from public.memory_dream_jobs
  where owner_id = p_owner_id and actor = p_actor and id = p_job_id
  for update;
  if not found then
    perform public.memory_behavior_internal_audit(
      p_owner_id, p_actor, 'dream_complete', null, null,
      'denied', 'MEMORY_DREAM_JOB_NOT_FOUND', p_request_id, 0, '{}'::text[],
      pg_catalog.jsonb_build_object('job_id', p_job_id)
    );
    return pg_catalog.jsonb_build_object(
      'ok', false, 'error_code', 'MEMORY_DREAM_JOB_NOT_FOUND',
      'message', 'Dream job was not found', 'audit_persisted', true
    );
  end if;
  if job.status = 'completed' then
    if job.output_hash <> outputs_hash then
      perform public.memory_behavior_internal_audit(
        p_owner_id, p_actor, 'dream_complete', null, null,
        'denied', 'MEMORY_DREAM_OUTPUT_CONFLICT', p_request_id, 0, '{}'::text[],
        pg_catalog.jsonb_build_object('job_id', job.id)
      );
      return pg_catalog.jsonb_build_object(
        'ok', false, 'error_code', 'MEMORY_DREAM_OUTPUT_CONFLICT',
        'message', 'Completed Dream output is immutable', 'audit_persisted', true
      );
    end if;
    select coalesce(pg_catalog.array_agg(id order by id), '{}'::bigint[])
      into candidate_ids
    from public.memory_ingest_candidates
    where dream_job_id = job.id;
    return pg_catalog.jsonb_build_object(
      'ok', true, 'candidate_ids', candidate_ids, 'replayed', true
    );
  end if;
  if job.status <> 'processing'
    or job.lease_expires_at <= now()
    or job.curator_provider <> provider_key
    or job.curator_model <> model_key
  then
    perform public.memory_behavior_internal_audit(
      p_owner_id, p_actor, 'dream_complete', null, null,
      'denied', 'MEMORY_DREAM_LEASE_INVALID', p_request_id, 0, '{}'::text[],
      pg_catalog.jsonb_build_object('job_id', job.id)
    );
    return pg_catalog.jsonb_build_object(
      'ok', false, 'error_code', 'MEMORY_DREAM_LEASE_INVALID',
      'message', 'Dream completion does not match the active lease',
      'audit_persisted', true
    );
  end if;

  for output_record in
    select value, ordinality
    from pg_catalog.jsonb_array_elements(p_outputs) with ordinality
  loop
    if pg_catalog.jsonb_typeof(output_record.value) <> 'object' then
      raise exception 'Every Dream output must be an object' using errcode = '23514';
    end if;
    proposal_kind := coalesce(nullif(output_record.value ->> 'proposal_kind', ''), 'derived_memory');
    if proposal_kind not in ('derived_memory', 'revision_suggestion', 'shared_candidate') then
      raise exception 'Invalid Dream proposal kind' using errcode = '23514';
    end if;
    proposed_content := pg_catalog.btrim(coalesce(output_record.value ->> 'content', ''));
    if length(proposed_content) not between 1 and 12000 then
      raise exception 'Dream candidate content must contain 1-12000 characters'
        using errcode = '23514';
    end if;
    proposed_type := coalesce(nullif(output_record.value ->> 'memory_type', ''), 'summary');
    if not exists (
      select 1 from public.memory_type_catalog where memory_type = proposed_type
    ) then
      raise exception 'Unknown Dream candidate memory type' using errcode = '23514';
    end if;

    proposed_tags := '{}'::text[];
    if output_record.value ? 'tags' then
      if pg_catalog.jsonb_typeof(output_record.value -> 'tags') <> 'array'
        or pg_catalog.jsonb_array_length(output_record.value -> 'tags') > 12
      then
        raise exception 'Dream candidate tags must be an array of at most 12 values'
          using errcode = '23514';
      end if;
      select coalesce(pg_catalog.array_agg(distinct left(pg_catalog.btrim(value), 80)), '{}'::text[])
        into proposed_tags
      from pg_catalog.jsonb_array_elements_text(output_record.value -> 'tags') tags(value)
      where pg_catalog.btrim(value) <> '';
    end if;
    if proposed_tags && array['gpt', 'GPT', 'claude', 'Claude', 'cc', 'CC', 'codex', 'Codex']::text[] then
      raise exception 'Actor identity is not a Dream candidate tag' using errcode = '23514';
    end if;
    proposed_emotion := case
      when pg_catalog.jsonb_typeof(output_record.value -> 'emotion') = 'object'
        then output_record.value -> 'emotion'
      else '{}'::jsonb
    end;
    proposed_importance := case
      when coalesce(output_record.value ->> 'importance', '') ~ '^[1-5]$'
        then (output_record.value ->> 'importance')::smallint
      else 1
    end;

    if output_record.value ? 'source_ordinals' then
      if pg_catalog.jsonb_typeof(output_record.value -> 'source_ordinals') <> 'array'
        or pg_catalog.jsonb_array_length(output_record.value -> 'source_ordinals') not between 1 and 4
        or exists (
          select 1
          from pg_catalog.jsonb_array_elements_text(output_record.value -> 'source_ordinals') ord(value)
          where value !~ '^[1-4]$'
        )
      then
        raise exception 'Dream source ordinals must contain 1-4 bounded source references'
          using errcode = '23514';
      end if;
      select pg_catalog.array_agg(distinct value::smallint order by value::smallint)
        into selected_ordinals
      from pg_catalog.jsonb_array_elements_text(output_record.value -> 'source_ordinals') ord(value);
    else
      select pg_catalog.array_agg(ordinal order by ordinal) into selected_ordinals
      from public.memory_dream_job_sources
      where owner_id = p_owner_id and dream_job_id = job.id;
    end if;
    select count(*) into source_count
    from public.memory_dream_job_sources
    where owner_id = p_owner_id and dream_job_id = job.id
      and ordinal = any(selected_ordinals);
    if source_count <> pg_catalog.cardinality(selected_ordinals) then
      raise exception 'Dream output referenced a source outside its immutable job snapshot'
        using errcode = '23514';
    end if;

    target_source := null;
    if proposal_kind = 'revision_suggestion' then
      if coalesce(output_record.value ->> 'target_source_ordinal', '') !~ '^[1-4]$' then
        raise exception 'Revision suggestions require a bounded target source ordinal'
          using errcode = '23514';
      end if;
      target_ordinal := (output_record.value ->> 'target_source_ordinal')::smallint;
      select * into target_source
      from public.memory_dream_job_sources
      where owner_id = p_owner_id and dream_job_id = job.id
        and ordinal = target_ordinal and source_space_key = p_actor;
      if not found or not (target_ordinal = any(selected_ordinals)) then
        raise exception 'Revision target must be an actor-private source in this output provenance'
          using errcode = '23514';
      end if;
    end if;

    proposed_space := case when proposal_kind = 'shared_candidate' then 'shared' else p_actor end;
    insert into public.memory_ingest_candidates (
      owner_id, proposed_space_key, proposed_memory_type, proposed_tags,
      content, emotion, importance, source_window_id, source_model,
      source_type, source_ref, source_metadata, dream_run_ref,
      dream_job_id, dream_output_key, proposal_kind,
      target_memory_id, target_revision_id, target_revision_hash,
      curator_provider, curator_model, perspective
    ) values (
      p_owner_id, proposed_space, proposed_type, proposed_tags,
      proposed_content, proposed_emotion, proposed_importance,
      'dream-job:' || job.id, model_key,
      'dream', 'dream-job:' || job.id,
      pg_catalog.jsonb_build_object(
        'dream_job_id', job.id, 'dream_actor', p_actor,
        'curator_provider', provider_key, 'curator_model', model_key,
        'source_ordinals', selected_ordinals
      ),
      job.id::text, job.id, 'candidate-' || output_record.ordinality,
      proposal_kind,
      case when proposal_kind = 'revision_suggestion' then target_source.source_memory_id end,
      case when proposal_kind = 'revision_suggestion' then target_source.source_revision_id end,
      case when proposal_kind = 'revision_suggestion' then target_source.source_revision_hash end,
      provider_key, model_key, job.perspective
    ) returning * into candidate;
    candidate_ids := pg_catalog.array_append(candidate_ids, candidate.id);

    insert into public.memory_ingest_candidate_sources (
      owner_id, candidate_id, dream_job_id, source_ordinal,
      source_memory_id, source_revision_id, source_revision_hash,
      source_space_key, dream_actor, source_actor, perspective,
      curator_provider, curator_model
    )
    select
      p_owner_id, candidate.id, job.id, source.ordinal,
      source.source_memory_id, source.source_revision_id, source.source_revision_hash,
      source.source_space_key, source.dream_actor, source.source_actor,
      job.perspective, provider_key, model_key
    from public.memory_dream_job_sources source
    where source.owner_id = p_owner_id and source.dream_job_id = job.id
      and source.ordinal = any(selected_ordinals);
  end loop;

  update public.memory_dream_jobs
    set status = 'completed', lease_expires_at = null,
        output_hash = outputs_hash,
        output_count = pg_catalog.cardinality(candidate_ids),
        last_error_code = null, completed_at = now(), updated_at = now()
    where id = job.id;

  perform public.memory_behavior_internal_audit(
    p_owner_id, p_actor, 'dream_complete', null, null,
    'allowed', null, p_request_id, pg_catalog.cardinality(candidate_ids), '{}'::text[],
    pg_catalog.jsonb_build_object(
      'job_id', job.id, 'candidate_ids', candidate_ids,
      'curator_provider', provider_key, 'curator_model', model_key
    )
  );
  return pg_catalog.jsonb_build_object(
    'ok', true, 'candidate_ids', candidate_ids, 'replayed', false
  );
exception when others then
  -- The function transaction rolls back every candidate/source insert. It
  -- deliberately never catches and converts validation errors after writes.
  raise;
end;
$$;

create or replace function public.memory_behavior_internal_fail_dream(
  p_actor text,
  p_owner_id uuid,
  p_request_id uuid,
  p_job_id bigint,
  p_curator_provider text,
  p_curator_model text,
  p_reason_code text
)
returns jsonb
language plpgsql
set search_path = ''
as $$
declare
  provider_key text := left(pg_catalog.btrim(coalesce(p_curator_provider, '')), 100);
  model_key text := left(pg_catalog.btrim(coalesce(p_curator_model, '')), 150);
  reason_code text := left(pg_catalog.btrim(coalesce(p_reason_code, 'MEMORY_DREAM_CURATOR_FAILED')), 100);
  job public.memory_dream_jobs%rowtype;
  final_failure boolean;
begin
  if p_actor not in ('gpt', 'claude') then
    raise exception 'Invalid fixed actor' using errcode = '42501';
  end if;
  select * into job
  from public.memory_dream_jobs
  where owner_id = p_owner_id and actor = p_actor and id = p_job_id
  for update;
  if not found or job.status <> 'processing'
    or job.curator_provider <> provider_key or job.curator_model <> model_key
  then
    perform public.memory_behavior_internal_audit(
      p_owner_id, p_actor, 'dream_fail', null, null,
      'denied', 'MEMORY_DREAM_LEASE_INVALID', p_request_id, 0, '{}'::text[],
      pg_catalog.jsonb_build_object('job_id', p_job_id)
    );
    return pg_catalog.jsonb_build_object(
      'ok', false, 'error_code', 'MEMORY_DREAM_LEASE_INVALID',
      'message', 'Dream failure does not match an active job',
      'audit_persisted', true
    );
  end if;
  final_failure := job.attempt_count >= 3;
  update public.memory_dream_jobs
    set status = case when final_failure then 'failed' else 'pending' end,
        available_at = case
          when final_failure then available_at
          else now() + (interval '1 minute' * greatest(job.attempt_count, 1))
        end,
        lease_expires_at = null,
        last_error_code = reason_code,
        completed_at = case when final_failure then now() else null end,
        updated_at = now()
    where id = job.id
    returning * into job;
  perform public.memory_behavior_internal_audit(
    p_owner_id, p_actor, 'dream_fail', null, null,
    case when final_failure then 'error' else 'allowed' end,
    case when final_failure then reason_code else null end,
    p_request_id, 0, '{}'::text[],
    pg_catalog.jsonb_build_object(
      'job_id', job.id, 'attempt', job.attempt_count,
      'retry_scheduled', not final_failure,
      'curator_provider', provider_key, 'curator_model', model_key
    )
  );
  return pg_catalog.jsonb_build_object(
    'ok', true, 'status', job.status, 'retry_scheduled', not final_failure
  );
end;
$$;

-- Fixed actor doors. These are Bridge-internal and are not MCP tools; callers
-- never supply actor, space, owner authority, source revision or provider hash.
create or replace function public.memory_behavior_set_anchor_gpt(
  p_owner_id uuid, p_request_id uuid, p_memory_id bigint, p_pinned boolean, p_reason text
)
returns jsonb language sql security definer set search_path = '' as $$
  select public.memory_behavior_internal_set_anchor(
    'gpt', p_owner_id, p_request_id, p_memory_id, p_pinned, p_reason
  )
$$;
create or replace function public.memory_behavior_set_anchor_claude(
  p_owner_id uuid, p_request_id uuid, p_memory_id bigint, p_pinned boolean, p_reason text
)
returns jsonb language sql security definer set search_path = '' as $$
  select public.memory_behavior_internal_set_anchor(
    'claude', p_owner_id, p_request_id, p_memory_id, p_pinned, p_reason
  )
$$;
create or replace function public.memory_behavior_list_anchors_gpt(p_owner_id uuid)
returns jsonb language sql security definer set search_path = '' as $$
  select public.memory_behavior_internal_list_anchors('gpt', p_owner_id)
$$;
create or replace function public.memory_behavior_list_anchors_claude(p_owner_id uuid)
returns jsonb language sql security definer set search_path = '' as $$
  select public.memory_behavior_internal_list_anchors('claude', p_owner_id)
$$;
create or replace function public.memory_behavior_enqueue_dream_gpt(
  p_owner_id uuid, p_request_id uuid, p_perspective text, p_limit integer
)
returns jsonb language sql security definer set search_path = '' as $$
  select public.memory_behavior_internal_enqueue_dream(
    'gpt', p_owner_id, p_request_id, p_perspective, p_limit
  )
$$;
create or replace function public.memory_behavior_enqueue_dream_claude(
  p_owner_id uuid, p_request_id uuid, p_perspective text, p_limit integer
)
returns jsonb language sql security definer set search_path = '' as $$
  select public.memory_behavior_internal_enqueue_dream(
    'claude', p_owner_id, p_request_id, p_perspective, p_limit
  )
$$;
create or replace function public.memory_behavior_claim_dream_gpt(
  p_owner_id uuid, p_request_id uuid, p_curator_provider text, p_curator_model text
)
returns jsonb language sql security definer set search_path = '' as $$
  select public.memory_behavior_internal_claim_dream(
    'gpt', p_owner_id, p_request_id, p_curator_provider, p_curator_model
  )
$$;
create or replace function public.memory_behavior_claim_dream_claude(
  p_owner_id uuid, p_request_id uuid, p_curator_provider text, p_curator_model text
)
returns jsonb language sql security definer set search_path = '' as $$
  select public.memory_behavior_internal_claim_dream(
    'claude', p_owner_id, p_request_id, p_curator_provider, p_curator_model
  )
$$;
create or replace function public.memory_behavior_complete_dream_gpt(
  p_owner_id uuid, p_request_id uuid, p_job_id bigint,
  p_curator_provider text, p_curator_model text, p_outputs jsonb
)
returns jsonb language sql security definer set search_path = '' as $$
  select public.memory_behavior_internal_complete_dream(
    'gpt', p_owner_id, p_request_id, p_job_id,
    p_curator_provider, p_curator_model, p_outputs
  )
$$;
create or replace function public.memory_behavior_complete_dream_claude(
  p_owner_id uuid, p_request_id uuid, p_job_id bigint,
  p_curator_provider text, p_curator_model text, p_outputs jsonb
)
returns jsonb language sql security definer set search_path = '' as $$
  select public.memory_behavior_internal_complete_dream(
    'claude', p_owner_id, p_request_id, p_job_id,
    p_curator_provider, p_curator_model, p_outputs
  )
$$;
create or replace function public.memory_behavior_fail_dream_gpt(
  p_owner_id uuid, p_request_id uuid, p_job_id bigint,
  p_curator_provider text, p_curator_model text, p_reason_code text
)
returns jsonb language sql security definer set search_path = '' as $$
  select public.memory_behavior_internal_fail_dream(
    'gpt', p_owner_id, p_request_id, p_job_id,
    p_curator_provider, p_curator_model, p_reason_code
  )
$$;
create or replace function public.memory_behavior_fail_dream_claude(
  p_owner_id uuid, p_request_id uuid, p_job_id bigint,
  p_curator_provider text, p_curator_model text, p_reason_code text
)
returns jsonb language sql security definer set search_path = '' as $$
  select public.memory_behavior_internal_fail_dream(
    'claude', p_owner_id, p_request_id, p_job_id,
    p_curator_provider, p_curator_model, p_reason_code
  )
$$;

alter table public.memory_anchor_records enable row level security;
alter table public.memory_anchor_records force row level security;
alter table public.memory_dream_jobs enable row level security;
alter table public.memory_dream_jobs force row level security;
alter table public.memory_dream_job_sources enable row level security;
alter table public.memory_dream_job_sources force row level security;
alter table public.memory_ingest_candidate_sources enable row level security;
alter table public.memory_ingest_candidate_sources force row level security;

create policy memory_anchor_owner_read
  on public.memory_anchor_records for select to authenticated
  using ((select auth.uid()) = owner_id);
create policy memory_dream_jobs_owner_read
  on public.memory_dream_jobs for select to authenticated
  using ((select auth.uid()) = owner_id);
create policy memory_dream_sources_owner_read
  on public.memory_dream_job_sources for select to authenticated
  using ((select auth.uid()) = owner_id);
create policy memory_candidate_sources_owner_read
  on public.memory_ingest_candidate_sources for select to authenticated
  using ((select auth.uid()) = owner_id);

revoke all on table public.memory_anchor_records from public, anon, authenticated, service_role;
revoke all on table public.memory_dream_jobs from public, anon, authenticated, service_role;
revoke all on table public.memory_dream_job_sources from public, anon, authenticated, service_role;
revoke all on table public.memory_ingest_candidate_sources from public, anon, authenticated, service_role;
grant select on table public.memory_anchor_records to authenticated;
grant select on table public.memory_dream_jobs to authenticated;
grant select on table public.memory_dream_job_sources to authenticated;
grant select on table public.memory_ingest_candidate_sources to authenticated;

revoke all on sequence public.memory_anchor_records_id_seq from public, anon, authenticated, service_role;
revoke all on sequence public.memory_dream_jobs_id_seq from public, anon, authenticated, service_role;
revoke all on sequence public.memory_dream_job_sources_id_seq from public, anon, authenticated, service_role;
revoke all on sequence public.memory_ingest_candidate_sources_id_seq from public, anon, authenticated, service_role;

revoke execute on function public.memory_behavior_guard_anchor_update() from public, anon, authenticated, service_role;
revoke execute on function public.memory_behavior_internal_set_anchor(text, uuid, uuid, bigint, boolean, text) from public, anon, authenticated, service_role;
revoke execute on function public.memory_behavior_internal_list_anchors(text, uuid) from public, anon, authenticated, service_role;
revoke execute on function public.memory_behavior_internal_enqueue_dream(text, uuid, uuid, text, integer) from public, anon, authenticated, service_role;
revoke execute on function public.memory_behavior_internal_claim_dream(text, uuid, uuid, text, text) from public, anon, authenticated, service_role;
revoke execute on function public.memory_behavior_internal_complete_dream(text, uuid, uuid, bigint, text, text, jsonb) from public, anon, authenticated, service_role;
revoke execute on function public.memory_behavior_internal_fail_dream(text, uuid, uuid, bigint, text, text, text) from public, anon, authenticated, service_role;

revoke execute on function public.memory_behavior_set_anchor_gpt(uuid, uuid, bigint, boolean, text) from public, anon, authenticated, service_role;
revoke execute on function public.memory_behavior_set_anchor_claude(uuid, uuid, bigint, boolean, text) from public, anon, authenticated, service_role;
revoke execute on function public.memory_behavior_list_anchors_gpt(uuid) from public, anon, authenticated, service_role;
revoke execute on function public.memory_behavior_list_anchors_claude(uuid) from public, anon, authenticated, service_role;
revoke execute on function public.memory_behavior_enqueue_dream_gpt(uuid, uuid, text, integer) from public, anon, authenticated, service_role;
revoke execute on function public.memory_behavior_enqueue_dream_claude(uuid, uuid, text, integer) from public, anon, authenticated, service_role;
revoke execute on function public.memory_behavior_claim_dream_gpt(uuid, uuid, text, text) from public, anon, authenticated, service_role;
revoke execute on function public.memory_behavior_claim_dream_claude(uuid, uuid, text, text) from public, anon, authenticated, service_role;
revoke execute on function public.memory_behavior_complete_dream_gpt(uuid, uuid, bigint, text, text, jsonb) from public, anon, authenticated, service_role;
revoke execute on function public.memory_behavior_complete_dream_claude(uuid, uuid, bigint, text, text, jsonb) from public, anon, authenticated, service_role;
revoke execute on function public.memory_behavior_fail_dream_gpt(uuid, uuid, bigint, text, text, text) from public, anon, authenticated, service_role;
revoke execute on function public.memory_behavior_fail_dream_claude(uuid, uuid, bigint, text, text, text) from public, anon, authenticated, service_role;

grant execute on function public.memory_behavior_set_anchor_gpt(uuid, uuid, bigint, boolean, text) to service_role;
grant execute on function public.memory_behavior_set_anchor_claude(uuid, uuid, bigint, boolean, text) to service_role;
grant execute on function public.memory_behavior_list_anchors_gpt(uuid) to service_role;
grant execute on function public.memory_behavior_list_anchors_claude(uuid) to service_role;
grant execute on function public.memory_behavior_enqueue_dream_gpt(uuid, uuid, text, integer) to service_role;
grant execute on function public.memory_behavior_enqueue_dream_claude(uuid, uuid, text, integer) to service_role;
grant execute on function public.memory_behavior_claim_dream_gpt(uuid, uuid, text, text) to service_role;
grant execute on function public.memory_behavior_claim_dream_claude(uuid, uuid, text, text) to service_role;
grant execute on function public.memory_behavior_complete_dream_gpt(uuid, uuid, bigint, text, text, jsonb) to service_role;
grant execute on function public.memory_behavior_complete_dream_claude(uuid, uuid, bigint, text, text, jsonb) to service_role;
grant execute on function public.memory_behavior_fail_dream_gpt(uuid, uuid, bigint, text, text, text) to service_role;
grant execute on function public.memory_behavior_fail_dream_claude(uuid, uuid, bigint, text, text, text) to service_role;

comment on table public.memory_anchor_records is
  'Bounded active pins plus immutable release history. Anchors never change memory content.';
comment on table public.memory_dream_jobs is
  'Small fixed-actor background curation jobs. Provider/model are runtime identities, not schema enums.';
comment on table public.memory_dream_job_sources is
  'Immutable exact-revision Dream input provenance, preserving dream actor and original source actor.';
comment on table public.memory_ingest_candidate_sources is
  'Immutable candidate-level provenance linking every Dream proposal to exact source revisions.';
comment on function public.memory_behavior_complete_dream_gpt(uuid, uuid, bigint, text, text, jsonb) is
  'Fixed GPT Dream completion door. Creates pending ingest candidates only; never mutates canonical memory.';
comment on function public.memory_behavior_complete_dream_claude(uuid, uuid, bigint, text, text, jsonb) is
  'Fixed Claude Dream completion door. Creates pending ingest candidates only; never mutates canonical memory.';

commit;
