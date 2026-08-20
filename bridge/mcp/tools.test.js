import test from 'node:test'
import assert from 'node:assert/strict'

import { createLivingroomRest } from '../livingroom.js'
import { MEMORY_ACTORS } from '../memory/index.js'
import { MemoryV2Service } from '../memory-v2/index.js'
import {
  createMcpToolDefinitions,
  createMcpToolHandler,
  MCP_TOOL_ROUTES,
} from './tools.js'

const MEMORY_ID = '11111111-1111-4111-8111-111111111111'
const SOURCE_ID = '22222222-2222-4222-8222-222222222222'
const REVISION_ID = '33333333-3333-4333-8333-333333333333'
const NOW = new Date('2026-08-21T00:00:00.000Z')
const TOOL_NAMES = [
  'wake_up',
  'remember',
  'recall',
  'revise',
  'open_memory',
  'read_livingroom',
  'say_livingroom',
]

const livingroomFence = rest => createLivingroomRest({ rest })

function facadeService(factory) {
  return {
    forActor(actor) {
      return factory(actor)
    },
  }
}

function recordingFacade(calls, actor) {
  return {
    async starterPack(input) { calls.push(['starterPack', actor, input]); return { actor, items: [] } },
    async remember(input) { calls.push(['remember', actor, input]); return { memory_id: MEMORY_ID } },
    async recall(input) { calls.push(['recall', actor, input]); return { mode: 'semantic', items: [] } },
    async revise(memoryId, input) {
      calls.push(['revise', actor, memoryId, input])
      return { memory_id: memoryId, revision_id: REVISION_ID }
    },
    async history(memoryId) {
      calls.push(['history', actor, memoryId])
      return [{ id: REVISION_ID, sources: [{ source_id: SOURCE_ID, ordinal: 1 }] }]
    },
    async expandSource(sourceId) {
      calls.push(['expandSource', actor, sourceId])
      return { source_id: sourceId, available: true, quote_text: '原文' }
    },
  }
}

function candidate(overrides = {}) {
  return {
    memory_id: MEMORY_ID,
    revision_id: REVISION_ID,
    content: 'current memory',
    metadata: {},
    relevance: 0.9,
    space_key: 'gpt',
    status: 'active',
    created_at: '2026-08-20T00:00:00Z',
    ...overrides,
  }
}

test('formal MCP surface is exactly the seven reviewed tools', () => {
  for (const actor of [MEMORY_ACTORS.GPT, MEMORY_ACTORS.CLAUDE]) {
    const names = createMcpToolDefinitions(actor).map(tool => tool.name)
    assert.deepEqual(names, TOOL_NAMES)
    assert.equal(names.length, 7)
  }
  assert.deepEqual(Object.keys(MCP_TOOL_ROUTES), TOOL_NAMES)
  assert.deepEqual(Object.values(MCP_TOOL_ROUTES), [
    'memory-v2.starterPack',
    'memory-v2.remember',
    'memory-v2.recall',
    'memory-v2.revise',
    'memory-v2.open',
    'livingroom.read',
    'livingroom.write',
  ])
})

test('schemas stay closed and expose no actor, owner, space or Shared approval controls', () => {
  const forbidden = /^(actor|author|created_by_actor|owner|owner_id|permission|permissions|space|space_key|namespace|shared_status|approval_status)$/
  for (const actor of [MEMORY_ACTORS.GPT, MEMORY_ACTORS.CLAUDE]) {
    for (const tool of createMcpToolDefinitions(actor)) {
      const schemas = tool.inputSchema.oneOf || [tool.inputSchema]
      for (const schema of schemas) {
        assert.equal(schema.additionalProperties, false, tool.name)
        assert.deepEqual(Object.keys(schema.properties || {}).filter(name => forbidden.test(name)), [], tool.name)
      }
    }
  }
})

