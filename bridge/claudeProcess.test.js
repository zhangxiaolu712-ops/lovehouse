import test from 'node:test'
import assert from 'node:assert/strict'
import { EventEmitter } from 'node:events'

import {
  createClaudeSessionManager,
  isValidWindowId,
  normalizeRecentHistory,
  RECENT_HISTORY_LIMITS,
} from './claudeProcess.js'
import { CLAUDE_ALLOWED_TOOLS } from './claudePolicy.js'

const WINDOW_A = '11111111-1111-4111-8111-111111111111'
const WINDOW_B = '22222222-2222-4222-8222-222222222222'
const SESSION_A = 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa'
const SESSION_B = 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb'
const SESSION_C = 'cccccccc-cccc-4ccc-8ccc-cccccccccccc'

class FakeProcess extends EventEmitter {
  constructor() {
    super()
    this.stdout = new EventEmitter()
    this.stderr = new EventEmitter()
    this.killed = false
  }

  kill() {
    this.killed = true
  }
}

function emitJson(proc, event) {
  proc.stdout.emit('data', Buffer.from(`${JSON.stringify(event)}\n`))
}

function emitMcpInit(proc, {
  sessionId,
  status = 'connected',
  tools = CLAUDE_ALLOWED_TOOLS,
} = {}) {
  emitJson(proc, {
    type: 'system',
    subtype: 'init',
    session_id: sessionId,
    mcp_servers: [{ name: 'lovehouse', status }],
    tools,
  })
}

function complete(proc, {
  sessionId,
  text = 'reply',
  usage = { input_tokens: 12 },
  cost = 0.001,
  initialize = true,
} = {}) {
  if (initialize) emitMcpInit(proc, { sessionId })
  emitJson(proc, {
    type: 'stream_event',
    session_id: sessionId,
    event: { type: 'content_block_delta', delta: { type: 'text_delta', text } },
  })
  emitJson(proc, {
    type: 'result',
    subtype: 'success',
    is_error: false,
    session_id: sessionId,
    result: text,
    usage,
    total_cost_usd: cost,
    num_turns: 1,
  })
  proc.emit('close', 0)
}

function harness(sessionIds = [SESSION_A, SESSION_B, SESSION_C], sourceEnv = {}) {
  const spawned = []
  const logs = []
  const ids = [...sessionIds]
  const manager = createClaudeSessionManager({
    createSessionId: () => ids.shift(),
    spawnProcess(path, args, options) {
      const proc = new FakeProcess()
      spawned.push({ path, args, options, proc })
      return proc
    },
    logger: { error(message) { logs.push(message) } },
    sourceEnv,
    mcpUrl: 'https://bridge.example.test/api/mcp/claude',
  })
  return { manager, spawned, logs }
}

test('window ids must be UUIDs', () => {
  assert.equal(isValidWindowId(WINDOW_A), true)
  assert.equal(isValidWindowId('shared-window'), false)
  assert.equal(isValidWindowId(''), false)
})

test('first turn binds --session-id and later turns use only --resume', () => {
  const { manager, spawned } = harness()
  const text = []
  const sessions = []
  const done = []
  const callbacks = {
    onText(delta) { text.push(delta) },
    onSession(session) { sessions.push(session) },
    onDone(result) { done.push(result) },
  }

  assert.equal(manager.sendMessage(WINDOW_A, 'first message', 'system', callbacks), true)
  assert.deepEqual(spawned[0].args.slice(0, 2), ['-p', 'first message'])
  assert.equal(spawned[0].args.includes('--continue'), false)
  assert.equal(spawned[0].args.at(spawned[0].args.indexOf('--session-id') + 1), SESSION_A)
  assert.equal(spawned[0].args.includes('--resume'), false)
  complete(spawned[0].proc, { sessionId: SESSION_A })

  assert.equal(manager.sendMessage(WINDOW_A, 'second message', 'system', callbacks), true)
  assert.deepEqual(spawned[1].args.slice(0, 2), ['-p', 'second message'])
  assert.equal(spawned[1].args.includes('--continue'), false)
  assert.equal(spawned[1].args.at(spawned[1].args.indexOf('--resume') + 1), SESSION_A)
  assert.equal(spawned[1].args.includes('--session-id'), false)
  complete(spawned[1].proc, { sessionId: SESSION_A, text: 'second reply' })

  assert.deepEqual(text, ['reply', 'second reply'])
  assert.deepEqual(sessions.map(session => session.mode), ['new_session', 'resumed_session'])
  assert.equal(done[1].session_id, SESSION_A)
  assert.equal(done[1].usage.input_tokens, 12)
  assert.equal(done[1].usage.total_cost_usd, 0.001)
})

