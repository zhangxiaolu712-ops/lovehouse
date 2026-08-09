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

test('hybrid recall uses a fixed actor behavior door and server-only ranking inputs', async () => {
  const calls = []
  const repository = new SupabaseMemoryRepository({
    ownerId,
    rest: async (...args) => {
      calls.push(args)
      return { ok: true, items: [] }
    },
  })
  await repository.hybridSearch({
    scope: gptScope,
    query: '玫瑰',
    queryEmbedding: [0.1, 0.2, 0.3],
    queryEmbeddingProfile: 'semantic-test-v1',
    queryEmbeddingModel: 'test/model-v1',
    rankingProfile: 'ranking_v1',
    requestId,
  })

  assert.equal(calls[0][1], 'rpc/memory_behavior_recall_gpt')
  assert.equal(calls[0][2].p_ranking_profile, 'ranking_v1')
  assert.deepEqual(calls[0][2].p_query_embedding, [0.1, 0.2, 0.3])
  assert.equal(calls[0][2].p_query_embedding_profile, 'semantic-test-v1')
  assert.equal(calls[0][2].p_query_embedding_model, 'test/model-v1')
  assert.equal('actor' in calls[0][2], false)
  assert.equal('space_key' in calls[0][2], false)
})

test('repository rejects malformed server embeddings before calling Supabase', async () => {
  let calls = 0
  const repository = new SupabaseMemoryRepository({ ownerId, rest: async () => { calls += 1 } })
  await assert.rejects(
    repository.hybridSearch({
      scope: gptScope,
      query: 'rose',
      queryEmbedding: [0.1, Number.NaN],
      queryEmbeddingProfile: 'semantic-test-v1',
      queryEmbeddingModel: 'test-model',
      requestId,
    }),
    error => error.code === 'INVALID_MEMORY_QUERY_EMBEDDING'
  )
  assert.equal(calls, 0)
})

test('embedding lifecycle uses only fixed actor RPCs', async () => {
  const calls = []
  const repository = new SupabaseMemoryRepository({
    ownerId,
    rest: async (...args) => {
      calls.push(args)
      return args[1].includes('claim') ? { ok: true, items: [] } : { ok: true }
    },
  })
  await repository.claimEmbeddings({ actor: 'claude', limit: 99, requestId })
  await repository.completeEmbedding(7, [0.2, 0.8], { actor: 'claude', requestId })
  await repository.failEmbedding(8, 'UPSTREAM_TIMEOUT', { actor: 'claude', requestId })

  assert.deepEqual(calls.map(call => call[1]), [
    'rpc/memory_behavior_claim_embeddings_claude',
    'rpc/memory_behavior_complete_embedding_claude',
    'rpc/memory_behavior_fail_embedding_claude',
  ])
  assert.equal(calls[0][2].p_limit, 8)
  assert.equal(calls.every(call => !('actor' in call[2]) && !('space_key' in call[2])), true)
})

test('Anchor capability stays behind fixed actor internal RPCs', async () => {
  const calls = []
  const repository = new SupabaseMemoryRepository({
    ownerId,
    rest: async (...args) => {
      calls.push(args)
      return args[1].includes('list')
        ? { ok: true, items: [{ id: 1, actor: 'gpt' }] }
        : { ok: true, anchor: { id: 1, actor: 'gpt' } }
    },
  })
  await repository.setAnchor(9, true, 'core memory', { actor: 'gpt', requestId })
  await repository.listAnchors({ actor: 'gpt' })
  assert.deepEqual(calls.map(call => call[1]), [
    'rpc/memory_behavior_set_anchor_gpt',
    'rpc/memory_behavior_list_anchors_gpt',
  ])
  assert.equal(calls.every(call => !('actor' in call[2]) && !('space_key' in call[2])), true)
})

test('Dream queue uses fixed actor RPCs and server Curator identity only', async () => {
  const calls = []
  const repository = new SupabaseMemoryRepository({
    ownerId,
    rest: async (...args) => {
      calls.push(args)
      if (args[1].includes('claim')) return { ok: true, job: { id: 7, sources: [] } }
      if (args[1].includes('enqueue')) return { ok: true, job: { id: 7 } }
      return { ok: true, candidate_ids: [31] }
    },
  })
  const context = {
    actor: 'claude', requestId,
    providerKey: 'deepseek-compatible', model: 'deepseek-curator',
  }
  await repository.enqueueDream({
    actor: 'claude', requestId, perspective: 'claude private perspective', limit: 999,
  })
  await repository.claimDream(context)
  await repository.completeDream(7, [{ content: 'pending candidate' }], context)
  await repository.failDream(7, 'MEMORY_DREAM_TEST_FAILURE', context)

  assert.deepEqual(calls.map(call => call[1]), [
    'rpc/memory_behavior_enqueue_dream_claude',
    'rpc/memory_behavior_claim_dream_claude',
    'rpc/memory_behavior_complete_dream_claude',
    'rpc/memory_behavior_fail_dream_claude',
  ])
  assert.equal(calls[0][2].p_limit, 4)
  assert.equal(calls[1][2].p_curator_provider, 'deepseek-compatible')
  assert.equal(calls[1][2].p_curator_model, 'deepseek-curator')
  assert.equal(calls.every(call => !('actor' in call[2]) && !('space_key' in call[2])), true)
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
