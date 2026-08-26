import assert from 'node:assert/strict'
import test from 'node:test'
import { addProjectChecklistItem, loadProjectChecklist, saveProjectChecklistItem } from './projectChecklistService.js'

function response(payload) { return { ok: true, status: 200, json: async () => payload } }

test('server is canonical and local v1 imports once before read-back', async () => {
  const previousWindow = globalThis.window
  globalThis.window = { localStorage: { getItem: () => JSON.stringify({ overrides: { 'base-0-0': { status: 'partial', note: 'local' } }, custom: [] }), removeItem() {} } }
  let loaded = 0; const calls = []
  const dependencies = { getAccessToken: async () => 'token', fetchImpl: async (url, options) => {
    calls.push({ url, options })
    if (url.endsWith('migrate-local-v1')) return response({ ok: true, migrated: true, count: 1 })
    loaded += 1
    return response(loaded === 1 ? { ok: true, local_v1_migrated: false, items: [] } : { ok: true, local_v1_migrated: true, items: [{ id: 'base-0-0', sectionIndex: 0, status: 'partial', note: 'local', completedAt: '', custom: false }] })
  } }
  try {
    const sections = await loadProjectChecklist(dependencies)
    assert.equal(sections[0].items[0].status, 'partial')
    assert.equal(calls.filter(call => call.url.endsWith('migrate-local-v1')).length, 1)
  } finally { globalThis.window = previousWindow }
})

test('writes and custom additions go to authenticated Engineering endpoint', async () => {
  const calls = []; const dependencies = { getAccessToken: async () => 'owner-token', fetchImpl: async (url, options) => { calls.push({ url, options }); return response({ ok: true }) } }
  await saveProjectChecklistItem({ id: 'base-0-0', sectionIndex: 0, status: 'done' }, dependencies)
  await addProjectChecklistItem(1, 'new item', dependencies)
  assert.equal(calls.every(call => call.options.headers.Authorization === 'Bearer owner-token'), true)
  assert.equal(calls.every(call => call.options.method === 'PUT'), true)
})
