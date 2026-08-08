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
  }

  async insert(entry) {
    this.lastInsert = { ...entry }
    const saved = { id: this.rows.length + 1, ...entry }
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
  assert.equal(saved.source_type, 'mcp')
  assert.equal(saved.source_model, MEMORY_ACTORS.GPT)
  assert.equal(saved.source, undefined)
  assert.equal(repository.lastInsert.space_key, MEMORY_SPACES.GPT)
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
