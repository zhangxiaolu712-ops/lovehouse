-- Applied to Supabase on 2026-07-27.
-- Owner-only RLS for LoveHouse content tables.
-- toy_commands is intentionally excluded: its existing ADB control policy is unchanged.

begin;

alter table public.memories enable row level security;
drop policy if exists lovehouse_owner_only on public.memories;
create policy lovehouse_owner_only
  on public.memories for all to authenticated
  using ((select auth.uid()) = 'df12b754-ea94-4289-974e-82ed506ae260'::uuid)
  with check ((select auth.uid()) = 'df12b754-ea94-4289-974e-82ed506ae260'::uuid);

alter table public.diary enable row level security;
drop policy if exists lovehouse_owner_only on public.diary;
create policy lovehouse_owner_only
  on public.diary for all to authenticated
  using ((select auth.uid()) = 'df12b754-ea94-4289-974e-82ed506ae260'::uuid)
  with check ((select auth.uid()) = 'df12b754-ea94-4289-974e-82ed506ae260'::uuid);

alter table public.quotes enable row level security;
drop policy if exists lovehouse_owner_only on public.quotes;
create policy lovehouse_owner_only
  on public.quotes for all to authenticated
  using ((select auth.uid()) = 'df12b754-ea94-4289-974e-82ed506ae260'::uuid)
  with check ((select auth.uid()) = 'df12b754-ea94-4289-974e-82ed506ae260'::uuid);

alter table public.mood_log enable row level security;
drop policy if exists lovehouse_owner_only on public.mood_log;
create policy lovehouse_owner_only
  on public.mood_log for all to authenticated
  using ((select auth.uid()) = 'df12b754-ea94-4289-974e-82ed506ae260'::uuid)
  with check ((select auth.uid()) = 'df12b754-ea94-4289-974e-82ed506ae260'::uuid);

alter table public.todo enable row level security;
drop policy if exists lovehouse_owner_only on public.todo;
create policy lovehouse_owner_only
  on public.todo for all to authenticated
  using ((select auth.uid()) = 'df12b754-ea94-4289-974e-82ed506ae260'::uuid)
  with check ((select auth.uid()) = 'df12b754-ea94-4289-974e-82ed506ae260'::uuid);

alter table public.stream enable row level security;
drop policy if exists lovehouse_owner_only on public.stream;
create policy lovehouse_owner_only
  on public.stream for all to authenticated
  using ((select auth.uid()) = 'df12b754-ea94-4289-974e-82ed506ae260'::uuid)
  with check ((select auth.uid()) = 'df12b754-ea94-4289-974e-82ed506ae260'::uuid);

alter table public.notes enable row level security;
drop policy if exists lovehouse_owner_only on public.notes;
create policy lovehouse_owner_only
  on public.notes for all to authenticated
  using ((select auth.uid()) = 'df12b754-ea94-4289-974e-82ed506ae260'::uuid)
  with check ((select auth.uid()) = 'df12b754-ea94-4289-974e-82ed506ae260'::uuid);

alter table public.codebook enable row level security;
drop policy if exists lovehouse_owner_only on public.codebook;
create policy lovehouse_owner_only
  on public.codebook for all to authenticated
  using ((select auth.uid()) = 'df12b754-ea94-4289-974e-82ed506ae260'::uuid)
  with check ((select auth.uid()) = 'df12b754-ea94-4289-974e-82ed506ae260'::uuid);

alter table public.timeline enable row level security;
drop policy if exists lovehouse_owner_only on public.timeline;
create policy lovehouse_owner_only
  on public.timeline for all to authenticated
  using ((select auth.uid()) = 'df12b754-ea94-4289-974e-82ed506ae260'::uuid)
  with check ((select auth.uid()) = 'df12b754-ea94-4289-974e-82ed506ae260'::uuid);

alter table public.api_config enable row level security;
drop policy if exists lovehouse_owner_only on public.api_config;
create policy lovehouse_owner_only
  on public.api_config for all to authenticated
  using ((select auth.uid()) = 'df12b754-ea94-4289-974e-82ed506ae260'::uuid)
  with check ((select auth.uid()) = 'df12b754-ea94-4289-974e-82ed506ae260'::uuid);

commit;
