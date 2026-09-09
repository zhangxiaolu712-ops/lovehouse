import assert from 'node:assert/strict'
import { once } from 'node:events'
import http from 'node:http'
import test from 'node:test'
import express from 'express'
import { installProjectChecklistApi, ProjectChecklistStore } from './projectChecklist.js'

test('checklist store delegates unchanged contract to Postgres repository', async () => {
  const calls = []
  const repository = {
    loadChecklist: async owner => (calls.push(['load', owner]), { items: [] }),
    saveChecklist: async (owner, item) => (calls.push(['save', owner, item]), item),
    deleteChecklist: async (owner, key) => (calls.push(['delete', owner, key]), true),
    migrateChecklistLocalV1: async (owner, items) => (calls.push(['migrate', owner, items]), { migrated: true }),
  }
  const store = new ProjectChecklistStore({ repository })
  await store.load('owner-1')
  await store.save('owner-1', { id: 'item' })
  await store.delete('owner-1', 'item')
  await store.migrateLocalV1('owner-1', [])
  assert.deepEqual(calls.map(call => call[0]), ['load', 'save', 'delete', 'migrate'])
})

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
