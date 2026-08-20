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
