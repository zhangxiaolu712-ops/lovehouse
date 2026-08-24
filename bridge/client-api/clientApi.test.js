import assert from 'node:assert/strict'
import { once } from 'node:events'
import http from 'node:http'
import test from 'node:test'

import express from 'express'

import {
  createClientOwnerAuth,
  installClientApi,
  resolveDeploymentSha,
} from './clientApi.js'
import { ClientApiError } from './errors.js'
import { createPersonaRegistry } from './personas.js'
import {
  createClaudeAdapter,
  createCodexAdapter,
  createProviderRouter,
} from './providerAdapters.js'
import { InMemoryRuntimeBindingStore } from './runtimeBindingStore.js'

const OWNER_ID = 'owner-user'
const THREAD_ID = '11111111-1111-4111-8111-111111111111'
const PROVIDER_SESSION_ID = '22222222-2222-4222-8222-222222222222'

function fakeAdapter(runtime, overrides = {}) {
  return {
    runtime,
    async health() { return { status: 'available' } },
    async chat({ onText }) {
      onText?.(`${runtime} reply`)
      return { usage: runtime === 'claude' ? { input_tokens: 2, output_tokens: 3 } : null }
    },
    async reset() { return { reset: true } },
    ...overrides,
  }
}

async function startHarness(t, {
  token = 'owner-token',
  adapters = {
    claude: fakeAdapter('claude'),
    codex: fakeAdapter('codex'),
  },
} = {}) {
  const app = express()
  app.use(express.json())
  app.post('/chat', (_req, res) => res.json({ legacy: true }))
  const verifyOwner = createClientOwnerAuth({
    verifyOwnerToken: async candidate => candidate === token ? { id: OWNER_ID } : null,
    checkRate: () => true,
  })
  const providerRouter = createProviderRouter({
    personaRegistry: createPersonaRegistry(),
    adapters,
  })
  installClientApi(app, {
    verifyOwner,
    providerRouter,
    startedAt: '2026-08-24T08:00:00.000Z',
    deploymentSha: 'a'.repeat(40),
    features: { memory: true, livingroom: true },
  })
  const server = http.createServer(app)
  server.listen(0, '127.0.0.1')
  await once(server, 'listening')
  t.after(() => server.close())
  return `http://127.0.0.1:${server.address().port}`
}

function authHeaders(extra = {}) {
  return {
    Authorization: 'Bearer owner-token',
    'Content-Type': 'application/json',
    ...extra,
  }
}

function chatBody(overrides = {}) {
  return {
    persona_id: 'claude',
    thread_id: THREAD_ID,
    window_id: 'client-window-1',
    message: { type: 'text', text: '你好' },
    ...overrides,
  }
}

function parseSse(text) {
  return text.trim().split(/\r?\n\r?\n/).map(frame => {
    const lines = frame.split(/\r?\n/)
    const event = lines.find(line => line.startsWith('event:'))?.slice(6).trim()
    const data = lines.filter(line => line.startsWith('data:')).map(line => line.slice(5).trim()).join('\n')
    return { event, data: JSON.parse(data) }
  })
}

test('runtime deployment identity comes from the actual release path only', () => {
  assert.equal(
    resolveDeploymentSha('/root/lovehouse-deployments/43d975728e00ba5c48babef7e144277e5d0bf1e8/bridge'),
    '43d975728e00ba5c48babef7e144277e5d0bf1e8',
  )
  assert.equal(resolveDeploymentSha('/root/lovehouse-bridge'), null)
})

test('bootstrap, health and personas expose capability facts without internal endpoints or secrets', async t => {
  const base = await startHarness(t)
  for (const path of ['/v1/bootstrap', '/v1/health', '/v1/personas']) {
    const response = await fetch(`${base}${path}`, { headers: authHeaders() })
    assert.equal(response.status, 200)
    const payload = await response.json()
    assert.equal(payload.ok, true)
    assert.equal(payload.api_version, 1)
    assert.match(payload.request_id, /^[0-9a-f-]{36}$/i)
    const serialized = JSON.stringify(payload)
    assert.equal(serialized.includes('127.0.0.1'), false)
    assert.equal(serialized.includes('SUPABASE'), false)
    assert.equal(serialized.includes('service_role'), false)
    assert.equal(serialized.includes('owner-user'), false)
  }

  const bootstrap = await fetch(`${base}/v1/bootstrap`, { headers: authHeaders() }).then(r => r.json())
  assert.equal(bootstrap.features.memory, true)
  assert.equal(bootstrap.features.livingroom, true)
  assert.equal(bootstrap.features.worldbook, false)
  assert.equal(bootstrap.features.voice_tts, false)
  assert.deepEqual(bootstrap.personas.map(({ id, enabled }) => ({ id, enabled })), [
    { id: 'gpt', enabled: false },
    { id: 'claude', enabled: true },
    { id: 'codex', enabled: true },
  ])
})

