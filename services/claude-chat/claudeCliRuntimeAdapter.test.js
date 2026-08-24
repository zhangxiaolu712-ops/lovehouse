import { EventEmitter } from 'node:events'
import { PassThrough } from 'node:stream'
import assert from 'node:assert/strict'
import test from 'node:test'

import { assertRuntimeAdapter } from '../codex-chat/runtimeContract.js'
import { ClaudeCliRuntimeAdapter } from './claudeCliRuntimeAdapter.js'

const SESSION_ID = '22222222-2222-4222-8222-222222222222'

function fakeSpawn(events, { code = 0, stderr = '', calls = [] } = {}) {
  return (_executable, args, options) => {
    calls.push({ args, options })
    const child = new EventEmitter()
    child.stdin = new PassThrough()
    child.stdout = new PassThrough()
    child.stderr = new PassThrough()
    child.kill = () => {}
    queueMicrotask(() => {
      if (stderr) child.stderr.write(stderr)
      for (const event of events) child.stdout.write(`${JSON.stringify(event)}\n`)
      child.stdout.end()
      child.emit('close', code)
    })
    return child
  }
}

function successEvents({ sessionId = SESSION_ID, text = '你好。', reasoning = null } = {}) {
  return [
    { type: 'system', subtype: 'init', session_id: sessionId, mcp_servers: [], tools: [] },
    ...(reasoning ? [{
      type: 'assistant', session_id: sessionId,
      message: { content: [{ type: 'thinking_summary', summary: reasoning }] },
    }] : []),
    {
      type: 'stream_event', session_id: sessionId,
      event: { type: 'content_block_delta', delta: { type: 'text_delta', text } },
    },
    {
      type: 'assistant', session_id: sessionId,
      message: {
        content: [{ type: 'text', text }],
        usage: { input_tokens: 12, cache_read_input_tokens: 4, output_tokens: 5 },
      },
    },
    {
      type: 'result', subtype: 'success', is_error: false, session_id: sessionId,
      result: text, usage: {
        input_tokens: 12, cache_read_input_tokens: 4, output_tokens: 5,
        output_tokens_details: { thinking_tokens: 2 },
      },
    },
  ]
}

test('Claude CLI implements the shared runtime contract without requiring MCP', () => {
  const adapter = new ClaudeCliRuntimeAdapter({ spawnImpl: fakeSpawn([]) })
  assert.equal(assertRuntimeAdapter(adapter), adapter)
  assert.deepEqual(adapter.getCapabilities(), {
    runtime_type: 'claude_cli', adapter_id: 'claude-cli-v1', enabled: true,
    capabilities: {
      streaming_text: true, reasoning_summary: 'conditional', tool_events: true,
      actual_usage: true, quota: false, context_breakdown: 'basic', mcp_required: false,
    },
  })
  assert.equal(adapter.getQuota().status, 'unknown')
})

test('new Claude session streams text and usage while MCP is empty', async () => {
  const calls = []
  const events = []
  let text = ''
  let bound = null
  const adapter = new ClaudeCliRuntimeAdapter({
    createSessionId: () => SESSION_ID,
    env: {
      HOME: '/root',
      PATH: '/usr/bin',
      CLAUDE_CODE_OAUTH_TOKEN: 'official-headless-token',
      SUPABASE_SECRET_KEY: 'must-not-pass',
    },
    spawnImpl: fakeSpawn(successEvents(), { calls }),
  })
  const result = await adapter.streamEvents({
    message: '你好', history: [],
    onRuntimeBinding(value) { bound = value },
    onText(value) { text += value },
    onEvent(event, data) { events.push({ event, data }) },
  })
  assert.equal(result.sessionId, SESSION_ID)
  assert.equal(bound, SESSION_ID)
  assert.equal(text, '你好。')
  assert.deepEqual(events.map(item => item.event), ['usage', 'reasoning_status'])
  assert.equal(events[0].data.actual_input_tokens, 12)
  assert.equal(events[0].data.cached_input_tokens, 4)
  assert.equal(events[0].data.actual_output_tokens, 5)
  assert.equal(events[0].data.reasoning_output_tokens, 2)
  assert.deepEqual(events[1].data, {
    available: false, status: 'unavailable', summary: null, source: 'claude_cli',
  })
  assert.ok(calls[0].args.includes('--safe-mode'))
  assert.ok(calls[0].args.includes('--strict-mcp-config'))
  assert.equal(calls[0].args[calls[0].args.indexOf('--mcp-config') + 1], '{"mcpServers":{}}')
  assert.equal(calls[0].args[calls[0].args.indexOf('--tools') + 1], '')
  assert.deepEqual(calls[0].options.env, {
    HOME: '/root', PATH: '/usr/bin', CLAUDE_CODE_OAUTH_TOKEN: 'official-headless-token',
  })
  assert.equal(JSON.stringify(events).includes('official-headless-token'), false)
})