test('seven handler routes are thin Memory V2 or fenced LivingRoom calls', async () => {
  const calls = []
  const memoryV2Service = facadeService(actor => recordingFacade(calls, actor))
  const handler = createMcpToolHandler({
    actor: MEMORY_ACTORS.GPT,
    memoryV2Service,
    livingroomRest: livingroomFence(async (method, path, body) => {
      calls.push([method === 'POST' ? 'livingroom.write' : 'livingroom.read', path, body])
      return method === 'POST'
        ? [{ id: 7, sender: 'GPT', message: body.message }]
        : [{ id: 6, sender: 'CC', message: 'hello' }]
    }),
  })

  await handler('wake_up', { soft_limit: 10, token_budget: 1200 })
  await handler('remember', {
    content: 'one',
    event_time: '2026-08-20T12:00:00+08:00',
    sources: [{ source_kind: 'manual_quote', quote_text: '原文' }],
  })
  await handler('recall', { query: 'two', limit: 3 })
  await handler('revise', { memory_id: MEMORY_ID, content: 'three', reason: 'changed' })
  const history = JSON.parse(await handler('open_memory', { memory_id: MEMORY_ID }))
  const source = JSON.parse(await handler('open_memory', { source_id: SOURCE_ID }))
  const room = JSON.parse(await handler('read_livingroom', { limit: 10 }))
  const sent = JSON.parse(await handler('say_livingroom', { message: 'hi' }))

  assert.deepEqual(calls.slice(0, 6), [
    ['starterPack', 'gpt', { softLimit: 10, tokenBudget: 1200 }],
    ['remember', 'gpt', {
      content: 'one',
      eventTime: '2026-08-20T12:00:00+08:00',
      sources: [{
        sourceKind: 'manual_quote',
        locator: undefined,
        provenance: undefined,
        quoteText: '原文',
      }],
    }],
    ['recall', 'gpt', { query: 'two', limit: 3 }],
    ['revise', 'gpt', MEMORY_ID, { content: 'three', reason: 'changed' }],
    ['history', 'gpt', MEMORY_ID],
    ['expandSource', 'gpt', SOURCE_ID],
  ])
  assert.equal(history.mode, 'history')
  assert.equal(history.revisions[0].sources[0].source_id, SOURCE_ID)
  assert.equal(source.mode, 'source')
  assert.equal(source.source.quote_text, '原文')
  assert.deepEqual(room.messages.map(row => row.id), [6])
  assert.equal(room.context, '[CC] hello')
  assert.equal(sent.sender, 'GPT')
})

test('GPT and Claude channels select fixed facades and ignore actor spoofing', async () => {
  const visible = {
    gpt: [candidate({ content: 'gpt private', space_key: 'gpt' }), candidate({ content: 'shared', space_key: 'shared' })],
    claude: [candidate({ content: 'claude private', space_key: 'claude' }), candidate({ content: 'shared', space_key: 'shared' })],
  }
  for (const actor of [MEMORY_ACTORS.GPT, MEMORY_ACTORS.CLAUDE]) {
    const memoryV2Service = facadeService(fixed => ({
      async recall() { return { mode: 'semantic', items: visible[fixed] } },
      async remember() { return { actor: fixed, memory_id: MEMORY_ID } },
    }))
    const handler = createMcpToolHandler({
      actor,
      memoryV2Service,
      livingroomRest: livingroomFence(async () => []),
    })
    const forged = actor === 'gpt' ? 'claude' : 'gpt'
    const saved = JSON.parse(await handler('remember', {
      content: 'fixed actor wins',
      actor: forged,
      owner_id: 'forged',
      space_key: forged,
    }))
    const recalled = JSON.parse(await handler('recall', { query: 'memory', actor: forged }))
    assert.equal(saved.actor, actor)
    assert.equal(recalled.items.some(item => item.space_key === forged), false)
    assert.equal(recalled.items.some(item => item.space_key === 'shared'), true)
  }
})

test('MCP recall uses Memory V2 semantic mode and preserves current/Shared visibility', async () => {
  const repositoryCalls = []
  const repository = {
    async recallSemantic(actor) {
      repositoryCalls.push(['semantic', actor])
      return [
        candidate({ content: `${actor} current`, space_key: actor }),
        candidate({ memory_id: '44444444-4444-4444-8444-444444444444', content: 'approved shared', space_key: 'shared' }),
      ]
    },
    async recallLexical() { throw new Error('lexical should not run') },
    async recordRecall() {},
  }
  const service = new MemoryV2Service({
    repository,
    embedding: { async embed() { return { vector: [0.1], model: 'test' } } },
    clock: () => NOW,
  })
  const handler = createMcpToolHandler({
    actor: MEMORY_ACTORS.CLAUDE,
    memoryV2Service: service,
    livingroomRest: livingroomFence(async () => []),
  })
  const result = JSON.parse(await handler('recall', { query: '近义问题' }))
  assert.equal(result.mode, 'semantic')
  assert.equal(result.semantic_error, null)
  assert.deepEqual(result.items.map(item => item.content), ['claude current', 'approved shared'])
  assert.deepEqual(repositoryCalls, [['semantic', 'claude']])
  assert.equal(result.items.some(item => item.content.includes('old revision')), false)
})

