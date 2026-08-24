import assert from 'node:assert/strict'
import test from 'node:test'

import { streamCodexV1 } from './codexChatV1Service.js'

const THREAD_ID = '11111111-1111-4111-8111-111111111111'

test('frontend consumes the unified stream without any Codex CLI private schema', async () => {
  let request
  const events = []
  const text = []
  const result = await streamCodexV1({
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
        'event: message_start\ndata: {"thread_id":"11111111-1111-4111-8111-111111111111","runtime":"codex_cli","adapter_id":"codex-cli-v1"}',
        'event: reasoning_status\ndata: {"available":true,"status":"completed","summary":"Native summary"}',
        'event: tool_call\ndata: {"call_id":"item-1","tool_type":"command","name":"shell","status":"running","lifecycle":"updated"}',
        'event: tool_result\ndata: {"call_id":"item-1","tool_type":"command","name":"shell","status":"success","lifecycle":"completed","summary":"Command completed"}',
        'event: usage\ndata: {"estimated_input_tokens":2,"actual_input_tokens":3,"actual_output_tokens":4,"total_tokens":7,"cumulative_input_tokens":103,"cumulative_output_tokens":24,"previous_cumulative_input_tokens":100,"previous_cumulative_output_tokens":20,"baseline_status":"known","usage_source":"codex_cli_cumulative_delta"}',
        'event: quota\ndata: {"status":"unknown","remaining":null,"unit":null,"reset_at":null,"source":"codex_cli_unavailable"}',
        'event: context_breakdown\ndata: {"recent_chat":{"enabled":true},"memory":{"enabled":false},"worldbook":{"enabled":false},"persona":{"enabled":false},"current_message":{"enabled":true},"reasoning":{"enabled":true,"available":true,"status":"completed","summary":"Native summary","active_context":true,"resumes_with_thread":true,"compaction":"codex_native"},"estimated_tokens":2}',
        'event: text_delta\ndata: {"delta":"hel"}',
        'event: text_delta\ndata: {"delta":"lo"}',
        'event: message_end\ndata: {"ok":true}',
        '',
      ].join('\n\n'), { status: 200, headers: { 'Content-Type': 'text/event-stream' } })
    },
  })
  assert.equal(request.url, '/api/v1/chat')
  assert.equal(request.options.headers.Authorization, 'Bearer owner-jwt')
  const body = JSON.parse(request.options.body)
  assert.equal(body.persona_id, 'codex')
  assert.equal(body.thread_id, THREAD_ID)
  assert.equal(body.scene, 'work')
  assert.equal('session_id' in body, false)
  assert.equal(result.text, 'hello')
  assert.deepEqual(text, ['hel', 'hello'])
  assert.deepEqual(events.map(item => item.event), [
    'message_start', 'reasoning_status', 'tool_call', 'tool_result', 'usage', 'quota',
    'context_breakdown', 'text_delta', 'text_delta', 'message_end',
  ])
  assert.equal(events[1].data.summary, 'Native summary')
  assert.equal(events[2].data.lifecycle, 'updated')
  assert.equal(events[4].data.cumulative_input_tokens, 103)
  assert.equal(events[6].data.reasoning.resumes_with_thread, true)
})

test('stream error preserves exact code and stage for the experiment UI', async () => {
  const result = await streamCodexV1({
    threadId: THREAD_ID, windowId: 'client-window-1', message: 'hello',
  }, {}, {
    getAccessToken: async () => 'owner-jwt',
    fetchImpl: async () => new Response([
      'event: message_start\ndata: {}',
      'event: quota\ndata: {"status":"unknown"}',
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