test('all v1 routes require the existing owner bearer boundary with a uniform error', async t => {
  const base = await startHarness(t)
  const response = await fetch(`${base}/v1/bootstrap`)
  assert.equal(response.status, 401)
  const payload = await response.json()
  assert.equal(payload.ok, false)
  assert.equal(payload.error.code, 'AUTH_REQUIRED')
  assert.equal(payload.error.stage, 'auth')
  assert.match(payload.error.request_id, /^[0-9a-f-]{36}$/i)
})

test('unknown and configured-but-disabled personas fail before streaming', async t => {
  const base = await startHarness(t)
  const unknown = await fetch(`${base}/v1/chat`, {
    method: 'POST', headers: authHeaders(), body: JSON.stringify(chatBody({ persona_id: 'nobody' })),
  })
  assert.equal(unknown.status, 400)
  assert.equal((await unknown.json()).error.code, 'UNKNOWN_PERSONA')

  const gpt = await fetch(`${base}/v1/chat`, {
    method: 'POST', headers: authHeaders(), body: JSON.stringify(chatBody({ persona_id: 'gpt' })),
  })
  assert.equal(gpt.status, 503)
  assert.equal((await gpt.json()).error.code, 'PROVIDER_UNAVAILABLE')
})

test('Claude and Codex share the same stable SSE contract without exposing provider sessions', async t => {
  const base = await startHarness(t)
  for (const personaId of ['claude', 'codex']) {
    const response = await fetch(`${base}/v1/chat`, {
      method: 'POST',
      headers: authHeaders(),
      body: JSON.stringify(chatBody({ persona_id: personaId })),
    })
    assert.equal(response.status, 200)
    assert.match(response.headers.get('content-type'), /^text\/event-stream/)
    const events = parseSse(await response.text())
    assert.deepEqual(events.map(event => event.event), personaId === 'claude'
      ? ['message_start', 'text_delta', 'usage', 'message_end']
      : ['message_start', 'text_delta', 'message_end'])
    assert.equal(events[0].data.thread_id, THREAD_ID)
    assert.equal(events[0].data.runtime, personaId)
    assert.equal(events[1].data.delta, `${personaId} reply`)
    assert.equal(JSON.stringify(events).includes('session_id'), false)
  }
})

test('Codex runtime metadata crosses the handler only through the unified safe event surface', async t => {
  const base = await startHarness(t, {
    adapters: {
      claude: fakeAdapter('claude'),
      codex: fakeAdapter('codex', {
        getCapabilities() {
          return {
            runtime_type: 'codex_cli', adapter_id: 'codex-cli-v1', enabled: true,
            capabilities: { reasoning_summary: 'conditional', tool_events: true },
          }
        },
        async chat({ onText, onEvent }) {
          onEvent('reasoning_status', {
            available: false, status: 'unavailable', summary: null, source: 'codex_cli',
          })
          onEvent('tool_call', {
            call_id: 'item-1', tool_type: 'command', name: 'shell', status: 'running',
          })
          onEvent('tool_result', {
            call_id: 'item-1', tool_type: 'command', name: 'shell', status: 'success',
            summary: 'Command completed',
          })
          onEvent('usage', {
            estimated_input_tokens: 2, actual_input_tokens: 3, actual_output_tokens: 4,
            total_tokens: 7, usage_source: 'codex_cli',
          })
          onEvent('quota', {
            status: 'unknown', remaining: null, unit: null, reset_at: null,
            source: 'codex_cli_unavailable',
          })
          onEvent('context_breakdown', {
            recent_chat: { enabled: true, available: true, estimated_tokens: null },
            memory: { enabled: false, available: false, estimated_tokens: 0 },
            worldbook: { enabled: false, available: false, estimated_tokens: 0 },
            persona: { enabled: false, available: false, estimated_tokens: 0 },
            current_message: { enabled: true, available: true, estimated_tokens: 2 },
            estimated_tokens: 2,
          })
          onText('reply')
          return { usage: null }
        },
      }),
    },
  })
  const response = await fetch(`${base}/v1/chat`, {
    method: 'POST', headers: authHeaders(),
    body: JSON.stringify(chatBody({ persona_id: 'codex', scene: 'work' })),
  })
  const events = parseSse(await response.text())
  assert.deepEqual(events.map(item => item.event), [
    'message_start', 'reasoning_status', 'tool_call', 'tool_result', 'usage',
    'quota', 'context_breakdown', 'text_delta', 'message_end',
  ])
  assert.equal(events[0].data.runtime, 'codex_cli')
  assert.equal(events[0].data.adapter_id, 'codex-cli-v1')
  assert.deepEqual(events[0].data.reply_policy, {
    default_modality: 'text', voice_enabled: false,
  })
  assert.equal(events[1].data.thread_id, THREAD_ID)
  assert.equal(events[5].data.status, 'unknown')
  assert.equal(events[6].data.memory.enabled, false)
  assert.equal(JSON.stringify(events).includes('session_id'), false)
})

