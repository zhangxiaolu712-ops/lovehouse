begin;

drop trigger if exists memory_entries_enqueue_approved_shared on public.memory_entries;
drop trigger if exists memory_revisions_enqueue_embedding on public.memory_revisions;
drop trigger if exists memory_ranking_profiles_immutable on public.memory_ranking_profiles;

drop function if exists public.memory_behavior_recall_gpt(uuid, uuid, text, real[], text, text, text, integer, bigint, text[]);
drop function if exists public.memory_behavior_recall_claude(uuid, uuid, text, real[], text, text, text, integer, bigint, text[]);
drop function if exists public.memory_behavior_claim_embeddings_gpt(uuid, uuid, integer);
drop function if exists public.memory_behavior_claim_embeddings_claude(uuid, uuid, integer);
drop function if exists public.memory_behavior_complete_embedding_gpt(uuid, uuid, bigint, real[]);
drop function if exists public.memory_behavior_complete_embedding_claude(uuid, uuid, bigint, real[]);
drop function if exists public.memory_behavior_fail_embedding_gpt(uuid, uuid, bigint, text);
drop function if exists public.memory_behavior_fail_embedding_claude(uuid, uuid, bigint, text);

drop function if exists public.memory_behavior_internal_recall(text, uuid, uuid, text, real[], text, text, text, integer, bigint, text[]);
drop function if exists public.memory_behavior_internal_fail_embedding(text, uuid, uuid, bigint, text);
drop function if exists public.memory_behavior_internal_complete_embedding(text, uuid, uuid, bigint, real[]);
drop function if exists public.memory_behavior_internal_claim_embeddings(text, uuid, uuid, integer);
drop function if exists public.memory_behavior_internal_audit(uuid, text, text, bigint, text, text, text, uuid, integer, text[], jsonb);
drop function if exists public.memory_behavior_enqueue_approved_shared();
drop function if exists public.memory_behavior_enqueue_revision();
drop function if exists public.memory_behavior_embedding_input(text, text, text[], text);
drop function if exists public.memory_behavior_reject_profile_change();

drop table if exists public.memory_embeddings;
drop table if exists public.memory_ranking_profiles;

-- The vector extension is intentionally retained: it may be shared by another
-- component and its presence contains no Phase 4A data or behavior.

commit;
