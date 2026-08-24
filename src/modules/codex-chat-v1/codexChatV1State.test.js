import assert from 'node:assert/strict'
import test from 'node:test'

import {
  CODEX_V1_STORAGE_KEYS,
  boundCodexV1History,
  getCodexV1Identity,
  loadCodexV1History,
  saveCodexV1History,
} from './codexChatV1State.js'

function storage() {
  const values = new Map()
  return {
    getItem: key => values.get(key) || null,
    setItem: (key, value) => values.set(key, String(value)),
  }
}

test('LoveHouse thread and client window persist independently without provider session state', () => {
  const local = storage()
  const first = getCodexV1Identity(local)
  const second = getCodexV1Identity(local)
  assert.deepEqual(second, first)
  assert.notEqual(first.threadId, first.windowId)
  assert.equal(local.getItem(CODEX_V1_STORAGE_KEYS.THREAD_KEY), first.threadId)
  assert.equal(local.getItem(CODEX_V1_STORAGE_KEYS.WINDOW_KEY), first.windowId)
  assert.equal(JSON.stringify([...Object.values(CODEX_V1_STORAGE_KEYS)]).includes('session'), false)
})

test('recent UI history is bounded and strips runtime/session metadata', () => {
  const local = storage()
  const messages = Array.from({ length: 20 }, (_, index) => ({
    role: index % 2 ? 'assistant' : 'user',
    content: `message-${index}`,
    runtime_session_id: 'must-not-persist',
  }))
  const saved = saveCodexV1History(messages, local)
  assert.equal(saved.length, 12)
  assert.deepEqual(loadCodexV1History(local), saved)
  assert.equal(JSON.stringify(saved).includes('runtime_session_id'), false)
  assert.deepEqual(boundCodexV1History(null), [])
})
