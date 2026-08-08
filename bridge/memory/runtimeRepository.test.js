import test from 'node:test'
import assert from 'node:assert/strict'

import { MEMORY_ACTORS } from './model.js'
import { MemoryService } from './service.js'
import {
  createRuntimeMemoryRepository,
  DisabledMemoryRepository,
  MemorySystemDisabledError,
} from './runtimeRepository.js'
import { createMcpToolHandler } from '../mcp/tools.js'

test('disabled runtime repository fails closed for every operation without touching canonical storage', async () => {
  let canonicalCalls = 0
  const canonicalRepository = new Proxy({}, {
    get() {
      return async () => { canonicalCalls += 1 }
    },
  })
  const repository = createRuntimeMemoryRepository({ enabled: false, canonicalRepository })

  assert.equal(repository instanceof DisabledMemoryRepository, true)
  for (const operation of [
    () => repository.remember({}),
    () => repository.getById(1),
    () => repository.list({}),
    () => repository.search({}),
    () => repository.revise(1, {}, 'reason'),
    () => repository.proposeShared(1, 'reason'),
  ]) {
    await assert.rejects(
      operation(),
      error => error instanceof MemorySystemDisabledError
        && error.code === 'MEMORY_SYSTEM_DISABLED'
    )
  }
  assert.equal(canonicalCalls, 0)
})

test('enabled runtime repository uses only the canonical repository', () => {
  const canonicalRepository = { name: 'memory_entries only' }
  assert.equal(
    createRuntimeMemoryRepository({ enabled: true, canonicalRepository }),
    canonicalRepository
  )
})

test('all nine memory MCP tools fail closed while Memory System is disabled', async () => {
  const memoryService = new MemoryService({
    repository: createRuntimeMemoryRepository({ enabled: false }),
    auditSink: { persistent: true, async record() {} },
    writeEnabled: true,
  })
  let livingroomCalls = 0
  const handler = createMcpToolHandler({
    actor: MEMORY_ACTORS.GPT,
    memoryService,
    livingroomRest: async () => { livingroomCalls += 1; return [] },
  })
  const calls = [
    ['get_starter_pack', {}],
    ['save_memory', { content: 'blocked' }],
    ['recall', { query: 'blocked' }],
    ['load_memories', {}],
    ['search_memories', { keyword: 'blocked' }],
    ['save_to_memories', { content: 'blocked' }],
    ['get_memory', { memory_id: 1 }],
    ['revise_memory', { memory_id: 1, content: 'blocked', reason: 'test' }],
    ['propose_shared_candidate', { memory_id: 1, reason: 'test' }],
  ]

  for (const [name, args] of calls) {
    await assert.rejects(
      handler(name, args),
      error => error.code === 'MEMORY_SYSTEM_DISABLED'
    )
  }
  assert.equal(livingroomCalls, 0)
})
