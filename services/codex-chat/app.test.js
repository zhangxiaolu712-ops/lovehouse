import assert from 'node:assert/strict'
import { once } from 'node:events'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'

import { createCodexChatServer } from './app.js'
import { ChatRuntimeError } from './errors.js'
import { FileThreadBindingStore, InMemoryThreadBindingStore } from './threadBindingStore.js'
import { unknownQuota } from './runtimeContract.js'

const THREAD_ID = '11111111-1111-4111-8111-111111111111'
const SESSION_ID = '22222222-2222-4222-8222-222222222222'

function runtime({ observed = [], sessionId = SESSION_ID } = {}) {
  return {
    getCapabilities() {
      return {
        runtime_type: 'codex_cli', adapter_id: 'codex-cli-v1', enabled: true,
        capabilities: { streaming_text: true },
      }
    },
    getQuota() { return unknownQuota('test') },
    getUsage() { return null },
    startOrResume() { return {} },
    sendMessage() {},
    resetRuntime() { return { reset: true } },
    async streamEvents(input) {
      observed.push({
        sessionId: input.sessionId,
        message: input.message,
        previousUsage: input.previousUsage,
      })
      input.onRuntimeBinding(input.sessionId || sessionId)
      input.onEvent('reasoning_status', {
        available: false, status: 'unavailable', summary: null, source: 'codex_cli',
      })
      const previousInput = input.previousUsage?.input_tokens || 0
      const previousOutput = input.previousUsage?.output_tokens || 0
      const previousReasoning = input.previousUsage?.reasoning_output_tokens || 0
      const usage = {
        estimated_input_tokens: 2,
        actual_input_tokens: 10,
        actual_output_tokens: 2,
        total_tokens: 12,
        cumulative_input_tokens: previousInput + 10,
        cumulative_output_tokens: previousOutput + 2,
        cumulative_cached_input_tokens: 0,
        cumulative_reasoning_output_tokens: previousReasoning + 1,
        cumulative_total_tokens: previousInput + previousOutput + 12,
        previous_cumulative_input_tokens: previousInput,
        previous_cumulative_output_tokens: previousOutput,
        usage_source: 'codex_cli_cumulative_delta',
        baseline_status: 'known',
      }
      input.onEvent('usage', usage)
      input.onText('reply')
      return { text: 'reply', sessionId: input.sessionId || sessionId, usage }
    },
  }
}

async function open({ runtimeAdapter = runtime(), threadBindings = new InMemoryThreadBindingStore() } = {}) {
  const server = createCodexChatServer({
    authenticate: async authorization => {
      if (authorization !== 'Bearer good') {
        throw new ChatRuntimeError('AUTH_FAILED', 'Owner token invalid', { stage: 'auth', status: 401 })
      }
      return { userId: 'owner' }
    },
    runtime: runtimeAdapter,
    threadBindings,
  })
  server.listen(0, '127.0.0.1')
  await once(server, 'listening')
  return {
    base: `http://127.0.0.1:${server.address().port}`,
    close: () => new Promise(resolve => server.close(resolve)),
  }
}

async function start(t, options) {
  const opened = await open(options)
  t.after(opened.close)
  return opened.base
}

function parseSse(text) {
  return text.trim().split(/\r?\n\r?\n/).map(block => {
    const lines = block.split(/\r?\n/)
    const event = lines.find(line => line.startsWith('event:'))?.slice(6).trim()
    const data = lines.filter(line => line.startsWith('data:')).map(line => line.slice(5).trim()).join('\n')
    return { event, data: JSON.parse(data) }
  })
}

async function chat(base, body, authorization = 'Bearer good') {
  return fetch(`${base}/api/codex/chat`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: authorization },
    body: JSON.stringify(body),
  })
}

test('health reports the independent runtime contract without secrets', async t => {
  const base = await start(t)
  const response = await fetch(`${base}/api/codex/health`)
  assert.equal(response.status, 200)
  const payload = await response.json()
  assert.equal(payload.service, 'lovehouse-codex-chat')
  assert.equal(payload.runtime.runtime_type, 'codex_cli')
  assert.equal(JSON.stringify(payload).includes('session_id'), false)
})

