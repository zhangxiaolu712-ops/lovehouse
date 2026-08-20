begin;

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
  select coalesce(
    jsonb_agg(
      to_jsonb(r) || jsonb_build_object(
        'sources', coalesce((
          select jsonb_agg(
            bounded_source.descriptor
            order by bounded_source.ordinal, bounded_source.source_id
          )
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
            order by links.ordinal, source.id
            limit 101
          ) bounded_source
        ), '[]'::jsonb)
      )
      order by r.revision_number
    ),
    '[]'::jsonb
  )
  from public.memory_v2_revisions r
  join public.memory_v2_entries e on e.id = r.memory_id
  where e.id = p_memory_id and e.owner_id = p_owner_id
    and p_actor in ('gpt', 'claude')
    and (
      e.space_key = p_actor
      or (e.space_key = 'shared' and e.shared_status = 'approved')
    );
$$;

revoke all on function public.memory_v2_history(uuid, text, uuid) from public, anon, authenticated;
grant execute on function public.memory_v2_history(uuid, text, uuid) to service_role;

commit;
