import assert from 'node:assert/strict'
import test from 'node:test'

import { SupabaseMemoryV2Repository } from './repository.js'

test('preserves JSON arrays returned by Memory V2 RPCs', async () => {
  const payload = [
    { memory_id: 'memory-1', content: 'current' },
    { memory_id: 'memory-2', content: 'another' },
  ]
  const repository = new SupabaseMemoryV2Repository({
    ownerId: 'owner-1',
    rest: async () => payload,
  })

  assert.deepEqual(
    await repository.recallLexical('gpt', { query: 'current' }),
    payload,
  )
  assert.deepEqual(await repository.history('gpt', 'memory-1'), payload)
})

test('preserves JSON objects returned by Memory V2 RPCs', async () => {
  const payload = { memory_id: 'memory-1', revision_id: 'revision-1' }
  const repository = new SupabaseMemoryV2Repository({
    ownerId: 'owner-1',
    rest: async () => payload,
  })

  assert.deepEqual(await repository.remember('gpt', 'content'), payload)
})
