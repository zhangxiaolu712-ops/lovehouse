import test from 'node:test'
import assert from 'node:assert/strict'

import { streamCodexChat } from './codexChatService.js'

function sseResponse(chunks, status = 200) {
  const encoder = new TextEncoder()
  return new Response(new ReadableStream({
    start(controller) {
      for (const chunk of chunks) controller.enqueue(encoder.encode(chunk))
      controller.close()
    },
  }), {
    status,
    headers: { 'Content-Type': 'text/event-stream' },
  })
}

test('Codex service sends Supabase JWT and handles session, text and done SSE', async () => {
  const requests = []
  const sessions = []
  const deltas = []
  const done = []
  const result = await streamCodexChat({
    windowId: 'window-stable-001',
    message: '你好',
    recentHistory: [{ role: 'assistant', content: '上一条' }],
  }, {
    onSession: event => sessions.push(event),
    onText: (delta, fullText) => deltas.push({ delta, fullText }),
    onDone: event => done.push(event),
  }, {
    getAccessToken: async () => 'user-jwt',
    fetchImpl: async (url, options) => {
      requests.push({ url, options })
      return sseResponse([
        'event: session\ndata: {"session_id":"0199a213-81c0-7800-8aa1-bbab2a035a53",',
        '"window_id":"window-stable-001","resumed":true}\n\nevent: text\ndata: {"text":"你',
        '好"}\n\nevent: text\ndata: {"text":"呀"}\n\nevent: done\ndata: {"ok":true,"session_id":"0199a213-81c0-7800-8aa1-bbab2a035a53"}\n\n',
      ])
    },
  })

  assert.equal(requests.length, 1)
  assert.equal(requests[0].url, '/api/codex/chat')
  assert.equal(requests[0].options.headers.Authorization, 'Bearer user-jwt')
  assert.deepEqual(JSON.parse(requests[0].options.body), {
    window_id: 'window-stable-001',
    message: '你好',
    recent_history: [{ role: 'assistant', content: '上一条' }],
  })
  assert.equal('known_session_id' in JSON.parse(requests[0].options.body), false)
  assert.deepEqual(sessions, [{
    session_id: '0199a213-81c0-7800-8aa1-bbab2a035a53',
    window_id: 'window-stable-001',
    resumed: true,
  }])
  assert.deepEqual(deltas, [
    { delta: '你好', fullText: '你好' },
    { delta: '呀', fullText: '你好呀' },
  ])
  assert.deepEqual(done, [{ ok: true, session_id: '0199a213-81c0-7800-8aa1-bbab2a035a53' }])
  assert.deepEqual(result, {
    ok: true,
    text: '你好呀',
    session: sessions[0],
    error: null,
  })
})

test('Codex service preserves explicit error and terminal done events', async () => {
  const errors = []
  const done = []
  const result = await streamCodexChat({
    windowId: 'window-stable-002',
    message: '重试',
  }, {
    onError: event => errors.push(event),
    onDone: event => done.push(event),
  }, {
    getAccessToken: async () => 'user-jwt',
    fetchImpl: async () => sseResponse([
      'event: error\ndata: {"type":"provider","code":"provider_unavailable","message":"Codex CLI could not start","retryable":true}\n\n',
      'event: done\ndata: {"ok":false,"session_id":null}\n\n',
    ]),
  })

  assert.deepEqual(errors, [{
    type: 'provider',
    code: 'provider_unavailable',
    message: 'Codex CLI could not start',
    retryable: true,
  }])
  assert.deepEqual(done, [{ ok: false, session_id: null }])
  assert.equal(result.ok, false)
  assert.equal(result.error.code, 'provider_unavailable')
})

test('Codex service keeps categorized HTTP errors visible', async () => {
  await assert.rejects(
    streamCodexChat({ windowId: 'window-stable-003', message: 'hello' }, {}, {
      getAccessToken: async () => 'expired-jwt',
      fetchImpl: async () => new Response(JSON.stringify({
        error: { type: 'auth', code: 'auth_invalid', message: 'Invalid user JWT', retryable: false },
      }), { status: 401, headers: { 'Content-Type': 'application/json' } }),
    }),
    error => error.detail?.type === 'auth'
      && error.detail?.code === 'auth_invalid'
      && error.message === 'Invalid user JWT',
  )
})
