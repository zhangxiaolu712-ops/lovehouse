import test from 'node:test'
import assert from 'node:assert/strict'

import { SupabaseMemoryRepository } from './repository.js'

const gptScope = {
  privateSpace: 'gpt',
  sharedSpace: 'shared',
  requiredSharedState: 'approved',
}

test('repository targets one canonical table with a scoped read filter', async () => {
  const calls = []
  const repository = new SupabaseMemoryRepository({
    rest: async (...args) => {
      calls.push(args)
      return []
    },
  })

  await repository.search({ scope: gptScope, query: '玫瑰', limit: 8 })

  assert.equal(calls.length, 1)
  assert.equal(calls[0][0], 'GET')
  assert.match(calls[0][1], /^memory_entries\?/)
  assert.match(calls[0][1], /space_key\.eq\.gpt/)
  assert.match(calls[0][1], /space_key\.eq\.shared/)
  assert.match(calls[0][1], /shared_status\.eq\.approved/)
  assert.doesNotMatch(calls[0][1], /brain|memories/)
})

test('repository inserts into the canonical table only', async () => {
  const calls = []
  const repository = new SupabaseMemoryRepository({
    rest: async (...args) => {
      calls.push(args)
      return [{ id: 7 }]
    },
  })

  const saved = await repository.insert({ content: 'one system' })
  assert.deepEqual(saved, { id: 7 })
  assert.deepEqual(calls[0], ['POST', 'memory_entries', { content: 'one system' }])
})

test('legacy category and level filters remain structural filters on the canonical table', async () => {
  const calls = []
  const repository = new SupabaseMemoryRepository({
    rest: async (...args) => {
      calls.push(args)
      return []
    },
  })

  await repository.list({
    scope: gptScope,
    tags: ['日常点滴'],
    retention: '长期',
  })

  assert.match(calls[0][1], /tags=cs\./)
  assert.match(calls[0][1], /retention=eq\./)
})
