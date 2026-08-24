import { EventEmitter } from 'node:events'
import { PassThrough } from 'node:stream'
import assert from 'node:assert/strict'
import test from 'node:test'

import { CodexCliRuntimeAdapter } from './codexCliRuntimeAdapter.js'
import { assertRuntimeAdapter } from './runtimeContract.js'

const SESSION_ID = '0199a213-81c0-7800-8aa1-bbab2a035a53'

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

test('Codex CLI implements the complete reusable runtime adapter contract', () => {
  const adapter = new CodexCliRuntimeAdapter({ spawnImpl: fakeSpawn([]) })
  assert.equal(assertRuntimeAdapter(adapter), adapter)
  assert.deepEqual(adapter.getCapabilities(), {
    runtime_type: 'codex_cli',
    adapter_id: 'codex-cli-v1',
    enabled: true,
    capabilities: {
      streaming_text: true,
      reasoning_summary: 'detailed',
      tool_events: true,
      actual_usage: true,
      quota: false,
      context_breakdown: 'basic',
      runtime_reset: true,
    },
  })
  assert.equal(adapter.getQuota().status, 'unknown')
})

test('real Codex 0.146 JSONL shape maps text, safe tool events, usage and unavailable reasoning', async () => {
  const calls = []
  const emitted = []
  let text = ''
  const adapter = new CodexCliRuntimeAdapter({
    env: { PATH: '/usr/bin', HOME: '/root', SUPABASE_SECRET_KEY: 'must-not-pass' },
    spawnImpl: fakeSpawn([
      { type: 'thread.started', thread_id: SESSION_ID },
      { type: 'turn.started' },
      { type: 'item.completed', item: { id: 'item_0', type: 'agent_message', text: 'Working. ' } },
      { type: 'item.started', item: {
        id: 'item_1', type: 'command_execution', command: '/bin/bash -lc printenv SECRET',
        aggregated_output: '', exit_code: null, status: 'in_progress',
      } },
      { type: 'item.updated', item: {
        id: 'item_1', type: 'command_execution', status: 'in_progress',
      } },
      { type: 'item.completed', item: {
        id: 'item_1', type: 'command_execution', command: '/bin/bash -lc printenv SECRET',
        aggregated_output: 'must-not-leak', exit_code: 0, status: 'completed',
      } },
      { type: 'item.completed', item: { id: 'item_2', type: 'agent_message', text: 'Done.' } },
      { type: 'turn.completed', usage: {
        input_tokens: 32062, cached_input_tokens: 26112, output_tokens: 52,
      } },
    ], { calls }),
  })
  const result = await adapter.streamEvents({
    message: 'hello',
    history: [],
    onRuntimeBinding() {},
    onText(value) { text += value },
    onEvent(event, data) { emitted.push({ event, data }) },
  })
  assert.equal(result.sessionId, SESSION_ID)
  assert.equal(text, 'Working. Done.')
  assert.deepEqual(emitted.map(item => item.event), [
    'tool_call', 'tool_call', 'tool_result', 'usage', 'reasoning_status',
  ])
  assert.deepEqual(emitted[0].data, {
    call_id: 'item_1', tool_type: 'command', name: 'shell', status: 'running', lifecycle: 'started',
  })
  assert.equal(emitted[1].data.lifecycle, 'updated')
  assert.equal(emitted[2].data.lifecycle, 'completed')
  assert.match(emitted[2].data.summary, /^Command completed/)
  assert.equal(JSON.stringify(emitted).includes('printenv'), false)
  assert.equal(JSON.stringify(emitted).includes('must-not-leak'), false)
  assert.equal(emitted[3].data.actual_input_tokens, 32062)
  assert.equal(emitted[3].data.actual_output_tokens, 52)
  assert.equal(emitted[3].data.total_tokens, 32114)
  assert.equal(emitted[3].data.cumulative_input_tokens, 32062)
  assert.equal(emitted[3].data.previous_cumulative_input_tokens, 0)
  assert.equal(emitted[3].data.usage_source, 'codex_cli_cumulative_delta')
  assert.deepEqual(emitted[4].data, {
    available: false, status: 'unavailable', summary: null, source: 'codex_cli',
  })
  assert.deepEqual(calls[0].args.slice(0, 6), [
    'exec', '--json', '-c', 'model_reasoning_summary="detailed"',
    '-c', 'hide_agent_reasoning=false',
  ])
  assert.deepEqual(calls[0].options.env, { HOME: '/root', PATH: '/usr/bin' })
})