test('launch policy disables built-ins, isolates settings and passes only a narrow environment', () => {
  const { manager, spawned } = harness([SESSION_A], {
    HOME: '/root',
    PATH: '/usr/bin',
    CLAUDE_CODE_OAUTH_TOKEN: 'claude-auth',
    ANTHROPIC_API_KEY: 'must-not-leak',
    SUPABASE_SECRET_KEY: 'must-not-leak',
    LIVINGROOM_KEY: 'must-not-leak',
    OAUTH_TOKEN_SECRET: 'must-not-leak',
    PM2_HOME: '/root/.pm2',
  })
  manager.sendMessage(WINDOW_A, 'hello', 'system', {})

  const { args, options } = spawned[0]
  assert.equal(args.at(args.indexOf('--tools') + 1), '')
  assert.equal(args.at(args.indexOf('--permission-mode') + 1), 'dontAsk')
  assert.equal(args.at(args.indexOf('--setting-sources') + 1), '')
  assert.equal(args.at(args.indexOf('--settings') + 1), '{}')
  assert.equal(args.includes('--strict-mcp-config'), true)
  assert.equal(args.includes('--disable-slash-commands'), true)
  assert.equal(args.includes('--dangerously-skip-permissions'), false)
  assert.deepEqual(
    args.slice(args.indexOf('--allowedTools') + 1, args.indexOf('--permission-mode')),
    CLAUDE_ALLOWED_TOOLS
  )
  assert.deepEqual(options.env, {
    HOME: '/root',
    PATH: '/usr/bin',
  })
  const mcpConfig = JSON.parse(args.at(args.indexOf('--mcp-config') + 1))
  assert.deepEqual(mcpConfig, {
    mcpServers: {
      lovehouse: { type: 'http', url: 'https://bridge.example.test/api/mcp/claude' },
    },
  })
})

test('MCP load failure is explicit and no model text escapes as a false success', () => {
  const { manager, spawned } = harness()
  const text = []
  const errors = []
  const done = []
  manager.sendMessage(WINDOW_A, 'hello', 'system', {
    onText: delta => text.push(delta),
    onError: error => errors.push(error),
    onDone: result => done.push(result),
  })

  emitMcpInit(spawned[0].proc, { sessionId: SESSION_A, status: 'failed', tools: [] })
  emitJson(spawned[0].proc, {
    type: 'stream_event',
    session_id: SESSION_A,
    event: { type: 'content_block_delta', delta: { type: 'text_delta', text: 'fake answer' } },
  })

  assert.deepEqual(text, [])
  assert.deepEqual(done, [])
  assert.deepEqual(errors, ['LoveHouse MCP failed to initialize (failed)'])
  assert.equal(spawned[0].proc.killed, true)
  assert.deepEqual(manager.getStats(), { windows: 1, busy: 0, turns: 0, fallbacks: 0 })
})

test('missing or changed MCP tools fail closed', () => {
  const missing = harness()
  const missingErrors = []
  missing.manager.sendMessage(WINDOW_A, 'hello', 'system', { onError: error => missingErrors.push(error) })
  emitMcpInit(missing.spawned[0].proc, {
    sessionId: SESSION_A,
    tools: CLAUDE_ALLOWED_TOOLS.slice(1),
  })
  assert.deepEqual(missingErrors, ['LoveHouse MCP tool allowlist did not match Claude initialization'])

  const unreported = harness()
  const unreportedErrors = []
  unreported.manager.sendMessage(WINDOW_A, 'hello', 'system', { onError: error => unreportedErrors.push(error) })
  emitJson(unreported.spawned[0].proc, {
    type: 'system', subtype: 'init', session_id: SESSION_A, mcp_servers: [], tools: [],
  })
  assert.deepEqual(unreportedErrors, ['LoveHouse MCP was not reported by Claude'])
})

