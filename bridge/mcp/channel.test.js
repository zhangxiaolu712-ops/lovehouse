import test from 'node:test'
import assert from 'node:assert/strict'

import { createLivingroomRest } from '../livingroom.js'
import { MEMORY_ACTORS } from '../memory/index.js'
import { createMcpChannel } from './channel.js'

const emptyLivingroomFence = () => createLivingroomRest({ rest: async () => [] })

function recordingMemoryV2Service(calls) {
  return {
    forActor(actor) {
      calls.push(['forActor', actor])
      return {
        async remember(input) {
          calls.push(['remember', actor, input])
          return { actor }
        },
        async recall(input) {
          calls.push(['recall', actor, input])
          return { actor, items: [] }
        },
      }
    },
  }
}

for (const expectedActor of [MEMORY_ACTORS.GPT, MEMORY_ACTORS.CLAUDE]) {
  test(`${expectedActor} channel exposes exactly seven tools and fixes its Memory V2 actor`, async () => {
    const calls = []
    const channel = createMcpChannel({
      actor: expectedActor,
      memoryV2Service: recordingMemoryV2Service(calls),
      livingroomRest: emptyLivingroomFence(),
    })
    const forgedActor = expectedActor === MEMORY_ACTORS.GPT
      ? MEMORY_ACTORS.CLAUDE
      : MEMORY_ACTORS.GPT

    assert.deepEqual(channel.tools.map(tool => tool.name), [
      'wake_up',
      'remember',
      'recall',
      'revise',
      'open_memory',
      'read_livingroom',
      'say_livingroom',
    ])
    await channel.callTool('remember', {
      content: 'server actor wins',
      actor: forgedActor,
      space_key: forgedActor,
    }, {
      body: { actor: forgedActor },
      query: { actor: forgedActor },
      headers: { 'x-memory-actor': forgedActor },
    })
    await channel.callTool('recall', { query: 'rose', actor: forgedActor })

    assert.equal(channel.actor, expectedActor)
    assert.deepEqual(calls.map(call => call[1]), [expectedActor, expectedActor, expectedActor])
    assert.equal(calls[1][2].actor, undefined)
    assert.equal(calls[1][2].space_key, undefined)
  })
}
