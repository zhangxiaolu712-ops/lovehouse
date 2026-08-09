import test from 'node:test'
import assert from 'node:assert/strict'

import {
  MEMORY_ACTORS,
  MEMORY_SPACES,
  InMemoryAuditSink,
  MemoryAccessPolicy,
  MemoryAccessError,
  MemoryService,
  SHARED_STATES,
} from './index.js'

class InMemoryRepository {
  constructor(rows = []) {
    this.rows = rows.map(row => ({ ...row }))
    this.lastInsert = null
    this.lastList = null
    this.lastSearch = null
    this.lastMemoryBox = null
  }

  async remember(entry, { actor }) {
    this.lastInsert = { ...entry, actor }
    const saved = {
      id: this.rows.length + 1,
      ...entry,
      space_key: actor,
      created_by_actor: actor,
      source_type: 'mcp_runtime',
      source_model: actor,
    }
    this.rows.push(saved)
    return saved
  }

  async getById(id) {
    return this.rows.find(row => row.id === id) || null
  }

  async list(input) {
    this.lastList = input
    // Deliberately return every row. MemoryService must enforce its own boundary.
    return this.rows
  }

  async search(input) {
    this.lastSearch = input
    // Deliberately return every row. MemoryService must enforce its own boundary.
    return this.rows
  }

  async memoryBox(input) {
    this.lastMemoryBox = input
    // Deliberately return every row. MemoryService is still a second boundary.
    return { actor: input.scope.privateSpace, mode: 'random_history', items: this.rows }
  }
}

const seed = [
  { id: 1, space_key: MEMORY_SPACES.GPT, content: 'gpt private' },
  { id: 2, space_key: MEMORY_SPACES.CLAUDE, content: 'claude private' },
  {
    id: 3,
    space_key: MEMORY_SPACES.SHARED,
    shared_status: SHARED_STATES.APPROVED,
    content: 'approved shared',
  },
  {
    id: 4,
    space_key: MEMORY_SPACES.SHARED,
    shared_status: SHARED_STATES.PENDING,
    content: 'pending shared',
  },
  { id: 5, space_key: MEMORY_SPACES.LEGACY_PENDING, content: 'legacy pending' },
]

function createService(rows = seed) {
  const repository = new InMemoryRepository(rows)
  const service = new MemoryService({
    repository,
    auditSink: { persistent: true, async record() {} },
    writeEnabled: true,
    clock: () => new Date('2026-08-09T00:00:00.000Z'),
  })
  return { repository, service }
}

test('GPT can write only to GPT Memory and cannot choose a space', async () => {
  const { repository, service } = createService([])
  const saved = await service.write(MEMORY_ACTORS.GPT, {
    content: 'a new memory',
    kind: '记感受',
    feeling: '安心',
    importance: 4,
  })

  assert.equal(saved.space_key, MEMORY_SPACES.GPT)
  assert.equal(saved.created_by_actor, MEMORY_ACTORS.GPT)
  assert.equal(saved.memory_type, 'feeling')
  assert.equal(saved.importance, 4)
  assert.equal(saved.source_type, 'mcp_runtime')
  assert.equal(saved.source_model, MEMORY_ACTORS.GPT)
  assert.equal(saved.source, undefined)
  assert.equal(repository.lastInsert.actor, MEMORY_ACTORS.GPT)
  assert.equal(repository.lastInsert.space_key, undefined)
})

test('Claude can write only to Claude Memory', async () => {
  const { service } = createService([])
  const saved = await service.write(MEMORY_ACTORS.CLAUDE, {
    content: 'claude memory',
    tag: '日记',
  })

  assert.equal(saved.space_key, MEMORY_SPACES.CLAUDE)
  assert.equal(saved.memory_type, 'diary')
})

test('GPT reads GPT Memory plus explicitly approved Shared Memory', async () => {
  const { service } = createService()
  const rows = await service.recall(MEMORY_ACTORS.GPT, { query: 'memory' })

  assert.deepEqual(rows.map(row => row.id), [1, 3])
})

