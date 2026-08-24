import assert from 'node:assert/strict'
import test from 'node:test'

import {
  CLAUDE_V1_STORAGE_KEYS,
  boundClaudeV1History,
  getClaudeV1Identity,
  loadClaudeV1History,
  saveClaudeV1History,
} from './claudeChatV1State.js'

function storage() {
  const values = new Map()
  return {
    getItem: key => values.get(key) || null,
    setItem: (key, value) => values.set(key, String(value)),
  }
}

test('Claude LoveHouse thread persists independently without provider session state', () => {
  const local = storage()
  const first = getClaudeV1Identity(local)
  const second = getClaudeV1Identity(local)
  assert.deepEqual(second, first)
  assert.notEqual(first.threadId, first.windowId)
  assert.equal(local.getItem(CLAUDE_V1_STORAGE_KEYS.THREAD_KEY), first.threadId)
  assert.equal(local.getItem(CLAUDE_V1_STORAGE_KEYS.WINDOW_KEY), first.windowId)
  assert.equal(JSON.stringify(Object.values(CLAUDE_V1_STORAGE_KEYS)).includes('session'), false)
})

test('Claude local UI history is bounded and strips provider metadata', () => {
  const local = storage()
  const messages = Array.from({ length: 20 }, (_, index) => ({
    role: index % 2 ? 'assistant' : 'user', content: `message-${index}`,
    provider_session_id: 'must-not-persist',
  }))
  const saved = saveClaudeV1History(messages, local)
  assert.equal(saved.length, 12)
  assert.deepEqual(loadClaudeV1History(local), saved)
  assert.equal(JSON.stringify(saved).includes('provider_session_id'), false)
  assert.deepEqual(boundClaudeV1History(null), [])
})
