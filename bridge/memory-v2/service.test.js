import assert from 'node:assert/strict'
import test from 'node:test'

import { MemoryV2Service, rankMemoryCandidates } from './service.js'

const NOW = new Date('2026-08-20T11:06:00.000Z')

class FakeRepository {
  calls = []
  candidates = []
  semanticCandidates = []

  async remember(actor, content, options) {
    this.calls.push(['remember', actor, content, options])
    return {
      memory_id: `${actor}-memory`,
      revision_id: `${actor}-revision-1`,
      revision_number: 1,
      space_key: actor,
      created_at: '2026-08-20T11:06:00.000Z',
    }
  }

  async revise(actor, memoryId, content, options) {
    this.calls.push(['revise', actor, memoryId, content, options])
    return {
      memory_id: memoryId,
      revision_id: `${actor}-revision-2`,
      revision_number: 2,
      space_key: actor,
      created_at: '2026-08-20T11:07:00.000Z',
    }
  }

  async recallLexical(actor, input) {
    this.calls.push(['recallLexical', actor, input])
    return this.candidates.filter(item => item.space_key === actor || item.space_key === 'shared')
  }

  async recallSemantic(actor, input) {
    this.calls.push(['recallSemantic', actor, input])
    return this.semanticCandidates.filter(item => item.space_key === actor || item.space_key === 'shared')
  }

  async storeEmbedding(actor, revisionId, result) {
    this.calls.push(['storeEmbedding', actor, revisionId, result])
  }

  async recordRecall(actor, ids, time) {
    this.calls.push(['recordRecall', actor, ids, time])
  }

  async history(actor, memoryId) {
    this.calls.push(['history', actor, memoryId])
    return [{ revision_number: 1 }, { revision_number: 2 }]
  }

  async expandSource(actor, sourceId) {
    this.calls.push(['expandSource', actor, sourceId])
    return { source_id: sourceId, available: true, quote_text: '原文' }
  }

  async approveShared(memoryId) {
    this.calls.push(['approveShared', memoryId])
    return { memory_id: 'shared-memory', origin_memory_id: memoryId, shared_status: 'approved' }
  }
}

function candidate(overrides = {}) {
  return {
    memory_id: 'memory-1',
    revision_id: 'revision-1',
    revision_number: 1,
    content: '默认记忆内容',
    event_time: null,
    human_importance: null,
    ai_importance: null,
    metadata: {},
    created_at: '2026-08-20T10:00:00.000Z',
    space_key: 'gpt',
    last_recalled_at: null,
    recall_count: 0,
    source_count: 0,
    relevance: 0.8,
    ...overrides,
  }
}

function createService(repository, overrides = {}) {
  return new MemoryV2Service({ repository, clock: () => NOW, ...overrides })
}

function nextTurn() {
  return new Promise(resolve => setImmediate(resolve))
}

test('remember requires only content and server-fixes actor, scope and current time', async () => {
  const repository = new FakeRepository()
  const gpt = createService(repository).forActor('gpt')

  const saved = await gpt.remember('  只需要正文  ')

  assert.equal(saved.memory_id, 'gpt-memory')
  assert.equal(saved.current_time, '2026-08-20T19:06:00.000+08:00')
  assert.deepEqual(repository.calls[0], ['remember', 'gpt', '只需要正文', {}])
})

test('optional metadata and source are forwarded without becoming required', async () => {
  const repository = new FakeRepository()
  const claude = createService(repository).forActor('claude')

  await claude.remember({
    content: '有可选信息的记忆',
    tag: '旅行',
    eventTime: null,
    humanImportance: 5,
    sources: [{ sourceKind: 'manual_quote', quoteText: '精选原文' }],
  })

  assert.deepEqual(repository.calls[0][3], {
    metadata: { tag: '旅行' },
    event_time: null,
    human_importance: 5,
    sources: [{ source_kind: 'manual_quote', locator: {}, quote_text: '精选原文', provenance: {} }],
  })
})

test('authority fields are rejected instead of trusting caller-supplied namespace', async () => {
  const repository = new FakeRepository()
  const gpt = createService(repository).forActor('gpt')

  await assert.rejects(
    gpt.remember({ content: '越权尝试', space_key: 'claude' }),
    error => error.code === 'MEMORY_V2_AUTHORITY_FIELD_REJECTED'
  )
  assert.equal(repository.calls.length, 0)
})

test('clock and embedding failures never roll back a successful remember', async () => {
  const repository = new FakeRepository()
  const embeddingErrors = []
  const service = new MemoryV2Service({
    repository,
    clock: () => { throw new Error('clock unavailable') },
    embedding: { embed: async () => { throw new Error('ollama offline') } },
    onEmbeddingError: error => embeddingErrors.push(error.message),
  })

  const saved = await service.forActor('gpt').remember('仍然要落库')
  assert.equal(saved.time_status, 'unavailable')
  assert.equal(saved.current_time, null)
  assert.equal(repository.calls[0][0], 'remember')
  await nextTurn()
  assert.deepEqual(embeddingErrors, ['ollama offline'])
})