test('provider quota failures stay observable as a provider-stage stream error', async t => {
  const base = await startHarness(t, {
    adapters: {
      claude: fakeAdapter('claude', {
        async chat() {
          throw new ClientApiError('PROVIDER_QUOTA_EXHAUSTED', 'Claude usage is currently unavailable', {
            stage: 'provider', status: 503,
          })
        },
      }),
      codex: fakeAdapter('codex'),
    },
  })
  const response = await fetch(`${base}/v1/chat`, {
    method: 'POST', headers: authHeaders(), body: JSON.stringify(chatBody()),
  })
  const events = parseSse(await response.text())
  assert.deepEqual(events.map(event => event.event), ['message_start', 'error', 'message_end'])
  assert.equal(events[1].data.error.code, 'PROVIDER_QUOTA_EXHAUSTED')
  assert.equal(events[1].data.error.stage, 'provider')
  assert.equal(events[2].data.ok, false)
})

test('future message types are reserved but fail explicitly until implemented', async t => {
  const base = await startHarness(t)
  const response = await fetch(`${base}/v1/chat`, {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify(chatBody({ message: { type: 'audio' } })),
  })
  assert.equal(response.status, 415)
  assert.equal((await response.json()).error.code, 'UNSUPPORTED_MESSAGE_TYPE')
})

test('thread and message archive identity fields are accepted but remain adapter metadata', async t => {
  let received
  const base = await startHarness(t, {
    adapters: {
      claude: fakeAdapter('claude', {
        async chat(input) {
          received = input
          input.onText('ok')
          return { usage: null }
        },
      }),
      codex: fakeAdapter('codex'),
    },
  })
  const response = await fetch(`${base}/v1/chat`, {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify(chatBody({
      source_platform: 'chatgpt',
      source_conversation_id: 'conversation-1',
      imported_at: '2026-08-24T12:00:00+08:00',
      message: {
        type: 'text',
        text: 'imported text',
        source_platform: 'chatgpt',
        source_message_id: 'message-1',
      },
    })),
  })
  assert.equal(response.status, 200)
  assert.deepEqual(received.threadSource, {
    source_platform: 'chatgpt',
    source_conversation_id: 'conversation-1',
    imported_at: '2026-08-24T12:00:00+08:00',
  })
  assert.deepEqual(received.source, {
    source_platform: 'chatgpt',
    source_message_id: 'message-1',
  })
  assert.equal((await response.text()).includes('chatgpt'), false)
})

test('reset rotates LoveHouse thread identity while keeping legacy /chat untouched', async t => {
  let resets = 0
  const base = await startHarness(t, {
    adapters: {
      claude: fakeAdapter('claude', { async reset() { resets += 1; return { reset: true } } }),
      codex: fakeAdapter('codex'),
    },
  })
  const reset = await fetch(`${base}/v1/chat/reset`, {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify({ persona_id: 'claude', thread_id: THREAD_ID, window_id: 'client-window-1' }),
  })
  assert.equal(reset.status, 200)
  const payload = await reset.json()
  assert.equal(payload.previous_thread_id, THREAD_ID)
  assert.notEqual(payload.thread_id, THREAD_ID)
  assert.equal(resets, 1)

  const legacy = await fetch(`${base}/chat`, { method: 'POST' })
  assert.equal(legacy.status, 200)
  assert.deepEqual(await legacy.json(), { legacy: true })
})

