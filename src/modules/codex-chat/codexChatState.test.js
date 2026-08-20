import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

import {
  boundCodexRecentHistory,
  getCodexWindowId,
  loadCodexRecentHistory,
  saveCodexRecentHistory,
} from './codexChatState.js'

class MemoryStorage {
  values = new Map()
  getItem(key) { return this.values.get(key) ?? null }
  setItem(key, value) { this.values.set(key, String(value)) }
}

test('Codex window id remains stable when the page is refreshed', () => {
  const storage = new MemoryStorage()
  const first = getCodexWindowId(storage)
  const reopened = getCodexWindowId(storage)
  assert.match(first, /^[A-Za-z0-9_-]{8,128}$/)
  assert.equal(reopened, first)
})

test('local fallback history is bounded and contains no thread identity', () => {
  const storage = new MemoryStorage()
  const messages = Array.from({ length: 20 }, (_, index) => ({
    role: index % 2 ? 'assistant' : 'user',
    content: `message-${index}`,
    session_id: 'must-not-persist',
  }))
  const saved = saveCodexRecentHistory(messages, storage)
  const loaded = loadCodexRecentHistory(storage)

  assert.equal(saved.length, 12)
  assert.deepEqual(loaded, saved)
  assert.equal(loaded.some(item => 'session_id' in item), false)
  assert.deepEqual(boundCodexRecentHistory([{ role: 'system', content: 'ignore' }]), [])
})

test('router and 4x4 home expose the independent Codex entry', async () => {
  const router = await readFile(new URL('../../core/router.jsx', import.meta.url), 'utf8')
  const home = await readFile(new URL('../../shared/Home.jsx', import.meta.url), 'utf8')
  assert.match(router, /path: 'codex-chat'/)
  assert.match(router, /CodexChatPage/)
  assert.match(home, /to="\/codex-chat"/)
  assert.match(home, /label="Codex"/)
})