test('Claude reads Claude Memory plus explicitly approved Shared Memory', async () => {
  const { service } = createService()
  const rows = await service.recall(MEMORY_ACTORS.CLAUDE, { query: 'memory' })

  assert.deepEqual(rows.map(row => row.id), [2, 3])
})

test('AI Memory Box uses the canonical scope and keeps only own private plus approved Shared', async () => {
  const { repository, service } = createService()
  const box = await service.memoryBox(MEMORY_ACTORS.GPT, { limit: 3 }, { requestId: 'box-request' })

  assert.equal(box.schema_version, 'lovehouse.memory_box.v1')
  assert.equal(box.actor, MEMORY_ACTORS.GPT)
  assert.equal(box.mode, 'random_history')
  assert.deepEqual(box.items.map(row => row.id), [1, 3])
  assert.deepEqual(repository.lastMemoryBox.scope, {
    privateSpace: MEMORY_SPACES.GPT,
    sharedSpace: MEMORY_SPACES.SHARED,
    requiredSharedState: SHARED_STATES.APPROVED,
  })
  assert.equal(repository.lastMemoryBox.limit, 3)
  assert.equal(repository.lastMemoryBox.requestId, 'box-request')
})

test('AI Memory Box rejects authority and revision spoofing before repository access', async () => {
  for (const input of [
    { actor: 'claude' },
    { namespace: 'legacy_pending' },
    { space_key: 'shared' },
    { revision_id: 99 },
  ]) {
    const { repository, service } = createService()
    await assert.rejects(
      service.memoryBox(MEMORY_ACTORS.GPT, input),
      error => error instanceof MemoryAccessError && error.code === 'SPACE_OVERRIDE_REJECTED'
    )
    assert.equal(repository.lastMemoryBox, null)
  }
})

test('semantic recall uses hybrid ranking without changing the AI-facing input', async () => {
  const calls = []
  const repository = {
    transactionalAudit: true,
    async hybridSearch(input) {
      calls.push(input)
      return [
        { id: 1, space_key: 'gpt', content: 'semantic private' },
        { id: 2, space_key: 'claude', content: 'repository leak' },
      ]
    },
  }
  const service = new MemoryService({
    repository,
    auditSink: { persistent: true, async record() {} },
    semanticRecallEnabled: true,
    embeddingProvider: {
      async embed(query) {
        assert.equal(query, '没有原词的意思')
        return { vector: [0.1, 0.9], profile: 'semantic-test-v1', model: 'test-model', dimensions: 2 }
      },
    },
    rankingProfile: 'ranking_v1',
  })

  const rows = await service.recall('gpt', { query: '没有原词的意思', limit: 5 })
  assert.deepEqual(rows.map(row => row.id), [1])
  assert.deepEqual(calls[0].queryEmbedding, [0.1, 0.9])
  assert.equal(calls[0].queryEmbeddingProfile, 'semantic-test-v1')
  assert.equal(calls[0].queryEmbeddingModel, 'test-model')
  assert.equal(calls[0].rankingProfile, 'ranking_v1')
})

test('semantic provider failure is persistently audited before keyword fallback', async () => {
  const events = []
  let keywordCalls = 0
  const repository = {
    transactionalAudit: true,
    async search() {
      keywordCalls += 1
      return [{ id: 1, space_key: 'gpt', content: 'keyword result' }]
    },
  }
  const error = new Error('provider offline')
  error.code = 'MEMORY_EMBEDDING_NETWORK_ERROR'
  const service = new MemoryService({
    repository,
    auditSink: { persistent: true, async record(event) { events.push(event) } },
    semanticRecallEnabled: true,
    embeddingProvider: { async embed() { throw error } },
  })

  const rows = await service.recall('gpt', { query: 'keyword' })
  assert.deepEqual(rows.map(row => row.id), [1])
  assert.equal(keywordCalls, 1)
  assert.equal(events[0].action, 'recall_semantic_fallback')
  assert.equal(events[0].reason_code, 'MEMORY_EMBEDDING_NETWORK_ERROR')
  assert.equal(JSON.stringify(events).includes('keyword result'), false)
})

