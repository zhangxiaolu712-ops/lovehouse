begin;

-- Applied only after the Memory V2 frontend and Owner Client API pass production read-back.
revoke all on table public.brain from anon, authenticated;
revoke all on table public.memories from anon, authenticated;
revoke all on table public.memory_entries from anon, authenticated;

comment on table public.brain is 'ARCHIVED/FROZEN 2026-08-26: migrated to Claude Memory V2; no production reader/writer; retain temporarily for rollback evidence only.';
comment on table public.memories is 'ARCHIVED/FROZEN 2026-08-26: migrated to Claude Memory V2; no production reader/writer; retain temporarily for rollback evidence only.';
comment on table public.memory_entries is 'ARCHIVED/FROZEN 2026-08-26: canonical V1 retired from active code; retain temporarily pending final deletion review.';

commit;