test('user-visible Codex reasoning item is passed as a bounded redacted summary, never fabricated', async () => {
  const emitted = []
  const adapter = new CodexCliRuntimeAdapter({
    spawnImpl: fakeSpawn([
      { type: 'thread.started', thread_id: SESSION_ID },
      { type: 'item.started', item: { id: 'reason-1', type: 'reasoning' } },
      { type: 'item.updated', item: {
        id: 'reason-1', type: 'reasoning', text: 'Checking the current state.',
      } },
      { type: 'item.completed', item: {
        id: 'reason-1', type: 'reasoning', text: 'Checking Bearer hidden-token-value before answering.',
      } },
      { type: 'item.completed', item: { id: 'answer-1', type: 'agent_message', text: 'OK' } },
      { type: 'turn.completed', usage: { input_tokens: 10, output_tokens: 2 } },
    ]),
  })
  await adapter.streamEvents({
    message: 'hello', history: [], onRuntimeBinding() {}, onText() {},
    onEvent(event, data) { emitted.push({ event, data }) },
  })
  const reasoning = emitted.find(item => item.event === 'reasoning_status').data
  assert.equal(reasoning.available, true)
  assert.equal(reasoning.status, 'started')
  assert.equal(reasoning.summary, null)
  const reasoningEvents = emitted.filter(item => item.event === 'reasoning_status')
  assert.deepEqual(reasoningEvents.map(item => item.data.status), ['started', 'updated', 'completed'])
  assert.equal(reasoningEvents[1].data.summary, 'Checking the current state.')
  assert.equal(reasoningEvents[2].data.summary, 'Checking Bearer [redacted] before answering.')
})

test('failed tool becomes tool_error without exposing its command or output', async () => {
  const emitted = []
  const adapter = new CodexCliRuntimeAdapter({
    spawnImpl: fakeSpawn([
      { type: 'thread.started', thread_id: SESSION_ID },
      { type: 'item.completed', item: {
        id: 'tool-1', type: 'command_execution', command: 'secret command',
        aggregated_output: 'secret output', exit_code: 1, status: 'failed',
      } },
      { type: 'item.completed', item: { id: 'answer', type: 'agent_message', text: 'Could not do that.' } },
      { type: 'turn.completed', usage: { input_tokens: 10, output_tokens: 5 } },
    ]),
  })
  await adapter.streamEvents({
    message: 'hello', history: [], onRuntimeBinding() {}, onText() {},
    onEvent(event, data) { emitted.push({ event, data }) },
  })
  assert.deepEqual(emitted.slice(0, 2).map(item => item.event), ['tool_call', 'tool_error'])
  assert.deepEqual(emitted.slice(0, 2).map(item => item.data.lifecycle), ['started', 'completed'])
  assert.equal(JSON.stringify(emitted).includes('secret command'), false)
  assert.equal(JSON.stringify(emitted).includes('secret output'), false)
})

test('file and MCP tool items preserve native started, updated and completed lifecycle', async () => {
  const emitted = []
  const adapter = new CodexCliRuntimeAdapter({
    spawnImpl: fakeSpawn([
      { type: 'thread.started', thread_id: SESSION_ID },
      { type: 'item.started', item: { id: 'file-1', type: 'file_change', status: 'in_progress' } },
      { type: 'item.updated', item: { id: 'file-1', type: 'file_change', status: 'in_progress' } },
      { type: 'item.completed', item: { id: 'file-1', type: 'file_change', status: 'completed' } },
      { type: 'item.started', item: {
        id: 'mcp-1', type: 'mcp_tool_call', server: 'lovehouse', tool: 'wake_up', status: 'in_progress',
      } },
      { type: 'item.updated', item: {
        id: 'mcp-1', type: 'mcp_tool_call', server: 'lovehouse', tool: 'wake_up', status: 'in_progress',
      } },
      { type: 'item.completed', item: {
        id: 'mcp-1', type: 'mcp_tool_call', server: 'lovehouse', tool: 'wake_up', status: 'completed',
      } },
      { type: 'item.completed', item: { id: 'answer', type: 'agent_message', text: 'Done.' } },
      { type: 'turn.completed', usage: { input_tokens: 10, output_tokens: 2 } },
    ]),
  })
  await adapter.streamEvents({
    message: 'do it', history: [], onRuntimeBinding() {}, onText() {},
    onEvent(event, data) { emitted.push({ event, data }) },
  })
  const tools = emitted.filter(item => item.data?.call_id)
  assert.deepEqual(tools.map(item => item.data.lifecycle), [
    'started', 'updated', 'completed', 'started', 'updated', 'completed',
  ])
  assert.deepEqual(tools.map(item => item.data.tool_type), [
    'file_change', 'file_change', 'file_change', 'mcp', 'mcp', 'mcp',
  ])
  assert.equal(tools.at(-1).data.name, 'lovehouse.wake_up')
})

