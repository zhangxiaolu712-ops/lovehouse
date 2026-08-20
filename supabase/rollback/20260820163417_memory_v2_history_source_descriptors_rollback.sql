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

revoke all on function public.memory_v2_history(uuid, text, uuid) from public, anon, authenticated;
grant execute on function public.memory_v2_history(uuid, text, uuid) to service_role;

commit;