test('semantic recall is preferred and embedding failure falls back to lexical', async () => {
  const repository = new FakeRepository()
  repository.semanticCandidates = [candidate({ content: '语义命中' })]
  repository.candidates = [candidate({ content: '关键词命中' })]
  const vector = Array(1536).fill(0.01)
  const semantic = createService(repository, {
    embedding: { embed: async () => ({ vector, model: 'qwen3-embedding:4b' }) },
  })

  const semanticResult = await semantic.forActor('gpt').recall({ query: '近义问题' })
  assert.equal(semanticResult.mode, 'semantic')
  assert.equal(semanticResult.items[0].content, '语义命中')

  const offlineRepository = new FakeRepository()
  offlineRepository.candidates = [candidate({ content: '离线关键词结果' })]
  const offline = createService(offlineRepository, {
    embedding: { embed: async () => { throw new Error('OLLAMA_OFFLINE') } },
  })
  const fallback = await offline.forActor('gpt').recall({ query: '关键词' })
  assert.equal(fallback.mode, 'lexical_fallback')
  assert.equal(fallback.items[0].content, '离线关键词结果')
  assert.match(fallback.semantic_error, /OLLAMA_OFFLINE/)
})

test('dynamic weight only reorders relevant candidates and bounds recall feedback', () => {
  const ranked = rankMemoryCandidates([
    candidate({ memory_id: 'important', relevance: 0.7, human_importance: 5 }),
    candidate({ memory_id: 'ordinary', relevance: 0.7 }),
    candidate({
      memory_id: 'frequent-old',
      relevance: 0.7,
      recall_count: 1000000,
      last_recalled_at: '2025-01-01T00:00:00Z',
    }),
    candidate({ memory_id: 'irrelevant', relevance: 0, human_importance: 5 }),
  ], NOW)

  assert.equal(ranked[0].memory_id, 'important')
  assert.ok(ranked.find(item => item.memory_id === 'ordinary').tide_score > 0)
  assert.equal(ranked.find(item => item.memory_id === 'irrelevant').rank_score, 0)
  assert.ok(ranked.every(item => item.dynamic_weight <= 1))
})

test('GPT and Claude facades receive only own private plus approved Shared candidates', async () => {
  const repository = new FakeRepository()
  repository.candidates = [
    candidate({ memory_id: 'gpt-private', space_key: 'gpt' }),
    candidate({ memory_id: 'claude-private', space_key: 'claude' }),
    candidate({ memory_id: 'approved-shared', space_key: 'shared' }),
  ]
  const service = createService(repository)

  const gpt = await service.forActor('gpt').recall({ query: '记忆' })
  const claude = await service.forActor('claude').recall({ query: '记忆' })
  assert.deepEqual(gpt.items.map(item => item.memory_id).sort(), ['approved-shared', 'gpt-private'])
  assert.deepEqual(claude.items.map(item => item.memory_id).sort(), ['approved-shared', 'claude-private'])
})

test('revision, history and source expansion remain actor-bound', async () => {
  const repository = new FakeRepository()
  const claude = createService(repository).forActor('claude')

  const revised = await claude.revise('memory-1', {
    content: '第二版内容',
    reason: '理解更新',
  })
  assert.equal(revised.revision_number, 2)
  assert.deepEqual(repository.calls[0], [
    'revise', 'claude', 'memory-1', '第二版内容', { reason: '理解更新' },
  ])
  assert.equal((await claude.history('memory-1')).length, 2)
  assert.equal((await claude.expandSource('source-1')).quote_text, '原文')
})

test('Shared creation is unavailable without explicit owner approval', async () => {
  const repository = new FakeRepository()
  const service = createService(repository)

  await assert.rejects(
    service.ownerApproveShared('gpt-memory'),
    error => error.code === 'MEMORY_V2_OWNER_APPROVAL_REQUIRED'
  )
  const shared = await service.ownerApproveShared('gpt-memory', { ownerApproved: true })
  assert.equal(shared.shared_status, 'approved')
})

test('starter pack applies a soft item limit, hard token budget and never expands source', async () => {
  const repository = new FakeRepository()
  repository.candidates = Array.from({ length: 20 }, (_, index) => candidate({
    memory_id: `memory-${index}`,
    revision_id: `revision-${index}`,
    content: `第 ${index} 条很长的记忆`.repeat(30),
    source_count: 2,
    created_at: `2026-08-${String(20 - Math.min(index, 19)).padStart(2, '0')}T10:00:00Z`,
  }))

  const pack = await createService(repository).forActor('gpt').starterPack({
    softLimit: 12,
    tokenBudget: 600,
  })
  assert.ok(pack.items.length > 0 && pack.items.length < 12)
  assert.ok(pack.estimated_tokens <= 600)
  assert.ok(pack.items.every(item => item.summary.length <= 240))
  assert.ok(pack.items.every(item => !('quote_text' in item) && !('sources' in item)))
})