test('confirmed missing resume recovers with bounded context but keeps caller thread identity outside adapter', async () => {
  const calls = []
  let invocation = 0
  const adapter = new CodexCliRuntimeAdapter({
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
          child.stderr.write('thread not found')
          child.emit('close', 1)
        } else {
          child.stdout.write(`${JSON.stringify({ type: 'thread.started', thread_id: SESSION_ID })}\n`)
          child.stdout.write(`${JSON.stringify({ type: 'item.completed', item: { type: 'agent_message', text: 'recovered' } })}\n`)
          child.stdout.write(`${JSON.stringify({ type: 'turn.completed', usage: { input_tokens: 20, output_tokens: 4 } })}\n`)
          child.emit('close', 0)
        }
      })
      return child
    },
  })
  const emitted = []
  const result = await adapter.streamEvents({
    message: 'continue',
    history: [],
    sessionId: 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee',
    getContinuationContext: async () => [{ role: 'assistant', content: 'bounded recovery' }],
    onRuntimeBinding() {}, onText() {},
    onEvent(event, data) { emitted.push({ event, data }) },
  })
  assert.equal(calls.length, 2)
  assert.deepEqual(calls[0].args.slice(0, 7), [
    'exec', 'resume', 'aaaaaaaa-bbbb-4ccc-8ddd-eeeeeeeeeeee', '--json',
    '-c', 'model_reasoning_summary="detailed"', '-c',
  ])
  assert.equal(calls[0].args[7], 'hide_agent_reasoning=false')
  assert.deepEqual(calls[1].args.slice(0, 6), [
    'exec', '--json', '-c', 'model_reasoning_summary="detailed"',
    '-c', 'hide_agent_reasoning=false',
  ])
  assert.equal(result.sessionId, SESSION_ID)
  assert.equal(emitted[0].event, 'runtime_status')
  assert.equal(emitted[0].data.status, 'recovering')
})

test('quota, auth and generic exits keep distinct stable errors', async () => {
  for (const [stderr, code] of [
    ['out of extra usage', 'QUOTA_EXHAUSTED'],
    ['login required', 'AUTH_FAILED'],
    ['connection reset', 'STREAM_INTERRUPTED'],
  ]) {
    const adapter = new CodexCliRuntimeAdapter({ spawnImpl: fakeSpawn([], { code: 1, stderr }) })
    await assert.rejects(
      adapter.streamEvents({ message: 'x', history: [], onRuntimeBinding() {}, onText() {}, onEvent() {} }),
      error => error.code === code,
    )
  }
})

test('resume usage is cumulative and emits a per-turn delta from the persisted baseline', async () => {
  const emitted = []
  const adapter = new CodexCliRuntimeAdapter({
    spawnImpl: fakeSpawn([
      { type: 'thread.started', thread_id: SESSION_ID },
      { type: 'item.completed', item: { id: 'answer', type: 'agent_message', text: 'OK' } },
      { type: 'turn.completed', usage: {
        input_tokens: 150, cached_input_tokens: 40, output_tokens: 35,
      } },
    ]),
  })
  await adapter.streamEvents({
    message: 'next turn',
    history: [],
    sessionId: SESSION_ID,
    previousUsage: { input_tokens: 100, cached_input_tokens: 30, output_tokens: 20 },
    onRuntimeBinding() {}, onText() {},
    onEvent(event, data) { emitted.push({ event, data }) },
  })
  const usage = emitted.find(item => item.event === 'usage').data
  assert.equal(usage.actual_input_tokens, 50)
  assert.equal(usage.actual_output_tokens, 15)
  assert.equal(usage.total_tokens, 65)
  assert.equal(usage.cached_input_tokens, 10)
  assert.equal(usage.cumulative_total_tokens, 185)
  assert.equal(usage.usage_source, 'codex_cli_cumulative_delta')
  assert.equal(usage.baseline_status, 'known')
})

test('first resumed turn without a persisted cumulative baseline does not mislabel totals as this-turn usage', async () => {
  const emitted = []
  const adapter = new CodexCliRuntimeAdapter({
    spawnImpl: fakeSpawn([
      { type: 'thread.started', thread_id: SESSION_ID },
      { type: 'item.completed', item: { id: 'answer', type: 'agent_message', text: 'OK' } },
      { type: 'turn.completed', usage: { input_tokens: 500, output_tokens: 80 } },
    ]),
  })
  await adapter.streamEvents({
    message: 'upgrade baseline', history: [], sessionId: SESSION_ID,
    onRuntimeBinding() {}, onText() {},
    onEvent(event, data) { emitted.push({ event, data }) },
  })
  const usage = emitted.find(item => item.event === 'usage').data
  assert.equal(usage.actual_input_tokens, null)
  assert.equal(usage.total_tokens, null)
  assert.equal(usage.cumulative_total_tokens, 580)
  assert.equal(usage.baseline_status, 'establishing')
})

test('unknown JSONL events and successful stderr diagnostics never reach the frontend stream', async () => {
  const emitted = []
  const adapter = new CodexCliRuntimeAdapter({
    spawnImpl: fakeSpawn([
      { type: 'thread.started', thread_id: SESSION_ID },
      { type: 'future.event', secret: 'must-not-pass' },
      { type: 'item.updated', item: { id: 'future', type: 'future_tool', text: 'must-not-pass' } },
      { type: 'item.completed', item: { id: 'answer', type: 'agent_message', text: 'OK' } },
      { type: 'turn.completed', usage: { input_tokens: 5, output_tokens: 2 } },
    ], { stderr: 'diagnostic must stay on stderr' }),
  })
  await adapter.streamEvents({
    message: 'hello', history: [], onRuntimeBinding() {}, onText() {},
    onEvent(event, data) { emitted.push({ event, data }) },
  })
  const serialized = JSON.stringify(emitted)
  assert.equal(serialized.includes('must-not-pass'), false)
  assert.equal(serialized.includes('diagnostic'), false)
})
