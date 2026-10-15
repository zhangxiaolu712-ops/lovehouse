import assert from 'node:assert/strict'
import test from 'node:test'

import { createToolActionProducer } from './toolActionProducer.js'

test('Tool Action producer sends metadata-only canonical relation through internal auth', async () => {
  const action = { tool_action_id: '0199db1d-6c00-7003-8003-000000000003',
    app_account_id: 'account-a', execution_id: '11111111-1111-4111-8111-111111111111',
    assistant_message_id: '0199db1d-6c00-7002-8002-000000000002',
    thread_id: '22222222-2222-4222-8222-222222222222', tool_identity: 'Read',
    provider_source: 'claude_cli', provider_raw_call_id: 'toolu_1', lifecycle_state: 'running',
    provider_metadata: { tool_type: 'claude_tool' } }
  const calls = []
  const producer = createToolActionProducer({
    identityEndpoint: 'https://app.example/internal/identity/verify-session', internalKey: 'key',
    fetchImpl: async (url, options) => {
      calls.push({ url, options })
      return new Response(JSON.stringify({ tool_action: action }), {
        status: 200, headers: { 'Content-Type': 'application/json' },
      })
    },
  })
  assert.equal((await producer.produce(action)).tool_action_id, action.tool_action_id)
  assert.equal(calls[0].url, 'https://app.example/internal/tool-actions/upsert')
  assert.equal(calls[0].options.headers['X-LoveHouse-Internal-Key'], 'key')
  assert.deepEqual(JSON.parse(calls[0].options.body), action)
  assert.equal(calls[0].options.body.includes('Authorization'), false)
})