test('a successful exit without MCP initialization is still an explicit failure', () => {
  const { manager, spawned } = harness()
  const errors = []
  const done = []
  manager.sendMessage(WINDOW_A, 'hello', 'system', {
    onError: error => errors.push(error),
    onDone: result => done.push(result),
  })
  complete(spawned[0].proc, { sessionId: SESSION_A, initialize: false })

  assert.deepEqual(done, [])
  assert.deepEqual(errors, ['LoveHouse MCP initialization was not confirmed'])
})

test('tool lifecycle can continue to an answer and resumed turns retain the same policy', () => {
  const { manager, spawned } = harness()
  const text = []
  const done = []
  const callbacks = {
    onText: delta => text.push(delta),
    onDone: result => done.push(result),
  }
  manager.sendMessage(WINDOW_A, 'read the room', 'system', callbacks)
  emitMcpInit(spawned[0].proc, { sessionId: SESSION_A })
  emitJson(spawned[0].proc, {
    type: 'assistant',
    session_id: SESSION_A,
    message: { content: [{ type: 'tool_use', name: CLAUDE_ALLOWED_TOOLS[0], input: { limit: 3 } }] },
  })
  emitJson(spawned[0].proc, {
    type: 'user',
    session_id: SESSION_A,
    message: { content: [{ type: 'tool_result', content: '[{"id":77}]' }] },
  })
  complete(spawned[0].proc, { sessionId: SESSION_A, text: '看到了 #77', initialize: false })

  manager.sendMessage(WINDOW_A, 'read again', 'system', callbacks)
  complete(spawned[1].proc, { sessionId: SESSION_A, text: '工具仍可用' })

  assert.deepEqual(text, ['看到了 #77', '工具仍可用'])
  assert.equal(done.length, 2)
  assert.equal(spawned[1].args.at(spawned[1].args.indexOf('--resume') + 1), SESSION_A)
  assert.deepEqual(
    spawned[1].args.slice(
      spawned[1].args.indexOf('--allowedTools') + 1,
      spawned[1].args.indexOf('--permission-mode')
    ),
    CLAUDE_ALLOWED_TOOLS
  )
})

test('concurrent windows isolate active processes, busy state and reset', () => {
  const { manager, spawned } = harness()
  const errors = []
  assert.equal(manager.sendMessage(WINDOW_A, 'a', 'system', { onError: error => errors.push(error) }), true)
  assert.equal(manager.sendMessage(WINDOW_B, 'b', 'system', { onError: error => errors.push(error) }), true)
  assert.equal(spawned.length, 2)
  assert.deepEqual(manager.getStats(), { windows: 2, busy: 2, turns: 0, fallbacks: 0 })

  assert.equal(manager.sendMessage(WINDOW_A, 'a again', 'system', { onError: error => errors.push(error) }), false)
  assert.deepEqual(errors, ['busy'])
  assert.equal(manager.resetSession(WINDOW_B), true)
  assert.equal(spawned[1].proc.killed, true)
  assert.equal(spawned[0].proc.killed, false)
  assert.deepEqual(manager.getStats(), { windows: 1, busy: 1, turns: 0, fallbacks: 0 })
})

