begin;

revoke execute on function public.memory_behavior_set_anchor_gpt(uuid, uuid, bigint, boolean, text) from service_role;
revoke execute on function public.memory_behavior_set_anchor_claude(uuid, uuid, bigint, boolean, text) from service_role;
revoke execute on function public.memory_behavior_list_anchors_gpt(uuid) from service_role;
revoke execute on function public.memory_behavior_list_anchors_claude(uuid) from service_role;
revoke execute on function public.memory_behavior_enqueue_dream_gpt(uuid, uuid, text, integer) from service_role;
revoke execute on function public.memory_behavior_enqueue_dream_claude(uuid, uuid, text, integer) from service_role;
revoke execute on function public.memory_behavior_claim_dream_gpt(uuid, uuid, text, text) from service_role;
revoke execute on function public.memory_behavior_claim_dream_claude(uuid, uuid, text, text) from service_role;
revoke execute on function public.memory_behavior_complete_dream_gpt(uuid, uuid, bigint, text, text, jsonb) from service_role;
revoke execute on function public.memory_behavior_complete_dream_claude(uuid, uuid, bigint, text, text, jsonb) from service_role;
revoke execute on function public.memory_behavior_fail_dream_gpt(uuid, uuid, bigint, text, text, text) from service_role;
revoke execute on function public.memory_behavior_fail_dream_claude(uuid, uuid, bigint, text, text, text) from service_role;

drop function if exists public.memory_behavior_set_anchor_gpt(uuid, uuid, bigint, boolean, text);
drop function if exists public.memory_behavior_set_anchor_claude(uuid, uuid, bigint, boolean, text);
drop function if exists public.memory_behavior_list_anchors_gpt(uuid);
drop function if exists public.memory_behavior_list_anchors_claude(uuid);
drop function if exists public.memory_behavior_enqueue_dream_gpt(uuid, uuid, text, integer);
drop function if exists public.memory_behavior_enqueue_dream_claude(uuid, uuid, text, integer);
drop function if exists public.memory_behavior_claim_dream_gpt(uuid, uuid, text, text);
drop function if exists public.memory_behavior_claim_dream_claude(uuid, uuid, text, text);
drop function if exists public.memory_behavior_complete_dream_gpt(uuid, uuid, bigint, text, text, jsonb);
drop function if exists public.memory_behavior_complete_dream_claude(uuid, uuid, bigint, text, text, jsonb);
drop function if exists public.memory_behavior_fail_dream_gpt(uuid, uuid, bigint, text, text, text);
drop function if exists public.memory_behavior_fail_dream_claude(uuid, uuid, bigint, text, text, text);

drop function if exists public.memory_behavior_internal_set_anchor(text, uuid, uuid, bigint, boolean, text);
drop function if exists public.memory_behavior_internal_list_anchors(text, uuid);
drop function if exists public.memory_behavior_internal_enqueue_dream(text, uuid, uuid, text, integer);
drop function if exists public.memory_behavior_internal_claim_dream(text, uuid, uuid, text, text);
drop function if exists public.memory_behavior_internal_complete_dream(text, uuid, uuid, bigint, text, text, jsonb);
drop function if exists public.memory_behavior_internal_fail_dream(text, uuid, uuid, bigint, text, text, text);

drop trigger if exists memory_anchor_guard_update on public.memory_anchor_records;
drop trigger if exists memory_anchor_reject_delete on public.memory_anchor_records;
drop trigger if exists memory_dream_sources_append_only on public.memory_dream_job_sources;
drop trigger if exists memory_candidate_sources_append_only on public.memory_ingest_candidate_sources;
drop function if exists public.memory_behavior_guard_anchor_update();

drop table if exists public.memory_ingest_candidate_sources;

-- Phase 4B Dream outputs are pending suggestions only. Removing them during a
-- rollback cannot remove or alter canonical memory/revision rows.
delete from public.memory_ingest_candidates where dream_job_id is not null;
drop index if exists public.memory_ingest_candidate_dream_output_idx;
alter table public.memory_ingest_candidates
  drop constraint if exists memory_ingest_candidate_target_revision_fk,
  drop constraint if exists memory_ingest_candidate_dream_identity_check,
  drop constraint if exists memory_ingest_candidate_revision_target_check,
  drop column if exists dream_job_id,
  drop column if exists dream_output_key,
  drop column if exists proposal_kind,
  drop column if exists target_memory_id,
  drop column if exists target_revision_id,
  drop column if exists target_revision_hash,
  drop column if exists curator_provider,
  drop column if exists curator_model,
  drop column if exists perspective;

drop table if exists public.memory_dream_job_sources;
drop table if exists public.memory_dream_jobs;
drop table if exists public.memory_anchor_records;
drop index if exists public.memory_entries_dream_recent_idx;

commit;
