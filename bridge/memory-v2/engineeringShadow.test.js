import assert from 'node:assert/strict'
import test from 'node:test'

import { EngineeringShadowMonitor, ShadowEngineeringRepository, ShadowProjectChecklistStore, firstDifference } from './engineeringShadow.js'

test('comparison tolerates float drift but keeps ids, order and non-floats strict', () => {
  assert.equal(firstDifference([{ id: 'a', relevance: 0.1 }], [{ id: 'a', relevance: 0.1 + 1e-13 }]), null)
  assert.equal(firstDifference([{ id: 'a' }, { id: 'b' }], [{ id: 'b' }, { id: 'a' }]).reason, 'value_mismatch')
  assert.equal(firstDifference({ count: 1 }, { count: 2 }).reason, 'integer_mismatch')
})

test('reads return primary immediately and record bounded shadow diagnostics', async () => {
  const logs = []
  const monitor = new EngineeringShadowMonitor({ logger: { warn: value => logs.push(value), error: value => logs.push(value) } })
  const repository = new ShadowEngineeringRepository({
    primary: { recallEngineering: async () => [{ id: 'primary', relevance: 0.2 }] },
    shadow: { recallEngineering: async () => [{ id: 'shadow', relevance: 0.2 }] },
    monitor,
  })
  assert.deepEqual(await repository.recallEngineering('owner', {}), [{ id: 'primary', relevance: 0.2 }])
  await monitor.drain()
  assert.equal(monitor.snapshot().mismatch_count, 1)
  assert.equal(logs.some(value => value.includes('"primary"') || value.includes('"shadow"')), false)
})

test('writes stay primary-only while checklist shadows load only', async () => {
  const calls = []
  const monitor = new EngineeringShadowMonitor({ logger: { warn() {}, error() {} } })
  const repository = new ShadowEngineeringRepository({
    primary: { upsertEngineering: async () => calls.push('primary-write') },
    shadow: { upsertEngineering: async () => calls.push('shadow-write') }, monitor,
  })
  await repository.upsertEngineering('owner', 'subject', 'content')
  const checklist = new ShadowProjectChecklistStore({
    primary: { load: async () => ({ items: [] }), save: async () => calls.push('primary-save') },
    shadow: { loadChecklist: async () => ({ items: [] }), saveChecklist: async () => calls.push('shadow-save') }, monitor,
  })
  await checklist.load('owner'); await checklist.save('owner', {})
  await monitor.drain()
  assert.deepEqual(calls, ['primary-write', 'primary-save'])
  assert.equal(monitor.snapshot().mismatch_count, 0)
})
