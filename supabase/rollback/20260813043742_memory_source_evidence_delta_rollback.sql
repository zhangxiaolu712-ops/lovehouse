begin;

drop function if exists public.memory_runtime_expand_source_gpt(uuid,uuid,bigint);
drop function if exists public.memory_runtime_expand_source_claude(uuid,uuid,bigint);

create or replace function public.memory_runtime_list_gpt(p_owner_id uuid,p_request_id uuid,p_limit integer,p_cursor_id bigint,p_memory_type text,p_tags text[],p_retention text)
returns jsonb language sql security definer set search_path='' as $$ select public.memory_runtime_internal_list('gpt',p_owner_id,p_request_id,p_limit,p_cursor_id,p_memory_type,p_tags,p_retention) $$;
create or replace function public.memory_runtime_list_claude(p_owner_id uuid,p_request_id uuid,p_limit integer,p_cursor_id bigint,p_memory_type text,p_tags text[],p_retention text)
returns jsonb language sql security definer set search_path='' as $$ select public.memory_runtime_internal_list('claude',p_owner_id,p_request_id,p_limit,p_cursor_id,p_memory_type,p_tags,p_retention) $$;
create or replace function public.memory_runtime_recall_gpt(p_owner_id uuid,p_request_id uuid,p_query text,p_limit integer,p_cursor_id bigint,p_tags text[])
returns jsonb language sql security definer set search_path='' as $$ select public.memory_runtime_internal_recall('gpt',p_owner_id,p_request_id,p_query,p_limit,p_cursor_id,p_tags) $$;
create or replace function public.memory_runtime_recall_claude(p_owner_id uuid,p_request_id uuid,p_query text,p_limit integer,p_cursor_id bigint,p_tags text[])
returns jsonb language sql security definer set search_path='' as $$ select public.memory_runtime_internal_recall('claude',p_owner_id,p_request_id,p_query,p_limit,p_cursor_id,p_tags) $$;
create or replace function public.memory_runtime_remember_gpt(p_owner_id uuid,p_request_id uuid,p_memory jsonb)
returns jsonb language sql security definer set search_path='' as $$ select public.memory_runtime_internal_remember('gpt',p_owner_id,p_request_id,p_memory) $$;
create or replace function public.memory_runtime_remember_claude(p_owner_id uuid,p_request_id uuid,p_memory jsonb)
returns jsonb language sql security definer set search_path='' as $$ select public.memory_runtime_internal_remember('claude',p_owner_id,p_request_id,p_memory) $$;
create or replace function public.memory_runtime_revise_gpt(p_owner_id uuid,p_request_id uuid,p_memory_id bigint,p_patch jsonb,p_reason text)
returns jsonb language sql security definer set search_path='' as $$ select public.memory_runtime_internal_revise('gpt',p_owner_id,p_request_id,p_memory_id,p_patch,p_reason) $$;
create or replace function public.memory_runtime_revise_claude(p_owner_id uuid,p_request_id uuid,p_memory_id bigint,p_patch jsonb,p_reason text)
returns jsonb language sql security definer set search_path='' as $$ select public.memory_runtime_internal_revise('claude',p_owner_id,p_request_id,p_memory_id,p_patch,p_reason) $$;
create or replace function public.memory_runtime_memory_box_gpt(p_owner_id uuid,p_request_id uuid,p_limit integer)
returns jsonb language sql security definer set search_path='' as $$ select public.memory_runtime_internal_memory_box('gpt',p_owner_id,p_request_id,p_limit) $$;
create or replace function public.memory_runtime_memory_box_claude(p_owner_id uuid,p_request_id uuid,p_limit integer)
returns jsonb language sql security definer set search_path='' as $$ select public.memory_runtime_internal_memory_box('claude',p_owner_id,p_request_id,p_limit) $$;
create or replace function public.memory_behavior_recall_gpt(p_owner_id uuid,p_request_id uuid,p_query text,p_query_embedding real[],p_query_embedding_profile text,p_query_embedding_model text,p_ranking_profile text,p_limit integer,p_cursor_id bigint,p_tags text[])
returns jsonb language sql security definer set search_path='' as $$ select public.memory_behavior_internal_recall('gpt',p_owner_id,p_request_id,p_query,p_query_embedding,p_query_embedding_profile,p_query_embedding_model,p_ranking_profile,p_limit,p_cursor_id,p_tags) $$;
create or replace function public.memory_behavior_recall_claude(p_owner_id uuid,p_request_id uuid,p_query text,p_query_embedding real[],p_query_embedding_profile text,p_query_embedding_model text,p_ranking_profile text,p_limit integer,p_cursor_id bigint,p_tags text[])
returns jsonb language sql security definer set search_path='' as $$ select public.memory_behavior_internal_recall('claude',p_owner_id,p_request_id,p_query,p_query_embedding,p_query_embedding_profile,p_query_embedding_model,p_ranking_profile,p_limit,p_cursor_id,p_tags) $$;