test('Claude adapter persists thread to provider-session binding and reuses it after adapter recreation', async () => {
  const bindingStore = new InMemoryRuntimeBindingStore()
  const calls = []
  const sendMessage = (_windowId, _text, _system, callbacks, options) => {
    calls.push(options)
    queueMicrotask(() => {
      callbacks.onSession({ session_id: PROVIDER_SESSION_ID })
      callbacks.onText('ok')
      callbacks.onDone({ usage: { input_tokens: 1 } })
    })
    return true
  }
  const makeAdapter = () => createClaudeAdapter({
    sendMessage,
    abortWindow: () => true,
    resetSession: () => true,
    bindingStore,
    systemPrompt: 'system',
  })
  await makeAdapter().chat({
    ownerUserId: OWNER_ID, threadId: THREAD_ID, text: 'first', onText() {},
  })
  await makeAdapter().chat({
    ownerUserId: OWNER_ID, threadId: THREAD_ID, text: 'second', onText() {},
  })
  assert.equal(calls[0].sessionIntent, 'new')
  assert.equal(calls[0].knownSessionId, null)
  assert.equal(calls[1].sessionIntent, 'continue')
  assert.equal(calls[1].knownSessionId, PROVIDER_SESSION_ID)
  assert.notEqual(THREAD_ID, PROVIDER_SESSION_ID)
})

test('Claude usage-limit text maps to the uniform provider quota code', async () => {
  const adapter = createClaudeAdapter({
    sendMessage(_windowId, _text, _system, callbacks) {
      queueMicrotask(() => callbacks.onError('You are out of extra usage'))
      return true
    },
    abortWindow: () => true,
    resetSession: () => true,
    bindingStore: new InMemoryRuntimeBindingStore(),
    systemPrompt: 'system',
  })
  await assert.rejects(
    adapter.chat({ ownerUserId: OWNER_ID, threadId: THREAD_ID, text: 'hello' }),
    error => error.code === 'PROVIDER_QUOTA_EXHAUSTED' && error.stage === 'provider',
  )
})

test('Codex adapter forwards owner auth and translates sidecar SSE without leaking its session id', async () => {
  let request
  const adapter = createCodexAdapter({
    fetchImpl: async (url, options) => {
      request = { url, options }
      return new Response([
        'event: runtime_status\ndata: {"status":"ready","runtime_type":"codex_cli","adapter_id":"codex-cli-v1","capabilities":{"streaming_text":true,"reasoning_summary":"conditional","tool_events":true,"actual_usage":true,"quota":false,"context_breakdown":"basic"}}',
        'event: quota\ndata: {"status":"unknown","remaining":null,"unit":null,"reset_at":null,"source":"codex_cli_unavailable"}',
        'event: context_breakdown\ndata: {"recent_chat":{"enabled":true,"available":true,"source":"codex_native_thread","estimated_tokens":null},"memory":{"enabled":false,"available":false,"estimated_tokens":0},"worldbook":{"enabled":false,"available":false,"estimated_tokens":0},"persona":{"enabled":false,"available":false,"estimated_tokens":0},"current_message":{"enabled":true,"available":true,"estimated_tokens":2},"estimated_tokens":2}',
        'event: session\ndata: {"session_id":"22222222-2222-4222-8222-222222222222"}',
        'event: tool_call\ndata: {"call_id":"item-1","tool_type":"command","name":"shell","status":"running","command":"must-not-pass"}',
        'event: tool_result\ndata: {"call_id":"item-1","tool_type":"command","name":"shell","status":"success","summary":"Command completed","aggregated_output":"must-not-pass"}',
        'event: text\ndata: {"text":"hello"}',
        'event: reasoning_status\ndata: {"available":false,"status":"unavailable","summary":null,"source":"codex_cli"}',
        'event: usage\ndata: {"estimated_input_tokens":2,"actual_input_tokens":3,"actual_output_tokens":4,"total_tokens":7,"usage_source":"codex_cli"}',
        'event: done\ndata: {"ok":true}',
        '',
      ].join('\n\n'), { status: 200, headers: { 'Content-Type': 'text/event-stream' } })
    },
  })
  const text = []
  const events = []
  const result = await adapter.chat({
    threadId: THREAD_ID,
    text: 'hi',
    authorization: 'Bearer owner-token',
    onText: delta => text.push(delta),
    onEvent: (event, data) => events.push({ event, data }),
  })
  assert.match(request.url, /\/api\/codex\/chat$/)
  assert.equal(request.options.headers.Authorization, 'Bearer owner-token')
  assert.deepEqual(JSON.parse(request.options.body), {
    thread_id: THREAD_ID, window_id: THREAD_ID, message: 'hi',
  })
  assert.deepEqual(text, ['hello'])
  assert.deepEqual(events.map(item => item.event), [
    'runtime_status', 'quota', 'context_breakdown', 'tool_call', 'tool_result',
    'reasoning_status', 'usage',
  ])
  assert.equal(JSON.stringify(events).includes('must-not-pass'), false)
  assert.equal(JSON.stringify(events).includes('session_id'), false)
  assert.deepEqual(result, { usage: null })
})
