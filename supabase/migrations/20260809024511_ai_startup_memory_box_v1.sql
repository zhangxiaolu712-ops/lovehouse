begin;

-- AI startup Memory Box V1.
-- The legal actor-visible candidate set is materialized before randomization.
-- This migration does not touch legacy tables, production flags, or the old
-- human-facing brain blind-box path.

create or replace function public.memory_runtime_internal_memory_box(
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
  safe_limit integer := least(greatest(coalesce(p_limit, 3), 1), 4);
  items jsonb;
  spaces text[];
begin
  if p_actor not in ('gpt', 'claude') then
    raise exception 'Memory Box actor must be fixed by a trusted wrapper'
      using errcode = '42501';
  end if;
  if p_owner_id is null or p_request_id is null then
    raise exception 'Trusted owner and request id are required'
      using errcode = '23514';
  end if;

  with legal_candidates as materialized (
    select
      e.id as memory_id,
      e.owner_id,
      e.space_key,
      e.shared_status,
      e.created_by_actor,
      e.source_type,
      e.source_model,
      e.source_ref,
      e.source_metadata,
      e.source_memory_id,
      e.source_revision_id,
      e.source_revision_hash,
      e.created_at as memory_created_at,
      r.id as revision_id,
      r.revision_number,
      public.memory_compute_revision_hash(r.id) as revision_hash,
      r.title,
      r.content,
      r.author,
      r.memory_type,
      r.tags,
      r.emotion,
      r.importance,
      r.retention,
      r.created_at as revision_created_at
    from public.memory_entries e
    join public.memory_revisions r
      on r.owner_id = e.owner_id
      and r.memory_id = e.id
      and r.revision_number = e.revision_number
    where e.owner_id = p_owner_id
      and (
        e.space_key = p_actor
        or (e.space_key = 'shared' and e.shared_status = 'approved')
      )
  ),
  candidates_with_provenance as (
    select
      c.*,
      case
        when c.source_memory_id is null then null
        else pg_catalog.jsonb_build_object(
          'memory_id', c.source_memory_id,
          'revision_id', c.source_revision_id,
          'revision_hash', c.source_revision_hash,
          'space_key', source_entry.space_key,
          'created_by_actor', source_entry.created_by_actor
        )
      end as source_link,
      coalesce((
        select pg_catalog.jsonb_agg(
          pg_catalog.jsonb_build_object(
            'event_type', provenance.event_type,
            'actor', provenance.actor,
            'parent_memory_id', provenance.parent_memory_id,
            'parent_revision_id', provenance.parent_revision_id,
            'source_type', provenance.source_type,
            'source_model', provenance.source_model,
            'source_ref', provenance.source_ref,
            'reason', provenance.reason,
            'details', provenance.details,
            'created_at', provenance.created_at
          ) order by provenance.created_at, provenance.id
        )
        from public.memory_provenance provenance
        where provenance.owner_id = c.owner_id
          and provenance.memory_id = c.memory_id
      ), '[]'::jsonb) as provenance_events
    from legal_candidates c
    left join public.memory_entries source_entry
      on source_entry.owner_id = c.owner_id
      and source_entry.id = c.source_memory_id
  ),
  randomized as (
    select c.*, pg_catalog.random() as random_key
    from candidates_with_provenance c
  ),
  selected as (
    select *
    from randomized
    order by random_key
    limit safe_limit
  )
  select
    coalesce(pg_catalog.jsonb_agg(
      pg_catalog.jsonb_build_object(
        'memory_id', selected.memory_id,
        'revision_id', selected.revision_id,
        'revision_number', selected.revision_number,
        'revision_hash', selected.revision_hash,
        'space_key', selected.space_key,
        'shared_status', selected.shared_status,
        'title', selected.title,
        'content', selected.content,
        'author', selected.author,
        'memory_type', selected.memory_type,
        'tags', selected.tags,
        'emotion', selected.emotion,
        'importance', selected.importance,
        'retention', selected.retention,
        'memory_created_at', selected.memory_created_at,
        'revision_created_at', selected.revision_created_at,
        'provenance', pg_catalog.jsonb_build_object(
          'perspective_actor', p_actor,
          'entry_created_by_actor', selected.created_by_actor,
          'entry_source', pg_catalog.jsonb_build_object(
            'source_type', selected.source_type,
            'source_model', selected.source_model,
            'source_ref', selected.source_ref,
            'source_metadata', selected.source_metadata
          ),
          'source_link', selected.source_link,
          'events', selected.provenance_events
        )
      ) order by selected.random_key
    ), '[]'::jsonb),
    coalesce(
      pg_catalog.array_agg(distinct selected.space_key)
        filter (where selected.space_key is not null),
      '{}'::text[]
    )
  into items, spaces
  from selected;

  perform public.memory_runtime_internal_audit(
    p_owner_id, p_actor, 'memory_box', null, null, 'allowed', null,
    p_request_id, pg_catalog.jsonb_array_length(items), spaces
  );

  return pg_catalog.jsonb_build_object(
    'ok', true,
    'actor', p_actor,
    'mode', 'random_history',
    'items', items
  );
end;
$$;

-- Actor is a literal selected by the server route, never a client argument.
create or replace function public.memory_runtime_memory_box_gpt(
  p_owner_id uuid,
  p_request_id uuid,
  p_limit integer
)
returns jsonb
language sql
security definer
set search_path = ''
as $$
  select public.memory_runtime_internal_memory_box(
    'gpt', p_owner_id, p_request_id, p_limit
  );
$$;

create or replace function public.memory_runtime_memory_box_claude(
  p_owner_id uuid,
  p_request_id uuid,
  p_limit integer
)
returns jsonb
language sql
security definer
set search_path = ''
as $$
  select public.memory_runtime_internal_memory_box(
    'claude', p_owner_id, p_request_id, p_limit
  );
$$;

revoke execute on function public.memory_runtime_internal_memory_box(text, uuid, uuid, integer)
  from public, anon, authenticated, service_role;
revoke execute on function public.memory_runtime_memory_box_gpt(uuid, uuid, integer)
  from public, anon, authenticated, service_role;
revoke execute on function public.memory_runtime_memory_box_claude(uuid, uuid, integer)
  from public, anon, authenticated, service_role;

grant execute on function public.memory_runtime_memory_box_gpt(uuid, uuid, integer)
  to service_role;
grant execute on function public.memory_runtime_memory_box_claude(uuid, uuid, integer)
  to service_role;

comment on function public.memory_runtime_memory_box_gpt(uuid, uuid, integer) is
  'Fixed GPT random-history door. Legal own-private plus approved-Shared candidates are materialized before randomization.';
comment on function public.memory_runtime_memory_box_claude(uuid, uuid, integer) is
  'Fixed Claude random-history door. Legal own-private plus approved-Shared candidates are materialized before randomization.';

commit;
