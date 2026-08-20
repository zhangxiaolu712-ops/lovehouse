\set ON_ERROR_STOP on

begin;

insert into auth.users (id, email, raw_user_meta_data)
values ('50000000-0000-0000-0000-000000000005', 'memory-v2-embedding-owner@example.invalid', '{}'::jsonb)
on conflict (id) do nothing;

do $$
declare
  owner_id constant uuid := '50000000-0000-0000-0000-000000000005';
  model_name constant text := 'qwen3-embedding:4b';
  test_vector constant real[] := array_fill(0.01::real, array[1536]);
  gpt_saved jsonb;
  gpt_revised jsonb;
  claude_saved jsonb;
  shared_saved jsonb;
  revoked_source jsonb;
  revoked_shared jsonb;
  denied boolean;
  candidate_rejected boolean := false;
begin
  gpt_saved := public.memory_v2_remember(owner_id, 'gpt', 'GPT embedding delta test', '{}'::jsonb);
  claude_saved := public.memory_v2_remember(owner_id, 'claude', 'Claude embedding delta test', '{}'::jsonb);
  shared_saved := public.memory_v2_approve_shared(owner_id, (gpt_saved ->> 'memory_id')::uuid);
  revoked_source := public.memory_v2_remember(owner_id, 'gpt', 'revoked Shared source', '{}'::jsonb);
  revoked_shared := public.memory_v2_approve_shared(owner_id, (revoked_source ->> 'memory_id')::uuid);
  update public.memory_v2_entries set shared_status = 'revoked'
  where id = (revoked_shared ->> 'memory_id')::uuid;

  perform public.memory_v2_store_embedding(
    owner_id, 'gpt', (gpt_saved ->> 'revision_id')::uuid, model_name, test_vector
  );
  perform public.memory_v2_store_embedding(
    owner_id, 'claude', (claude_saved ->> 'revision_id')::uuid, model_name, test_vector
  );
  perform public.memory_v2_store_embedding(
    owner_id, 'gpt', (shared_saved ->> 'revision_id')::uuid, model_name, test_vector
  );
  perform public.memory_v2_store_embedding(
    owner_id, 'claude', (shared_saved ->> 'revision_id')::uuid, model_name, test_vector
  );

  if (select count(*) from public.memory_v2_embeddings
      where revision_id in (
        (gpt_saved ->> 'revision_id')::uuid,
        (claude_saved ->> 'revision_id')::uuid,
        (shared_saved ->> 'revision_id')::uuid
      ) and model = model_name and extensions.vector_dims(memory_v2_embeddings.embedding) = 1536) <> 3 then
    raise exception 'private or approved Shared embedding write failed';
  end if;

  denied := false;
  begin
    perform public.memory_v2_store_embedding(
      owner_id, 'gpt', (claude_saved ->> 'revision_id')::uuid, model_name, test_vector
    );
  exception when insufficient_privilege then denied := true;
  end;
  if not denied then raise exception 'GPT wrote Claude private embedding'; end if;

  denied := false;
  begin
    perform public.memory_v2_store_embedding(
      owner_id, 'claude', (gpt_saved ->> 'revision_id')::uuid, model_name, test_vector
    );
  exception when insufficient_privilege then denied := true;
  end;
  if not denied then raise exception 'Claude wrote GPT private embedding'; end if;

  denied := false;
  begin
    perform public.memory_v2_store_embedding(
      owner_id, 'gpt', (revoked_shared ->> 'revision_id')::uuid, model_name, test_vector
    );
  exception when insufficient_privilege then denied := true;
  end;
  if not denied then raise exception 'GPT wrote revoked Shared embedding'; end if;

  denied := false;
  begin
    perform public.memory_v2_store_embedding(
      owner_id, 'claude', (revoked_shared ->> 'revision_id')::uuid, model_name, test_vector
    );
  exception when insufficient_privilege then denied := true;
  end;
  if not denied then raise exception 'Claude wrote revoked Shared embedding'; end if;

  begin
    insert into public.memory_v2_entries (
      owner_id, space_key, created_by_actor, current_revision_id,
      shared_status, origin_revision_id
    ) values (
      owner_id, 'shared', 'owner', (gpt_saved ->> 'revision_id')::uuid,
      'candidate', (gpt_saved ->> 'revision_id')::uuid
    );
  exception when check_violation then candidate_rejected := true;
  end;
  if not candidate_rejected then raise exception 'V2 admitted candidate Shared state'; end if;

  -- The delta intentionally does not add a current-revision restriction.
  gpt_revised := public.memory_v2_revise(
    owner_id, 'gpt', (gpt_saved ->> 'memory_id')::uuid,
    'GPT embedding delta current revision', '{}'::jsonb
  );
  perform public.memory_v2_store_embedding(
    owner_id, 'gpt', (gpt_saved ->> 'revision_id')::uuid, model_name, test_vector
  );
  if not exists (
    select 1 from public.memory_v2_embeddings
    where revision_id = (gpt_saved ->> 'revision_id')::uuid
  ) then raise exception 'historical private revision behavior changed'; end if;

  if gpt_revised ->> 'revision_number' <> '2' then
    raise exception 'revision setup failed';
  end if;

  if not has_function_privilege('service_role', 'public.memory_v2_store_embedding(uuid,text,uuid,text,real[])', 'execute')
    or has_function_privilege('anon', 'public.memory_v2_store_embedding(uuid,text,uuid,text,real[])', 'execute')
    or has_function_privilege('authenticated', 'public.memory_v2_store_embedding(uuid,text,uuid,text,real[])', 'execute') then
    raise exception 'function grants drifted';
  end if;
end;
$$;

rollback;
