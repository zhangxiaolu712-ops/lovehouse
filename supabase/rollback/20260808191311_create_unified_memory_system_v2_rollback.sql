\set ON_ERROR_STOP on

begin;

drop function if exists public.memory_recall_claude(uuid, text, integer, text[]);
drop function if exists public.memory_recall_gpt(uuid, text, integer, text[]);
drop function if exists public.memory_list_claude(uuid, integer, text, text[], text);
drop function if exists public.memory_list_gpt(uuid, integer, text, text[], text);
drop function if exists public.memory_get_claude(uuid, bigint);
drop function if exists public.memory_get_gpt(uuid, bigint);
drop function if exists public.memory_claim_idempotency(uuid, text, text, uuid, jsonb);

drop trigger if exists memory_idempotency_prepare on public.memory_mutation_idempotency;
drop function if exists public.memory_prepare_idempotency();

drop trigger if exists memory_entries_capture_history on public.memory_entries;
drop trigger if exists memory_entries_prepare_write on public.memory_entries;
drop trigger if exists memory_revisions_validate_insert on public.memory_revisions;
drop function if exists public.memory_capture_entry_history();
drop function if exists public.memory_prepare_entry_write();
drop function if exists public.memory_validate_revision_insert();
drop function if exists public.memory_compute_revision_hash(bigint);

alter table if exists public.memory_entries
  drop constraint if exists memory_entries_source_revision_fk;

drop table if exists public.memory_ingest_candidates;
drop table if exists public.memory_mutation_idempotency;
drop table if exists public.memory_audit_log;
drop table if exists public.memory_shared_transitions;
drop table if exists public.memory_provenance;
drop table if exists public.memory_revisions;
drop table if exists public.memory_entries;
drop table if exists public.memory_type_catalog;
drop table if exists public.memory_space_catalog;

drop function if exists public.memory_reject_append_only_change();
drop function if exists public.memory_hash_jsonb(jsonb);

commit;