drop function if exists public.memory_source_runtime_internal_expand(text,uuid,uuid,bigint);
drop function if exists public.memory_source_behavior_internal_recall(text,uuid,uuid,text,real[],text,text,text,integer,bigint,text[]);
drop function if exists public.memory_source_runtime_internal_memory_box(text,uuid,uuid,integer);
drop function if exists public.memory_source_runtime_internal_recall(text,uuid,uuid,text,integer,bigint,text[]);
drop function if exists public.memory_source_runtime_internal_list(text,uuid,uuid,integer,bigint,text,text[],text);
drop function if exists public.memory_source_runtime_internal_revise(text,uuid,uuid,bigint,jsonb,text);
drop function if exists public.memory_source_runtime_internal_remember(text,uuid,uuid,jsonb);
drop function if exists public.memory_source_summarize_items(jsonb);
drop function if exists public.memory_source_summary_item(public.memory_entries);

drop trigger if exists memory_revision_sources_append_only on public.memory_revision_sources;
drop trigger if exists memory_sources_append_only on public.memory_sources;
drop trigger if exists memory_revision_sources_validate on public.memory_revision_sources;
drop trigger if exists memory_revisions_inherit_sources on public.memory_revisions;
drop function if exists public.memory_source_materialize_links(text,uuid,bigint,bigint,jsonb);
drop function if exists public.memory_source_inherit_revision_links();
drop function if exists public.memory_source_validate_link();
drop function if exists public.memory_source_reject_change();
drop table if exists public.memory_revision_sources;
drop table if exists public.memory_sources;

create or replace function public.memory_compute_revision_hash(p_revision_id bigint)
returns text language sql stable set search_path='' as $$
  select public.memory_hash_jsonb(pg_catalog.jsonb_build_object(
    'id',r.id,'owner_id',r.owner_id,'memory_id',r.memory_id,'revision_number',r.revision_number,
    'title',r.title,'content',r.content,'author',r.author,'memory_type',r.memory_type,
    'tags',r.tags,'emotion',r.emotion,'importance',r.importance,'retention',r.retention,
    'lifecycle_status',r.lifecycle_status,'editor_actor',r.editor_actor,
    'revision_reason',r.revision_reason,'created_at',r.created_at
  )) from public.memory_revisions r where r.id=p_revision_id
$$;

