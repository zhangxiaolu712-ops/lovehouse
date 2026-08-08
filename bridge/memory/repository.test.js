import test from 'node:test'
import assert from 'node:assert/strict'

import { SupabaseMemoryAuditSink } from './audit.js'
import { SupabaseMemoryRepository } from './repository.js'

const ownerId = '00000000-0000-0000-0000-000000000001'

const gptScope = {
  privateSpace: 'gpt',
  sharedSpace: 'shared',
  requiredSharedState: 'approved',
}

test('repository uses the fixed GPT database read door instead of raw table reads', async () => {
  const calls = []
  const repository = new SupabaseMemoryRepository({
    ownerId,
    rest: async (...args) => {
      calls.push(args)
      return []
    },
  })

  await repository.search({ scope: gptScope, query: '玫瑰', limit: 8 })

  assert.equal(calls.length, 1)
  assert.equal(calls[0][0], 'POST')
  assert.equal(calls[0][1], 'rpc/memory_recall_gpt')
  assert.equal(calls[0][2].p_owner_id, ownerId)
  assert.equal(calls[0][2].p_query, '玫瑰')
  assert.equal('space_key' in calls[0][2], false)
  assert.equal('actor' in calls[0][2], false)
  assert.doesNotMatch(calls[0][1], /brain|memories/)
})

test('repository inserts into the canonical table only', async () => {
  const calls = []
  const repository = new SupabaseMemoryRepository({
    ownerId,
    rest: async (...args) => {
      calls.push(args)
      return [{ id: 7 }]
    },
  })

  const saved = await repository.insert({ content: 'one system' })
  assert.deepEqual(saved, { id: 7 })
  assert.deepEqual(calls[0], ['POST', 'memory_entries', {
    content: 'one system',
    owner_id: ownerId,
  }])
})

test('legacy category and level filters remain structural filters on the canonical table', async () => {
  const calls = []
  const repository = new SupabaseMemoryRepository({
    ownerId,
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

  assert.equal(calls[0][1], 'rpc/memory_list_gpt')
  assert.deepEqual(calls[0][2].p_tags, ['日常点滴'])
  assert.equal(calls[0][2].p_retention, '长期')
})

test('repository fails closed when the server owner id is unavailable', async () => {
  const repository = new SupabaseMemoryRepository({ rest: async () => [] })

  await assert.rejects(
    repository.getById('memory-id', { scope: gptScope }),
    error => error.code === 'MEMORY_OWNER_NOT_CONFIGURED'
  )
})

test('repository rejects a caller-created or malformed scope', async () => {
  const repository = new SupabaseMemoryRepository({ ownerId, rest: async () => [] })

  await assert.rejects(
    repository.search({
      scope: { privateSpace: 'claude', sharedSpace: 'shared', requiredSharedState: 'candidate' },
      query: 'forged',
    }),
    error => error.code === 'INVALID_MEMORY_SCOPE'
  )
})

test('persistent audit sink writes metadata only to the canonical audit table', async () => {
  const calls = []
  const sink = new SupabaseMemoryAuditSink({
    ownerId,
    rest: async (...args) => {
      calls.push(args)
      return [{ id: 9 }]
    },
  })

  await sink.record({
    actor: 'gpt',
    action: 'read',
    allowed: false,
    memory_id: '11111111-1111-1111-1111-111111111111',
    target_space: 'claude',
    reason_code: 'MEMORY_ACCESS_DENIED',
    occurred_at: '2026-08-09T00:00:00.000Z',
  })

  assert.equal(calls[0][0], 'POST')
  assert.equal(calls[0][1], 'memory_audit_log')
  assert.equal(calls[0][2].owner_id, ownerId)
  assert.equal(calls[0][2].space_key, 'claude')
  assert.equal(calls[0][2].result, 'denied')
  assert.equal(JSON.stringify(calls[0][2]).includes('content'), false)
})
