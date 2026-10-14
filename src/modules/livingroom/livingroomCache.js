const DB_NAME = 'lovehouse-livingroom-cache'
const DB_VERSION = 1
const STORE_NAME = 'owner-message-cache'

export const LIVINGROOM_CACHE_LIMIT = 200

function messageIdentity(message) {
  if (message?.message_id) return `message:${message.message_id}`
  if (message?.id !== undefined && message?.id !== null) return `legacy:${message.id}`
  return null
}

function compareMessages(left, right) {
  const timestamp = String(left.created_at || '').localeCompare(String(right.created_at || ''))
  if (timestamp !== 0) return timestamp
  return Number(left.id || 0) - Number(right.id || 0)
}

export function mergeLivingroomMessages(...collections) {
  const merged = []
  const byMessageId = new Map()
  const byLegacyId = new Map()

  for (const message of collections.flat()) {
    const identity = messageIdentity(message)
    if (!identity) continue

    const messageIdIndex = message.message_id ? byMessageId.get(String(message.message_id)) : undefined
    const legacyIdIndex = message.id !== undefined && message.id !== null
      ? byLegacyId.get(String(message.id))
      : undefined
    const existingIndex = messageIdIndex ?? legacyIdIndex

    if (existingIndex !== undefined) {
      merged[existingIndex] = { ...merged[existingIndex], ...message }
      if (message.message_id) byMessageId.set(String(message.message_id), existingIndex)
      if (message.id !== undefined && message.id !== null) byLegacyId.set(String(message.id), existingIndex)
      continue
    }

    const index = merged.length
    merged.push({ ...message })
    if (message.message_id) byMessageId.set(String(message.message_id), index)
    if (message.id !== undefined && message.id !== null) byLegacyId.set(String(message.id), index)
  }

  return merged.sort(compareMessages).slice(-LIVINGROOM_CACHE_LIMIT)
}

function requestResult(request) {
  return new Promise((resolve, reject) => {
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error || new Error('IndexedDB request failed'))
  })
}

function openDatabase(indexedDBImpl) {
  if (!indexedDBImpl?.open) return Promise.reject(new Error('IndexedDB is unavailable'))

  return new Promise((resolve, reject) => {
    const request = indexedDBImpl.open(DB_NAME, DB_VERSION)
    request.onupgradeneeded = () => {
      if (!request.result.objectStoreNames.contains(STORE_NAME)) {
        request.result.createObjectStore(STORE_NAME, { keyPath: 'owner_id' })
      }
    }
    request.onsuccess = () => resolve(request.result)
    request.onerror = () => reject(request.error || new Error('LivingRoom cache could not open'))
  })
}

function ownerCacheRecord(ownerId, messages) {
  return {
    owner_id: ownerId,
    messages: mergeLivingroomMessages(messages),
    updated_at: new Date().toISOString(),
  }
}

export async function readLivingroomCache(ownerId, { indexedDBImpl = globalThis.indexedDB } = {}) {
  if (!ownerId) throw new Error('Owner identity is required for LivingRoom cache')
  const database = await openDatabase(indexedDBImpl)
  try {
    const transaction = database.transaction(STORE_NAME, 'readonly')
    const record = await requestResult(transaction.objectStore(STORE_NAME).get(ownerId))
    if (!record) return []
    if (!Array.isArray(record.messages)) throw new Error('LivingRoom cache is invalid')
    return mergeLivingroomMessages(record.messages)
  } finally {
    database.close()
  }
}

export async function writeLivingroomCache(ownerId, messages, { indexedDBImpl = globalThis.indexedDB } = {}) {
  if (!ownerId) throw new Error('Owner identity is required for LivingRoom cache')
  const database = await openDatabase(indexedDBImpl)
  try {
    const transaction = database.transaction(STORE_NAME, 'readwrite')
    await requestResult(transaction.objectStore(STORE_NAME).put(ownerCacheRecord(ownerId, messages)))
  } finally {
    database.close()
  }
}
