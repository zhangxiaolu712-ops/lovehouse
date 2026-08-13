import test from 'node:test'
import assert from 'node:assert/strict'

import {
  ChatMessageEvidenceResolver,
  createCanonicalSourceResolver,
  SourceResolver,
} from './source.js'

const fixed = {
  source_id: 8,
  source_channel: 'chatgpt_app',
  created_by_actor: 'gpt',
  created_at: '2026-08-13T00:00:00Z',
}

test('resolver dispatches by source kind and channel with exact routes first', async () => {
  const resolver = new SourceResolver({ routes: [
    { sourceKinds: ['manual_quote'], resolver: { async resolve() { return 'generic' } } },
    { sourceKinds: ['manual_quote'], sourceChannels: ['wechat'], resolver: { async resolve() { return 'wechat' } } },
  ] })
  assert.equal(await resolver.resolve({ ...fixed, source_kind: 'manual_quote' }), 'generic')
  assert.equal(await resolver.resolve({ ...fixed, source_channel: 'wechat', source_kind: 'manual_quote' }), 'wechat')
})

test('manual quote resolves its snapshot and manual summary exposes provenance without evidence', async () => {
  const resolver = createCanonicalSourceResolver()
  const quote = await resolver.resolve({
    ...fixed, source_kind: 'manual_quote', locator: { reference: 'selected text' }, quote_text: 'exact quote',
  })
  assert.equal(quote.evidence.quote_text, 'exact quote')
  const summary = await resolver.resolve({
    ...fixed, source_kind: 'manual_summary', locator: { reference: 'user summary' }, quote_text: null,
  })
  assert.equal(summary.evidence.available, false)
  assert.deepEqual(summary.evidence.provenance.locator, { reference: 'user summary' })
  assert.equal('quote_text' in summary.evidence, false)
})

test('LoveHouse chat source fails closed and never falls through to livingroom', async () => {
  const resolver = createCanonicalSourceResolver()
  await assert.rejects(resolver.resolve({
    ...fixed,
    source_channel: 'lovehouse',
    source_kind: 'lovehouse_message',
    locator: { message_id: 101 },
  }), error => error.code === 'MEMORY_CHAT_SOURCE_NOT_CONFIGURED')
})

test('unknown source kind or channel fails closed', async () => {
  const resolver = createCanonicalSourceResolver()
  await assert.rejects(resolver.resolve({
    ...fixed, source_channel: 'wechat', source_kind: 'wechat_message', locator: { message_id: 1 },
  }), error => error.code === 'MEMORY_SOURCE_RESOLVER_NOT_CONFIGURED')
  await assert.rejects(resolver.resolve({
    ...fixed, source_channel: 'livingroom', source_kind: 'lovehouse_message', locator: { message_id: 1 },
  }), error => error.code === 'MEMORY_SOURCE_RESOLVER_NOT_CONFIGURED')
})

test('chat message repository adapter preserves pagination and hard limits', async () => {
  const calls = []
  const resolver = new ChatMessageEvidenceResolver({ messageRepository: {
    async getMessage(input) { calls.push(['get', input]); return { id: input.messageId, body: 'one' } },
    async listMessages(input) {
      calls.push(['list', input])
      return [{ id: 101 }, { id: 102 }, { id: 103 }]
    },
  } })
  const single = await resolver.resolve({
    ...fixed, source_channel: 'api_chat', source_kind: 'lovehouse_message', locator: { message_id: 99 },
  })
  assert.equal(single.evidence.messages[0].id, 99)
  const range = await resolver.resolve({
    ...fixed, source_channel: 'api_chat', source_kind: 'lovehouse_message_range',
    locator: { start_message_id: 101, end_message_id: 110 },
  }, { limit: 2 })
  assert.equal(range.evidence.messages.length, 2)
  assert.equal(range.evidence.has_more, true)
  assert.equal(range.evidence.next_cursor, 102)
  assert.deepEqual(calls[1][1], {
    sourceChannel: 'api_chat', startMessageId: 101, endMessageId: 110,
    afterMessageId: null, limit: 3,
  })
  await assert.rejects(resolver.resolve({
    ...fixed, source_kind: 'lovehouse_message_range', locator: { start_message_id: 1, end_message_id: 51 },
  }), error => error.code === 'MEMORY_SOURCE_RANGE_INVALID')
})
