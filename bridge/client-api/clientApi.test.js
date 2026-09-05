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
  createClaudeCliAdapter,
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
  engineeringMemoryService = null,
  runtimeStatusProvider = null,
  toolCenterService = null,
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
    engineeringMemoryService,
    runtimeStatusProvider,
    toolCenterService,
  })
  const server = http.createServer(app)
  server.listen(0, '127.0.0.1')
  await once(server, 'listening')
  t.after(() => server.close())
  return `http://127.0.0.1:${server.address().port}`
}

test('Tool Center validation does not affect Claude or legacy requests without tools', async t => {
  const validationCalls = []
  const adapterCalls = []
  const toolCenterService = {
    capabilities() { return [] },
    async test() { return { ok: false } },
    validateRequest(input) {
      validationCalls.push(input)
      return input.requestedIds
    },
  }
  const adapters = {
    claude: fakeAdapter('claude', {
      async chat(input) {
        adapterCalls.push(input)
        input.onText?.('claude reply')
        return { usage: null }
      },
    }),
    codex: fakeAdapter('codex'),
  }
  const base = await startHarness(t, { adapters, toolCenterService })

  for (const body of [
    chatBody(),
    chatBody({ allowed_tool_ids: ['not-a-codex-tool'] }),
  ]) {
    const response = await fetch(`${base}/v1/chat`, {
      method: 'POST', headers: authHeaders(), body: JSON.stringify(body),
    })
    assert.equal(response.status, 200)
    await response.text()
  }

  assert.equal(validationCalls.length, 0)
  assert.equal(adapterCalls.length, 2)
  assert.deepEqual(adapterCalls.map(call => call.allowedToolIds), [[], []])
})

