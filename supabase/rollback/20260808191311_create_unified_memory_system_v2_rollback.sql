\set ON_ERROR_STOP on

begin;

drop function if exists public.memory_recall_claude(uuid, text, integer, text[]);
drop function if exists public.memory_recall_gpt(uuid, text, integer, text[]);
drop function if exists public.memory_list_claude(uuid, integer, text, text[], text);
drop function if exists public.memory_list_gpt(uuid, integer, text, text[], text);
drop function if exists public.memory_get_claude(uuid, bigint);
drop function if exists public.memory_get_gpt(uuid, bigint);

drop table if exists public.memory_ingest_candidates;
drop table if exists public.memory_audit_log;
drop table if exists public.memory_shared_transitions;
drop table if exists public.memory_provenance;
drop table if exists public.memory_revisions;
drop table if exists public.memory_entries;
drop table if exists public.memory_type_catalog;
drop table if exists public.memory_space_catalog;

drop function if exists public.memory_capture_entry_history();
drop function if exists public.memory_prepare_entry_write();
drop function if exists public.memory_reject_append_only_change();

commit;
