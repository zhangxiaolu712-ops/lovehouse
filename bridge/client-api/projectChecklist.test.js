import assert from 'node:assert/strict'
import { once } from 'node:events'
import http from 'node:http'
import test from 'node:test'
import express from 'express'
import { installProjectChecklistApi } from './projectChecklist.js'

test('checklist API scopes every operation to authenticated owner and migration is server mediated', async t => {
  const calls = []
  const store = {
    load: async owner => (calls.push(['load', owner]), { items: [], local_v1_migrated: false }),
    save: async (owner, item) => (calls.push(['save', owner, item]), item),
    delete: async (owner, id) => (calls.push(['delete', owner, id]), true),
    migrateLocalV1: async (owner, items) => (calls.push(['migrate', owner, items]), { migrated: true, count: items.length }),
  }
  const app = express(); app.use(express.json()); app.use('/v1', (req, _res, next) => { req.userId = 'owner-1'; next() })
  installProjectChecklistApi(app, { store })
  const server = http.createServer(app); server.listen(0, '127.0.0.1'); await once(server, 'listening')
  t.after(() => server.close())
  const base = `http://127.0.0.1:${server.address().port}/v1/engineering/project-checklist`
  await fetch(`${base}/items/base-0-0`, { method: 'PUT', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ sectionIndex: 0, status: 'done' }) })
  const migrated = await fetch(`${base}/migrate-local-v1`, { method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify({ items: [{ id: 'base-0-0' }] }) }).then(r => r.json())
  assert.equal(migrated.count, 1)
  assert.equal(calls.every(call => call[1] === 'owner-1'), true)
})