create or replace function public.memory_prepare_entry_write()
returns trigger language plpgsql set search_path='' as $$
declare source_owner uuid; source_space text; source_revision public.memory_revisions%rowtype; content_changed boolean;
begin
  if tg_op='INSERT' then
    if new.updated_by_actor is not null or new.revision_reason is not null then raise exception 'Initial memory creation cannot preload revision metadata' using errcode='23514'; end if;
    if new.space_key='shared' then
      if not public.memory_internal_authority_is('curator') then raise exception 'Shared candidates require the trusted Curator RPC' using errcode='42501'; end if;
      if new.created_by_actor<>'curator' then raise exception 'Only Curator may create a Shared candidate' using errcode='42501'; end if;
      if new.shared_status<>'candidate' then raise exception 'Shared memory must enter as candidate' using errcode='23514'; end if;
      if new.source_memory_id is null or new.source_revision_id is null then raise exception 'Shared candidate requires a source memory and exact revision' using errcode='23514'; end if;
      select owner_id,space_key into source_owner,source_space from public.memory_entries where id=new.source_memory_id;
      if not found or source_owner is distinct from new.owner_id or source_space not in ('gpt','claude') then raise exception 'Shared candidate source must be a same-owner private memory' using errcode='23514'; end if;
      select * into source_revision from public.memory_revisions where id=new.source_revision_id and memory_id=new.source_memory_id and owner_id=new.owner_id;
      if not found then raise exception 'Shared candidate revision does not belong to its source memory and owner' using errcode='23514'; end if;
      new.title:=source_revision.title; new.content:=source_revision.content; new.author:=source_revision.author;
      new.memory_type:=source_revision.memory_type; new.tags:=source_revision.tags; new.emotion:=source_revision.emotion;
      new.importance:=source_revision.importance; new.retention:=source_revision.retention; new.lifecycle_status:=source_revision.lifecycle_status;
      new.source_revision_hash:=public.memory_compute_revision_hash(new.source_revision_id);
    end if;
    new.revision_number:=1; new.updated_at:=new.created_at; return new;
  end if;
  if new.owner_id is distinct from old.owner_id or new.space_key is distinct from old.space_key or new.created_by_actor is distinct from old.created_by_actor
    or new.source_type is distinct from old.source_type or new.source_model is distinct from old.source_model or new.source_ref is distinct from old.source_ref
    or new.source_metadata is distinct from old.source_metadata or new.source_memory_id is distinct from old.source_memory_id or new.source_revision_id is distinct from old.source_revision_id
    or new.source_revision_hash is distinct from old.source_revision_hash or new.original_table is distinct from old.original_table or new.original_id is distinct from old.original_id
    or new.original_created_at is distinct from old.original_created_at or new.legacy_source is distinct from old.legacy_source or new.created_at is distinct from old.created_at
  then raise exception 'Memory ownership, space and origin are immutable' using errcode='55000'; end if;
  if old.space_key='shared' then
    if not public.memory_internal_authority_is('owner') then raise exception 'Shared decisions require the authenticated Owner RPC' using errcode='42501'; end if;
    if row(new.memory_type,new.tags,new.title,new.content,new.emotion,new.importance,new.retention,new.lifecycle_status,new.decay_score,new.decay_updated_at,new.awaken_count,new.last_awakened_at,new.last_accessed_at,new.author)
      is distinct from row(old.memory_type,old.tags,old.title,old.content,old.emotion,old.importance,old.retention,old.lifecycle_status,old.decay_score,old.decay_updated_at,old.awaken_count,old.last_awakened_at,old.last_accessed_at,old.author)
    then raise exception 'Shared candidate and approved snapshots are immutable' using errcode='55000'; end if;
    if new.shared_status is not distinct from old.shared_status then raise exception 'Shared rows only permit an explicit Owner state transition' using errcode='55000'; end if;
    if new.updated_by_actor<>'owner' or nullif(btrim(new.revision_reason),'') is null or new.revision_reason is not distinct from old.revision_reason then raise exception 'Only Owner may approve, reject or revoke Shared memory' using errcode='42501'; end if;
    if not ((old.shared_status='candidate' and new.shared_status in ('approved','rejected')) or (old.shared_status='approved' and new.shared_status='revoked')) then raise exception 'Invalid Shared state transition: % -> %',old.shared_status,new.shared_status using errcode='23514'; end if;
    new.revision_number:=1; new.updated_at:=now(); return new;
  end if;
  if new.shared_status is distinct from old.shared_status then raise exception 'Private and Legacy memories cannot enter Shared by UPDATE' using errcode='23514'; end if;
  content_changed:=row(new.title,new.content,new.author,new.memory_type,new.tags,new.emotion,new.importance,new.retention,new.lifecycle_status)
    is distinct from row(old.title,old.content,old.author,old.memory_type,old.tags,old.emotion,old.importance,old.retention,old.lifecycle_status);
  if content_changed then
    if nullif(btrim(new.updated_by_actor),'') is null or nullif(btrim(new.revision_reason),'') is null or new.revision_reason is not distinct from old.revision_reason then raise exception 'Memory content changes require updated_by_actor and revision_reason' using errcode='23514'; end if;
    new.revision_number:=old.revision_number+1;
  else new.revision_number:=old.revision_number; end if;
  if not content_changed and new.shared_status is not distinct from old.shared_status and (new.updated_by_actor is distinct from old.updated_by_actor or new.revision_reason is distinct from old.revision_reason) then raise exception 'Revision metadata requires a tracked content or Shared state change' using errcode='23514'; end if;
  new.updated_at:=now(); return new;
end $$;

