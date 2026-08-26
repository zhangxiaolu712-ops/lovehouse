import assert from 'node:assert/strict'
import test from 'node:test'
import express from 'express'
import { installMemoryTimeline } from './memoryTimeline.js'

test('GPT timeline is owner-authenticated and actor-scoped', async () => {
  const calls = []
  const app = express()
  installMemoryTimeline(app, {
    verifyOwner(req, _res, next) { req.userId = 'owner-1'; next() },
    memoryV2Repository: { rest: async (method, path) => { calls.push({ method, path }); return [{ id: 'm1', space_key: 'gpt', status: 'active', current_revision_id: 'r1', created_at: '2026-08-25T00:00:00Z', memory_v2_revisions: { id: 'r1', revision_number: 1, content: 'hello', metadata: {} } }] } },
  })
  const server = app.listen(0, '127.0.0.1')
  try {
    const { port } = server.address()
    const response = await fetch(`http://127.0.0.1:${port}/v1/memory/gpt/timeline?limit=10`)
    assert.equal(response.status, 200)
    const payload = await response.json()
    assert.equal(payload.actor, 'gpt')
    assert.equal(payload.items[0].content, 'hello')
    assert.match(calls[0].path, /owner_id=eq\.owner-1/)
    assert.match(calls[0].path, /space_key=eq\.gpt/)
  } finally { await new Promise(resolve => server.close(resolve)) }
})