test('only a native Claude reasoning summary is exposed; thinking text is not republished', async () => {
  const events = []
  const adapter = new ClaudeCliRuntimeAdapter({
    createSessionId: () => SESSION_ID,
    spawnImpl: fakeSpawn([
      { type: 'system', subtype: 'init', session_id: SESSION_ID },
      {
        type: 'stream_event', session_id: SESSION_ID,
        event: { type: 'content_block_delta', delta: { type: 'thinking_delta', thinking: 'hidden chain' } },
      },
      ...successEvents({ reasoning: '先核对事实，再给出答案。' }).slice(1),
    ]),
  })
  await adapter.streamEvents({
    message: '分析', history: [], onRuntimeBinding() {}, onText() {},
    onEvent(event, data) { events.push({ event, data }) },
  })
  const reasoning = events.filter(item => item.event === 'reasoning_status')
  assert.deepEqual(reasoning, [{
    event: 'reasoning_status',
    data: {
      available: true, status: 'completed', summary: '先核对事实，再给出答案。', source: 'claude_cli',
    },
  }])
  assert.equal(JSON.stringify(events).includes('hidden chain'), false)
})

test('Claude tool lifecycle is normalized without exposing inputs or result bodies', async () => {
  const events = []
  const adapter = new ClaudeCliRuntimeAdapter({
    createSessionId: () => SESSION_ID,
    spawnImpl: fakeSpawn([
      { type: 'system', subtype: 'init', session_id: SESSION_ID },
      {
        type: 'stream_event', session_id: SESSION_ID,
        event: {
          type: 'content_block_start',
          content_block: { type: 'tool_use', id: 'tool-1', name: 'Read', input: { path: '/secret' } },
        },
      },
      {
        type: 'user', session_id: SESSION_ID,
        message: { content: [{
          type: 'tool_result', tool_use_id: 'tool-1', content: 'secret body', is_error: false,
        }] },
      },
      ...successEvents().slice(1),
    ]),
  })
  await adapter.streamEvents({
    message: 'read', history: [], onRuntimeBinding() {}, onText() {},
    onEvent(event, data) { events.push({ event, data }) },
  })
  assert.deepEqual(events.slice(0, 2).map(item => item.event), ['tool_call', 'tool_result'])
  assert.deepEqual(events.slice(0, 2).map(item => item.data.lifecycle), ['started', 'completed'])
  assert.equal(JSON.stringify(events).includes('/secret'), false)
  assert.equal(JSON.stringify(events).includes('secret body'), false)
})

