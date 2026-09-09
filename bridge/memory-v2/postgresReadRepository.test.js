import assert from 'node:assert/strict'
import test from 'node:test'

import {
  PostgresMemoryV2ReadRepository,
  ReadCutoverMemoryV2Repository,
} from './postgresReadRepository.js'

function fakePool(payload = []) {
  const calls = []
  return {
    calls,
    async query(text, values) {
      calls.push({ text, values })
      return { rows: [{ result: payload }] }
    },
  }
}

test('PostgreSQL Memory V2 reads call the portable functions with the fixed owner and actor', async () => {
  const pool = fakePool([{ memory_id: 'memory-1' }])
  const repository = new PostgresMemoryV2ReadRepository({ ownerId: 'owner-1', pool })

  await repository.recallLexical('gpt', { query: 'orchid', limit: 999 })
  await repository.history('claude', 'memory-1')
  await repository.expandSource('gpt', 'source-1')
  await repository.timeline('claude', { query: '  recent  ', limit: 999 })

  assert.match(pool.calls[0].text, /memory_v2_recall_lexical/)
  assert.deepEqual(pool.calls[0].values, ['owner-1', 'gpt', 'orchid', 50])
  assert.match(pool.calls[1].text, /memory_v2_history/)
  assert.deepEqual(pool.calls[1].values, ['owner-1', 'claude', 'memory-1'])
  assert.match(pool.calls[2].text, /memory_v2_expand_source/)
  assert.deepEqual(pool.calls[2].values, ['owner-1', 'gpt', 'source-1'])
  assert.match(pool.calls[3].text, /memory_v2_timeline/)
  assert.deepEqual(pool.calls[3].values, ['owner-1', 'claude', 'recent', 100])
  assert.throws(() => repository.history('codex', 'memory-1'), /fixed Memory V2 actor/)
})

test('PostgreSQL semantic recall keeps the validated 1536-value vector contract', async () => {
  const pool = fakePool([])
  const repository = new PostgresMemoryV2ReadRepository({ ownerId: 'owner-1', pool })
  const vector = Array.from({ length: 1536 }, (_, index) => index / 1536)

  await repository.recallSemantic('gpt', { vector, model: 'semantic-1536-v1', limit: 7 })

  assert.match(pool.calls[0].text, /memory_v2_recall_semantic/)
  assert.deepEqual(pool.calls[0].values, ['owner-1', 'gpt', vector, 'semantic-1536-v1', 7])
  assert.throws(
    () => repository.recallSemantic('gpt', { vector: [1], model: 'bad' }),
    /exactly 1536/,
  )
})

test('read cutover sends reads to PostgreSQL and every mutation to Supabase', async () => {
  const calls = []
  const legacy = Object.fromEntries([
    'remember', 'revise', 'storeEmbedding', 'recordRecall', 'archive', 'approveShared',
  ].map(method => [method, async (...args) => calls.push(['legacy', method, ...args])]))
  const postgres = Object.fromEntries([
    'recallLexical', 'starterPackCandidates', 'recallSemantic', 'history', 'expandSource', 'timeline',
  ].map(method => [method, async (...args) => calls.push(['postgres', method, ...args])]))
  const repository = new ReadCutoverMemoryV2Repository({ legacy, postgres })

  await repository.recallLexical('gpt', { query: 'read' })
  await repository.history('gpt', 'memory-1')
  await repository.remember('gpt', 'write')
  await repository.recordRecall('gpt', ['memory-1'], '2026-09-09T00:00:00Z')

  assert.deepEqual(calls.map(call => call.slice(0, 2)), [
    ['postgres', 'recallLexical'],
    ['postgres', 'history'],
    ['legacy', 'remember'],
    ['legacy', 'recordRecall'],
  ])
})
