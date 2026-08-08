begin;

drop function if exists public.memory_runtime_audit_claude(uuid, uuid, text, bigint, text, text, text, integer, text[]);
drop function if exists public.memory_runtime_propose_shared_claude(uuid, uuid, bigint, text);
drop function if exists public.memory_runtime_revise_claude(uuid, uuid, bigint, jsonb, text);
drop function if exists public.memory_runtime_remember_claude(uuid, uuid, jsonb);
drop function if exists public.memory_runtime_recall_claude(uuid, uuid, text, integer, bigint, text[]);
drop function if exists public.memory_runtime_list_claude(uuid, uuid, integer, bigint, text, text[], text);
drop function if exists public.memory_runtime_get_claude(uuid, uuid, bigint);
drop function if exists public.memory_runtime_audit_gpt(uuid, uuid, text, bigint, text, text, text, integer, text[]);
drop function if exists public.memory_runtime_propose_shared_gpt(uuid, uuid, bigint, text);
drop function if exists public.memory_runtime_revise_gpt(uuid, uuid, bigint, jsonb, text);
drop function if exists public.memory_runtime_remember_gpt(uuid, uuid, jsonb);
drop function if exists public.memory_runtime_recall_gpt(uuid, uuid, text, integer, bigint, text[]);
drop function if exists public.memory_runtime_list_gpt(uuid, uuid, integer, bigint, text, text[], text);
drop function if exists public.memory_runtime_get_gpt(uuid, uuid, bigint);

drop function if exists public.memory_runtime_internal_external_audit(text, uuid, uuid, text, bigint, text, text, text, integer, text[]);
drop function if exists public.memory_runtime_internal_propose_shared(text, uuid, uuid, bigint, text);
drop function if exists public.memory_runtime_internal_revise(text, uuid, uuid, bigint, jsonb, text);
drop function if exists public.memory_runtime_internal_remember(text, uuid, uuid, jsonb);
drop function if exists public.memory_runtime_internal_recall(text, uuid, uuid, text, integer, bigint, text[]);
drop function if exists public.memory_runtime_internal_list(text, uuid, uuid, integer, bigint, text, text[], text);
drop function if exists public.memory_runtime_internal_get(text, uuid, uuid, bigint);
drop function if exists public.memory_runtime_internal_audit(uuid, text, text, bigint, text, text, text, uuid, integer, text[]);

-- Restore the exact Phase 2 service-role surface for isolated rollback tests.
grant select, insert, update, delete on table public.memory_entries to service_role;
grant select on table public.memory_revisions to service_role;
grant select, insert on table public.memory_provenance to service_role;
grant select, insert on table public.memory_shared_transitions to service_role;
grant select, insert on table public.memory_audit_log to service_role;
grant select, insert, update on table public.memory_mutation_idempotency to service_role;
grant select, insert, update, delete on table public.memory_ingest_candidates to service_role;
grant usage, select on sequence public.memory_entries_id_seq to service_role;
grant usage, select on sequence public.memory_provenance_id_seq to service_role;
grant usage, select on sequence public.memory_shared_transitions_id_seq to service_role;
grant usage, select on sequence public.memory_audit_log_id_seq to service_role;
grant usage, select on sequence public.memory_mutation_idempotency_id_seq to service_role;
grant usage, select on sequence public.memory_ingest_candidates_id_seq to service_role;

grant execute on function public.memory_get_gpt(uuid, bigint) to service_role;
grant execute on function public.memory_get_claude(uuid, bigint) to service_role;
grant execute on function public.memory_list_gpt(uuid, integer, text, text[], text) to service_role;
grant execute on function public.memory_list_claude(uuid, integer, text, text[], text) to service_role;
grant execute on function public.memory_recall_gpt(uuid, text, integer, text[]) to service_role;
grant execute on function public.memory_recall_claude(uuid, text, integer, text[]) to service_role;
grant execute on function public.memory_hash_jsonb(jsonb) to service_role;
grant execute on function public.memory_compute_revision_hash(bigint) to service_role;
grant execute on function public.memory_claim_idempotency(uuid, text, text, uuid, jsonb) to service_role;
grant execute on function public.memory_curator_create_shared_candidate(uuid, bigint, bigint, text) to service_role;

commit;
