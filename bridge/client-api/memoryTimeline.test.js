import assert from 'node:assert/strict'
import { once } from 'node:events'
import http from 'node:http'
import test from 'node:test'
import express from 'express'
import { installMemoryTimeline } from './memoryTimeline.js'

async function harness(t) {
  const calls = []
  const app = express()
  app.use(express.json())
  app.use('/v1', (req, _res, next) => { req.userId = 'owner-1'; next() })
  const actorApi = {
    remember: async input => (calls.push(['remember', input]), { memory_id: 'm2', revision_id: 'r2' }),
    revise: async (id, input) => (calls.push(['revise', id, input]), { memory_id: id, revision_id: 'r3' }),
    history: async id => (calls.push(['history', id]), [{ revision_number: 2 }]),
    expandSource: async id => (calls.push(['source', id]), { source_id: id }),
  }
  installMemoryTimeline(app, {
    memoryV2Repository: {
      timeline: async (actor, input) => (calls.push(['timeline', actor, input]), [{ memory_id: 'm1', content: 'current' }]),
      archive: async (actor, id) => (calls.push(['archive', actor, id]), { memory_id: id, status: 'archived' }),
    },
    memoryV2Service: { forActor: actor => (calls.push(['actor', actor]), actorApi) },
  })
  const server = http.createServer(app)
  server.listen(0, '127.0.0.1')
  await once(server, 'listening')
  t.after(() => server.close())
  return { base: `http://127.0.0.1:${server.address().port}`, calls }
}

test('timeline is actor scoped and returns current revision projection', async t => {
  const { base, calls } = await harness(t)
  const response = await fetch(`${base}/v1/memory/gpt/timeline?limit=999&query=hello`)
  assert.equal(response.status, 200)
  assert.equal((await response.json()).items[0].content, 'current')
  assert.deepEqual(calls[0], ['timeline', 'gpt', { limit: 100, query: 'hello' }])
})

test('create and revise use fixed actor service and preserve revision semantics', async t => {
  const { base, calls } = await harness(t)
  await fetch(`${base}/v1/memory/claude`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ content: 'new', event_time: '2026-08-26T00:00:00Z', metadata: { tag: '日记' } }),
  })
  await fetch(`${base}/v1/memory/claude/m2/revise`, {
    method: 'POST', headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({ content: 'revised', reason: 'owner_edit' }),
  })
  assert.equal(calls.filter(call => call[0] === 'actor').every(call => call[1] === 'claude'), true)
  assert.equal(calls.find(call => call[0] === 'revise')[2].reason, 'owner_edit')
})

test('archive is actor scoped and V1-only actor names are rejected', async t => {
  const { base, calls } = await harness(t)
  const archived = await fetch(`${base}/v1/memory/claude/m1/archive`, { method: 'POST' })
  assert.equal(archived.status, 200)
  assert.deepEqual(calls.at(-1), ['archive', 'claude', 'm1'])
  assert.equal((await fetch(`${base}/v1/memory/brain/timeline`)).status, 404)
})
