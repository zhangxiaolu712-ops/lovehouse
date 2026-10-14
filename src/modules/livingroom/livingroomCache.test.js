import assert from 'node:assert/strict'
import test from 'node:test'

import {
  LIVINGROOM_CACHE_LIMIT,
  mergeLivingroomMessages,
  readLivingroomCache,
  writeLivingroomCache,
} from './livingroomCache.js'

function fakeIndexedDB() {
  const records = new Map()
  const stores = new Set()
  const database = {
    objectStoreNames: { contains: name => stores.has(name) },
    createObjectStore(name) {
      stores.add(name)
    },
    transaction() {
      return {
        objectStore() {
          return {
            get(key) {
              const request = {}
              queueMicrotask(() => {
                request.result = records.get(key)
                request.onsuccess?.()
              })
              return request
            },
            put(record) {
              const request = {}
              queueMicrotask(() => {
                records.set(record.owner_id, structuredClone(record))
                request.result = record.owner_id
                request.onsuccess?.()
              })
              return request
            },
          }
        },
      }
    },
    close() {},
  }

  return {
    open() {
      const request = { result: database }
      queueMicrotask(() => {
        request.onupgradeneeded?.()
        request.onsuccess?.()
      })
      return request
    },
  }
}

function message(id, createdAt, messageId = null) {
  return {
    id,
    sender: 'GPT',
    message: `message ${id}`,
    created_at: createdAt,
    message_id: messageId,
    thread_id: '01a137fb-26c1-74e3-8d17-e011e70c5c5b',
  }
}

test('merges cached and incremental rows oldest-to-newest without duplicating stable or legacy identities', () => {
  const cached = [
    message(1, '2026-10-14T00:00:01.000Z'),
    message(2, '2026-10-14T00:00:02.000Z', '01a13815-d519-7614-88f0-bf2736b30ccb'),
  ]
  const incremental = [
    message(2, '2026-10-14T00:00:02.000Z', '01a13815-d519-7614-88f0-bf2736b30ccb'),
    message(3, '2026-10-14T00:00:03.000Z', '01a1382a-9595-79b9-8d74-17e3bf2b2239'),
  ]

  assert.deepEqual(mergeLivingroomMessages(cached, incremental).map(item => item.id), [1, 2, 3])
})

test('a canonical row replaces its cached legacy form when the numeric id is the same', () => {
  const rows = mergeLivingroomMessages(
    [message(7, '2026-10-14T00:00:07.000Z')],
    [message(7, '2026-10-14T00:00:07.000Z', '01a1382a-9595-79b9-8d74-17e3bf2b2239')],
  )

  assert.equal(rows.length, 1)
  assert.equal(rows[0].message_id, '01a1382a-9595-79b9-8d74-17e3bf2b2239')
})

test('cache is bounded to the newest 200 messages', () => {
  const rows = Array.from({ length: LIVINGROOM_CACHE_LIMIT + 5 }, (_, index) => (
    message(index + 1, `2026-10-14T00:${String(Math.floor(index / 60)).padStart(2, '0')}:${String(index % 60).padStart(2, '0')}.000Z`)
  ))
  const bounded = mergeLivingroomMessages(rows)

  assert.equal(bounded.length, LIVINGROOM_CACHE_LIMIT)
  assert.equal(bounded[0].id, 6)
  assert.equal(bounded.at(-1).id, 205)
})

test('IndexedDB cache is isolated by Owner identity and never stores an access token', async () => {
  const indexedDBImpl = fakeIndexedDB()
  await writeLivingroomCache('owner-a', [message(1, '2026-10-14T00:00:01.000Z')], { indexedDBImpl })
  await writeLivingroomCache('owner-b', [message(2, '2026-10-14T00:00:02.000Z')], { indexedDBImpl })

  const ownerA = await readLivingroomCache('owner-a', { indexedDBImpl })
  const ownerB = await readLivingroomCache('owner-b', { indexedDBImpl })

  assert.deepEqual(ownerA.map(item => item.id), [1])
  assert.deepEqual(ownerB.map(item => item.id), [2])
  assert.equal(JSON.stringify([ownerA, ownerB]).includes('access_token'), false)
})

test('missing or damaged cache is explicit so the caller can perform a bounded bootstrap', async () => {
  await assert.rejects(readLivingroomCache('owner', { indexedDBImpl: null }), /IndexedDB is unavailable/)
})
