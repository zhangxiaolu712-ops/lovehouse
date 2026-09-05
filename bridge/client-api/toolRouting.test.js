import assert from 'node:assert/strict'
import test from 'node:test'

import { resolveAllowedToolIdsForChat } from './clientApi.js'

const THREAD_ID = '11111111-1111-4111-8111-111111111111'

test('only a Codex request with a non-empty tool preference reaches Tool Center validation', () => {
  const calls = []
  const toolCenterService = {
    validateRequest(input) {
      calls.push(input)
      return input.requestedIds
    },
  }

  assert.deepEqual(resolveAllowedToolIdsForChat({
    toolCenterService,
    personaId: 'claude',
    threadId: THREAD_ID,
    requestedIds: ['not-a-codex-tool'],
  }), [])
  assert.deepEqual(resolveAllowedToolIdsForChat({
    toolCenterService,
    personaId: 'codex',
    threadId: THREAD_ID,
    requestedIds: [],
  }), [])
  assert.deepEqual(resolveAllowedToolIdsForChat({
    toolCenterService,
    personaId: 'codex',
    threadId: THREAD_ID,
    requestedIds: ['builtin.engineering.read_current'],
  }), ['builtin.engineering.read_current'])

  assert.deepEqual(calls, [{
    personaId: 'codex',
    threadId: THREAD_ID,
    requestedIds: ['builtin.engineering.read_current'],
  }])
})
