begin;

do $$
begin
  if exists (
    select 1 from public.memory_v2_entries where space_key = 'engineering'
  ) then
    raise exception 'rollback refused: Engineering Memory data exists';
  end if;
end;
$$;

drop function if exists public.memory_v2_engineering_restore(uuid, text, text);
drop function if exists public.memory_v2_engineering_archive(uuid, text, text);
drop function if exists public.memory_v2_engineering_expand_source(uuid, text, uuid);
drop function if exists public.memory_v2_engineering_open(uuid, text, text);
drop function if exists public.memory_v2_engineering_recall(uuid, text, text, integer, boolean);
drop function if exists public.memory_v2_engineering_upsert(uuid, text, text, text, jsonb);

drop index if exists public.memory_v2_entries_engineering_subject_unique;

alter table public.memory_v2_sources
  drop constraint memory_v2_sources_actor_check,
  drop constraint memory_v2_sources_created_by_actor_check,
  drop constraint memory_v2_sources_space_key_check,
  add constraint memory_v2_sources_space_key_check
    check (space_key in ('gpt', 'claude')),
  add constraint memory_v2_sources_created_by_actor_check
    check (created_by_actor in ('gpt', 'claude', 'owner')),
  add constraint memory_v2_sources_actor_check check (
    (space_key = 'gpt' and created_by_actor in ('gpt', 'owner'))
    or (space_key = 'claude' and created_by_actor in ('claude', 'owner'))
  );

alter table public.memory_v2_revisions
  drop constraint memory_v2_revisions_created_by_actor_check,
  add constraint memory_v2_revisions_created_by_actor_check
    check (created_by_actor in ('gpt', 'claude', 'owner'));

alter table public.memory_v2_entries
  drop constraint memory_v2_entries_subject_key_check,
  drop constraint memory_v2_entries_space_actor_check,
  drop constraint memory_v2_entries_created_by_actor_check,
  drop constraint memory_v2_entries_space_key_check,
  drop column subject_key,
  add constraint memory_v2_entries_space_key_check
    check (space_key in ('gpt', 'claude', 'shared')),
  add constraint memory_v2_entries_created_by_actor_check
    check (created_by_actor in ('gpt', 'claude', 'owner')),
  add constraint memory_v2_entries_space_actor_check check (
    (space_key = 'gpt' and created_by_actor in ('gpt', 'owner'))
    or (space_key = 'claude' and created_by_actor in ('claude', 'owner'))
    or (space_key = 'shared' and created_by_actor = 'owner')
  );

commit;