test('owner auth fails before runtime and successful stream exposes normalized metadata', async t => {
  const base = await start(t)
  const denied = await chat(base, { thread_id: THREAD_ID, message: 'hello' }, 'Bearer bad')
  assert.equal(denied.status, 401)
  assert.equal((await denied.json()).error.code, 'AUTH_FAILED')

  const response = await chat(base, { thread_id: THREAD_ID, message: 'hello' })
  assert.equal(response.status, 200)
  const events = parseSse(await response.text())
  assert.deepEqual(events.map(item => item.event), [
    'runtime_status', 'quota', 'context_breakdown', 'session',
    'reasoning_status', 'context_breakdown', 'usage', 'text', 'done',
  ])
  assert.equal(events[0].data.runtime_type, 'codex_cli')
  assert.equal(events[1].data.status, 'unknown')
  assert.equal(events[2].data.memory.enabled, false)
  assert.equal(events[2].data.worldbook.enabled, false)
  assert.equal(events[2].data.current_message.enabled, true)
  assert.equal(events[2].data.reasoning.status, 'pending')
  assert.equal(events[5].data.reasoning.status, 'unavailable')
  assert.equal(events[5].data.reasoning.resumes_with_thread, true)
  assert.equal(events[5].data.reasoning.compaction, 'codex_native')
})

test('same LoveHouse thread survives sidecar restart while runtime session stays separate', async t => {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'lovehouse-codex-mainline-'))
  t.after(() => fs.rm(directory, { recursive: true, force: true }))
  const filePath = path.join(directory, 'bindings.json')
  const firstObserved = []
  const server1 = await open({
    runtimeAdapter: runtime({ observed: firstObserved }),
    threadBindings: new FileThreadBindingStore({ filePath }),
  })

  for (const message of ['turn one', 'turn two']) {
    const response = await chat(server1.base, { thread_id: THREAD_ID, message })
    assert.equal(parseSse(await response.text()).at(-1).data.ok, true)
  }
  assert.deepEqual(firstObserved.map(item => item.sessionId), [null, SESSION_ID])
  assert.equal(firstObserved[0].previousUsage, null)
  assert.deepEqual(firstObserved[1].previousUsage, {
    input_tokens: 10, output_tokens: 2, cached_input_tokens: 0,
    reasoning_output_tokens: 1,
  })
  await server1.close()

  const secondObserved = []
  const server2 = await open({
    runtimeAdapter: runtime({ observed: secondObserved }),
    threadBindings: new FileThreadBindingStore({ filePath }),
  })
  t.after(server2.close)
  const third = await chat(server2.base, { thread_id: THREAD_ID, message: 'turn three' })
  const events = parseSse(await third.text())
  assert.equal(events.at(-1).data.ok, true)
  assert.equal(secondObserved[0].sessionId, SESSION_ID)
  assert.deepEqual(secondObserved[0].previousUsage, {
    input_tokens: 20, output_tokens: 4, cached_input_tokens: 0,
    reasoning_output_tokens: 2,
  })
  assert.notEqual(THREAD_ID, SESSION_ID)
  const persisted = JSON.parse(await fs.readFile(filePath, 'utf8'))
  assert.equal(persisted.bindings.owner[THREAD_ID].codexThreadId, SESSION_ID)
  assert.deepEqual(persisted.bindings.owner[THREAD_ID].lastUsage, {
    input_tokens: 30, output_tokens: 6, cached_input_tokens: 0,
    reasoning_output_tokens: 3,
  })
})

test('runtime failure does not delete the persistent LoveHouse thread binding', async t => {
  const bindings = new InMemoryThreadBindingStore()
  await bindings.save({ ownerUserId: 'owner', threadId: THREAD_ID, runtimeSessionId: SESSION_ID })
  const failing = runtime()
  failing.streamEvents = async () => {
    throw new ChatRuntimeError('QUOTA_EXHAUSTED', 'Codex quota is unavailable', {
      stage: 'quota', status: 429,
    })
  }
  const base = await start(t, { runtimeAdapter: failing, threadBindings: bindings })
  const response = await chat(base, { thread_id: THREAD_ID, message: 'hello' })
  const events = parseSse(await response.text())
  assert.equal(events.at(-3).event, 'quota')
  assert.equal(events.at(-3).data.status, 'exhausted')
  assert.equal(events.at(-2).data.code, 'QUOTA_EXHAUSTED')
  assert.equal(events.at(-1).data.ok, false)
  assert.equal((await bindings.get({ ownerUserId: 'owner', threadId: THREAD_ID })).runtime_session_id, SESSION_ID)
})
