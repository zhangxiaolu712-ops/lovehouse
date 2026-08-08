import test from 'node:test'
import assert from 'node:assert/strict'

import { SupabaseMemoryAuditSink } from './audit.js'
import { SupabaseMemoryRepository } from './repository.js'

const ownerId = '00000000-0000-0000-0000-000000000001'
const requestId = '10000000-0000-4000-8000-000000000001'
const gptScope = {
  privateSpace: 'gpt',
  sharedSpace: 'shared',
  requiredSharedState: 'approved',
}

test('repository uses the fixed GPT runtime recall door instead of raw table reads', async () => {
  const calls = []
  const repository = new SupabaseMemoryRepository({
    ownerId,
    rest: async (...args) => {
      calls.push(args)
      return { ok: true, items: [] }
    },
  })

  await repository.search({ scope: gptScope, query: '玫瑰', limit: 8, requestId })

  assert.equal(calls.length, 1)
  assert.equal(calls[0][0], 'POST')
  assert.equal(calls[0][1], 'rpc/memory_runtime_recall_gpt')
  assert.equal(calls[0][2].p_owner_id, ownerId)
  assert.equal(calls[0][2].p_request_id, requestId)
  assert.equal(calls[0][2].p_query, '玫瑰')
  assert.equal('space_key' in calls[0][2], false)
  assert.equal('actor' in calls[0][2], false)
  assert.doesNotMatch(calls[0][1], /brain|memories/)
})

test('repository remembers only through the fixed transactional RPC', async () => {
  const calls = []
  const repository = new SupabaseMemoryRepository({
    ownerId,
    rest: async (...args) => {
      calls.push(args)
      return { ok: true, memory: { id: 7, space_key: 'gpt' } }
    },
  })

  const saved = await repository.remember(
    { content: 'one system' },
    { actor: 'gpt', requestId }
  )
  assert.deepEqual(saved, { id: 7, space_key: 'gpt' })
  assert.deepEqual(calls[0], ['POST', 'rpc/memory_runtime_remember_gpt', {
    p_owner_id: ownerId,
    p_request_id: requestId,
    p_memory: { content: 'one system' },
  }])
})

test('legacy category and level filters remain structural filters on the runtime list RPC', async () => {
  const calls = []
  const repository = new SupabaseMemoryRepository({
    ownerId,
    rest: async (...args) => {
      calls.push(args)
      return { ok: true, items: [] }
    },
  })

  await repository.list({
    scope: gptScope,
    tags: ['日常点滴'],
    retention: 'long',
    requestId,
  })

  assert.equal(calls[0][1], 'rpc/memory_runtime_list_gpt')
  assert.deepEqual(calls[0][2].p_tags, ['日常点滴'])
  assert.equal(calls[0][2].p_retention, 'long')
})

test('repository enforces hard list and recall result limits', async () => {
  const calls = []
  const repository = new SupabaseMemoryRepository({
    ownerId,
    rest: async (...args) => {
      calls.push(args)
      return { ok: true, items: [] }
    },
  })
  await repository.list({ scope: gptScope, limit: 999, requestId })
  await repository.search({ scope: gptScope, query: 'x', limit: 999, requestId })
  assert.equal(calls[0][2].p_limit, 50)
  assert.equal(calls[1][2].p_limit, 10)
})

test('repository exposes audited Runtime denial as a typed error', async () => {
  const repository = new SupabaseMemoryRepository({
    ownerId,
    rest: async () => ({
      ok: false,
      error_code: 'MEMORY_ACCESS_DENIED',
      message: 'denied',
      audit_persisted: true,
    }),
  })
  await assert.rejects(
    repository.getById(1, { scope: gptScope, requestId }),
    error => error.code === 'MEMORY_ACCESS_DENIED' && error.auditPersisted === true
  )
})

test('repository fails closed when the server owner id is unavailable', async () => {
  const repository = new SupabaseMemoryRepository({ rest: async () => ({ ok: true }) })
  await assert.rejects(
    repository.getById(1, { scope: gptScope, requestId }),
    error => error.code === 'MEMORY_OWNER_NOT_CONFIGURED'
  )
})

test('repository rejects a caller-created or malformed scope', async () => {
  const repository = new SupabaseMemoryRepository({ ownerId, rest: async () => ({ ok: true }) })
  await assert.rejects(
    repository.search({
      scope: { privateSpace: 'claude', sharedSpace: 'shared', requiredSharedState: 'candidate' },
      query: 'forged',
      requestId,
    }),
    error => error.code === 'INVALID_MEMORY_SCOPE'
  )
})

test('persistent audit sink uses the fixed actor audit RPC and stores metadata only', async () => {
  const calls = []
  const sink = new SupabaseMemoryAuditSink({
    ownerId,
    rest: async (...args) => {
      calls.push(args)
      return { ok: true }
    },
  })

  await sink.record({
    actor: 'gpt',
    action: 'read',
    allowed: false,
    request_id: requestId,
    memory_id: 11,
    target_space: 'claude',
    reason_code: 'MEMORY_ACCESS_DENIED',
    occurred_at: '2026-08-09T00:00:00.000Z',
  })

  assert.equal(calls[0][0], 'POST')
  assert.equal(calls[0][1], 'rpc/memory_runtime_audit_gpt')
  assert.equal(calls[0][2].p_owner_id, ownerId)
  assert.equal(calls[0][2].p_space_key, 'claude')
  assert.equal(calls[0][2].p_result, 'denied')
  assert.equal(JSON.stringify(calls[0][2]).includes('content'), false)
})