test('Ollama failure falls back at Memory V2 and does not block remember, revise or LivingRoom', async () => {
  const calls = []
  const embedding = {
    async embed() {
      const error = new Error('offline')
      error.code = 'MEMORY_V2_EMBEDDING_NETWORK_ERROR'
      throw error
    },
  }
  const repository = {
    async remember(actor, content) {
      calls.push(['remember', actor, content])
      return { memory_id: MEMORY_ID, revision_id: REVISION_ID }
    },
    async revise(actor, memoryId, content) {
      calls.push(['revise', actor, memoryId, content])
      return { memory_id: memoryId, revision_id: REVISION_ID }
    },
    async recallLexical(actor) {
      calls.push(['lexical', actor])
      return [candidate({ content: 'fallback result', space_key: actor })]
    },
    async recallSemantic() { throw new Error('semantic repository should not run') },
    async storeEmbedding() {},
    async recordRecall() {},
  }
  const service = new MemoryV2Service({ repository, embedding, clock: () => NOW })
  const handler = createMcpToolHandler({
    actor: MEMORY_ACTORS.GPT,
    memoryV2Service: service,
    livingroomRest: livingroomFence(async (method, _path, body) => {
      calls.push(['livingroom', method])
      return method === 'POST' ? [{ id: 1, sender: 'GPT', message: body.message }] : []
    }),
  })

  assert.equal(JSON.parse(await handler('remember', { content: 'remember offline' })).memory_id, MEMORY_ID)
  assert.equal(JSON.parse(await handler('revise', { memory_id: MEMORY_ID, content: 'revise offline' })).memory_id, MEMORY_ID)
  const recalled = JSON.parse(await handler('recall', { query: 'offline query' }))
  assert.equal(recalled.mode, 'lexical_fallback')
  assert.equal(recalled.semantic_error, 'MEMORY_V2_EMBEDDING_NETWORK_ERROR')
  assert.equal(recalled.items[0].content, 'fallback result')
  assert.equal(JSON.parse(await handler('say_livingroom', { message: 'still works' })).message, 'still works')
})

test('open_memory keeps history descriptors separate from explicit source expansion', async () => {
  const historyRows = [{
    id: REVISION_ID,
    revision_number: 1,
    content: 'current content',
    sources: [{
      source_id: SOURCE_ID,
      source_kind: 'manual_quote',
      locator: {},
      provenance: { channel: 'official_app' },
      ordinal: 1,
    }],
  }]
  const memoryV2Service = facadeService(() => ({
    async history() { return historyRows },
    async expandSource() {
      return { source_id: SOURCE_ID, available: true, quote_text: 'selected quote' }
    },
  }))
  const handler = createMcpToolHandler({
    actor: MEMORY_ACTORS.GPT,
    memoryV2Service,
    livingroomRest: livingroomFence(async () => []),
  })

  const opened = JSON.parse(await handler('open_memory', { memory_id: MEMORY_ID }))
  assert.equal(JSON.stringify(opened).includes('selected quote'), false)
  assert.equal(opened.revisions[0].sources[0].source_id, SOURCE_ID)
  const expanded = JSON.parse(await handler('open_memory', { source_id: SOURCE_ID }))
  assert.equal(expanded.source.quote_text, 'selected quote')
  await assert.rejects(handler('open_memory', {}), /exactly one/)
  await assert.rejects(handler('open_memory', { memory_id: MEMORY_ID, source_id: SOURCE_ID }), /exactly one/)
})

test('LivingRoom sender stays fixed and upstream errors remain explicit', async () => {
  for (const [actor, sender] of [['gpt', 'GPT'], ['claude', 'CC']]) {
    const restCalls = []
    const handler = createMcpToolHandler({
      actor,
      memoryV2Service: facadeService(() => ({})),
      livingroomRest: livingroomFence(async (...args) => {
        restCalls.push(args)
        return [{ id: 1, sender, message: 'hello' }]
      }),
    })
    await handler('say_livingroom', { message: 'hello', sender: sender === 'GPT' ? 'CC' : 'GPT' })
    assert.deepEqual(restCalls[0], ['POST', 'livingroom', { sender, message: 'hello' }])
  }

  const failed = createMcpToolHandler({
    actor: MEMORY_ACTORS.GPT,
    memoryV2Service: facadeService(() => ({})),
    livingroomRest: livingroomFence(async () => ({ status: 401, error: { message: 'unauthorized' } })),
  })
  for (const [name, args] of [['read_livingroom', {}], ['say_livingroom', { message: 'hello' }]]) {
    await assert.rejects(
      failed(name, args),
      error => error.code === 'LIVINGROOM_UPSTREAM_ERROR'
        && error.status === 401
        && /unauthorized/.test(error.message)
    )
  }
})

test('LivingRoom empty reads are real and empty writes cannot become fake success', async () => {
  const handler = createMcpToolHandler({
    actor: MEMORY_ACTORS.GPT,
    memoryV2Service: facadeService(() => ({})),
    livingroomRest: livingroomFence(async () => []),
  })
  assert.deepEqual(JSON.parse(await handler('read_livingroom', {})), { messages: [], context: '' })
  await assert.rejects(
    handler('say_livingroom', { message: 'hello' }),
    error => error.code === 'LIVINGROOM_WRITE_NOT_CONFIRMED'
  )
})

test('MCP refuses a V1 service or raw LivingRoom REST function', () => {
  assert.throws(
    () => createMcpToolHandler({
      actor: MEMORY_ACTORS.GPT,
      memoryV2Service: { recall() {} },
      livingroomRest: livingroomFence(async () => []),
    }),
    /MemoryV2Service/
  )
  assert.throws(
    () => createMcpToolHandler({
      actor: MEMORY_ACTORS.GPT,
      memoryV2Service: facadeService(() => ({})),
      livingroomRest: async () => [],
    }),
    /fenced livingroom REST/
  )
})
