import test from 'node:test'
import assert from 'node:assert/strict'

import { MEMORY_ACTORS } from '../memory/index.js'
import {
  createMcpToolDefinitions,
  createMcpToolHandler,
  MCP_TOOL_ROUTES,
} from './tools.js'

test('MCP schemas expose no actor, namespace, space or Shared approval selector', () => {
  for (const actor of [MEMORY_ACTORS.GPT, MEMORY_ACTORS.CLAUDE]) {
    for (const tool of createMcpToolDefinitions(actor)) {
      const propertyNames = Object.keys(tool.inputSchema.properties || {})
      assert.deepEqual(
        propertyNames.filter(name => (
          /^(actor|created_by_actor|space_key|spaceKey|namespace|shared_status|approval_status)$/
            .test(name)
        )),
        [],
        tool.name
      )
    }
  }
})

test('all nine compatibility tools have an explicit adapter route', () => {
  const toolNames = createMcpToolDefinitions(MEMORY_ACTORS.GPT).map(tool => tool.name)
  assert.equal(toolNames.length, 9)
  assert.deepEqual(Object.keys(MCP_TOOL_ROUTES), toolNames)
  assert.deepEqual(
    Object.values(MCP_TOOL_ROUTES).filter(route => route.startsWith('memory.')),
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

test('all nine adapter routes reach only MemoryService or livingroom REST', async () => {
  const calls = []
  const memoryService = {
    async write() { calls.push('memory.write'); return { id: 1 } },
    async recall() { calls.push('memory.recall'); return [] },
    async list() { calls.push('memory.list'); return [] },
    async starterPack() { calls.push('memory.starterPack'); return {} },
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
  ])
})

test('all MCP tool schemas reject unknown arguments', () => {
  for (const tool of createMcpToolDefinitions(MEMORY_ACTORS.GPT)) {
    assert.equal(tool.inputSchema.additionalProperties, false, tool.name)
  }
})

test('GPT compatibility tools call one MemoryService with fixed GPT actor', async () => {
  const calls = []
  const memoryService = {
    async write(...args) { calls.push(['write', ...args]); return { id: 1 } },
    async recall(...args) { calls.push(['recall', ...args]); return [] },
    async list(...args) { calls.push(['list', ...args]); return [] },
    async starterPack(...args) { calls.push(['starterPack', ...args]); return {} },
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
