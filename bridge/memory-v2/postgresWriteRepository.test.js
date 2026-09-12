import assert from 'node:assert/strict'
import test from 'node:test'

import {
  PostgresMemoryV2WriteRepository,
  WriteCutoverMemoryV2Repository,
} from './postgresWriteRepository.js'

function fakePool(payload = { ok: true }) {
  const calls = []
  return {
    calls,
    async query(text, values) {
      calls.push({ text, values })
      return { rows: [{ result: payload }] }
    },
  }
}

test('PostgreSQL Memory V2 writes call the migrated functions with fixed owner and actor', async () => {
  const pool = fakePool()
  const repository = new PostgresMemoryV2WriteRepository({ ownerId: 'owner-1', pool })
  const options = { space_key: 'gpt', source: { source_type: 'conversation' } }

  await repository.remember('gpt', 'remembered', options)
  await repository.revise('claude', 'memory-1', 'revised', { reason: 'correction' })
  await repository.recordRecall('gpt', ['memory-1'], '2026-09-12T01:02:03.123456+00:00')
  await repository.archive('claude', 'memory-2')
  await repository.approveShared('memory-3')

  assert.match(pool.calls[0].text, /memory_v2_remember/)
  assert.deepEqual(pool.calls[0].values, ['owner-1', 'gpt', 'remembered', options])
  assert.match(pool.calls[1].text, /memory_v2_revise/)
  assert.deepEqual(pool.calls[1].values, [
    'owner-1', 'claude', 'memory-1', 'revised', { reason: 'correction' },
  ])
  assert.match(pool.calls[2].text, /memory_v2_record_recall/)
  assert.deepEqual(pool.calls[2].values, [
    'owner-1', 'gpt', ['memory-1'], '2026-09-12T01:02:03.123456+00:00',
  ])
  assert.match(pool.calls[3].text, /memory_v2_archive/)
  assert.deepEqual(pool.calls[3].values, ['owner-1', 'claude', 'memory-2'])
  assert.match(pool.calls[4].text, /memory_v2_approve_shared/)
  assert.deepEqual(pool.calls[4].values, ['owner-1', 'memory-3'])
  assert.throws(() => repository.remember('codex', 'blocked'), /fixed Memory V2 actor/)
})

test('PostgreSQL embedding writes preserve the validated 1536-value contract', async () => {
  const pool = fakePool()
  const repository = new PostgresMemoryV2WriteRepository({ ownerId: 'owner-1', pool })
  const vector = Array.from({ length: 1536 }, (_, index) => index / 1536)

  await repository.storeEmbedding('gpt', 'revision-1', {
    vector,
    model: 'semantic-1536-v1',
  })

  assert.match(pool.calls[0].text, /memory_v2_store_embedding/)
  assert.deepEqual(pool.calls[0].values, [
    'owner-1', 'gpt', 'revision-1', 'semantic-1536-v1', vector,
  ])
  assert.throws(
    () => repository.storeEmbedding('gpt', 'revision-1', { vector: [1], model: 'bad' }),
    /exactly 1536/,
  )
})

test('write cutover sends every Life Memory mutation to PostgreSQL and preserves reads', async () => {
  const calls = []
  const read = Object.fromEntries([
    'recallLexical', 'starterPackCandidates', 'recallSemantic', 'history', 'expandSource', 'timeline',
  ].map(method => [method, async (...args) => calls.push(['read', method, ...args])]))
  const postgres = Object.fromEntries([
    'remember', 'revise', 'storeEmbedding', 'recordRecall', 'archive', 'approveShared',
  ].map(method => [method, async (...args) => calls.push(['postgres', method, ...args])]))
  const repository = new WriteCutoverMemoryV2Repository({ read, postgres })

  await repository.recallLexical('gpt', { query: 'read' })
  await repository.history('gpt', 'memory-1')
  await repository.remember('gpt', 'write')
  await repository.revise('gpt', 'memory-1', 'revision')
  await repository.storeEmbedding('gpt', 'revision-1', { vector: [] })
  await repository.recordRecall('gpt', ['memory-1'], 'time')
  await repository.archive('gpt', 'memory-1')
  await repository.approveShared('memory-1')

  assert.deepEqual(calls.map(call => call.slice(0, 2)), [
    ['read', 'recallLexical'],
    ['read', 'history'],
    ['postgres', 'remember'],
    ['postgres', 'revise'],
    ['postgres', 'storeEmbedding'],
    ['postgres', 'recordRecall'],
    ['postgres', 'archive'],
    ['postgres', 'approveShared'],
  ])
})
