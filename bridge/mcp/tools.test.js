import test from 'node:test'
import assert from 'node:assert/strict'

import { MEMORY_ACTORS, MemoryService } from '../memory/index.js'
import {
  createMcpToolDefinitions,
  createMcpToolHandler,
  MCP_TOOL_ROUTES,
} from './tools.js'

test('MCP schemas expose no authority, owner, revision, hash, space or Shared approval selector', () => {
  for (const actor of [MEMORY_ACTORS.GPT, MEMORY_ACTORS.CLAUDE]) {
    for (const tool of createMcpToolDefinitions(actor)) {
      const propertyNames = Object.keys(tool.inputSchema.properties || {})
      assert.deepEqual(
        propertyNames.filter(name => (
          /^(actor|created_by_actor|owner|owner_id|permission|permissions|revision_id|revision_hash|source_revision_id|source_revision_hash|request_id|request_hash|idempotency_key|space_key|spaceKey|namespace|shared_status|approval_status)$/
            .test(name)
        )),
        [],
        tool.name
      )
    }
  }
})

test('all nine compatibility tools remain and all twelve tools have an explicit adapter route', () => {
  const toolNames = createMcpToolDefinitions(MEMORY_ACTORS.GPT).map(tool => tool.name)
  assert.equal(toolNames.length, 12)
  assert.deepEqual(Object.keys(MCP_TOOL_ROUTES), toolNames)
  assert.deepEqual(
    Object.values(MCP_TOOL_ROUTES).slice(3, 9),
    [
      'memory.starterPack',
      'memory.write',
      'memory.recall',
      'memory.list',
      'memory.recall',
      'memory.write',
    ]
  )
})

test('all twelve adapter routes reach only MemoryService or livingroom REST', async () => {
  const calls = []
  const memoryService = {
    async write() { calls.push('memory.write'); return { id: 1 } },
    async recall() { calls.push('memory.recall'); return [] },
    async list() { calls.push('memory.list'); return [] },
    async starterPack() { calls.push('memory.starterPack'); return {} },
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

test('GPT compatibility tools call one MemoryService with fixed GPT actor', async () => {
  const calls = []
  const memoryService = {
    async write(...args) { calls.push(['write', ...args]); return { id: 1 } },
    async recall(...args) { calls.push(['recall', ...args]); return [] },
    async list(...args) { calls.push(['list', ...args]); return [] },
    async starterPack(...args) { calls.push(['starterPack', ...args]); return {} },
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

  assert.deepEqual(calls.map(call => call[1]), [
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
