import assert from 'node:assert/strict'
import test from 'node:test'

import { createReplyCompletedEventProducer } from './serverEventProducer.js'

const INPUT = {
  appAccountId: 'account-a',
  executionId: '11111111-1111-4111-8111-111111111111',
  threadId: '22222222-2222-4222-8222-222222222222',
}

test('reply-completed producer sends only stable identifiers through internal auth', async () => {
  const calls = []
  const producer = createReplyCompletedEventProducer({
    identityEndpoint: 'https://app.example/internal/identity/verify-session?ignored=yes',
    internalKey: 'fake-internal-key',
    fetchImpl: async (url, options) => {
      calls.push({ url, options })
      return new Response(JSON.stringify({
        event: {
          kind: 'reply_completed',
          app_account_id: INPUT.appAccountId,
          origin_id: INPUT.executionId,
          thread_id: INPUT.threadId,
        },
      }), { status: 200, headers: { 'Content-Type': 'application/json' } })
    },
  })

  await producer.produce(INPUT)
  assert.equal(calls.length, 1)
  assert.equal(calls[0].url, 'https://app.example/internal/events/reply-completed')
  assert.equal(calls[0].options.headers['X-LoveHouse-Internal-Key'], 'fake-internal-key')
  assert.deepEqual(JSON.parse(calls[0].options.body), {
    app_account_id: INPUT.appAccountId,
    execution_id: INPUT.executionId,
    thread_id: INPUT.threadId,
  })
  assert.equal(calls[0].options.body.includes('assistant'), false)
  assert.equal(calls[0].options.body.includes('prompt'), false)
})

test('reply-completed producer has a bounded timeout', async () => {
  const producer = createReplyCompletedEventProducer({
    identityEndpoint: 'https://app.example/internal/identity/verify-session',
    internalKey: 'fake-internal-key',
    timeoutMs: 10,
    fetchImpl: async () => new Promise(() => {}),
  })
  await assert.rejects(producer.produce(INPUT), error => error.code === 'REPLY_COMPLETED_DELIVERY_TIMEOUT')
})
