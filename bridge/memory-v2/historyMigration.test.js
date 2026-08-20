import assert from 'node:assert/strict'
import fs from 'node:fs'
import path from 'node:path'
import test from 'node:test'
import { fileURLToPath } from 'node:url'

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..')
const migration = fs.readFileSync(path.join(
  root,
  'supabase/migrations/20260820163417_memory_v2_history_source_descriptors.sql',
), 'utf8')
const rollback = fs.readFileSync(path.join(
  root,
  'supabase/rollback/20260820163417_memory_v2_history_source_descriptors_rollback.sql',
), 'utf8')

test('history delta changes only the existing RPC and preserves its security contract', () => {
  assert.match(migration, /create or replace function public\.memory_v2_history\(\s*p_owner_id uuid,\s*p_actor text,\s*p_memory_id uuid\s*\)/s)
  assert.match(migration, /returns jsonb\s+language sql\s+stable\s+security invoker\s+set search_path = pg_catalog, public/s)
  assert.match(migration, /revoke all on function public\.memory_v2_history\(uuid, text, uuid\) from public, anon, authenticated/)
  assert.match(migration, /grant execute on function public\.memory_v2_history\(uuid, text, uuid\) to service_role/)
  assert.doesNotMatch(migration, /\b(create table|alter table|create index|create trigger|create policy)\b/i)
  assert.equal((migration.match(/create or replace function/gi) || []).length, 1)
})

test('history descriptors are bounded and never include quote_text', () => {
  for (const field of ['source_id', 'source_kind', 'locator', 'provenance', 'ordinal']) {
    assert.match(migration, new RegExp(`'${field}'`))
  }
  assert.match(migration, /order by links\.ordinal, source\.id\s+limit 101/s)
  assert.match(migration, /'sources'.*'\[\]'::jsonb/s)
  assert.doesNotMatch(migration, /quote_text/)
})

test('rollback restores the original revision-only history definition', () => {
  assert.match(rollback, /jsonb_agg\(to_jsonb\(r\) order by r\.revision_number\)/)
  assert.doesNotMatch(rollback, /'sources'/)
  assert.match(rollback, /security invoker\s+set search_path = pg_catalog, public/s)
  assert.match(rollback, /revoke all on function public\.memory_v2_history\(uuid, text, uuid\) from public, anon, authenticated/)
  assert.match(rollback, /grant execute on function public\.memory_v2_history\(uuid, text, uuid\) to service_role/)
})