test('missing resumed session falls back once with a new explicit session id', () => {
  const { manager, spawned, logs } = harness()
  const sessions = []
  const errors = []
  const done = []
  const callbacks = {
    onSession: session => sessions.push(session),
    onError: error => errors.push(error),
    onDone: result => done.push(result),
  }

  manager.sendMessage(WINDOW_A, 'first', 'system', callbacks)
  complete(spawned[0].proc, { sessionId: SESSION_A })
  manager.sendMessage(WINDOW_A, 'resume me', 'system', callbacks)
  spawned[1].proc.stderr.emit('data', Buffer.from('No conversation found with session ID'))
  spawned[1].proc.emit('close', 1)

  assert.equal(spawned.length, 3)
  assert.equal(spawned[2].args.at(spawned[2].args.indexOf('--session-id') + 1), SESSION_B)
  assert.equal(spawned[2].args.includes('--resume'), false)
  assert.equal(spawned[2].args[1], 'resume me')
  assert.equal(sessions.at(-1).fallback, true)
  assert.equal(sessions.at(-1).fallback_reason, 'session_not_found')
  assert.match(logs[0], /reason=session_not_found/)
  complete(spawned[2].proc, { sessionId: SESSION_B, text: 'fallback reply' })

  assert.deepEqual(errors, [])
  assert.equal(done.at(-1).session_id, SESSION_B)
  assert.deepEqual(manager.getStats(), { windows: 1, busy: 0, turns: 2, fallbacks: 1 })
})

test('unrelated resume failures stay explicit and do not create a new session', () => {
  const { manager, spawned } = harness()
  const errors = []
  manager.sendMessage(WINDOW_A, 'first', 'system', {})
  complete(spawned[0].proc, { sessionId: SESSION_A })
  manager.sendMessage(WINDOW_A, 'second', 'system', { onError: error => errors.push(error) })
  spawned[1].proc.stderr.emit('data', Buffer.from('authentication failed'))
  spawned[1].proc.emit('close', 1)

  assert.equal(spawned.length, 2)
  assert.deepEqual(errors, ['authentication failed'])
})

test('lost Bridge window state recovers a valid known session before creating anything', () => {
  const { manager, spawned, logs } = harness([SESSION_B])
  const sessions = []
  const done = []
  manager.sendMessage(
    WINDOW_A,
    'after bridge restart',
    'system',
    {
      onSession: session => sessions.push(session),
      onDone: result => done.push(result),
    },
    { knownSessionId: SESSION_A, sessionIntent: 'continue' },
  )

  assert.equal(spawned[0].args.at(spawned[0].args.indexOf('--resume') + 1), SESSION_A)
  assert.equal(spawned[0].args.includes('--session-id'), false)
  assert.equal(sessions[0].mode, 'resumed_known_session')
  assert.equal(sessions[0].fallback, false)
  complete(spawned[0].proc, { sessionId: SESSION_A, text: 'recovered' })
  assert.equal(done[0].session_id, SESSION_A)
  assert.equal(done[0].session_mode, 'resumed_known_session')
  assert.deepEqual(logs, [])
  assert.deepEqual(manager.getStats(), { windows: 1, busy: 0, turns: 1, fallbacks: 0 })
})

test('invalid or missing known session ids use an explicit controlled fallback', () => {
  for (const [knownSessionId, reason] of [
    ['not-a-session', 'known_session_invalid'],
    [null, 'known_session_missing'],
  ]) {
    const { manager, spawned, logs } = harness()
    const sessions = []
    manager.sendMessage(
      WINDOW_A,
      'continue',
      'system',
      { onSession: session => sessions.push(session) },
      { knownSessionId, sessionIntent: 'continue' },
    )

    assert.equal(spawned.length, 1)
    assert.equal(spawned[0].args.at(spawned[0].args.indexOf('--session-id') + 1), SESSION_A)
    assert.equal(sessions[0].mode, 'fallback_new_session')
    assert.equal(sessions[0].fallback, true)
    assert.equal(sessions[0].fallback_reason, reason)
    assert.equal(sessions[0].history_bootstrapped, false)
    assert.match(logs[0], new RegExp(`reason=${reason}`))
  }
})

