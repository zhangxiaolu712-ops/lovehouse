import assert from 'node:assert/strict'
import test from 'node:test'

import { categoryDetails, groupEngineeringItems } from './engineeringCategories.js'
import { listEngineeringFacts, saveEngineeringFact } from './engineeringService.js'

const dependencies = body => ({
  getAccessToken: async () => 'owner-token',
  fetchImpl: async (url, options) => ({ ok: true, status: 200, json: async () => body(url, options) }),
})

test('unknown and incomplete classifications remain visible', () => {
  const groups = groupEngineeringItems([
    { subject_key: 'one', metadata: { category: 'future-module', component: 'worker' } },
    { subject_key: 'two', metadata: {} },
  ])
  assert.equal(categoryDetails('future-module').label, 'future-module')
  assert.deepEqual(groups.map(group => group.key), ['future-module', 'uncategorized'])
  assert.equal(groups[1].components[0].key, '未指定组件')
})

test('list uses Owner bearer Bridge API and supports archived records', async () => {
  const calls = []
  const items = await listEngineeringFacts({ query: 'runtime', includeArchived: true }, dependencies((url, options) => {
    calls.push([url, options])
    return { ok: true, items: [{ subject_key: 'runtime.codex' }] }
  }))
  assert.equal(items[0].subject_key, 'runtime.codex')
  assert.match(calls[0][0], /include_archived=true/)
  assert.equal(calls[0][1].headers.Authorization, 'Bearer owner-token')
})

test('save forwards classification metadata without requiring it', async () => {
  let sent
  await saveEngineeringFact({ subject_key: 'runtime.codex', content: 'works' }, dependencies((_url, options) => {
    sent = JSON.parse(options.body)
    return { ok: true, action: 'created' }
  }))
  assert.deepEqual(sent, { subject_key: 'runtime.codex', content: 'works' })
})
