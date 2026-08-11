import test from 'node:test'
import assert from 'node:assert/strict'

import { createLivingroomRest } from './livingroom.js'

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
  const livingroomRest = createLivingroomRest({
    rest: async () => {
      calls += 1
      return []
    },
  })

  for (const [method, path] of [
    ['GET', 'active_threads?limit=1'],
    ['POST', 'memory_candidates'],
    ['GET', 'livingroom/../dream_runs'],
    ['PATCH', 'livingroom'],
    ['DELETE', 'livingroom'],
  ]) {
    await assert.rejects(
      livingroomRest(method, path),
      error => error.code === 'LIVINGROOM_SCOPE_VIOLATION'
    )
  }

  assert.equal(calls, 0)
})

test('livingroom fence turns upstream error objects into explicit failures', async () => {
  const livingroomRest = createLivingroomRest({
    rest: async () => ({
      status: 401,
      error: { code: 'PGRST_AUTH', message: 'unauthorized' },
    }),
  })

  await assert.rejects(
    livingroomRest('GET', 'livingroom?limit=20'),
    error => error.code === 'LIVINGROOM_UPSTREAM_ERROR'
      && error.status === 401
      && error.upstreamCode === 'PGRST_AUTH'
      && /unauthorized/.test(error.message)
  )
})

test('livingroom fence rejects unconfirmed writes instead of inventing success', async () => {
  const livingroomRest = createLivingroomRest({ rest: async () => [] })

  await assert.rejects(
    livingroomRest('POST', 'livingroom', { sender: 'GPT', message: 'hello' }),
    error => error.code === 'LIVINGROOM_WRITE_NOT_CONFIRMED'
  )
})
