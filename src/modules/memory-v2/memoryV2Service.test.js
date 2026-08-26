import assert from 'node:assert/strict'
import test from 'node:test'
import { createMemory, listMemoryTimeline, reviseMemory } from './memoryV2Service.js'

function dependencies(payload = { ok: true }) {
  const calls = []
  return { calls, value: { endpoint: '/api/v1/memory', getAccessToken: async () => 'owner-token', fetchImpl: async (url, options) => {
    calls.push({ url, options }); return { ok: true, status: 200, json: async () => payload }
  } } }
}
test('timeline and writes use Owner Client API, never V1 tables', async () => {
  const timeline = dependencies({ ok: true, items: [{ memory_id: 'm1' }] })
  assert.equal((await listMemoryTimeline('gpt', { query: 'hello' }, timeline.value))[0].memory_id, 'm1')
  assert.equal(timeline.calls[0].options.headers.Authorization, 'Bearer owner-token')
  const writes = dependencies(); await createMemory('claude', { content: 'one' }, writes.value); await reviseMemory('claude', 'm1', { content: 'two' }, writes.value)
  assert.deepEqual(writes.calls.map(call => call.options.method), ['POST', 'POST'])
  assert.equal(writes.calls.some(call => /brain|memories/.test(call.url)), false)
})
