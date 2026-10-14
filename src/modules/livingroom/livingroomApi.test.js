import assert from 'node:assert/strict'
import test from 'node:test'

import { buildLivingroomReadUrl, readLivingroom } from './livingroomApi.js'

test('builds a bounded private-backend LivingRoom read URL', () => {
  assert.equal(buildLivingroomReadUrl(), '/api/livingroom?limit=200')
  assert.equal(buildLivingroomReadUrl({ limit: 500 }), '/api/livingroom?limit=200')
  assert.equal(
    buildLivingroomReadUrl({ limit: 20, since: '2026-10-14T00:00:00.000Z' }),
    '/api/livingroom?limit=20&since=2026-10-14T00%3A00%3A00.000Z',
  )
})

test('reads only through the authenticated private-backend route and maps safe fields', async () => {
  const calls = []
  const rows = await readLivingroom({
    accessToken: 'owner-session-token',
    fetchImpl: async (url, options) => {
      calls.push([url, options])
      return {
        ok: true,
        status: 200,
        json: async () => [{
          id: 219,
          sender: 'GPT',
          message: 'hello',
          created_at: '2026-10-14T01:45:18.407Z',
          message_id: '01a13815-d519-7614-88f0-bf2736b30ccb',
          thread_id: '01a137fb-26c1-74e3-8d17-e011e70c5c5b',
          ignored_database_field: 'not exposed',
        }],
      }
    },
  })

  assert.equal(calls[0][0], '/api/livingroom?limit=200')
  assert.equal(calls[0][1].method, 'GET')
  assert.equal(calls[0][1].headers.Authorization, 'Bearer owner-session-token')
  assert.deepEqual(rows, [{
    id: 219,
    sender: 'GPT',
    message: 'hello',
    created_at: '2026-10-14T01:45:18.407Z',
    message_id: '01a13815-d519-7614-88f0-bf2736b30ccb',
    thread_id: '01a137fb-26c1-74e3-8d17-e011e70c5c5b',
  }])
})

test('fails closed without an Owner session and never issues a request', async () => {
  let called = false
  await assert.rejects(
    readLivingroom({ accessToken: '', fetchImpl: async () => { called = true } }),
    /Owner session is required/,
  )
  assert.equal(called, false)
})

test('does not turn an upstream authentication failure into an empty room', async () => {
  await assert.rejects(
    readLivingroom({
      accessToken: 'expired',
      fetchImpl: async () => ({ ok: false, status: 401 }),
    }),
    /LivingRoom read failed \(401\)/,
  )
})
