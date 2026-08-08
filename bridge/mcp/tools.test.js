import test from 'node:test'
import assert from 'node:assert/strict'

import { MEMORY_ACTORS } from '../memory/index.js'
import { createMcpToolDefinitions, createMcpToolHandler } from './tools.js'

test('MCP schemas do not expose a namespace selector', () => {
  for (const actor of [MEMORY_ACTORS.GPT, MEMORY_ACTORS.CLAUDE]) {
    const serialized = JSON.stringify(createMcpToolDefinitions(actor))
    assert.doesNotMatch(serialized, /space_key|spaceKey|namespace/)
  }
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
