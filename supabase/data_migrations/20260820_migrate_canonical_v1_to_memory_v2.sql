-- Memory V2 Phase 2B: canonical V1 -> Memory V2 data-only migration.
--
-- This file creates no persistent database object. It is dry-run by default.
-- The caller must run it inside one transaction and set both controls before
-- applying:
--   set local lovehouse.memory_v2_phase2b_apply = 'true';
--   set local lovehouse.memory_v2_phase2b_expected_fingerprint = '<dry-run fingerprint>';
--
-- Candidate Shared, brain, memories, MCP, Chat, and all V1 rows are untouched.

do $phase2b$
declare
  apply_mode boolean := coalesce(
    pg_catalog.current_setting('lovehouse.memory_v2_phase2b_apply', true),
    'false'
  ) = 'true';
  expected_fingerprint text := pg_catalog.current_setting(
    'lovehouse.memory_v2_phase2b_expected_fingerprint', true
  );
  source_fingerprint text;
  entry_row record;
  revision_row record;
  source_row record;
  link_row record;
  v2_memory_id uuid;
  v2_revision_id uuid;
  v2_current_revision_id uuid;
  v2_source_id uuid;
  v2_origin_revision_id uuid;
  marker_count integer;
  metadata_value jsonb;
begin
  if pg_catalog.to_regclass('public.memory_entries') is null
    or pg_catalog.to_regclass('public.memory_revisions') is null
    or pg_catalog.to_regclass('public.memory_sources') is null
    or pg_catalog.to_regclass('public.memory_revision_sources') is null
    or pg_catalog.to_regclass('public.memory_v2_entries') is null
    or pg_catalog.to_regclass('public.memory_v2_revisions') is null
    or pg_catalog.to_regclass('public.memory_v2_sources') is null
    or pg_catalog.to_regclass('public.memory_v2_revision_sources') is null
  then
    raise exception 'canonical V1 and Memory V2 tables are required';
  end if;

  select pg_catalog.md5(pg_catalog.concat_ws('|',
    coalesce((select pg_catalog.md5(pg_catalog.jsonb_agg(pg_catalog.to_jsonb(e) order by e.id)::text)
      from public.memory_entries e), 'empty'),
    coalesce((select pg_catalog.md5(pg_catalog.jsonb_agg(pg_catalog.to_jsonb(r) order by r.id)::text)
      from public.memory_revisions r), 'empty'),
    coalesce((select pg_catalog.md5(pg_catalog.jsonb_agg(pg_catalog.to_jsonb(s) order by s.id)::text)
      from public.memory_sources s), 'empty'),
    coalesce((select pg_catalog.md5(pg_catalog.jsonb_agg(pg_catalog.to_jsonb(l) order by l.id)::text)
      from public.memory_revision_sources l), 'empty')
  )) into source_fingerprint;

  if apply_mode and (
    expected_fingerprint is null
    or expected_fingerprint = ''
    or expected_fingerprint <> source_fingerprint
  ) then
    raise exception 'canonical V1 changed after dry-run; refusing Phase 2B apply';
  end if;

  if exists (
    select 1
    from public.memory_entries e
    left join public.memory_revisions r
      on r.memory_id = e.id and r.revision_number = e.revision_number
    where e.lifecycle_status = 'active' and r.id is null
  ) then
    raise exception 'an active canonical entry has no matching current revision';
  end if;

  if exists (
    select 1
    from (
      select memory_id, pg_catalog.min(revision_number) as minimum,
             pg_catalog.max(revision_number) as maximum, pg_catalog.count(*) as total
      from public.memory_revisions
      group by memory_id
    ) revisions
    where revisions.minimum <> 1 or revisions.maximum <> revisions.total
  ) then
    raise exception 'canonical revision history contains a gap';
  end if;

  if exists (
    select 1
    from public.memory_entries shared
    left join public.memory_entries private on private.id = shared.source_memory_id
    left join public.memory_revisions source_revision
      on source_revision.id = shared.source_revision_id
      and source_revision.memory_id = shared.source_memory_id
      and source_revision.owner_id = shared.owner_id
    where shared.space_key = 'shared'
      and shared.shared_status = 'approved'
      and shared.lifecycle_status = 'active'
      and (
        private.id is null
        or private.owner_id <> shared.owner_id
        or private.space_key not in ('gpt', 'claude')
        or private.lifecycle_status <> 'active'
        or source_revision.id is null
      )
  ) then
    raise exception 'approved Shared does not reference an active same-owner private revision';
  end if;

  if exists (
    select 1
    from public.memory_revision_sources links
    join public.memory_entries entry on entry.id = links.memory_id
    join public.memory_sources source on source.id = links.source_id
    where entry.space_key in ('gpt', 'claude')
      and entry.lifecycle_status = 'active'
      and source.source_space_key <> entry.space_key
  ) then
    raise exception 'a canonical private revision links to a cross-namespace source';
  end if;

  if exists (
    select 1
    from public.memory_v2_revisions revision
    where revision.metadata ->> 'legacy_source' = 'canonical_v1'
    group by revision.metadata ->> 'legacy_revision_id'
    having pg_catalog.count(*) > 1
  ) then
    raise exception 'duplicate canonical revision marker exists in Memory V2';
  end if;

  if exists (
    select 1
    from public.memory_v2_revisions revision
    where revision.metadata ->> 'legacy_source' = 'canonical_v1'
    group by revision.metadata ->> 'legacy_memory_id'
    having pg_catalog.count(distinct revision.memory_id) > 1
  ) then
    raise exception 'one canonical memory marker maps to multiple Memory V2 entries';
  end if;

  if exists (
    select 1
    from public.memory_v2_sources source
    where source.provenance ->> 'legacy_source' = 'canonical_v1'
    group by source.provenance ->> 'legacy_source_id'
    having pg_catalog.count(*) > 1
  ) then
    raise exception 'duplicate canonical source marker exists in Memory V2';
  end if;

  if not apply_mode then
    return;
  end if;

  -- Private entries and their complete revision history are migrated first.
  for entry_row in
    select entry.*
    from public.memory_entries entry
    where entry.space_key in ('gpt', 'claude')
      and entry.lifecycle_status = 'active'
    order by entry.id
  loop
    select pg_catalog.count(distinct revision.memory_id)
      into marker_count
    from public.memory_v2_revisions revision
    where revision.metadata ->> 'legacy_source' = 'canonical_v1'
      and revision.metadata ->> 'legacy_memory_id' = entry_row.id::text;

    if marker_count > 1 then
      raise exception 'canonical memory % has multiple V2 mappings', entry_row.id;
    end if;

    select revision.memory_id
      into v2_memory_id
    from public.memory_v2_revisions revision
    where revision.metadata ->> 'legacy_source' = 'canonical_v1'
      and revision.metadata ->> 'legacy_memory_id' = entry_row.id::text
    limit 1;

    if v2_memory_id is null then
      v2_memory_id := extensions.gen_random_uuid();
      v2_current_revision_id := extensions.gen_random_uuid();
      insert into public.memory_v2_entries (
        id, owner_id, space_key, created_by_actor, current_revision_id,
        status, created_at, updated_at
      ) values (
        v2_memory_id, entry_row.owner_id, entry_row.space_key, entry_row.space_key,
        v2_current_revision_id, 'active', entry_row.created_at, entry_row.updated_at
      );
    else
      select pg_catalog.count(*) into marker_count
      from public.memory_v2_entries mapped
      where mapped.id = v2_memory_id
        and mapped.owner_id = entry_row.owner_id
        and mapped.space_key = entry_row.space_key
        and mapped.status = 'active'
        and mapped.shared_status is null
        and mapped.origin_revision_id is null;
      if marker_count <> 1 then
        raise exception 'canonical memory % existing V2 entry is incompatible', entry_row.id;
      end if;
      v2_current_revision_id := null;
    end if;

    for revision_row in
      select revision.*
      from public.memory_revisions revision
      where revision.memory_id = entry_row.id
      order by revision.revision_number
    loop
      select pg_catalog.count(*) into marker_count
      from public.memory_v2_revisions mapped
      where mapped.metadata ->> 'legacy_source' = 'canonical_v1'
        and mapped.metadata ->> 'legacy_revision_id' = revision_row.id::text;

      if marker_count > 1 then
        raise exception 'canonical revision % has multiple V2 mappings', revision_row.id;
      end if;

      select mapped.id into v2_revision_id
      from public.memory_v2_revisions mapped
      where mapped.metadata ->> 'legacy_source' = 'canonical_v1'
        and mapped.metadata ->> 'legacy_revision_id' = revision_row.id::text
      limit 1;

      metadata_value := pg_catalog.jsonb_build_object(
        'legacy_source', 'canonical_v1',
        'legacy_memory_id', entry_row.id,
        'legacy_revision_id', revision_row.id,
        'title', revision_row.title,
        'tags', pg_catalog.to_jsonb(revision_row.tags),
        'memory_type', revision_row.memory_type,
        'emotion', revision_row.emotion,
        'retention', revision_row.retention,
        'legacy_importance', revision_row.importance,
        'author', revision_row.author,
        'lifecycle_status', revision_row.lifecycle_status,
        'editor_actor', revision_row.editor_actor,
        'revision_reason', revision_row.revision_reason,
        'summary', revision_row.summary,
        'entry_source_type', entry_row.source_type,
        'entry_source_model', entry_row.source_model,
        'entry_source_ref', entry_row.source_ref,
        'entry_source_metadata', entry_row.source_metadata,
        'entry_created_by_actor', entry_row.created_by_actor,
        'entry_decay_score', entry_row.decay_score,
        'entry_decay_updated_at', entry_row.decay_updated_at,
        'entry_awaken_count', entry_row.awaken_count,
        'entry_last_awakened_at', entry_row.last_awakened_at,
        'entry_last_accessed_at', entry_row.last_accessed_at,
        'entry_created_at', entry_row.created_at,
        'entry_updated_at', entry_row.updated_at
      );

      if v2_revision_id is null then
        if revision_row.revision_number = entry_row.revision_number
          and v2_current_revision_id is not null
        then
          v2_revision_id := v2_current_revision_id;
        else
          v2_revision_id := extensions.gen_random_uuid();
        end if;

        insert into public.memory_v2_revisions (
          id, memory_id, revision_number, content, event_time,
          human_importance, ai_importance, metadata,
          created_by_actor, reason, created_at
        ) values (
          v2_revision_id, v2_memory_id, revision_row.revision_number,
          revision_row.content, null, null, null, metadata_value,
          entry_row.space_key, nullif(revision_row.revision_reason, ''),
          revision_row.created_at
        );
      else
        select pg_catalog.count(*) into marker_count
        from public.memory_v2_revisions mapped
        where mapped.id = v2_revision_id
          and mapped.memory_id = v2_memory_id
          and mapped.revision_number = revision_row.revision_number
          and mapped.content = revision_row.content
          and mapped.event_time is null
          and mapped.human_importance is null
          and mapped.ai_importance is null
          and mapped.metadata = metadata_value
          and mapped.created_at = revision_row.created_at;
        if marker_count <> 1 then
          raise exception 'canonical revision % existing V2 revision is incompatible', revision_row.id;
        end if;
      end if;

      if revision_row.revision_number = entry_row.revision_number then
        v2_current_revision_id := v2_revision_id;
      end if;
    end loop;

    if v2_current_revision_id is null then
      raise exception 'canonical memory % current V2 revision is missing', entry_row.id;
    end if;

    update public.memory_v2_entries
    set current_revision_id = v2_current_revision_id,
        updated_at = entry_row.updated_at
    where id = v2_memory_id;
  end loop;

  -- Every source referenced by a migrated private/approved Shared revision is
  -- copied once. source_channel and the canonical id remain in provenance.
  for source_row in
    select distinct source.*
    from public.memory_sources source
    join public.memory_revision_sources links on links.source_id = source.id
    join public.memory_entries entry on entry.id = links.memory_id
    where (
      entry.space_key in ('gpt', 'claude')
      and entry.lifecycle_status = 'active'
    ) or (
      entry.space_key = 'shared'
      and entry.shared_status = 'approved'
      and entry.lifecycle_status = 'active'
    )
    order by source.id
  loop
    select pg_catalog.count(*) into marker_count
    from public.memory_v2_sources mapped
    where mapped.provenance ->> 'legacy_source' = 'canonical_v1'
      and mapped.provenance ->> 'legacy_source_id' = source_row.id::text;

    if marker_count > 1 then
      raise exception 'canonical source % has multiple V2 mappings', source_row.id;
    end if;

    select mapped.id into v2_source_id
    from public.memory_v2_sources mapped
    where mapped.provenance ->> 'legacy_source' = 'canonical_v1'
      and mapped.provenance ->> 'legacy_source_id' = source_row.id::text
    limit 1;

    if v2_source_id is null then
      insert into public.memory_v2_sources (
        owner_id, space_key, source_kind, locator, quote_text,
        provenance, created_by_actor, created_at
      ) values (
        source_row.owner_id, source_row.source_space_key,
        source_row.source_kind, source_row.locator, source_row.quote_text,
        pg_catalog.jsonb_build_object(
          'legacy_source', 'canonical_v1',
          'legacy_source_id', source_row.id,
          'source_channel', source_row.source_channel
        ),
        source_row.created_by_actor, source_row.created_at
      ) returning id into v2_source_id;
    else
      select pg_catalog.count(*) into marker_count
      from public.memory_v2_sources mapped
      where mapped.id = v2_source_id
        and mapped.owner_id = source_row.owner_id
        and mapped.space_key = source_row.source_space_key
        and mapped.source_kind = source_row.source_kind
        and mapped.locator = source_row.locator
        and mapped.quote_text is not distinct from source_row.quote_text
        and mapped.created_by_actor = source_row.created_by_actor
        and mapped.created_at = source_row.created_at
        and mapped.provenance = pg_catalog.jsonb_build_object(
          'legacy_source', 'canonical_v1',
          'legacy_source_id', source_row.id,
          'source_channel', source_row.source_channel
        );
      if marker_count <> 1 then
        raise exception 'canonical source % existing V2 source is incompatible', source_row.id;
      end if;
    end if;
  end loop;

  -- Approved Shared is created only after its private source revision exists.
  -- Candidate Shared is intentionally absent from this selection.
  for entry_row in
    select shared.*
    from public.memory_entries shared
    join public.memory_entries private on private.id = shared.source_memory_id
    where shared.space_key = 'shared'
      and shared.shared_status = 'approved'
      and shared.lifecycle_status = 'active'
      and private.space_key in ('gpt', 'claude')
      and private.lifecycle_status = 'active'
    order by shared.id
  loop
    select mapped.id into v2_origin_revision_id
    from public.memory_v2_revisions mapped
    where mapped.metadata ->> 'legacy_source' = 'canonical_v1'
      and mapped.metadata ->> 'legacy_revision_id' = entry_row.source_revision_id::text
    limit 1;
    if v2_origin_revision_id is null then
      raise exception 'approved Shared % private origin revision is missing', entry_row.id;
    end if;

    select pg_catalog.count(distinct mapped.memory_id) into marker_count
    from public.memory_v2_revisions mapped
    where mapped.metadata ->> 'legacy_source' = 'canonical_v1'
      and mapped.metadata ->> 'legacy_memory_id' = entry_row.id::text;
    if marker_count > 1 then
      raise exception 'approved Shared % has multiple V2 mappings', entry_row.id;
    end if;

    select mapped.memory_id into v2_memory_id
    from public.memory_v2_revisions mapped
    where mapped.metadata ->> 'legacy_source' = 'canonical_v1'
      and mapped.metadata ->> 'legacy_memory_id' = entry_row.id::text
    limit 1;

    if v2_memory_id is null then
      v2_memory_id := extensions.gen_random_uuid();
      v2_current_revision_id := extensions.gen_random_uuid();
      insert into public.memory_v2_entries (
        id, owner_id, space_key, created_by_actor, current_revision_id,
        status, shared_status, origin_revision_id, created_at, updated_at
      ) values (
        v2_memory_id, entry_row.owner_id, 'shared', 'owner',
        v2_current_revision_id, 'active', 'approved', v2_origin_revision_id,
        entry_row.created_at, entry_row.updated_at
      );
    else
      select pg_catalog.count(*) into marker_count
      from public.memory_v2_entries mapped
      where mapped.id = v2_memory_id
        and mapped.owner_id = entry_row.owner_id
        and mapped.space_key = 'shared'
        and mapped.created_by_actor = 'owner'
        and mapped.status = 'active'
        and mapped.shared_status = 'approved'
        and mapped.origin_revision_id = v2_origin_revision_id;
      if marker_count <> 1 then
        raise exception 'approved Shared % existing V2 entry is incompatible', entry_row.id;
      end if;
      v2_current_revision_id := null;
    end if;

    for revision_row in
      select revision.*
      from public.memory_revisions revision
      where revision.memory_id = entry_row.id
      order by revision.revision_number
    loop
      select pg_catalog.count(*) into marker_count
      from public.memory_v2_revisions mapped
      where mapped.metadata ->> 'legacy_source' = 'canonical_v1'
        and mapped.metadata ->> 'legacy_revision_id' = revision_row.id::text;
      if marker_count > 1 then
        raise exception 'approved Shared revision % has multiple V2 mappings', revision_row.id;
      end if;

      select mapped.id into v2_revision_id
      from public.memory_v2_revisions mapped
      where mapped.metadata ->> 'legacy_source' = 'canonical_v1'
        and mapped.metadata ->> 'legacy_revision_id' = revision_row.id::text
      limit 1;

      metadata_value := pg_catalog.jsonb_build_object(
        'legacy_source', 'canonical_v1',
        'legacy_memory_id', entry_row.id,
        'legacy_revision_id', revision_row.id,
        'title', revision_row.title,
        'tags', pg_catalog.to_jsonb(revision_row.tags),
        'memory_type', revision_row.memory_type,
        'emotion', revision_row.emotion,
        'retention', revision_row.retention,
        'legacy_importance', revision_row.importance,
        'author', revision_row.author,
        'lifecycle_status', revision_row.lifecycle_status,
        'editor_actor', revision_row.editor_actor,
        'revision_reason', revision_row.revision_reason,
        'summary', revision_row.summary,
        'entry_source_type', entry_row.source_type,
        'entry_source_model', entry_row.source_model,
        'entry_source_ref', entry_row.source_ref,
        'entry_source_metadata', entry_row.source_metadata,
        'entry_created_by_actor', entry_row.created_by_actor,
        'entry_created_at', entry_row.created_at,
        'entry_updated_at', entry_row.updated_at,
        'approved_shared_source_memory_id', entry_row.source_memory_id,
        'approved_shared_source_revision_id', entry_row.source_revision_id
      );

      if v2_revision_id is null then
        if revision_row.revision_number = entry_row.revision_number
          and v2_current_revision_id is not null
        then
          v2_revision_id := v2_current_revision_id;
        else
          v2_revision_id := extensions.gen_random_uuid();
        end if;
        insert into public.memory_v2_revisions (
          id, memory_id, revision_number, content, event_time,
          human_importance, ai_importance, metadata,
          created_by_actor, reason, created_at
        ) values (
          v2_revision_id, v2_memory_id, revision_row.revision_number,
          revision_row.content, null, null, null, metadata_value,
          'owner', 'approved_shared_snapshot', revision_row.created_at
        );
      else
        select pg_catalog.count(*) into marker_count
        from public.memory_v2_revisions mapped
        where mapped.id = v2_revision_id
          and mapped.memory_id = v2_memory_id
          and mapped.revision_number = revision_row.revision_number
          and mapped.content = revision_row.content
          and mapped.event_time is null
          and mapped.human_importance is null
          and mapped.ai_importance is null
          and mapped.metadata = metadata_value
          and mapped.created_at = revision_row.created_at;
        if marker_count <> 1 then
          raise exception 'approved Shared revision % existing V2 revision is incompatible', revision_row.id;
        end if;
      end if;

      if revision_row.revision_number = entry_row.revision_number then
        v2_current_revision_id := v2_revision_id;
      end if;
    end loop;

    if v2_current_revision_id is null then
      raise exception 'approved Shared % current V2 revision is missing', entry_row.id;
    end if;

    update public.memory_v2_entries
    set current_revision_id = v2_current_revision_id,
        origin_revision_id = v2_origin_revision_id,
        updated_at = entry_row.updated_at
    where id = v2_memory_id;
  end loop;

  -- Recreate only links whose canonical revisions were migrated. The one link
  -- owned by candidate Shared remains solely in canonical V1 by design.
  for link_row in
    select links.*
    from public.memory_revision_sources links
    join public.memory_entries entry on entry.id = links.memory_id
    where (
      entry.space_key in ('gpt', 'claude')
      and entry.lifecycle_status = 'active'
    ) or (
      entry.space_key = 'shared'
      and entry.shared_status = 'approved'
      and entry.lifecycle_status = 'active'
    )
    order by links.id
  loop
    select mapped.id into v2_revision_id
    from public.memory_v2_revisions mapped
    where mapped.metadata ->> 'legacy_source' = 'canonical_v1'
      and mapped.metadata ->> 'legacy_revision_id' = link_row.revision_id::text
    limit 1;
    select mapped.id into v2_source_id
    from public.memory_v2_sources mapped
    where mapped.provenance ->> 'legacy_source' = 'canonical_v1'
      and mapped.provenance ->> 'legacy_source_id' = link_row.source_id::text
    limit 1;

    if v2_revision_id is null or v2_source_id is null then
      raise exception 'canonical link % mapping is incomplete', link_row.id;
    end if;

    if exists (
      select 1 from public.memory_v2_revision_sources mapped
      where mapped.revision_id = v2_revision_id and mapped.source_id = v2_source_id
    ) then
      if not exists (
        select 1 from public.memory_v2_revision_sources mapped
        where mapped.revision_id = v2_revision_id
          and mapped.source_id = v2_source_id
          and mapped.ordinal = link_row.ordinal
          and mapped.created_at = link_row.created_at
      ) then
        raise exception 'canonical link % existing V2 link is incompatible', link_row.id;
      end if;
    else
      insert into public.memory_v2_revision_sources (
        revision_id, source_id, ordinal, created_at
      ) values (
        v2_revision_id, v2_source_id, link_row.ordinal, link_row.created_at
      );
    end if;
  end loop;
