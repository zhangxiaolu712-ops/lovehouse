import test from 'node:test'
import assert from 'node:assert/strict'

import { MEMORY_ACTORS } from '../memory/index.js'
import { createMcpChannel } from './channel.js'

function recordingMemoryService(calls) {
  return {
    async write(...args) { calls.push(['write', ...args]); return { id: 1 } },
    async recall(...args) { calls.push(['recall', ...args]); return [] },
    async list(...args) { calls.push(['list', ...args]); return [] },
    async starterPack(...args) { calls.push(['starterPack', ...args]); return {} },
    async get(...args) { calls.push(['get', ...args]); return null },
    async revise(...args) { calls.push(['revise', ...args]); return {} },
    async proposeShared(...args) { calls.push(['proposeShared', ...args]); return {} },
  }
}

for (const expectedActor of [MEMORY_ACTORS.GPT, MEMORY_ACTORS.CLAUDE]) {
  test(`${expectedActor} channel ignores actor spoofing in body, query, headers and tool args`, async () => {
    const calls = []
    const channel = createMcpChannel({
      actor: expectedActor,
      memoryService: recordingMemoryService(calls),
      livingroomRest: async () => [],
    })
    const forgedActor = expectedActor === MEMORY_ACTORS.GPT
      ? MEMORY_ACTORS.CLAUDE
      : MEMORY_ACTORS.GPT

    await channel.callTool(
      'save_memory',
      {
        content: 'server actor wins',
        actor: forgedActor,
        space_key: forgedActor,
      },
      {
        body: { actor: forgedActor },
        query: { actor: forgedActor, space_key: forgedActor },
        headers: {
          actor: forgedActor,
          'x-memory-actor': forgedActor,
          'x-memory-space': forgedActor,
        },
      }
    )

    assert.equal(channel.actor, expectedActor)
    assert.equal(calls[0][1], expectedActor)
  })

  test(`${expectedActor} channel forwards only trusted request context`, async () => {
    const calls = []
    const channel = createMcpChannel({
      actor: expectedActor,
      memoryService: recordingMemoryService(calls),
      livingroomRest: async () => [],
    })
    await channel.callTool('recall', { query: 'rose' }, { requestId: 'trusted-id' })
    assert.deepEqual(calls[0][3], { requestId: 'trusted-id' })
  })
}
