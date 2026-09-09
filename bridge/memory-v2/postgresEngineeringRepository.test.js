import assert from 'node:assert/strict'
import test from 'node:test'

import { PostgresEngineeringRepository } from './postgresEngineeringRepository.js'

function fixture() {
  const calls = []
  const pool = {
    async query(text, values) {
      calls.push({ text, values })
      return { rows: [{ result: { call: calls.length } }] }
    },
  }
  return {
    calls,
    repository: new PostgresEngineeringRepository({ pool, ownerId: '11111111-1111-4111-8111-111111111111' }),
  }
}

test('Postgres Engineering methods call only parameterized public functions and preserve JSON results', async () => {
  const { calls, repository } = fixture()
  assert.deepEqual(await repository.upsertEngineering('codex', 'runtime.codex', 'current', { metadata: { v: 1 } }), { call: 1 })
  assert.deepEqual(await repository.recallEngineering('claude', { query: 'runtime', limit: 500 }), { call: 2 })
  await repository.openEngineering('gpt', 'runtime.codex')
  await repository.expandEngineeringSource('owner', '22222222-2222-4222-8222-222222222222')
  await repository.archiveEngineering('owner', 'runtime.codex')
  await repository.restoreEngineering('owner', 'runtime.codex')

  assert.deepEqual(calls.map(call => call.text.match(/public\.([a-z0-9_]+)/)[1]), [
    'memory_v2_engineering_upsert', 'memory_v2_engineering_recall',
    'memory_v2_engineering_open', 'memory_v2_engineering_expand_source',
    'memory_v2_engineering_archive', 'memory_v2_engineering_restore',
  ])
  assert.equal(calls.every(call => !call.text.includes(calls[0].values[0])), true)
  assert.equal(calls[1].values[3], 50)
  assert.deepEqual(calls[0].values[4], { metadata: { v: 1 } })
})

test('Postgres checklist methods match legacy store results and enforce the fixed owner', async () => {
  const { calls, repository } = fixture()
  const owner = repository.ownerId
  await repository.loadChecklist(owner)
  await repository.saveChecklist(owner, { id: 'base-0-0', status: 'done' })
  await repository.deleteChecklist(owner, 'custom-1')
  await repository.migrateChecklistLocalV1(owner, [{ id: 'base-0-0' }])
  assert.deepEqual(calls.map(call => call.text.match(/public\.([a-z0-9_]+)/)[1]), [
    'engineering_project_checklist_load', 'engineering_project_checklist_save',
    'engineering_project_checklist_delete', 'engineering_project_checklist_migrate_local_v1',
  ])
  assert.throws(() => repository.loadChecklist('other-owner'), /configured owner/)
})

test('Postgres adapter keeps actor validation closed before any query', async () => {
  const { calls, repository } = fixture()
  assert.throws(() => repository.upsertEngineering('visitor', 'subject', 'content'), /trusted Engineering/)
  assert.equal(calls.length, 0)
})
