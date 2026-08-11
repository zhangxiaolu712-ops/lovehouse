import test from 'node:test'
import assert from 'node:assert/strict'
import { EventEmitter } from 'node:events'

import { createClaudeSessionManager, isValidWindowId } from './claudeProcess.js'

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

function complete(proc, { sessionId, text = 'reply', usage = { input_tokens: 12 }, cost = 0.001 } = {}) {
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

function harness(sessionIds = [SESSION_A, SESSION_B, SESSION_C]) {
  const spawned = []
  const logs = []
  const ids = [...sessionIds]
  const manager = createClaudeSessionManager({
    createSessionId: () => ids.shift(),
    spawnProcess(path, args) {
      const proc = new FakeProcess()
      spawned.push({ path, args, proc })
      return proc
    },
    logger: { error(message) { logs.push(message) } },
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
  assert.deepEqual(sessions.map(session => session.mode), ['created', 'resumed'])
  assert.equal(done[1].session_id, SESSION_A)
  assert.equal(done[1].usage.input_tokens, 12)
  assert.equal(done[1].usage.total_cost_usd, 0.001)
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

test('lost Bridge window state creates a new session and tells the frontend', () => {
  const { manager, spawned, logs } = harness([SESSION_B])
  const sessions = []
  manager.sendMessage(
    WINDOW_A,
    'after bridge restart',
    'system',
    { onSession: session => sessions.push(session) },
    { knownSessionId: SESSION_A },
  )

  assert.equal(spawned[0].args.at(spawned[0].args.indexOf('--session-id') + 1), SESSION_B)
  assert.equal(spawned[0].args.includes('--resume'), false)
  assert.equal(sessions[0].fallback, true)
  assert.equal(sessions[0].fallback_reason, 'bridge_state_lost')
  assert.match(logs[0], /reason=bridge_state_lost/)
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
