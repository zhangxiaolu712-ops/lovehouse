import assert from 'node:assert/strict'
import test from 'node:test'

import { SupabaseMemoryV2Repository } from './repository.js'

test('preserves JSON arrays returned by Memory V2 RPCs', async () => {
  const payload = [
    {
      memory_id: 'memory-1',
      content: 'current',
      sources: [{
        source_id: 'source-1',
        source_kind: 'manual_quote',
        locator: { reference: 'selected text' },
        provenance: { source_channel: 'manual' },
        ordinal: 0,
      }],
    },
    { memory_id: 'memory-2', content: 'another', sources: [] },
  ]
  const repository = new SupabaseMemoryV2Repository({
    ownerId: 'owner-1',
    rest: async () => payload,
  })

  assert.deepEqual(
    await repository.recallLexical('gpt', { query: 'current' }),
    payload,
  )
  const history = await repository.history('gpt', 'memory-1')
  assert.deepEqual(history, payload)
  assert.equal('quote_text' in history[0].sources[0], false)
})

test('preserves JSON objects returned by Memory V2 RPCs', async () => {
  const payload = { memory_id: 'memory-1', revision_id: 'revision-1' }
  const repository = new SupabaseMemoryV2Repository({
    ownerId: 'owner-1',
    rest: async () => payload,
  })

  assert.deepEqual(await repository.remember('gpt', 'content'), payload)
})

test('Engineering methods use only dedicated RPCs and keep ordinary actor validation closed', async () => {
  const calls = []
  const repository = new SupabaseMemoryV2Repository({
    ownerId: 'owner-1',
    rest: async (method, path, body) => {
      calls.push({ method, path, body })
      return { ok: true }
    },
  })

  await repository.upsertEngineering('codex', 'runtime.codex', 'current state', {})
  await repository.recallEngineering('claude', { query: 'runtime', includeArchived: false })
  await repository.openEngineering('gpt', 'runtime.codex')
  await repository.expandEngineeringSource('owner', 'source-1')
  await repository.archiveEngineering('owner', 'runtime.codex')
  await repository.restoreEngineering('owner', 'runtime.codex')

  assert.deepEqual(calls.map(call => call.path), [
    'rpc/memory_v2_engineering_upsert',
    'rpc/memory_v2_engineering_recall',
    'rpc/memory_v2_engineering_open',
    'rpc/memory_v2_engineering_expand_source',
    'rpc/memory_v2_engineering_archive',
    'rpc/memory_v2_engineering_restore',
  ])
  assert.equal(calls[0].body.p_actor, 'codex')
  assert.equal(calls[0].body.p_owner_id, 'owner-1')
  assert.throws(() => repository.remember('codex', 'forbidden'), /fixed Memory V2 actor/)
  assert.throws(() => repository.history('owner', 'private-memory'), /fixed Memory V2 actor/)
})
