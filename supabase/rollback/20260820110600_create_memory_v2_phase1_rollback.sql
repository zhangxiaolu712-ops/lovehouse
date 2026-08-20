begin;

drop function if exists public.memory_v2_expand_source(uuid, text, uuid);
drop function if exists public.memory_v2_history(uuid, text, uuid);
drop function if exists public.memory_v2_record_recall(uuid, text, uuid[], timestamptz);
drop function if exists public.memory_v2_store_embedding(uuid, text, uuid, text, real[]);
drop function if exists public.memory_v2_recall_semantic(uuid, text, real[], text, integer);
drop function if exists public.memory_v2_starter_pack_candidates(uuid, text);
drop function if exists public.memory_v2_recall_lexical(uuid, text, text, integer);
drop function if exists public.memory_v2_approve_shared(uuid, uuid);
drop function if exists public.memory_v2_revise(uuid, text, uuid, text, jsonb);
drop function if exists public.memory_v2_remember(uuid, text, text, jsonb);
drop function if exists public.memory_v2_materialize_sources(uuid, text, text, uuid, jsonb);

drop table if exists public.memory_v2_embeddings;
drop table if exists public.memory_v2_revision_sources;
drop table if exists public.memory_v2_sources;
alter table if exists public.memory_v2_entries
  drop constraint if exists memory_v2_entries_current_revision_fk,
  drop constraint if exists memory_v2_entries_origin_revision_fk;
alter table if exists public.memory_v2_revisions
  drop constraint if exists memory_v2_revisions_memory_id_fkey;
drop table if exists public.memory_v2_revisions;
drop table if exists public.memory_v2_entries;

commit;
