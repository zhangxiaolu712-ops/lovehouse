import test from 'node:test'
import assert from 'node:assert/strict'

import {
  HouseRulesConfigurationError,
  MEMORY_ACTORS,
  MemoryService,
} from '../memory/index.js'
import {
  createMcpToolDefinitions,
  createMcpToolHandler,
  MCP_TOOL_ROUTES,
} from './tools.js'

test('MCP schemas expose no author, authority, owner, revision, hash, space or Shared approval selector', () => {
  for (const actor of [MEMORY_ACTORS.GPT, MEMORY_ACTORS.CLAUDE]) {
    for (const tool of createMcpToolDefinitions(actor)) {
      const propertyNames = Object.keys(tool.inputSchema.properties || {})
      assert.deepEqual(
        propertyNames.filter(name => (
          /^(actor|author|created_by_actor|owner|owner_id|permission|permissions|revision_id|revision_hash|source_revision_id|source_revision_hash|request_id|request_hash|idempotency_key|space_key|spaceKey|namespace|shared_status|approval_status)$/
            .test(name)
        )),
        [],
        tool.name
      )
    }
  }
})

test('all nine compatibility tools remain and all thirteen tools have an explicit adapter route', () => {
  const toolNames = createMcpToolDefinitions(MEMORY_ACTORS.GPT).map(tool => tool.name)
  assert.equal(toolNames.length, 13)
  assert.deepEqual(Object.keys(MCP_TOOL_ROUTES), toolNames)
  assert.deepEqual(
    Object.values(MCP_TOOL_ROUTES).slice(3, 10),
    [
      'memory.starterPack',
      'memory.memoryBox',
      'memory.write',
      'memory.recall',
      'memory.list',
      'memory.recall',
      'memory.write',
    ]
  )
})

test('all thirteen adapter routes reach only MemoryService or livingroom REST', async () => {
  const calls = []
  const memoryService = {
    async write() { calls.push('memory.write'); return { id: 1 } },
    async recall() { calls.push('memory.recall'); return [] },
    async list() { calls.push('memory.list'); return [] },
    async starterPack() { calls.push('memory.starterPack'); return {} },
    async memoryBox() { calls.push('memory.memoryBox'); return {} },
    async get() { calls.push('memory.get'); return null },
    async revise() { calls.push('memory.revise'); return {} },
    async proposeShared() { calls.push('memory.proposeShared'); return {} },
  }
  const handler = createMcpToolHandler({
    actor: MEMORY_ACTORS.GPT,
    memoryService,
    livingroomRest: async (method, path) => {
      calls.push(method === 'POST'
        ? 'livingroom.write'
        : path.includes('limit=20') ? 'livingroom.context' : 'livingroom.read')
      return []
    },
  })

  await handler('read_livingroom_messages', {})
  await handler('send_livingroom_message', { message: 'hello' })
  await handler('get_livingroom_context', {})
  await handler('get_starter_pack', {})
  await handler('open_memory_box', {})
  await handler('save_memory', { content: 'one' })
  await handler('recall', { query: 'two' })
  await handler('load_memories', {})
  await handler('search_memories', { keyword: 'three' })
  await handler('save_to_memories', { content: 'four' })
  await handler('get_memory', { memory_id: 1 })
  await handler('revise_memory', { memory_id: 1, content: 'five', reason: 'clarify' })
  await handler('propose_shared_candidate', { memory_id: 1, reason: 'useful together' })

  assert.deepEqual(calls, [
    'livingroom.read',
    'livingroom.write',
    'livingroom.context',
    'memory.starterPack',
    'memory.memoryBox',
    'memory.write',
    'memory.recall',
    'memory.list',
    'memory.recall',
    'memory.write',
    'memory.get',
    'memory.revise',
    'memory.proposeShared',
  ])
})

test('all MCP tool schemas reject unknown arguments', () => {
  for (const tool of createMcpToolDefinitions(MEMORY_ACTORS.GPT)) {
    assert.equal(tool.inputSchema.additionalProperties, false, tool.name)
  }
})

test('starter pack describes the complete session-start contract to a new AI', () => {
  for (const actor of [MEMORY_ACTORS.GPT, MEMORY_ACTORS.CLAUDE]) {
    const tool = createMcpToolDefinitions(actor).find(candidate => candidate.name === 'get_starter_pack')
    assert.match(tool.description, /新对话开始时先调用/)
    assert.match(tool.description, /House Rules/)
    assert.match(tool.description, /无需预先了解 LoveHouse 历史/)
    assert.match(tool.description, /不会返回另一 AI 的私有记忆或 Legacy Pending/)
  }
})

test('Memory Box tool is understandable to a memoryless AI and exposes only a small limit', () => {
  const tool = createMcpToolDefinitions(MEMORY_ACTORS.GPT)
    .find(candidate => candidate.name === 'open_memory_box')
  assert.deepEqual(Object.keys(tool.inputSchema.properties), ['limit'])
  assert.equal(tool.inputSchema.properties.limit.default, 3)
  assert.equal(tool.inputSchema.properties.limit.minimum, 1)
  assert.equal(tool.inputSchema.properties.limit.maximum, 4)
  assert.match(tool.description, /get_starter_pack/)
  assert.match(tool.description, /不是相关性搜索/)
  assert.match(tool.description, /接受旧理解、质疑它/)
  assert.match(tool.description, /revise_memory/)
  assert.match(tool.description, /暂时不处理/)
})

