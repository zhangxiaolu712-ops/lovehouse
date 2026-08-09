begin;

drop function if exists public.memory_runtime_memory_box_gpt(uuid, uuid, integer);
drop function if exists public.memory_runtime_memory_box_claude(uuid, uuid, integer);
drop function if exists public.memory_runtime_internal_memory_box(text, uuid, uuid, integer);

commit;
