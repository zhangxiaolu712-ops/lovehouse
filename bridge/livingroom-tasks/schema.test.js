import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'

const migration = fs.readFileSync(
  new URL('../../supabase/migrations/20260831140000_livingroom_codex_ticket_dispatch.sql', import.meta.url),
  'utf8',
)

test('migration persists only generic task skeleton and approval audit tables', () => {
  const tables = [...migration.matchAll(/create table public\.([a-z0-9_]+)/gi)].map(match => match[1])
  assert.deepEqual(tables, ['livingroom_tasks', 'livingroom_approvals'])
  assert.equal(tables.some(name => /message|event|log|codex/.test(name)), false)
  for (const field of ['target_agent', 'target_runtime', 'target_endpoint']) {
    assert.match(migration, new RegExp(`\\b${field} text not null\\b`))
  }
  assert.doesNotMatch(migration, /check\s*\(\s*target_(?:agent|runtime|endpoint)\s+in/i)
})

test('approval RPC is one-shot, owner/request-specific and blocks expired approval', () => {
  assert.match(migration, /id = p_approval_id and owner_id = p_owner_id and status = 'pending'/)
  assert.match(migration, /expires_at is null or expires_at > now\(\)/)
  assert.match(migration, /set status = 'expired'/)
  assert.match(migration, /revoke all on function public\.livingroom_decide_approval[^;]+from public, anon, authenticated/s)
})

test('schema supports risk levels and local-user checkpoints without durable progress tables', () => {
  assert.match(migration, /risk_level text not null check \(risk_level in \('low', 'medium', 'high'\)\)/)
  assert.match(migration, /'requires_local_user'/)
  assert.match(migration, /livingroom_resume_local_user/)
  assert.doesNotMatch(migration, /create table public\.[a-z0-9_]*(?:message|event|progress|log)/i)
})
