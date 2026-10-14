import assert from 'node:assert/strict'
import test from 'node:test'

import {
  createLivingroomRest,
  createPostgresLivingroomReadCutover,
  createPostgresLivingroomWriteCutover,
  isLivingroomRest,
  preparePostgresLivingroomIdentity,
} from './livingroom.js'
import { isUuidV7 } from './messageIdentity.js'

const WORK_AREA_THREAD_ID = '0199db1d-6c00-7000-8000-000000000000'
const NEW_MESSAGE_ID = '0199db1d-6c00-7001-8001-000000000001'

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

test('PostgreSQL livingroom write cutover inserts through SQL and preserves selected reads', async () => {
  const legacyCalls = []
  const legacy = createLivingroomRest({
    rest: async (...args) => {
      legacyCalls.push(args)
      return []
    },
  })
  const poolCalls = []
  const pool = {
    async query(text, values) {
      poolCalls.push({ text, values })
      return { rows: [{ result: {
        id: 9,
        sender: 'GPT',
        message: 'written',
        created_at: '2026-09-12T01:02:03.123456+00:00',
      } }] }
    },
  }
  const adapter = createPostgresLivingroomWriteCutover({
    legacy, pool, identity: { workAreaThreadId: WORK_AREA_THREAD_ID },
  })

  const read = await adapter('GET', 'livingroom?order=created_at.desc&limit=20')
  const written = await adapter('POST', 'livingroom', { sender: 'GPT', message: 'written' })

  assert.equal(isLivingroomRest(adapter), true)
  assert.deepEqual(read, [])
  assert.deepEqual(legacyCalls, [['GET', 'livingroom?order=created_at.desc&limit=20', undefined]])
  assert.match(poolCalls[0].text, /insert into public\.livingroom/)
  assert.match(poolCalls[0].text, /returning id, message_id, thread_id, sender, message, created_at/)
  assert.equal(poolCalls[0].values[0], 'GPT')
  assert.equal(poolCalls[0].values[1], 'written')
  assert.equal(isUuidV7(poolCalls[0].values[2]), true)
  assert.equal(poolCalls[0].values[3], WORK_AREA_THREAD_ID)
  assert.equal(written[0].id, 9)
})

test('PostgreSQL livingroom write cutover rejects other mutations and unconfirmed inserts', async () => {
  const legacy = createLivingroomRest({ rest: async () => [] })
  const adapter = createPostgresLivingroomWriteCutover({
    legacy,
    pool: { query: async () => ({ rows: [] }) },
    identity: { workAreaThreadId: WORK_AREA_THREAD_ID },
  })

  await assert.rejects(adapter('PATCH', 'livingroom', {}), /restricted/)
  await assert.rejects(adapter('POST', 'other', {}), /restricted/)
  await assert.rejects(
    adapter('POST', 'livingroom', { sender: 'GPT', message: 'missing result' }),
    error => error.code === 'LIVINGROOM_WRITE_NOT_CONFIRMED',
  )
})

test('PostgreSQL livingroom write preserves a caller UUIDv7 and never replaces the numeric legacy id', async () => {
  const calls = []
  const adapter = createPostgresLivingroomWriteCutover({
    legacy: createLivingroomRest({ rest: async () => [] }),
    identity: { workAreaThreadId: WORK_AREA_THREAD_ID },
    pool: { async query(text, values) {
      calls.push({ text, values })
      return { rows: [{ result: {
        id: 214,
        message_id: values[2],
        thread_id: values[3],
        sender: values[0],
        message: values[1],
        created_at: '2026-10-13T00:00:00.000Z',
      } }] }
    } },
  })

  const [row] = await adapter('POST', 'livingroom', {
    sender: 'GPT', message: 'hello', message_id: NEW_MESSAGE_ID,
  })

  assert.equal(row.id, 214)
  assert.equal(row.message_id, NEW_MESSAGE_ID)
  assert.equal(row.thread_id, WORK_AREA_THREAD_ID)
  assert.deepEqual(calls[0].values, ['GPT', 'hello', NEW_MESSAGE_ID, WORK_AREA_THREAD_ID])
})

test('LivingRoom identity preparation persists one work-area thread and backfills only latest 30 idempotently', async () => {
  const rows = Array.from({ length: 31 }, (_, index) => ({
    id: 31 - index,
    message_id: index === 0 ? NEW_MESSAGE_ID : null,
    thread_id: index === 0 ? WORK_AREA_THREAD_ID : null,
  }))
  let storedThreadId = null
  const updates = new Map()
  const statements = []
  const client = {
    async query(text, values = []) {
      statements.push({ text, values })
      if (/insert into public\.livingroom_identity_scopes/i.test(text) && !storedThreadId) storedThreadId = values[0]
      if (/select thread_id::text/i.test(text)) return { rows: [{ thread_id: storedThreadId }] }
      if (/from public\.livingroom\s+order by/i.test(text)) return { rows: rows.slice(0, 30).map(row => ({ ...row })) }
      if (/update public\.livingroom/i.test(text)) {
        updates.set(values[2], { message_id: values[0], thread_id: values[1] })
      }
      return { rows: [] }
    },
    release() {},
  }
  const pool = { async connect() { return client } }
  let sequence = 2
  const createUuid = () => {
    if (sequence++ === 2) return WORK_AREA_THREAD_ID
    return `0199db1d-6c00-7${String(sequence).padStart(3, '0')}-8000-${String(sequence).padStart(12, '0')}`
  }

  const first = await preparePostgresLivingroomIdentity({ pool, createUuid })
  assert.equal(first.workAreaThreadId, WORK_AREA_THREAD_ID)
  assert.equal(first.recentCount, 30)
  assert.equal(first.backfilled, 29)
  assert.equal(updates.size, 29)
  assert.equal(updates.has(1), false)
  assert.equal(statements.some(call => /limit 30/i.test(call.text)), true)

  for (const [id, identity] of updates) {
    const row = rows.find(candidate => candidate.id === id)
    Object.assign(row, identity)
  }
  updates.clear()
  const second = await preparePostgresLivingroomIdentity({ pool, createUuid })
  assert.equal(second.workAreaThreadId, first.workAreaThreadId)
  assert.equal(second.backfilled, 0)
  assert.equal(updates.size, 0)
  assert.equal(rows[30].message_id, null)
  assert.equal(rows[30].thread_id, null)
})
