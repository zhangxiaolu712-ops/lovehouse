import assert from 'node:assert/strict'
import test from 'node:test'

import { streamClaudeV1 } from './claudeChatV1Service.js'

const THREAD_ID = '11111111-1111-4111-8111-111111111111'

test('Claude experiment consumes only the unified /api/v1 stream contract', async () => {
  let request
  const events = []
  const text = []
  const result = await streamClaudeV1({
    threadId: THREAD_ID,
    windowId: 'client-window-1',
    message: 'hello',
  }, {
    onEvent: (event, data) => events.push({ event, data }),
    onText: (_delta, full) => text.push(full),
  }, {
    getAccessToken: async () => 'owner-jwt',
    fetchImpl: async (url, options) => {
      request = { url, options }
      return new Response([
        'event: message_start\ndata: {"thread_id":"11111111-1111-4111-8111-111111111111","runtime":"claude_cli","adapter_id":"claude-cli-v1"}',
        'event: reasoning_status\ndata: {"available":false,"status":"unavailable","summary":null,"source":"claude_cli"}',
        'event: usage\ndata: {"estimated_input_tokens":8,"actual_input_tokens":12,"cached_input_tokens":4,"actual_output_tokens":5,"reasoning_output_tokens":null,"total_tokens":17,"usage_source":"claude_cli","baseline_status":"known"}',
        'event: quota\ndata: {"status":"unknown","remaining":null,"unit":null,"reset_at":null,"source":"claude_cli_unavailable"}',
        'event: context_breakdown\ndata: {"recent_chat":{"enabled":true},"memory":{"enabled":false},"worldbook":{"enabled":false},"persona":{"enabled":false},"current_message":{"enabled":true},"reasoning":{"enabled":true,"available":false,"status":"unavailable","summary":null,"active_context":true,"resumes_with_thread":true,"compaction":"claude_native"},"estimated_tokens":2}',
        'event: text_delta\ndata: {"delta":"你"}',
        'event: text_delta\ndata: {"delta":"好"}',
        'event: message_end\ndata: {"ok":true}',
        '',
      ].join('\n\n'), { status: 200 })
    },
  })
  assert.equal(request.url, '/api/v1/chat')
  assert.equal(request.options.headers.Authorization, 'Bearer owner-jwt')
  assert.deepEqual(JSON.parse(request.options.body), {
    persona_id: 'claude', thread_id: THREAD_ID, window_id: 'client-window-1',
    scene: 'casual', message: { type: 'text', text: 'hello' },
  })
  assert.equal(JSON.stringify(request).includes('session_id'), false)
  assert.deepEqual(text, ['你', '你好'])
  assert.equal(result.text, '你好')
  assert.deepEqual(events.map(item => item.event), [
    'message_start', 'reasoning_status', 'usage', 'quota', 'context_breakdown',
    'text_delta', 'text_delta', 'message_end',
  ])
})

test('Claude experiment preserves stable runtime errors and completes its stream', async () => {
  const result = await streamClaudeV1({
    threadId: THREAD_ID, windowId: 'client-window-1', message: 'hello',
  }, {}, {
    getAccessToken: async () => 'owner-jwt',
    fetchImpl: async () => new Response([
      'event: message_start\ndata: {}',
      'event: error\ndata: {"error":{"code":"QUOTA_EXHAUSTED","message":"No quota","stage":"quota","retryable":false}}',
      'event: message_end\ndata: {"ok":false}',
      '',
    ].join('\n\n'), { status: 200 }),
  })
  assert.equal(result.ok, false)
  assert.deepEqual(result.error, {
    code: 'QUOTA_EXHAUSTED', message: 'No quota', stage: 'quota', retryable: false,
  })
})
