import test from 'node:test'
import assert from 'node:assert/strict'

import { createTrustedRequestId } from './requestContext.js'

test('trusted request id is stable for a transport retry and changes across security boundaries', () => {
  const input = {
    actor: 'gpt',
    transportIdentity: 'authenticated-session-1',
    protocolRequestId: 42,
    toolName: 'remember',
  }
  const first = createTrustedRequestId(input)
  const replay = createTrustedRequestId(input)
  assert.equal(first, replay)
  assert.match(first, /^[0-9a-f]{8}-[0-9a-f]{4}-5[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
  assert.notEqual(first, createTrustedRequestId({ ...input, actor: 'claude' }))
  assert.notEqual(first, createTrustedRequestId({ ...input, transportIdentity: 'authenticated-session-2' }))
  assert.notEqual(first, createTrustedRequestId({ ...input, protocolRequestId: 43 }))
  assert.notEqual(first, createTrustedRequestId({ ...input, toolName: 'revise' }))
})

test('trusted request id rejects an unknown actor or missing authenticated transport', () => {
  assert.throws(() => createTrustedRequestId({
    actor: 'owner', transportIdentity: 'x', protocolRequestId: 1, toolName: 'remember',
  }))
  assert.throws(() => createTrustedRequestId({
    actor: 'gpt', protocolRequestId: 1, toolName: 'remember',
  }))
})