create or replace function public.memory_capture_entry_history()
returns trigger language plpgsql security definer set search_path='' as $$
declare provenance_event text;
begin
  if tg_op='INSERT' then
    insert into public.memory_revisions(owner_id,memory_id,revision_number,title,content,author,memory_type,tags,emotion,importance,retention,lifecycle_status,editor_actor,revision_reason,created_at)
    values(new.owner_id,new.id,1,new.title,new.content,new.author,new.memory_type,new.tags,new.emotion,new.importance,new.retention,new.lifecycle_status,new.created_by_actor,'initial_create',new.created_at);
    provenance_event:=case when new.space_key='legacy_pending' then 'legacy_staged' when new.space_key='shared' then 'shared_candidate' else 'created' end;
    insert into public.memory_provenance(owner_id,memory_id,parent_memory_id,parent_revision_id,event_type,actor,source_type,source_model,source_ref,original_table,original_id,original_created_at,legacy_source,reason,details,created_at)
    values(new.owner_id,new.id,new.source_memory_id,new.source_revision_id,provenance_event,new.created_by_actor,new.source_type,new.source_model,new.source_ref,new.original_table,new.original_id,new.original_created_at,new.legacy_source,'initial_create',case when new.space_key='shared' then new.source_metadata||jsonb_build_object('source_revision_id',new.source_revision_id,'source_revision_hash',new.source_revision_hash) else new.source_metadata end,new.created_at);
    if new.space_key='shared' then insert into public.memory_shared_transitions(owner_id,memory_id,from_status,to_status,actor,reason,source_memory_id,source_revision_id,source_revision_hash,created_at) values(new.owner_id,new.id,null,'candidate',new.created_by_actor,'shared_candidate_created',new.source_memory_id,new.source_revision_id,new.source_revision_hash,new.created_at); end if;
    return new;
  end if;
  if new.revision_number>old.revision_number then
    insert into public.memory_revisions(owner_id,memory_id,revision_number,title,content,author,memory_type,tags,emotion,importance,retention,lifecycle_status,editor_actor,revision_reason,created_at)
    values(new.owner_id,new.id,new.revision_number,new.title,new.content,new.author,new.memory_type,new.tags,new.emotion,new.importance,new.retention,new.lifecycle_status,new.updated_by_actor,new.revision_reason,new.updated_at);
    insert into public.memory_provenance(owner_id,memory_id,parent_memory_id,parent_revision_id,event_type,actor,reason,details,created_at)
    values(new.owner_id,new.id,new.source_memory_id,new.source_revision_id,'revised',new.updated_by_actor,new.revision_reason,jsonb_build_object('revision_number',new.revision_number),new.updated_at);
  end if;
  if new.shared_status is distinct from old.shared_status then
    insert into public.memory_shared_transitions(owner_id,memory_id,from_status,to_status,actor,reason,source_memory_id,source_revision_id,source_revision_hash,created_at)
    values(new.owner_id,new.id,old.shared_status,new.shared_status,new.updated_by_actor,new.revision_reason,new.source_memory_id,new.source_revision_id,new.source_revision_hash,new.updated_at);
    insert into public.memory_provenance(owner_id,memory_id,parent_memory_id,parent_revision_id,event_type,actor,reason,details,created_at)
    values(new.owner_id,new.id,new.source_memory_id,new.source_revision_id,case new.shared_status when 'candidate' then 'shared_candidate' when 'approved' then 'shared_approved' when 'rejected' then 'shared_rejected' when 'revoked' then 'shared_revoked' end,new.updated_by_actor,new.revision_reason,jsonb_build_object('from',old.shared_status,'to',new.shared_status,'source_revision_hash',new.source_revision_hash),new.updated_at);
  end if;
  return new;
end $$;

create or replace function public.memory_validate_revision_insert()
returns trigger language plpgsql set search_path='' as $$
declare entry public.memory_entries%rowtype;
begin
  select * into strict entry from public.memory_entries where id=new.memory_id;
  if entry.space_key='shared' and (new.owner_id is distinct from entry.owner_id or new.revision_number<>1
    or row(new.title,new.content,new.author,new.memory_type,new.tags,new.emotion,new.importance,new.retention,new.lifecycle_status)
      is distinct from row(entry.title,entry.content,entry.author,entry.memory_type,entry.tags,entry.emotion,entry.importance,entry.retention,entry.lifecycle_status)
    or new.editor_actor<>'curator' or new.revision_reason<>'initial_create')
  then raise exception 'Shared snapshots permit exactly one immutable initial revision' using errcode='23514'; end if;
  return new;
end $$;

alter table public.memory_entries drop constraint if exists memory_entries_summary_check, drop column if exists summary;
alter table public.memory_revisions drop constraint if exists memory_revisions_summary_check, drop column if exists summary;

commit;