test('a non-session failure while recovering a known session never triggers fallback', () => {
  const { manager, spawned } = harness()
  const errors = []
  manager.sendMessage(
    WINDOW_A,
    'recover me',
    'system',
    { onError: error => errors.push(error) },
    { knownSessionId: SESSION_A, sessionIntent: 'continue' },
  )
  spawned[0].proc.stderr.emit('data', Buffer.from('permission denied by upstream'))
  spawned[0].proc.emit('close', 1)

  assert.equal(spawned.length, 1)
  assert.deepEqual(errors, ['permission denied by upstream'])
  assert.deepEqual(manager.getStats(), { windows: 1, busy: 0, turns: 0, fallbacks: 0 })

  manager.sendMessage(WINDOW_A, 'retry recovery', 'system', {})
  assert.equal(spawned.length, 2)
  assert.equal(spawned[1].args.at(spawned[1].args.indexOf('--resume') + 1), SESSION_A)
  assert.equal(spawned[1].args.includes('--session-id'), false)
})

test('reset prevents a stale known session from being restored and stays window-scoped', () => {
  const { manager, spawned } = harness()
  manager.sendMessage(WINDOW_A, 'a', 'system', {})
  complete(spawned[0].proc, { sessionId: SESSION_A })
  manager.sendMessage(WINDOW_B, 'b', 'system', {})
  complete(spawned[1].proc, { sessionId: SESSION_B })

  assert.equal(manager.resetSession(WINDOW_A), true)
  const sessions = []
  manager.sendMessage(
    WINDOW_A,
    'stale retry',
    'system',
    { onSession: session => sessions.push(session) },
    { knownSessionId: SESSION_A, sessionIntent: 'continue' },
  )

  assert.equal(spawned[2].args.includes('--resume'), false)
  assert.equal(spawned[2].args.at(spawned[2].args.indexOf('--session-id') + 1), SESSION_C)
  assert.equal(sessions[0].mode, 'new_session')
  manager.sendMessage(WINDOW_B, 'b again', 'system', {})
  assert.equal(spawned[3].args.at(spawned[3].args.indexOf('--resume') + 1), SESSION_B)
})

test('recent history validates roles, item limits, total caps and current-message duplication', () => {
  const message = { role: 'user', content: 'hello' }
  assert.deepEqual(normalizeRecentHistory([message], 'different'), [message])
  assert.throws(
    () => normalizeRecentHistory(Array.from({ length: RECENT_HISTORY_LIMITS.messages + 1 }, () => message)),
    /cannot exceed 30 messages/,
  )
  assert.throws(() => normalizeRecentHistory([{ role: 'system', content: 'no' }]), /invalid role/)
  assert.throws(
    () => normalizeRecentHistory([{ role: 'user', content: 'a'.repeat(2_001) }]),
    /content is too long/,
  )
  assert.throws(
    () => normalizeRecentHistory(Array.from({ length: 11 }, () => ({
      role: 'assistant', content: 'a'.repeat(2_000),
    }))),
    /total character limit/,
  )
  assert.throws(
    () => normalizeRecentHistory(Array.from({ length: 5 }, () => ({
      role: 'assistant', content: '😀'.repeat(2_000),
    }))),
    /total byte limit/,
  )
  assert.throws(() => normalizeRecentHistory([message], 'hello'), /must not repeat the current message/)
})

test('fallback history is injected once without duplicating the current message', () => {
  const { manager, spawned } = harness([SESSION_B])
  const sessions = []
  const history = [
    { role: 'user', content: 'older question' },
    { role: 'assistant', content: 'older answer' },
  ]
  manager.sendMessage(
    WINDOW_A,
    'current unique question',
    'system',
    { onSession: session => sessions.push(session) },
    { knownSessionId: SESSION_A, recentHistory: history, sessionIntent: 'continue' },
  )
  assert.equal(spawned[0].args[1], 'current unique question')
  spawned[0].proc.stderr.emit('data', Buffer.from('No conversation found with session ID'))
  spawned[0].proc.emit('close', 1)

  const fallbackPrompt = spawned[1].args[1]
  assert.match(fallbackPrompt, /<recent_history_bootstrap>/)
  assert.match(fallbackPrompt, /older question/)
  assert.equal(fallbackPrompt.match(/current unique question/g)?.length, 1)
  assert.equal(sessions.at(-1).mode, 'fallback_new_session')
  assert.equal(sessions.at(-1).history_bootstrapped, true)
  complete(spawned[1].proc, { sessionId: SESSION_B })

  manager.sendMessage(WINDOW_A, 'next turn', 'system', {})
  assert.equal(spawned[2].args[1], 'next turn')
  assert.equal(spawned[2].args.at(spawned[2].args.indexOf('--resume') + 1), SESSION_B)
})

