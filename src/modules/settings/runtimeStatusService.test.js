import assert from 'node:assert/strict'
import test from 'node:test'

import { getRuntimeStatus } from './runtimeStatusService.js'

test('runtime status uses Owner bearer and returns only the server payload', async () => {
  const runtime = { version: 1, daemon: { count: 1 }, services: [] }
  const result = await getRuntimeStatus({
    getToken: async () => 'owner-token',
    fetchImpl: async (url, options) => {
      assert.equal(url, '/api/v1/runtime-status')
      assert.equal(options.headers.Authorization, 'Bearer owner-token')
      return { ok: true, async json() { return { ok: true, runtime } } }
    },
  })
  assert.deepEqual(result, runtime)
})
