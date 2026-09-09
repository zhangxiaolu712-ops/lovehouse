import assert from 'node:assert/strict'
import test from 'node:test'

import {
  createLivingroomRest,
  createPostgresLivingroomReadCutover,
  isLivingroomRest,
} from './livingroom.js'

test('privileged livingroom REST allows only livingroom reads and inserts', async () => {
  const calls = []
  const livingroomRest = createLivingroomRest({
    rest: async (method, ...args) => {
      const call = [method, ...args]
      calls.push(call)
      return method === 'POST' ? [{ id: 1, sender: 'GPT', message: 'hello' }] : []
    },
  })

  await livingroomRest('GET', 'livingroom?order=created_at.desc&limit=20')
  await livingroomRest('POST', 'livingroom', { sender: 'GPT', message: 'hello' })
  assert.deepEqual(calls, [
    ['GET', 'livingroom?order=created_at.desc&limit=20', undefined],
    ['POST', 'livingroom', { sender: 'GPT', message: 'hello' }],
  ])
})

test('privileged livingroom REST rejects other P0 tables and mutation methods before fetch', async () => {
  let calls = 0
  const livingroomRest = createLivingroomRest({ rest: async () => { calls += 1; return [] } })
  for (const [method, path] of [
    ['GET', 'active_threads?limit=1'], ['POST', 'memory_candidates'],
    ['GET', 'livingroom/../dream_runs'], ['PATCH', 'livingroom'], ['DELETE', 'livingroom'],
  ]) {
    await assert.rejects(livingroomRest(method, path), error => error.code === 'LIVINGROOM_SCOPE_VIOLATION')
  }
  assert.equal(calls, 0)
})

test('livingroom fence turns upstream error objects into explicit failures', async () => {
  const livingroomRest = createLivingroomRest({
    rest: async () => ({ status: 401, error: { code: 'PGRST_AUTH', message: 'unauthorized' } }),
  })
  await assert.rejects(
    livingroomRest('GET', 'livingroom?limit=20'),
    error => error.code === 'LIVINGROOM_UPSTREAM_ERROR'
      && error.status === 401
      && error.upstreamCode === 'PGRST_AUTH'
      && /unauthorized/.test(error.message),
  )
})

test('livingroom fence rejects unconfirmed writes instead of inventing success', async () => {
  const livingroomRest = createLivingroomRest({ rest: async () => [] })
  await assert.rejects(
    livingroomRest('POST', 'livingroom', { sender: 'GPT', message: 'hello' }),
    error => error.code === 'LIVINGROOM_WRITE_NOT_CONFIRMED',
  )
})

test('PostgreSQL livingroom cutover reads from PostgreSQL and preserves Supabase writes', async () => {
  const legacyCalls = []
  const legacy = createLivingroomRest({
    rest: async (...args) => {
      legacyCalls.push(args)
      return [{ id: 8, sender: 'GPT', message: 'written', created_at: 'source-time' }]
    },
  })
  const poolCalls = []
  const pool = {
    async query(text, values) {
      poolCalls.push({ text, values })
      return { rows: [{ result: [{ id: 7, sender: '小婷', message: 'read', created_at: '2026-09-09T00:00:00.123456+00:00' }] }] }
    },
  }
  const adapter = createPostgresLivingroomReadCutover({ legacy, pool })

  const rows = await adapter(
    'GET',
    'livingroom?order=created_at.desc&limit=20&created_at=gt.2026-09-09T00%3A00%3A00.123456%2B00%3A00',
  )
  const written = await adapter('POST', 'livingroom', { sender: 'GPT', message: 'written' })

  assert.equal(isLivingroomRest(adapter), true)
  assert.deepEqual(rows.map(row => row.id), [7])
  assert.match(poolCalls[0].text, /created_at > \$1::timestamptz/)
  assert.deepEqual(poolCalls[0].values, ['2026-09-09T00:00:00.123456+00:00', 20])
  assert.deepEqual(legacyCalls, [['POST', 'livingroom', { sender: 'GPT', message: 'written' }]])
  assert.equal(written[0].id, 8)
})

test('PostgreSQL livingroom cutover rejects queries outside the existing read contract', async () => {
  const adapter = createPostgresLivingroomReadCutover({
    legacy: createLivingroomRest({ rest: async () => [] }),
    pool: { query: async () => ({ rows: [{ result: [] }] }) },
  })

  await assert.rejects(adapter('DELETE', 'livingroom'), /restricted to the livingroom table/)
  await assert.rejects(adapter('GET', 'livingroom?order=id.desc&limit=20'), /restricted/)
  await assert.rejects(adapter('GET', 'livingroom?order=created_at.desc&limit=20&sender=eq.GPT'), /restricted/)
})