end;
$phase2b$;

with target_private as (
  select entry.id
  from public.memory_entries entry
  where entry.space_key in ('gpt', 'claude')
    and entry.lifecycle_status = 'active'
),
target_shared as (
  select shared.id
  from public.memory_entries shared
  join target_private private on private.id = shared.source_memory_id
  where shared.space_key = 'shared'
    and shared.shared_status = 'approved'
    and shared.lifecycle_status = 'active'
),
target_entries as (
  select id from target_private
  union all
  select id from target_shared
),
target_revisions as (
  select revision.id
  from public.memory_revisions revision
  join target_entries entry on entry.id = revision.memory_id
),
target_sources as (
  select distinct source.id
  from public.memory_sources source
  join public.memory_revision_sources links on links.source_id = source.id
  join target_entries entry on entry.id = links.memory_id
),
target_links as (
  select links.id, links.revision_id, links.source_id
  from public.memory_revision_sources links
  join target_entries entry on entry.id = links.memory_id
),
source_identity as (
  select pg_catalog.md5(pg_catalog.concat_ws('|',
    coalesce((select pg_catalog.md5(pg_catalog.jsonb_agg(pg_catalog.to_jsonb(e) order by e.id)::text)
      from public.memory_entries e), 'empty'),
    coalesce((select pg_catalog.md5(pg_catalog.jsonb_agg(pg_catalog.to_jsonb(r) order by r.id)::text)
      from public.memory_revisions r), 'empty'),
    coalesce((select pg_catalog.md5(pg_catalog.jsonb_agg(pg_catalog.to_jsonb(s) order by s.id)::text)
      from public.memory_sources s), 'empty'),
    coalesce((select pg_catalog.md5(pg_catalog.jsonb_agg(pg_catalog.to_jsonb(l) order by l.id)::text)
      from public.memory_revision_sources l), 'empty')
  )) as fingerprint
)
select pg_catalog.jsonb_build_object(
  'apply_mode', coalesce(
    pg_catalog.current_setting('lovehouse.memory_v2_phase2b_apply', true),
    'false'
  ) = 'true',
  'source_fingerprint', (select fingerprint from source_identity),
  'target', pg_catalog.jsonb_build_object(
    'gpt_private', (select pg_catalog.count(*) from public.memory_entries entry join target_private target on target.id = entry.id where entry.space_key = 'gpt'),
    'claude_private', (select pg_catalog.count(*) from public.memory_entries entry join target_private target on target.id = entry.id where entry.space_key = 'claude'),
    'approved_shared', (select pg_catalog.count(*) from target_shared),
    'revisions', (select pg_catalog.count(*) from target_revisions),
    'sources', (select pg_catalog.count(*) from target_sources),
    'links', (select pg_catalog.count(*) from target_links)
  ),
  'skipped_candidate_shared', pg_catalog.jsonb_build_object(
    'entries', (select pg_catalog.count(*) from public.memory_entries where space_key = 'shared' and shared_status = 'candidate'),
    'revisions', (select pg_catalog.count(*) from public.memory_revisions revision join public.memory_entries entry on entry.id = revision.memory_id where entry.space_key = 'shared' and entry.shared_status = 'candidate'),
    'links', (select pg_catalog.count(*) from public.memory_revision_sources links join public.memory_entries entry on entry.id = links.memory_id where entry.space_key = 'shared' and entry.shared_status = 'candidate')
  ),
  'already_present', pg_catalog.jsonb_build_object(
    'entries', (select pg_catalog.count(distinct revision.memory_id) from public.memory_v2_revisions revision where revision.metadata ->> 'legacy_source' = 'canonical_v1'),
    'revisions', (select pg_catalog.count(*) from public.memory_v2_revisions revision where revision.metadata ->> 'legacy_source' = 'canonical_v1'),
    'sources', (select pg_catalog.count(*) from public.memory_v2_sources source where source.provenance ->> 'legacy_source' = 'canonical_v1'),
    'links', (
      select pg_catalog.count(*)
      from target_links link
      join public.memory_v2_revisions revision
        on revision.metadata ->> 'legacy_source' = 'canonical_v1'
        and revision.metadata ->> 'legacy_revision_id' = link.revision_id::text
      join public.memory_v2_sources source
        on source.provenance ->> 'legacy_source' = 'canonical_v1'
        and source.provenance ->> 'legacy_source_id' = link.source_id::text
      join public.memory_v2_revision_sources mapped
        on mapped.revision_id = revision.id and mapped.source_id = source.id
    )
  ),
  'phase2a_smoke_unchanged', pg_catalog.jsonb_build_object(
    'entries', (select pg_catalog.count(distinct revision.memory_id) from public.memory_v2_revisions revision where revision.content like 'PHASE2A\_SMOKE\_%' escape '\'),
    'revisions', (select pg_catalog.count(*) from public.memory_v2_revisions revision where revision.content like 'PHASE2A\_SMOKE\_%' escape '\')
  )
) as phase2b_report;