test('save_memory explains first-person AI diary semantics and the fixed server author', () => {
  for (const actor of [MEMORY_ACTORS.GPT, MEMORY_ACTORS.CLAUDE]) {
    const tool = createMcpToolDefinitions(actor).find(candidate => candidate.name === 'save_memory')
    assert.match(tool.description, /memory_type=diary/)
    assert.match(tool.description, /第一人称记录自己的经历、判断、感受和变化/)
    assert.match(tool.description, /不是对小婷的观察报告/)
    assert.match(tool.description, new RegExp(`服务端固定为 ${actor}`))
    assert.equal('author' in tool.inputSchema.properties, false)
  }
})

test('GPT and Claude receive House Rules through the actual starter-pack MCP adapter', async () => {
  const rows = [
    { id: 1, space_key: 'gpt', content: 'gpt private' },
    { id: 2, space_key: 'claude', content: 'claude private' },
    { id: 3, space_key: 'shared', shared_status: 'approved', content: 'approved shared' },
  ]
  for (const actor of [MEMORY_ACTORS.GPT, MEMORY_ACTORS.CLAUDE]) {
    const memoryService = new MemoryService({
      repository: { async list() { return rows } },
      auditSink: { async record() {} },
    })
    const handler = createMcpToolHandler({ actor, memoryService, livingroomRest: async () => [] })
    const pack = JSON.parse(await handler('get_starter_pack', {}))
    assert.equal(pack.actor, actor)
    assert.equal(pack.house_rules.schema_version, 'lovehouse.house_rules.v1')
    assert.deepEqual(pack.private_memories.map(memory => memory.space_key), [actor])
    assert.deepEqual(pack.shared_memories.map(memory => memory.id), [3])
  }
})

test('House Rules failure is isolated to get_starter_pack and other MCP tools remain usable', async () => {
  const repositoryCalls = []
  const memoryService = new MemoryService({
    repository: {
      async list() {
        repositoryCalls.push('list')
        return []
      },
      async search() {
        repositoryCalls.push('search')
        return []
      },
    },
    houseRulesProvider: {
      async getRules() {
        throw new HouseRulesConfigurationError('missing')
      },
    },
    auditSink: { async record() {} },
  })
  const handler = createMcpToolHandler({
    actor: MEMORY_ACTORS.GPT,
    memoryService,
    livingroomRest: async () => [],
  })

  await assert.rejects(
    handler('get_starter_pack', {}),
    error => error.code === 'HOUSE_RULES_CONFIGURATION_INVALID'
  )
  assert.deepEqual(JSON.parse(await handler('recall', { query: 'still available' })), [])
  assert.deepEqual(repositoryCalls, ['search'])
})

test('GPT compatibility tools call one MemoryService with fixed GPT actor', async () => {
  const calls = []
  const memoryService = {
    async write(...args) { calls.push(['write', ...args]); return { id: 1 } },
    async recall(...args) { calls.push(['recall', ...args]); return [] },
    async list(...args) { calls.push(['list', ...args]); return [] },
    async starterPack(...args) { calls.push(['starterPack', ...args]); return {} },
    async memoryBox(...args) { calls.push(['memoryBox', ...args]); return {} },
    async get(...args) { calls.push(['get', ...args]); return null },
    async revise(...args) { calls.push(['revise', ...args]); return {} },
    async proposeShared(...args) { calls.push(['proposeShared', ...args]); return {} },
  }
  const handler = createMcpToolHandler({
    actor: MEMORY_ACTORS.GPT,
    memoryService,
    livingroomRest: async () => [],
  })

  await handler('save_memory', { content: 'one' })
  await handler('save_to_memories', { content: 'two', category: '日常点滴' })
  await handler('recall', { query: 'three' })
  await handler('search_memories', { keyword: 'four' })
  await handler('load_memories', { level: '固定' })
  await handler('open_memory_box', { limit: 4 })

  assert.deepEqual(calls.map(call => call[1]), [
    MEMORY_ACTORS.GPT,
    MEMORY_ACTORS.GPT,
    MEMORY_ACTORS.GPT,
    MEMORY_ACTORS.GPT,
    MEMORY_ACTORS.GPT,
    MEMORY_ACTORS.GPT,
  ])
})
test('Claude livingroom sender is fixed by the adapter', async () => {
  const restCalls = []
  const handler = createMcpToolHandler({
    actor: MEMORY_ACTORS.CLAUDE,
    memoryService: {},
    livingroomRest: async (...args) => {
      restCalls.push(args)
      return [{ id: 1 }]
    },
  })

  await handler('send_livingroom_message', { message: 'hello', sender: 'GPT' })
  assert.deepEqual(restCalls[0], [
    'POST',
    'livingroom',
    { sender: 'CC', message: 'hello' },
  ])
})