function fakeEngineeringMemoryService(calls) {
  return {
    forActor(actor) {
      calls.push(['actor', actor])
      return {
        async recallEngineering(input) {
          calls.push(['recall', input])
          return { mode: 'lexical', items: [{ subject_key: 'runtime.codex' }] }
        },
        async upsertEngineeringFact(input) {
          calls.push(['upsert', input])
          return { action: 'created', subject_key: input.subjectKey }
        },
        async openEngineeringFact(subjectKey) {
          calls.push(['open', subjectKey])
          return { entry: { subject_key: subjectKey }, revisions: [] }
        },
        async expandEngineeringSource(sourceId) {
          calls.push(['expand', sourceId])
          return { source_id: sourceId, quote_text: 'evidence' }
        },
        async archiveEngineeringFact(subjectKey) {
          calls.push(['archive', subjectKey])
          return { action: 'archived' }
        },
        async restoreEngineeringFact(subjectKey) {
          calls.push(['restore', subjectKey])
          return { action: 'restored' }
        },
      }
    },
  }
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

test('runtime status is Owner-only and returns the provider safe snapshot', async t => {
  const runtime = {
    version: 1,
    observed_at: '2026-08-27T12:00:00.000Z',
    daemon: { count: 1, pid: 684943, uptime_seconds: 3600, systemd_managed: true },
    services: [{
      name: 'lovehouse', label: 'Bridge', status: 'online', health: 'ok', pid: 686862,
      port: 3000, release: '22df726', uptime_seconds: 120, restart_count: 0,
      last_started_at: '2026-08-27T11:58:00.000Z',
    }],
  }
  const base = await startHarness(t, {
    runtimeStatusProvider: { async snapshot() { return runtime } },
  })

  assert.equal((await fetch(`${base}/v1/runtime-status`)).status, 401)
  const response = await fetch(`${base}/v1/runtime-status`, { headers: authHeaders() })
  assert.equal(response.status, 200)
  assert.equal(response.headers.get('cache-control'), 'no-store')
  const payload = await response.json()
  assert.deepEqual(payload.runtime, runtime)
})

test('runtime status failures are sanitized', async t => {
  const base = await startHarness(t, {
    runtimeStatusProvider: { async snapshot() { throw new Error('secret PM2 path') } },
  })
  const response = await fetch(`${base}/v1/runtime-status`, { headers: authHeaders() })
  assert.equal(response.status, 503)
  const payload = await response.json()
  assert.equal(payload.error.code, 'RUNTIME_STATUS_UNAVAILABLE')
  assert.equal(JSON.stringify(payload).includes('secret PM2 path'), false)
})

test('Owner Client API exposes Engineering Memory without accepting an actor from the client', async t => {
  const calls = []
  const base = await startHarness(t, {
    engineeringMemoryService: fakeEngineeringMemoryService(calls),
  })

  const unauthorized = await fetch(`${base}/v1/engineering-memory`)
  assert.equal(unauthorized.status, 401)

  const created = await fetch(`${base}/v1/engineering-memory`, {
    method: 'POST',
    headers: authHeaders(),
    body: JSON.stringify({
      subject_key: 'runtime.codex',
      content: 'current state',
      actor: 'codex',
      metadata: { category: 'runtime' },
    }),
  })
  assert.equal(created.status, 200)
  assert.equal((await created.json()).action, 'created')

  const listed = await fetch(`${base}/v1/engineering-memory?query=runtime&include_archived=true`, {
    headers: authHeaders(),
  }).then(response => response.json())
  assert.equal(listed.mode, 'lexical')
  assert.equal(listed.items[0].subject_key, 'runtime.codex')

  await fetch(`${base}/v1/engineering-memory/runtime.codex`, { headers: authHeaders() })
  await fetch(`${base}/v1/engineering-memory/sources/source-1`, { headers: authHeaders() })
  await fetch(`${base}/v1/engineering-memory/runtime.codex/archive`, {
    method: 'POST', headers: authHeaders(), body: '{}',
  })
  await fetch(`${base}/v1/engineering-memory/runtime.codex/restore`, {
    method: 'POST', headers: authHeaders(), body: '{}',
  })

  assert.deepEqual(calls[0], ['actor', 'owner'])
  assert.deepEqual(calls.find(call => call[0] === 'upsert')[1], {
    subjectKey: 'runtime.codex',
    content: 'current state',
    metadata: { category: 'runtime' },
    reason: undefined,
    eventTime: undefined,
    humanImportance: undefined,
    aiImportance: undefined,
    sources: undefined,
  })
  assert.equal(calls.some(call => call.includes('codex')), false)
  assert.deepEqual(calls.map(call => call[0]), [
    'actor', 'upsert', 'recall', 'open', 'expand', 'archive', 'restore',
  ])
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

test('Claude thinking crosses the unified v1 stream as a current-turn event', async t => {
  const base = await startHarness(t, {
    adapters: {
      claude: fakeAdapter('claude', {
        async chat({ onText, onEvent }) {
          onEvent('thinking', { thinking: '先检查当前状态。' })
          onText('reply')
          return { usage: null }
        },
      }),
      codex: fakeAdapter('codex'),
    },
  })
  const response = await fetch(`${base}/v1/chat`, {
    method: 'POST', headers: authHeaders(), body: JSON.stringify(chatBody()),
  })
  const events = parseSse(await response.text())
  assert.deepEqual(events.map(item => item.event), [
    'message_start', 'thinking', 'text_delta', 'message_end',
  ])
  assert.equal(events[1].data.thinking, '先检查当前状态。')
  assert.equal(events[2].data.delta, 'reply')
})

test('Codex runtime metadata crosses the handler only through the unified safe event surface', async t => {
  const base = await startHarness(t, {
    adapters: {
      claude: fakeAdapter('claude'),
      codex: fakeAdapter('codex', {
        getCapabilities() {
          return {
            runtime_type: 'codex_cli', adapter_id: 'codex-cli-v1', enabled: true,
            capabilities: { reasoning_summary: 'detailed', tool_events: true },
          }
        },
        async chat({ onText, onEvent }) {
          onEvent('reasoning_status', {
            available: false, status: 'unavailable', summary: null, source: 'codex_cli',
          })
          onEvent('tool_call', {
            call_id: 'item-1', tool_type: 'command', name: 'shell', status: 'running',
            lifecycle: 'started',
          })
          onEvent('tool_result', {
            call_id: 'item-1', tool_type: 'command', name: 'shell', status: 'success',
            lifecycle: 'completed', summary: 'Command completed',
          })
          onEvent('usage', {
            estimated_input_tokens: 2, actual_input_tokens: 3, actual_output_tokens: 4,
            cached_input_tokens: 2, reasoning_output_tokens: 1, total_tokens: 7,
            cumulative_input_tokens: 103,
            cumulative_cached_input_tokens: 82,
            cumulative_output_tokens: 24,
            cumulative_reasoning_output_tokens: 6,
            cumulative_total_tokens: 127,
            previous_cumulative_input_tokens: 100,
            previous_cumulative_cached_input_tokens: 80,
            previous_cumulative_output_tokens: 20,
            previous_cumulative_reasoning_output_tokens: 5,
            baseline_status: 'known',
            usage_source: 'codex_cli_cumulative_delta',
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
            reasoning: {
              enabled: true, available: false, status: 'unavailable', summary: null,
              source: 'codex_native_thread', active_context: true,
              resumes_with_thread: true, compaction: 'codex_native',
            },
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
  assert.equal(events[2].data.lifecycle, 'started')
  assert.equal(events[3].data.lifecycle, 'completed')
  assert.equal(events[4].data.actual_input_tokens, 3)
  assert.equal(events[4].data.cached_input_tokens, 2)
  assert.equal(events[4].data.reasoning_output_tokens, 1)
  assert.equal(events[4].data.cumulative_input_tokens, 103)
  assert.equal(events[6].data.reasoning.resumes_with_thread, true)
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
        'event: runtime_status\ndata: {"status":"ready","runtime_type":"codex_cli","adapter_id":"codex-cli-v1","capabilities":{"streaming_text":true,"reasoning_summary":"detailed","tool_events":true,"actual_usage":true,"quota":false,"context_breakdown":"basic"}}',
        'event: quota\ndata: {"status":"unknown","remaining":null,"unit":null,"reset_at":null,"source":"codex_cli_unavailable"}',
        'event: context_breakdown\ndata: {"recent_chat":{"enabled":true,"available":true,"source":"codex_native_thread","estimated_tokens":null},"memory":{"enabled":false,"available":false,"estimated_tokens":0},"worldbook":{"enabled":false,"available":false,"estimated_tokens":0},"persona":{"enabled":false,"available":false,"estimated_tokens":0},"current_message":{"enabled":true,"available":true,"estimated_tokens":2},"reasoning":{"enabled":true,"available":true,"status":"completed","summary":"Native summary","source":"codex_native_thread","active_context":true,"resumes_with_thread":true,"compaction":"codex_native"},"estimated_tokens":2}',
        'event: session\ndata: {"session_id":"22222222-2222-4222-8222-222222222222"}',
        'event: tool_call\ndata: {"call_id":"item-1","tool_type":"command","name":"shell","status":"running","lifecycle":"updated","command":"must-not-pass"}',
        'event: tool_result\ndata: {"call_id":"item-1","tool_type":"command","name":"shell","status":"success","lifecycle":"completed","summary":"Command completed","aggregated_output":"must-not-pass"}',
        'event: text\ndata: {"text":"hello"}',
        'event: reasoning_status\ndata: {"available":false,"status":"unavailable","summary":null,"source":"codex_cli"}',
        'event: usage\ndata: {"estimated_input_tokens":2,"actual_input_tokens":3,"cached_input_tokens":2,"actual_output_tokens":4,"reasoning_output_tokens":1,"total_tokens":7,"cumulative_input_tokens":103,"cumulative_cached_input_tokens":82,"cumulative_output_tokens":24,"cumulative_reasoning_output_tokens":6,"cumulative_total_tokens":127,"previous_cumulative_input_tokens":100,"previous_cumulative_cached_input_tokens":80,"previous_cumulative_output_tokens":20,"previous_cumulative_reasoning_output_tokens":5,"baseline_status":"known","usage_source":"codex_cli_cumulative_delta"}',
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
  assert.equal(events[0].data.capabilities.reasoning_summary, 'detailed')
  assert.equal(events[2].data.reasoning.summary, 'Native summary')
  assert.equal(events[3].data.lifecycle, 'updated')
  assert.equal(events[6].data.actual_input_tokens, 3)
  assert.equal(events[6].data.cached_input_tokens, 2)
  assert.equal(events[6].data.reasoning_output_tokens, 1)
  assert.deepEqual(result, { usage: null })
})

test('Claude CLI adapter uses the same safe stream contract without exposing its provider session', async () => {
  let request
  const adapter = createClaudeCliAdapter({
    fetchImpl: async (url, options) => {
      request = { url, options }
      return new Response([
        'event: runtime_status\ndata: {"status":"ready","runtime_type":"claude_cli","adapter_id":"claude-cli-v1","capabilities":{"streaming_text":true,"reasoning_summary":"conditional","tool_events":true,"actual_usage":true,"quota":false,"context_breakdown":"basic","mcp_required":false}}',
        'event: session\ndata: {"session_id":"22222222-2222-4222-8222-222222222222"}',
        'event: reasoning_status\ndata: {"available":false,"status":"unavailable","summary":null,"source":"claude_cli"}',
        'event: tool_call\ndata: {"call_id":"tool-1","tool_type":"claude_tool","name":"Read","status":"running","lifecycle":"started","input":{"path":"must-not-pass"}}',
        'event: tool_result\ndata: {"call_id":"tool-1","tool_type":"claude_tool","name":"Read","status":"success","lifecycle":"completed","summary":"Read completed","content":"must-not-pass"}',
        'event: usage\ndata: {"estimated_input_tokens":10,"actual_input_tokens":12,"cached_input_tokens":4,"actual_output_tokens":5,"reasoning_output_tokens":null,"total_tokens":17,"usage_source":"claude_cli","baseline_status":"known"}',
        'event: quota\ndata: {"status":"unknown","remaining":null,"unit":null,"reset_at":null,"source":"claude_cli_unavailable"}',
        'event: context_breakdown\ndata: {"recent_chat":{"enabled":true,"available":true,"source":"claude_native_session","estimated_tokens":null},"memory":{"enabled":false,"available":false,"estimated_tokens":0},"worldbook":{"enabled":false,"available":false,"estimated_tokens":0},"persona":{"enabled":false,"available":false,"estimated_tokens":0},"current_message":{"enabled":true,"available":true,"estimated_tokens":2},"reasoning":{"enabled":true,"available":false,"status":"unavailable","summary":null,"source":"claude_native_session","active_context":true,"resumes_with_thread":true,"compaction":"claude_native"},"estimated_tokens":2}',
        'event: thinking\ndata: {"thinking":"先检查当前状态。","signature":"must-not-pass"}',
        'event: text\ndata: {"text":"hello"}',
        'event: done\ndata: {"ok":true}',
        '',
      ].join('\n\n'), { status: 200, headers: { 'Content-Type': 'text/event-stream' } })
    },
  })
  const text = []
  const events = []
  await adapter.chat({
    threadId: THREAD_ID,
    text: 'hi',
    authorization: 'Bearer owner-token',
    onText: delta => text.push(delta),
    onEvent: (event, data) => events.push({ event, data }),
  })
  assert.match(request.url, /\/api\/claude\/chat$/)
  assert.equal(request.options.headers.Authorization, 'Bearer owner-token')
  assert.deepEqual(JSON.parse(request.options.body), {
    thread_id: THREAD_ID, window_id: THREAD_ID, message: 'hi',
  })
  assert.deepEqual(text, ['hello'])
  assert.deepEqual(events.map(item => item.event), [
    'runtime_status', 'reasoning_status', 'tool_call', 'tool_result', 'usage', 'quota',
    'context_breakdown', 'thinking',
  ])
  assert.equal(events[0].data.runtime_type, 'claude_cli')
  assert.equal(events[0].data.adapter_id, 'claude-cli-v1')
  assert.equal(events[2].data.tool_type, 'claude_tool')
  assert.equal(events[4].data.usage_source, 'claude_cli')
  assert.equal(events[6].data.reasoning.source, 'claude_native_session')
  assert.equal(events[6].data.reasoning.compaction, 'claude_native')
  assert.equal(events[7].data.thinking, '先检查当前状态。')
  assert.equal(JSON.stringify(events).includes('must-not-pass'), false)
  assert.equal(JSON.stringify(events).includes('session_id'), false)
})
