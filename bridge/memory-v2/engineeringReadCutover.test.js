import assert from 'node:assert/strict'
import test from 'node:test'

import { ReadCutoverEngineeringRepository, ReadCutoverProjectChecklistStore } from './engineeringReadCutover.js'

test('Engineering read cutover routes every read to Railway and every write to legacy', async () => {
  const calls = []
  const legacy = {
    upsertEngineering: async () => calls.push('legacy-upsert'),
    archiveEngineering: async () => calls.push('legacy-archive'),
    restoreEngineering: async () => calls.push('legacy-restore'),
  }
  const railway = {
    recallEngineering: async () => { calls.push('railway-recall'); return ['recall'] },
    openEngineering: async () => { calls.push('railway-open'); return ['open'] },
    expandEngineeringSource: async () => { calls.push('railway-source'); return ['source'] },
  }
  const repository = new ReadCutoverEngineeringRepository({ legacy, railway })

  assert.deepEqual(await repository.recallEngineering('gpt', {}), ['recall'])
  assert.deepEqual(await repository.openEngineering('gpt', 'subject'), ['open'])
  assert.deepEqual(await repository.expandEngineeringSource('gpt', 'source'), ['source'])
  await repository.upsertEngineering('gpt', 'subject', 'content')
  await repository.archiveEngineering('gpt', 'subject')
  await repository.restoreEngineering('gpt', 'subject')

  assert.deepEqual(calls, [
    'railway-recall', 'railway-open', 'railway-source',
    'legacy-upsert', 'legacy-archive', 'legacy-restore',
  ])
})

test('checklist cutover routes load to Railway and all mutations to legacy', async () => {
  const calls = []
  const store = new ReadCutoverProjectChecklistStore({
    legacy: {
      save: async () => calls.push('legacy-save'),
      delete: async () => calls.push('legacy-delete'),
      migrateLocalV1: async () => calls.push('legacy-migrate'),
    },
    railway: { loadChecklist: async () => { calls.push('railway-load'); return { items: [] } } },
  })

  assert.deepEqual(await store.load('owner'), { items: [] })
  await store.save('owner', {})
  await store.delete('owner', 'item')
  await store.migrateLocalV1('owner', [])
  assert.deepEqual(calls, ['railway-load', 'legacy-save', 'legacy-delete', 'legacy-migrate'])
})