test('fallback still requires the exact MCP tool set and fails closed when MCP is unavailable', () => {
  const { manager, spawned } = harness([SESSION_B])
  const errors = []
  const done = []
  manager.sendMessage(
    WINDOW_A,
    'recover',
    'system',
    { onError: error => errors.push(error), onDone: result => done.push(result) },
    { knownSessionId: SESSION_A, sessionIntent: 'continue' },
  )
  spawned[0].proc.stderr.emit('data', Buffer.from('No conversation found with session ID'))
  spawned[0].proc.emit('close', 1)

  assert.deepEqual(
    spawned[1].args.slice(
      spawned[1].args.indexOf('--allowedTools') + 1,
      spawned[1].args.indexOf('--permission-mode'),
    ),
    CLAUDE_ALLOWED_TOOLS,
  )
  emitMcpInit(spawned[1].proc, { sessionId: SESSION_B, status: 'failed', tools: [] })
  assert.deepEqual(done, [])
  assert.deepEqual(errors, ['LoveHouse MCP failed to initialize (failed)'])
  assert.equal(spawned[1].proc.killed, true)
  assert.equal(spawned.length, 2)
})

test('stream parser forwards thinking and falls back to final result text when partials are absent', () => {
  const { manager, spawned } = harness()
  const thinking = []
  const text = []
  const done = []
  manager.sendMessage(WINDOW_A, 'hello', 'system', {
    onThinking: delta => thinking.push(delta),
    onText: delta => text.push(delta),
    onDone: result => done.push(result),
  })
  emitMcpInit(spawned[0].proc, { sessionId: SESSION_A })
  emitJson(spawned[0].proc, {
    type: 'stream_event',
    session_id: SESSION_A,
    event: { type: 'content_block_delta', delta: { type: 'thinking_delta', thinking: 'think' } },
  })
  emitJson(spawned[0].proc, {
    type: 'result', subtype: 'success', is_error: false, session_id: SESSION_A, result: 'final text',
  })
  spawned[0].proc.emit('close', 0)

  assert.deepEqual(thinking, ['think'])
  assert.deepEqual(text, ['final text'])
  assert.equal(done[0].text, 'final text')
})

test('reported session id cannot silently replace the server binding', () => {
  const { manager, spawned } = harness()
  const errors = []
  manager.sendMessage(WINDOW_A, 'hello', 'system', { onError: error => errors.push(error) })
  complete(spawned[0].proc, { sessionId: SESSION_B })

  assert.deepEqual(errors, ['Claude returned a mismatched session_id'])
  assert.deepEqual(manager.getStats(), { windows: 1, busy: 0, turns: 0, fallbacks: 0 })
})

test('ten-turn native sessions stop replaying the global handwritten transcript', () => {
  const history = []
  let oldCharacters = 0
  let nativeCharacters = 0
  for (let turn = 1; turn <= 10; turn += 1) {
    const user = `第${turn}轮：请接着回答这个窗口里的问题。`
    const assistant = `第${turn}轮回复：这是只属于当前窗口的回答。`
    history.push({ role: 'user', content: user })
    const context = history
      .map(message => `${message.role === 'user' ? '小婷' : '小克'}: ${message.content}`)
      .join('\n')
    oldCharacters += `[最近的对话]\n${context}\n\n[现在]\n小婷: ${user}`.length
    nativeCharacters += user.length
    history.push({ role: 'assistant', content: assistant })
  }

  assert.ok(oldCharacters > nativeCharacters * 10)
  assert.equal(nativeCharacters, 181)
  assert.equal(oldCharacters, 2707)
})