test('fallback audit failure keeps keyword recall fail closed', async () => {
  let keywordCalls = 0
  const error = new Error('provider offline')
  error.code = 'MEMORY_EMBEDDING_TIMEOUT'
  const service = new MemoryService({
    repository: {
      transactionalAudit: true,
      async search() { keywordCalls += 1; return [] },
    },
    auditSink: {
      persistent: true,
      async record() { throw new Error('audit unavailable') },
    },
    semanticRecallEnabled: true,
    embeddingProvider: { async embed() { throw error } },
  })
  await assert.rejects(service.recall('gpt', { query: 'keyword' }), /audit unavailable/)
  assert.equal(keywordCalls, 0)
})

test('security failures never use keyword fallback', async () => {
  let keywordCalls = 0
  const securityError = new Error('scope denied')
  securityError.code = 'MEMORY_ACCESS_DENIED'
  const service = new MemoryService({
    repository: {
      transactionalAudit: true,
      async hybridSearch() { throw securityError },
      async search() { keywordCalls += 1; return [] },
    },
    auditSink: { persistent: true, async record() {} },
    semanticRecallEnabled: true,
    embeddingProvider: {
      async embed() { return { vector: [0.1, 0.9], profile: 'semantic-test-v1', model: 'test-model', dimensions: 2 } },
    },
  })
  await assert.rejects(
    service.recall('gpt', { query: 'private' }),
    error => error.code === 'MEMORY_ACCESS_DENIED'
  )
  assert.equal(keywordCalls, 0)
})

test('config, ranking and embedding identity errors cannot use keyword fallback', async () => {
  for (const code of [
    'MEMORY_EMBEDDING_NOT_CONFIGURED',
    'MEMORY_EMBEDDING_AUTHORIZATION_FAILED',
    'MEMORY_EMBEDDING_CLIENT_ERROR',
    'MEMORY_RANKING_PROFILE_INVALID',
    'MEMORY_EMBEDDING_IDENTITY_MISMATCH',
  ]) {
    let keywordCalls = 0
    const service = new MemoryService({
      repository: {
        transactionalAudit: true,
        async hybridSearch() { const error = new Error(code); error.code = code; throw error },
        async search() { keywordCalls += 1; return [] },
      },
      auditSink: { persistent: true, async record() {} },
      semanticRecallEnabled: true,
      embeddingProvider: {
        async embed() {
          if (code.startsWith('MEMORY_EMBEDDING_')
            && code !== 'MEMORY_EMBEDDING_IDENTITY_MISMATCH') {
            const error = new Error(code)
            error.code = code
            throw error
          }
          return {
            vector: [0.1, 0.9], profile: 'wrong-profile', model: 'wrong-model', dimensions: 2,
          }
        },
      },
    })
    await assert.rejects(
      service.recall('gpt', { query: 'private' }),
      error => error.code === code
    )
    assert.equal(keywordCalls, 0, code)
  }
})

test('GPT cannot read Claude private memory by id', async () => {
  const { service } = createService()
  await assert.rejects(
    service.get(MEMORY_ACTORS.GPT, 2),
    error => error instanceof MemoryAccessError && error.code === 'MEMORY_ACCESS_DENIED'
  )
})

test('Claude cannot read GPT private memory by id', async () => {
  const { service } = createService()
  await assert.rejects(
    service.get(MEMORY_ACTORS.CLAUDE, 1),
    error => error instanceof MemoryAccessError && error.code === 'MEMORY_ACCESS_DENIED'
  )
})

test('unapproved Shared and Legacy Pending never appear in daily recall', async () => {
  const { service } = createService()
  const gptRows = await service.recall(MEMORY_ACTORS.GPT, { query: 'anything' })
  const claudeRows = await service.recall(MEMORY_ACTORS.CLAUDE, { query: 'anything' })

  assert.equal(gptRows.some(row => row.id === 4 || row.id === 5), false)
  assert.equal(claudeRows.some(row => row.id === 4 || row.id === 5), false)
})

