begin;

-- Keep the existing function identity and every validation unchanged. The
-- only delta is that either fixed actor may materialize the exact revision of
-- an already-approved Shared memory.
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
  if length(btrim(coalesce(p_model, ''))) not between 1 and 120 then
    raise exception 'embedding model is required' using errcode = '22023';
  end if;
  if not exists (
    select 1
    from public.memory_v2_revisions r
    join public.memory_v2_entries e on e.id = r.memory_id
    where r.id = p_revision_id
      and e.owner_id = p_owner_id
      and (
        e.space_key = p_actor
        or (e.space_key = 'shared' and e.shared_status = 'approved')
      )
  ) then
    raise exception 'revision is outside the private namespace or approved Shared' using errcode = '42501';
  end if;

  insert into public.memory_v2_embeddings (revision_id, model, embedding)
  values (p_revision_id, btrim(p_model), p_embedding::extensions.vector)
  on conflict (revision_id) do update
  set model = excluded.model, embedding = excluded.embedding, updated_at = now();
end;
$$;

commit;
