import assert from 'node:assert/strict'
import test from 'node:test'

import {
  CODEX_V1_STORAGE_KEYS,
  boundCodexV1History,
  deriveCurrentTurnUsage,
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

test('web current-turn tokens subtract the previous cumulative thread usage', () => {
  const usage = deriveCurrentTurnUsage({
    estimated_input_tokens: 8,
    actual_input_tokens: 150,
    actual_output_tokens: 35,
    total_tokens: 185,
    cumulative_input_tokens: 150,
    cumulative_cached_input_tokens: 40,
    cumulative_output_tokens: 35,
    cumulative_reasoning_output_tokens: 12,
    previous_cumulative_input_tokens: 100,
    previous_cumulative_cached_input_tokens: 30,
    previous_cumulative_output_tokens: 20,
    previous_cumulative_reasoning_output_tokens: 7,
    usage_source: 'codex_cli_cumulative_delta',
    baseline_status: 'known',
  })
  assert.equal(usage.actual_input_tokens, 50)
  assert.equal(usage.cached_input_tokens, 10)
  assert.equal(usage.actual_output_tokens, 15)
  assert.equal(usage.reasoning_output_tokens, 5)
  assert.equal(usage.total_tokens, 65)
  assert.equal(usage.cumulative_input_tokens, 150)
})

test('web never invents a delta while a resumed thread is only establishing its baseline', () => {
  const usage = deriveCurrentTurnUsage({
    cumulative_input_tokens: 500,
    cumulative_cached_input_tokens: 300,
    cumulative_output_tokens: 80,
    cumulative_reasoning_output_tokens: 20,
    previous_cumulative_input_tokens: null,
    previous_cumulative_cached_input_tokens: null,
    previous_cumulative_output_tokens: null,
    previous_cumulative_reasoning_output_tokens: null,
    baseline_status: 'establishing',
  })
  assert.equal(usage.actual_input_tokens, null)
  assert.equal(usage.cached_input_tokens, null)
  assert.equal(usage.actual_output_tokens, null)
  assert.equal(usage.reasoning_output_tokens, null)
  assert.equal(usage.total_tokens, null)
})