test('direct reads of unapproved Shared and Legacy Pending fail closed', async () => {
  const { service } = createService()
  await assert.rejects(
    service.get(MEMORY_ACTORS.GPT, 4),
    error => error instanceof MemoryAccessError && error.code === 'MEMORY_ACCESS_DENIED'
  )
  await assert.rejects(
    service.get(MEMORY_ACTORS.CLAUDE, 5),
    error => error instanceof MemoryAccessError && error.code === 'MEMORY_ACCESS_DENIED'
  )
})

for (const attemptedKey of [
  'space_key',
  'spaceKey',
  'namespace',
  'space',
  'actor',
  'created_by_actor',
  'owner_id',
  'permissions',
  'revision_id',
  'revision_hash',
  'source_revision_id',
  'source_revision_hash',
  'request_hash',
  'request_id',
  'idempotency_key',
  'shared_status',
  'approval_status',
]) {
  test(`forged ${attemptedKey} is rejected instead of trusted`, async () => {
    const { service } = createService([])
    await assert.rejects(
      service.write(MEMORY_ACTORS.GPT, {
        content: 'forged',
        [attemptedKey]: MEMORY_SPACES.CLAUDE,
      }),
      error => error instanceof MemoryAccessError && error.code === 'SPACE_OVERRIDE_REJECTED'
    )
  })
}

test('approved Shared memory is read-only for both MCP actors', () => {
  const policy = new MemoryAccessPolicy()
  const approvedShared = {
    space_key: MEMORY_SPACES.SHARED,
    shared_status: SHARED_STATES.APPROVED,
  }

  for (const actor of [MEMORY_ACTORS.GPT, MEMORY_ACTORS.CLAUDE]) {
    assert.equal(policy.canRead(actor, approvedShared), true)
    assert.equal(policy.canMutate(actor, approvedShared), false)
    assert.throws(
      () => policy.assertCanMutate(actor, approvedShared),
      error => error instanceof MemoryAccessError && error.code === 'MEMORY_ACCESS_DENIED'
    )
  }
})

test('Shared approval fields cannot be supplied through the ordinary write path', async () => {
  const { service } = createService([])
  await assert.rejects(
    service.write(MEMORY_ACTORS.GPT, {
      content: 'not a direct shared write',
      shared_status: SHARED_STATES.APPROVED,
    }),
    error => error instanceof MemoryAccessError && error.code === 'SPACE_OVERRIDE_REJECTED'
  )
})

test('memory writes remain disabled without a persistent audit sink', async () => {
  const repository = new InMemoryRepository([])
  const auditSink = new InMemoryAuditSink()
  const service = new MemoryService({
    repository,
    auditSink,
    writeEnabled: true,
  })

  await assert.rejects(
    service.write(MEMORY_ACTORS.GPT, { content: 'must not persist' }),
    error => error.code === 'MEMORY_WRITES_DISABLED'
  )
  assert.equal(repository.lastInsert, null)
  assert.equal(auditSink.events[0].allowed, false)
  assert.equal(auditSink.events[0].reason_code, 'MEMORY_WRITES_DISABLED')
})

test('nested forged namespace is rejected', async () => {
  const { service } = createService()
  await assert.rejects(
    service.recall(MEMORY_ACTORS.GPT, {
      query: 'test',
      filter: { namespace: MEMORY_SPACES.CLAUDE },
    }),
    error => error instanceof MemoryAccessError && error.code === 'SPACE_OVERRIDE_REJECTED'
  )
})

test('repository receives a server-created actor scope', async () => {
  const { repository, service } = createService()
  await service.recall(MEMORY_ACTORS.GPT, { query: 'test' })

  assert.deepEqual(repository.lastSearch.scope, {
    privateSpace: MEMORY_SPACES.GPT,
    sharedSpace: MEMORY_SPACES.SHARED,
    requiredSharedState: SHARED_STATES.APPROVED,
  })
})