test('resume uses provider session separately and recovers with bounded thread context when missing', async () => {
  let invocation = 0
  const calls = []
  const events = []
  const adapter = new ClaudeCliRuntimeAdapter({
    createSessionId: () => SESSION_ID,
    spawnImpl: (_executable, args, options) => {
      calls.push({ args, options })
      const child = new EventEmitter()
      child.stdin = new PassThrough()
      child.stdout = new PassThrough()
      child.stderr = new PassThrough()
      child.kill = () => {}
      queueMicrotask(() => {
        invocation += 1
        if (invocation === 1) {
          child.stderr.write('session not found')
          child.emit('close', 1)
        } else {
          for (const event of successEvents()) child.stdout.write(`${JSON.stringify(event)}\n`)
          child.emit('close', 0)
        }
      })
      return child
    },
  })
  const result = await adapter.streamEvents({
    message: '继续', history: [],
    sessionId: 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee',
    getContinuationContext: async () => [{ role: 'assistant', content: 'bounded history' }],
    onRuntimeBinding() {}, onText() {},
    onEvent(event, data) { events.push({ event, data }) },
  })
  assert.equal(calls.length, 2)
  assert.equal(calls[0].args.at(-2), '--resume')
  assert.equal(calls[0].args.at(-1), 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee')
  assert.equal(calls[1].args.at(-2), '--session-id')
  assert.equal(result.sessionId, SESSION_ID)
  assert.equal(events[0].event, 'runtime_status')
  assert.equal(events[0].data.status, 'recovering')
})

test('quota, auth and generic failures remain distinct without deleting the caller thread', async () => {
  for (const [stderr, code] of [
    ['out of extra usage', 'QUOTA_EXHAUSTED'],
    ['login required', 'AUTH_FAILED'],
    ['connection reset', 'STREAM_INTERRUPTED'],
  ]) {
    const adapter = new ClaudeCliRuntimeAdapter({
      createSessionId: () => SESSION_ID,
      spawnImpl: fakeSpawn([], { code: 1, stderr }),
    })
    await assert.rejects(
      adapter.streamEvents({ message: 'x', history: [], onRuntimeBinding() {}, onText() {}, onEvent() {} }),
      error => error.code === code,
    )
  }
})

test('unknown Claude JSONL and successful stderr diagnostics never reach the frontend', async () => {
  const emitted = []
  let text = ''
  const adapter = new ClaudeCliRuntimeAdapter({
    createSessionId: () => SESSION_ID,
    spawnImpl: fakeSpawn([
      { type: 'system', subtype: 'init', session_id: SESSION_ID },
      { type: 'future_event', secret: 'must-not-pass' },
      ...successEvents().slice(1),
    ], { stderr: 'diagnostic must stay private' }),
  })
  await adapter.streamEvents({
    message: 'hello', history: [], onRuntimeBinding() {},
    onText(value) { text += value },
    onEvent(event, data) { emitted.push({ event, data }) },
  })
  assert.equal(text, '你好。')
  const serialized = JSON.stringify(emitted)
  assert.equal(serialized.includes('must-not-pass'), false)
  assert.equal(serialized.includes('diagnostic'), false)
})

test('real Claude 2.1.229 authentication error never escapes as assistant text', async () => {
  const text = []
  const adapter = new ClaudeCliRuntimeAdapter({
    createSessionId: () => SESSION_ID,
    spawnImpl: fakeSpawn([
      { type: 'system', subtype: 'init', session_id: SESSION_ID, tools: [], mcp_servers: [] },
      {
        type: 'assistant', session_id: SESSION_ID,
        error: 'authentication_failed', is_api_error_message: true,
        message: {
          content: [{ type: 'text', text: 'Failed to authenticate: OAuth session expired' }],
          usage: { input_tokens: 0, output_tokens: 0 },
        },
      },
      {
        type: 'result', subtype: 'success', is_error: true, session_id: SESSION_ID,
        result: 'Failed to authenticate: OAuth session expired and could not be refreshed',
        usage: {
          input_tokens: 0, output_tokens: 0, cache_read_input_tokens: 0,
          output_tokens_details: { thinking_tokens: 0 },
        },
      },
    ], { code: 1 }),
  })
  await assert.rejects(
    adapter.streamEvents({
      message: 'hello', history: [], onRuntimeBinding() {}, onEvent() {},
      onText(value) { text.push(value) },
    }),
    error => error.code === 'AUTH_FAILED' && error.stage === 'auth',
  )
  assert.deepEqual(text, [])
})
