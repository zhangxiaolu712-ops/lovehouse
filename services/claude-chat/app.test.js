import assert from 'node:assert/strict'
import { once } from 'node:events'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'

import { createChatRuntimeServer } from '../codex-chat/app.js'
import { ChatRuntimeError } from '../codex-chat/errors.js'
import { FileThreadBindingStore } from '../codex-chat/threadBindingStore.js'
import { unknownQuota } from '../codex-chat/runtimeContract.js'

const THREAD_ID = '11111111-1111-4111-8111-111111111111'
const SESSION_ID = '22222222-2222-4222-8222-222222222222'

function runtime(observed) {
  return {
    getCapabilities() {
      return {
        runtime_type: 'claude_cli', adapter_id: 'claude-cli-v1', enabled: true,
        capabilities: { streaming_text: true, reasoning_summary: 'conditional' },
      }
    },
    getQuota() { return unknownQuota('claude_cli_unavailable') },
    getUsage() { return null }, startOrResume() {}, sendMessage() {}, resetRuntime() {},
    async streamEvents(input) {
      observed.push(input.sessionId)
      input.onRuntimeBinding(input.sessionId || SESSION_ID)
      input.onEvent('reasoning_status', {
        available: false, status: 'unavailable', summary: null, source: 'claude_cli',
      })
      input.onText('reply')
      return { text: 'reply', sessionId: input.sessionId || SESSION_ID, usage: null }
    },
  }
}

async function open(observed, bindings) {
  const server = createChatRuntimeServer({
    authenticate: async authorization => {
      if (authorization !== 'Bearer good') {
        throw new ChatRuntimeError('AUTH_FAILED', 'Owner token invalid', { stage: 'auth', status: 401 })
      }
      return { userId: 'owner' }
    },
    runtime: runtime(observed),
    threadBindings: bindings,
    routePrefix: '/api/claude',
    serviceName: 'lovehouse-claude-chat',
  })
  server.listen(0, '127.0.0.1')
  await once(server, 'listening')
  return {
    base: `http://127.0.0.1:${server.address().port}`,
    close: () => new Promise(resolve => server.close(resolve)),
  }
}

async function chat(base) {
  return fetch(`${base}/api/claude/chat`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer good' },
    body: JSON.stringify({ thread_id: THREAD_ID, message: 'hello' }),
  })
}

test('Claude sidecar route and persistent binding survive sidecar restart', async t => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'lovehouse-claude-mainline-'))
  t.after(() => fs.rm(directory, { recursive: true, force: true }))
  const filePath = path.join(directory, 'bindings.json')
  const first = []
  const server1 = await open(first, new FileThreadBindingStore({ filePath, runtimeType: 'claude_cli' }))
  const health = await fetch(`${server1.base}/api/claude/health`)
  assert.equal(health.status, 200)
  assert.deepEqual(await health.json(), {
    ok: true,
    service: 'lovehouse-claude-chat',
    runtime: {
      runtime_type: 'claude_cli', adapter_id: 'claude-cli-v1', enabled: true,
      capabilities: { streaming_text: true, reasoning_summary: 'conditional' },
    },
  })
  assert.equal((await chat(server1.base)).status, 200)
  assert.deepEqual(first, [null])
  await server1.close()

  const second = []
  const server2 = await open(second, new FileThreadBindingStore({ filePath, runtimeType: 'claude_cli' }))
  t.after(server2.close)
  const response = await chat(server2.base)
  assert.equal(response.status, 200)
  assert.deepEqual(second, [SESSION_ID])
  const stream = await response.text()
  assert.match(stream, /claude_native_session/)
  assert.match(stream, /claude_native/)
  assert.equal(stream.includes('codex_native'), false)
  const persisted = JSON.parse(await fs.readFile(filePath, 'utf8'))
  assert.equal(persisted.bindings.owner[THREAD_ID].runtime_session_id, SESSION_ID)
  assert.equal(persisted.bindings.owner[THREAD_ID].runtime_type, 'claude_cli')
  assert.notEqual(THREAD_ID, SESSION_ID)
})