test('audit metadata records allowed and denied access without memory content', async () => {
  const repository = new InMemoryRepository(seed)
  const auditSink = new InMemoryAuditSink()
  const service = new MemoryService({
    repository,
    auditSink,
    clock: () => new Date('2026-08-09T00:00:00.000Z'),
  })

  await service.get(MEMORY_ACTORS.GPT, 1)
  await assert.rejects(service.get(MEMORY_ACTORS.GPT, 2))

  assert.equal(auditSink.events[0].allowed, true)
  assert.equal(auditSink.events[0].memory_id, 1)
  assert.equal(auditSink.events[1].allowed, false)
  assert.equal(auditSink.events[1].reason_code, 'MEMORY_ACCESS_DENIED')
  assert.equal(auditSink.events[1].memory_id, 2)
  assert.equal(auditSink.events[1].target_space, MEMORY_SPACES.CLAUDE)
  assert.equal(JSON.stringify(auditSink.events).includes('gpt private'), false)
})

test('transactional repository owns mutation audit and receives the trusted request id', async () => {
  const calls = []
  const repository = {
    transactionalAudit: true,
    async remember(entry, context) {
      calls.push({ entry, context })
      return { id: 91, space_key: context.actor, ...entry }
    },
  }
  let externalAudits = 0
  const service = new MemoryService({
    repository,
    auditSink: { persistent: true, async record() { externalAudits += 1 } },
    writeEnabled: true,
  })
  const requestId = '10000000-0000-4000-8000-000000000099'
  const saved = await service.write(
    MEMORY_ACTORS.GPT,
    { content: 'transactionally audited' },
    { requestId }
  )
  assert.equal(saved.id, 91)
  assert.deepEqual(calls[0].context, { actor: MEMORY_ACTORS.GPT, requestId })
  assert.equal(externalAudits, 0)
})

test('an already-audited database denial is not double-audited', async () => {
  let externalAudits = 0
  const repository = {
    transactionalAudit: true,
    async getById() {
      const error = new Error('denied')
      error.code = 'MEMORY_ACCESS_DENIED'
      error.auditPersisted = true
      throw error
    },
  }
  const service = new MemoryService({
    repository,
    auditSink: { persistent: true, async record() { externalAudits += 1 } },
  })
  await assert.rejects(service.get(MEMORY_ACTORS.GPT, 7))
  assert.equal(externalAudits, 0)
})

test('preflight audit failure keeps a rejected mutation fail closed', async () => {
  let repositoryCalls = 0
  const service = new MemoryService({
    repository: {
      transactionalAudit: true,
      async remember() { repositoryCalls += 1 },
    },
    auditSink: {
      persistent: true,
      async record() { throw new Error('audit unavailable') },
    },
    writeEnabled: true,
  })
  await assert.rejects(
    service.write(MEMORY_ACTORS.GPT, { content: 'blocked', space_key: 'shared' }),
    /audit unavailable/
  )
  assert.equal(repositoryCalls, 0)
})

test('revise and Shared proposal keep actor and runtime-only fields behind the service', async () => {
  const calls = []
  const repository = {
    transactionalAudit: true,
    async revise(...args) { calls.push(['revise', ...args]); return { id: 5, space_key: 'gpt' } },
    async proposeShared(...args) {
      calls.push(['proposeShared', ...args])
      return { id: 6, space_key: 'shared', shared_status: 'candidate' }
    },
  }
  const service = new MemoryService({
    repository,
    auditSink: { persistent: true, async record() {} },
    writeEnabled: true,
  })
  await service.revise(
    MEMORY_ACTORS.GPT,
    { memory_id: 5, content: 'revised', reason: 'clarify' },
    { requestId: '10000000-0000-4000-8000-000000000101' }
  )
  await service.proposeShared(
    MEMORY_ACTORS.GPT,
    { memory_id: 5, reason: 'useful to both' },
    { requestId: '10000000-0000-4000-8000-000000000102' }
  )
  assert.deepEqual(calls[0], [
    'revise',
    5,
    { content: 'revised' },
    'clarify',
    { actor: 'gpt', requestId: '10000000-0000-4000-8000-000000000101' },
  ])
  assert.deepEqual(calls[1], [
    'proposeShared',
    5,
    'useful to both',
    { actor: 'gpt', requestId: '10000000-0000-4000-8000-000000000102' },
  ])
})
