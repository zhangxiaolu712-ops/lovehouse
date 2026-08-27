import assert from 'node:assert/strict'
import test from 'node:test'

import {
  MemoryV2Service,
  RECALL_IMPORTANCE_WEIGHTS,
  rankMemoryCandidates,
} from './service.js'

const NOW = new Date('2026-08-20T11:06:00.000Z')

class FakeRepository {
  calls = []
  candidates = []
  semanticCandidates = []
  starterCandidates = []

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

  async starterPackCandidates(actor) {
    this.calls.push(['starterPackCandidates', actor])
    return this.starterCandidates.filter(item => item.space_key === actor || item.space_key === 'shared')
  }

  async storeEmbedding(actor, revisionId, result) {
    this.calls.push(['storeEmbedding', actor, revisionId, result])
  }

  async recordRecall(actor, ids, time) {
    this.calls.push(['recordRecall', actor, ids, time])
  }

  async history(actor, memoryId) {
    this.calls.push(['history', actor, memoryId])
    return [
      {
        revision_number: 1,
        sources: [{
          source_id: 'source-1',
          source_kind: 'manual_quote',
          locator: { reference: 'selected text' },
          provenance: { source_channel: 'manual' },
          ordinal: 0,
        }],
      },
      { revision_number: 2, sources: [] },
    ]
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

class ReadAfterWriteRepository extends FakeRepository {
  current = new Map()

  async remember(actor, content, options) {
    this.calls.push(['remember', actor, content, options])
    const stored = {
      memory_id: `${actor}-new-memory`,
      revision_id: `${actor}-new-revision-1`,
      revision_number: 1,
      space_key: actor,
      created_at: '2026-08-20T11:06:00.000Z',
    }
    this.setCurrent(candidate({
      ...stored,
      content,
      relevance: 0.75,
    }))
    return stored
  }

  async revise(actor, memoryId, content, options) {
    this.calls.push(['revise', actor, memoryId, content, options])
    const previous = this.current.get(memoryId)
    const stored = {
      memory_id: memoryId,
      revision_id: `${actor}-current-revision-2`,
      revision_number: 2,
      space_key: actor,
      created_at: '2026-08-20T11:07:00.000Z',
    }
    this.setCurrent(candidate({
      ...(previous || {}),
      ...stored,
      content,
      relevance: 0.75,
    }))
    this.semanticCandidates = this.semanticCandidates.filter(item => item.memory_id !== memoryId)
    return stored
  }

  setCurrent(item) {
    this.current.set(item.memory_id, item)
    this.candidates = [
      ...this.candidates.filter(candidateItem => candidateItem.memory_id !== item.memory_id),
      item,
    ]
  }

  async storeEmbedding(actor, revisionId, result) {
    this.calls.push(['storeEmbedding', actor, revisionId, result])
    const current = [...this.current.values()].find(item => item.revision_id === revisionId)
    if (current) {
      this.semanticCandidates = [
        ...this.semanticCandidates.filter(item => item.memory_id !== current.memory_id),
        { ...current, relevance: 0.9 },
      ]
    }
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

test('private remember and revise accept only optional 0-5 integer importance', async () => {
  const repository = new FakeRepository()
  const gpt = createService(repository).forActor('gpt')

  for (let importance = 0; importance <= 5; importance += 1) {
    await gpt.remember({
      content: 'valid remember', humanImportance: importance, aiImportance: importance,
    })
    await gpt.revise('memory-1', {
      content: 'valid revise', humanImportance: importance, aiImportance: importance,
    })
  }
  await gpt.remember({ content: 'null remember', humanImportance: null, aiImportance: null })
  await gpt.revise('memory-1', {
    content: 'null revise', humanImportance: null, aiImportance: null,
  })
  assert.deepEqual(repository.calls[0][3], { metadata: {}, human_importance: 0, ai_importance: 0 })
  assert.deepEqual(repository.calls[11][4], { human_importance: 5, ai_importance: 5 })
  assert.deepEqual(repository.calls[12][3], { metadata: {}, human_importance: null, ai_importance: null })
  assert.deepEqual(repository.calls[13][4], { human_importance: null, ai_importance: null })

  const invalidValues = [-1, 6, 0.5, 3.2, Number.NaN, '3']
  for (const operation of ['remember', 'revise']) {
    for (const field of ['humanImportance', 'aiImportance']) {
      for (const value of invalidValues) {
        const input = { content: 'invalid importance', [field]: value }
        const call = operation === 'remember'
          ? gpt.remember(input)
          : gpt.revise('memory-1', input)
        const parameter = field === 'humanImportance' ? 'human_importance' : 'ai_importance'
        await assert.rejects(call, new RegExp(`${parameter} must be an integer between 0 and 5`))
      }
    }
  }
  assert.equal(repository.calls.length, 14)
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

  const unconfigured = await createService(offlineRepository)
    .forActor('gpt').recall({ query: '关键词' })
  assert.equal(unconfigured.mode, 'lexical_fallback')
  assert.equal(unconfigured.semantic_error, 'embedding_not_configured')
})

test('remember is immediately recallable while its embedding is delayed for ten seconds', async () => {
  const repository = new ReadAfterWriteRepository()
  repository.semanticCandidates = [candidate({
    memory_id: 'existing-semantic',
    revision_id: 'existing-semantic-revision',
    content: '已有语义结果',
    relevance: 0.7,
  })]
  const vector = Array(1536).fill(0.01)
  const service = createService(repository, {
    embedding: {
      async embed(text) {
        if (text === '刚刚写入的苹果茉莉绿奶茶记忆') {
          await new Promise(resolve => setTimeout(resolve, 10_000))
        }
        return { vector, model: 'qwen3-embedding:4b' }
      },
    },
  }).forActor('gpt')

  const remembered = await service.remember('刚刚写入的苹果茉莉绿奶茶记忆')
  const immediate = await service.recall({ query: '苹果奶茶', limit: 5 })

  assert.equal(immediate.mode, 'semantic')
  assert.equal(immediate.semantic_error, null)
  assert.ok(immediate.items.some(item => item.memory_id === remembered.memory_id))
  assert.deepEqual(
    repository.calls.find(call => call[0] === 'recallLexical'),
    ['recallLexical', 'gpt', { query: '苹果奶茶', limit: 3 }],
  )
  assert.equal(repository.calls.some(call => call[0] === 'storeEmbedding'), false)

  await new Promise(resolve => setTimeout(resolve, 10_100))
  const embedded = await service.recall({ query: '苹果奶茶', limit: 5 })
  assert.equal(embedded.mode, 'semantic')
  assert.equal(
    embedded.items.filter(item => item.memory_id === remembered.memory_id).length,
    1,
  )
  assert.ok(repository.calls.some(call => (
    call[0] === 'storeEmbedding' && call[2] === remembered.revision_id
  )))
})

test('revise is immediately recallable and only the current revision is returned', async () => {
  const repository = new ReadAfterWriteRepository()
  const previous = candidate({
    memory_id: 'changing-memory',
    revision_id: 'old-revision-1',
    revision_number: 1,
    content: '旧事实：喜欢 A',
    relevance: 0.9,
  })
  repository.setCurrent(previous)
  repository.semanticCandidates = [
    previous,
    candidate({
      memory_id: 'existing-semantic',
      revision_id: 'existing-semantic-revision',
      content: '已有语义结果',
      relevance: 0.7,
    }),
  ]
  const vector = Array(1536).fill(0.01)
  const service = createService(repository, {
    embedding: {
      async embed(text) {
        if (text === '新事实：现在喜欢 B') return new Promise(() => {})
        return { vector, model: 'qwen3-embedding:4b' }
      },
    },
  }).forActor('gpt')

  const revised = await service.revise('changing-memory', {
    content: '新事实：现在喜欢 B',
    reason: '偏好改变',
  })
  const recalled = await service.recall({ query: '现在喜欢什么', limit: 5 })

  assert.equal(recalled.mode, 'semantic')
  assert.ok(recalled.items.some(item => item.revision_id === revised.revision_id))
  assert.equal(recalled.items.some(item => item.revision_id === 'old-revision-1'), false)
  assert.equal(
    recalled.items.filter(item => item.memory_id === 'changing-memory').length,
    1,
  )
})

test('semantic lexical supplements stay bounded, deduplicated and actor scoped', async () => {
  const repository = new FakeRepository()
  repository.semanticCandidates = [
    candidate({ memory_id: 'gpt-semantic', revision_id: 'gpt-semantic-revision', space_key: 'gpt' }),
    candidate({ memory_id: 'claude-semantic', revision_id: 'claude-semantic-revision', space_key: 'claude' }),
    candidate({ memory_id: 'shared-semantic', revision_id: 'shared-semantic-revision', space_key: 'shared' }),
  ]
  repository.candidates = [
    candidate({ memory_id: 'gpt-semantic', revision_id: 'gpt-semantic-revision', space_key: 'gpt' }),
    candidate({ memory_id: 'gpt-new', revision_id: 'gpt-new-revision', space_key: 'gpt', relevance: 0.75 }),
    candidate({ memory_id: 'claude-new', revision_id: 'claude-new-revision', space_key: 'claude', relevance: 0.75 }),
    candidate({ memory_id: 'shared-new', revision_id: 'shared-new-revision', space_key: 'shared', relevance: 0.75 }),
    candidate({ memory_id: 'shared-extra', revision_id: 'shared-extra-revision', space_key: 'shared', relevance: 0.7 }),
    candidate({ memory_id: 'shared-over-limit', revision_id: 'shared-over-limit-revision', space_key: 'shared', relevance: 0.7 }),
  ]
  const vector = Array(1536).fill(0.01)
  const service = createService(repository, {
    embedding: { embed: async () => ({ vector, model: 'qwen3-embedding:4b' }) },
  })

  const gpt = await service.forActor('gpt').recall({ query: '相关记忆', limit: 10 })
  const claude = await service.forActor('claude').recall({ query: '相关记忆', limit: 10 })

  assert.equal(gpt.items.some(item => item.memory_id === 'claude-new'), false)
  assert.equal(claude.items.some(item => item.memory_id === 'gpt-new'), false)
  assert.ok(gpt.items.some(item => item.memory_id === 'shared-new'))
  assert.ok(claude.items.some(item => item.memory_id === 'shared-new'))
  assert.equal(new Set(gpt.items.map(item => item.memory_id)).size, gpt.items.length)
  assert.equal(new Set(claude.items.map(item => item.memory_id)).size, claude.items.length)
  assert.ok(gpt.items.length <= 10)
  assert.ok(claude.items.length <= 10)
  assert.ok(repository.calls
    .filter(call => call[0] === 'recallLexical')
    .every(call => call[2].limit === 3))
})

test('semantic lexical supplements preserve the existing ranker and hard result limit', async () => {
  const repository = new FakeRepository()
  repository.semanticCandidates = [
    candidate({ memory_id: 'semantic-1', revision_id: 'semantic-revision-1', relevance: 0.95 }),
    candidate({ memory_id: 'semantic-2', revision_id: 'semantic-revision-2', relevance: 0.85 }),
    candidate({ memory_id: 'semantic-3', revision_id: 'semantic-revision-3', relevance: 0.75 }),
    candidate({ memory_id: 'semantic-4', revision_id: 'semantic-revision-4', relevance: 0.65 }),
  ]
  repository.candidates = [
    candidate({ memory_id: 'semantic-1', revision_id: 'semantic-revision-1', relevance: 0.95 }),
    candidate({ memory_id: 'lexical-1', revision_id: 'lexical-revision-1', relevance: 0.8 }),
    candidate({ memory_id: 'lexical-2', revision_id: 'lexical-revision-2', relevance: 0.7 }),
    candidate({ memory_id: 'lexical-3', revision_id: 'lexical-revision-3', relevance: 0.6 }),
  ]
  const vector = Array(1536).fill(0.01)
  const service = createService(repository, {
    embedding: { embed: async () => ({ vector, model: 'qwen3-embedding:4b' }) },
  }).forActor('gpt')

  const recalled = await service.recall({ query: '有界补充', limit: 4 })

  assert.equal(recalled.mode, 'semantic')
  assert.equal(recalled.items.length, 4)
  assert.equal(new Set(recalled.items.map(item => item.memory_id)).size, 4)
  assert.ok(recalled.items.every((item, index, items) => (
    index === 0 || items[index - 1].rank_score >= item.rank_score
  )))
  assert.deepEqual(
    repository.calls.find(call => call[0] === 'recallLexical')?.[2],
    { query: '有界补充', limit: 3 },
  )
})

test('a failed lexical supplement never downgrades a successful semantic recall', async () => {
  const repository = new FakeRepository()
  repository.semanticCandidates = [candidate({ content: '语义主路径结果', relevance: 0.9 })]
  repository.recallLexical = async () => {
    throw new Error('lexical supplement unavailable')
  }
  const vector = Array(1536).fill(0.01)
  const service = createService(repository, {
    embedding: { embed: async () => ({ vector, model: 'qwen3-embedding:4b' }) },
  }).forActor('gpt')

  const recalled = await service.recall({ query: '语义主路径', limit: 5 })

  assert.equal(recalled.mode, 'semantic')
  assert.equal(recalled.semantic_error, null)
  assert.deepEqual(recalled.items.map(item => item.content), ['语义主路径结果'])
})

test('recall exposes semantic degradation and later recovery without a new status service', async () => {
  const repository = new FakeRepository()
  repository.candidates = [candidate({ content: '备用搜索结果' })]
  repository.semanticCandidates = [candidate({ content: '恢复后的语义结果' })]
  const vector = Array(1536).fill(0.01)
  let online = false
  const service = createService(repository, {
    embedding: {
      embed: async () => {
        if (!online) throw Object.assign(new Error('Ollama connection refused'), { code: 'EMBEDDING_OFFLINE' })
        return { vector, model: 'qwen3-embedding:4b' }
      },
    },
  }).forActor('gpt')

  const degraded = await service.recall({ query: '测试' })
  assert.equal(degraded.mode, 'lexical_fallback')
  assert.equal(degraded.semantic_error, 'EMBEDDING_OFFLINE')

  online = true
  const recovered = await service.recall({ query: '测试' })
  assert.equal(recovered.mode, 'semantic')
  assert.equal(recovered.semantic_error, null)
})

test('embedding status is a lightweight adapter result and remains optional', () => {
  const repository = new FakeRepository()
  const unconfigured = createService(repository).forActor('gpt')
  assert.deepEqual(unconfigured.embeddingStatus(), {
    mode: 'unavailable',
    model: null,
    last_checked_at: null,
    error: 'embedding_not_configured',
  })

  const configured = createService(repository, {
    embedding: {
      model: 'qwen3-embedding:4b',
      async embed() { return { vector: Array(1536).fill(0.01), model: this.model } },
      getStatus() {
        return { mode: 'semantic', model: this.model, last_checked_at: '2026-08-20T14:30:00.000Z', error: null }
      },
    },
  }).forActor('claude')
  assert.deepEqual(configured.embeddingStatus(), {
    mode: 'semantic',
    model: 'qwen3-embedding:4b',
    last_checked_at: '2026-08-20T14:30:00.000Z',
    error: null,
  })
})

test('recall importance is adjustable AI 70 / human 30 and never overrides relevance', () => {
  const ranked = rankMemoryCandidates([
    candidate({ memory_id: 'ai-important', relevance: 0.7, ai_importance: 5 }),
    candidate({ memory_id: 'human-important', relevance: 0.7, human_importance: 5 }),
    candidate({ memory_id: 'ordinary', relevance: 0.7 }),
    candidate({
      memory_id: 'frequent-old',
      relevance: 0.7,
      recall_count: 1000000,
      last_recalled_at: '2025-01-01T00:00:00Z',
    }),
    candidate({ memory_id: 'irrelevant', relevance: 0, ai_importance: 5 }),
  ], NOW)

  assert.deepEqual(RECALL_IMPORTANCE_WEIGHTS, { ai: 0.7, human: 0.3 })
  assert.equal(ranked[0].memory_id, 'ai-important')
  assert.ok(
    ranked.find(item => item.memory_id === 'ai-important').importance_score
      > ranked.find(item => item.memory_id === 'human-important').importance_score
  )
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
  const history = await claude.history('memory-1')
  assert.equal(history.length, 2)
  assert.equal(history[0].sources[0].source_id, 'source-1')
  assert.equal('quote_text' in history[0].sources[0], false)
  assert.equal((await claude.expandSource(history[0].sources[0].source_id)).quote_text, '原文')
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

test('starter pack selects commitment then recent then random blindbox without importance ranking', async () => {
  const repository = new FakeRepository()
  const commitments = Array.from({ length: 5 }, (_, index) => candidate({
    memory_id: `commitment-${index}`,
    revision_id: `commitment-revision-${index}`,
    content: `当前承诺 ${index}`,
    metadata: { commitment_status: 'active' },
    created_at: `2026-08-${String(20 - index).padStart(2, '0')}T10:00:00Z`,
  }))
  const ordinary = Array.from({ length: 14 }, (_, index) => candidate({
    memory_id: `ordinary-${index}`,
    revision_id: `ordinary-revision-${index}`,
    content: `普通记忆 ${index}`,
    ai_importance: index === 13 ? 5 : null,
    human_importance: index === 13 ? 5 : null,
    created_at: `2026-07-${String(31 - index).padStart(2, '0')}T10:00:00Z`,
  }))
  repository.starterCandidates = [
    ...commitments.slice(0, 4).map(item => ({ ...item, starter_category: 'commitment' })),
    ...ordinary.slice(0, 8).map(item => ({ ...item, starter_category: 'recent' })),
    ...ordinary.slice(8, 11).map(item => ({ ...item, starter_category: 'blindbox' })),
  ]

  const pack = await createService(repository).forActor('gpt').starterPack({
    softLimit: 15,
    tokenBudget: 4000,
  })
  assert.equal(pack.items.filter(item => item.starter_category === 'commitment').length, 4)
  assert.equal(pack.items.filter(item => item.starter_category === 'recent').length, 8)
  assert.equal(pack.items.filter(item => item.starter_category === 'blindbox').length, 3)
  assert.equal(new Set(pack.items.map(item => item.memory_id)).size, pack.items.length)
  assert.deepEqual(
    pack.items.filter(item => item.starter_category === 'recent').map(item => item.memory_id),
    ordinary.slice(0, 8).map(item => item.memory_id)
  )
  assert.ok(!pack.items.some(item => item.memory_id === 'ordinary-13' && item.starter_category === 'recent'))
  assert.ok(pack.items.every(item => item.summary.length <= 240))
  assert.ok(pack.items.every(item => !('quote_text' in item) && !('sources' in item)))

  assert.deepEqual(repository.calls[0], ['starterPackCandidates', 'gpt'])

  const tight = await createService(repository).forActor('gpt').starterPack({
    softLimit: 15,
    tokenBudget: 180,
  })
  assert.ok(tight.items.length > 0)
  assert.ok(tight.items.every(item => item.starter_category === 'commitment'))
  assert.ok(tight.estimated_tokens <= 180)
})

test('starter pack failure stays isolated from remember and recall', async () => {
  const repository = new FakeRepository()
  repository.candidates = [candidate({ content: '普通召回仍可用' })]
  repository.starterPackCandidates = async () => { throw new Error('blindbox unavailable') }
  const gpt = createService(repository).forActor('gpt')

  await assert.rejects(gpt.starterPack(), /blindbox unavailable/)
  assert.equal((await gpt.remember('Starter Pack 故障不影响存储')).memory_id, 'gpt-memory')
  assert.equal((await gpt.recall({ query: '普通召回' })).items[0].content, '普通召回仍可用')
})
