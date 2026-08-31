import test from 'node:test'
import assert from 'node:assert/strict'
import fs from 'node:fs'
import os from 'node:os'
import path from 'node:path'
import { FileTransientThreadStore, TransientThreadStore } from './transientStore.js'

test('private thread events expire after the configured transient TTL', () => {
  let now = 1_000
  const store = new TransientThreadStore({ ttlMs: 200, now: () => now })
  store.append('thread', { type: 'status', status: 'queued' })
  assert.equal(store.read('thread').length, 1)
  now = 1_201
  assert.deepEqual(store.read('thread'), [])
})

test('status progress replaces the same task notification instead of accumulating events', () => {
  const store = new TransientThreadStore()
  const sameTask = item => item.type === 'task_notification' && item.task_id === 'task'
  store.upsert('thread', sameTask, { type: 'task_notification', task_id: 'task', status: 'queued' })
  store.upsert('thread', sameTask, { type: 'task_notification', task_id: 'task', status: 'running' })
  store.upsert('thread', sameTask, { type: 'task_notification', task_id: 'task', status: 'running', body: '正在运行测试' })
  assert.deepEqual(store.read('thread').map(item => item.status), ['running'])
})

test('upserted notification survives file-store restart as one event', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'livingroom-notification-'))
  const filePath = path.join(directory, 'threads.json')
  const match = item => item.type === 'task_notification' && item.task_id === 'task'
  const first = new FileTransientThreadStore({ filePath, cleanupIntervalMs: 0 })
  first.upsert('thread', match, { type: 'task_notification', task_id: 'task', status: 'queued' })
  first.upsert('thread', match, { type: 'task_notification', task_id: 'task', status: 'running' })
  const restarted = new FileTransientThreadStore({ filePath, cleanupIntervalMs: 0 })
  assert.deepEqual(restarted.read('thread').map(item => item.status), ['running'])
  first.close(); restarted.close(); fs.rmSync(directory, { recursive: true })
})

test('file transient store survives restart, writes atomically with private permissions, and expires', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'livingroom-transient-'))
  const filePath = path.join(directory, 'threads.json')
  let now = 1_000
  const first = new FileTransientThreadStore({ filePath, ttlMs: 200, now: () => now, cleanupIntervalMs: 0 })
  first.append('thread', { type: 'result', content: 'temporary detail' })
  assert.equal(fs.statSync(filePath).mode & 0o777, 0o600)
  assert.equal(fs.statSync(directory).mode & 0o777, 0o700)
  assert.equal(fs.readdirSync(directory).some(name => name.endsWith('.tmp')), false)
  const restarted = new FileTransientThreadStore({ filePath, ttlMs: 200, now: () => now, cleanupIntervalMs: 0 })
  assert.equal(restarted.read('thread')[0].content, 'temporary detail')
  now = 1_201
  restarted.cleanupExpired()
  assert.deepEqual(restarted.read('thread'), [])
  assert.deepEqual(JSON.parse(fs.readFileSync(filePath, 'utf8')).threads, {})
  first.close()
  restarted.close()
  fs.rmSync(directory, { recursive: true })
})

test('two stale store instances merge under the file lock instead of losing another thread', () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'livingroom-transient-race-'))
  const filePath = path.join(directory, 'threads.json')
  const first = new FileTransientThreadStore({ filePath, cleanupIntervalMs: 0 })
  const second = new FileTransientThreadStore({ filePath, cleanupIntervalMs: 0 })
  first.append('thread-a', { type: 'status', status: 'running' })
  second.append('thread-b', { type: 'status', status: 'running' })
  assert.equal(first.read('thread-a').length, 1)
  assert.equal(first.read('thread-b').length, 1)
  first.close()
  second.close()
  fs.rmSync(directory, { recursive: true })
})

test('cleanup timer removes expired records without a read request', async () => {
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'livingroom-transient-cleanup-'))
  const filePath = path.join(directory, 'threads.json')
  let now = 1_000
  const store = new FileTransientThreadStore({
    filePath, ttlMs: 20, cleanupIntervalMs: 5, now: () => now,
  })
  store.append('thread', { type: 'status', status: 'running' })
  now = 1_021
  await new Promise(resolve => setTimeout(resolve, 30))
  assert.deepEqual(JSON.parse(fs.readFileSync(filePath, 'utf8')).threads, {})
  store.close()
  fs.rmSync(directory, { recursive: true })
})

test('thread TTL deletion does not affect independently durable task summary', () => {
  const durableTask = { id: 'task', thread_id: 'thread', status: 'completed', final_result_summary: 'done' }
  let now = 1_000
  const store = new TransientThreadStore({ ttlMs: 200, now: () => now })
  store.append('thread', { type: 'result', content: 'full temporary result' })
  now = 1_201
  assert.deepEqual(store.read('thread'), [])
  assert.deepEqual(durableTask, { id: 'task', thread_id: 'thread', status: 'completed', final_result_summary: 'done' })
})
