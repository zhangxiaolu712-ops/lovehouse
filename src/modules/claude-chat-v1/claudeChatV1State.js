const THREAD_KEY = 'lovehouse_claude_v1_thread_id'
const WINDOW_KEY = 'lovehouse_claude_v1_window_id'
const HISTORY_KEY = 'lovehouse_claude_v1_recent_history'
const MAX_MESSAGES = 12
const MAX_CHARACTERS = 16_000

let volatileIdentity = null

function createId() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID()
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, value => {
    const random = Math.floor(Math.random() * 16)
    return (value === 'x' ? random : ((random & 0x3) | 0x8)).toString(16)
  })
}

export function getClaudeV1Identity(storage = globalThis.localStorage) {
  try {
    let threadId = storage?.getItem(THREAD_KEY)
    let windowId = storage?.getItem(WINDOW_KEY)
    if (!threadId) {
      threadId = createId()
      storage?.setItem(THREAD_KEY, threadId)
    }
    if (!windowId || windowId === threadId) {
      windowId = createId()
      storage?.setItem(WINDOW_KEY, windowId)
    }
    return { threadId, windowId }
  } catch {
    if (!volatileIdentity) volatileIdentity = { threadId: createId(), windowId: createId() }
    return { ...volatileIdentity }
  }
}

export function boundClaudeV1History(value) {
  if (!Array.isArray(value)) return []
  let characters = 0
  const result = []
  for (const item of value.slice(-MAX_MESSAGES).reverse()) {
    if (!item || !['user', 'assistant'].includes(item.role) || typeof item.content !== 'string') continue
    const content = item.content.trim().slice(-4_000)
    if (!content) continue
    const remaining = MAX_CHARACTERS - characters
    if (remaining <= 0) break
    result.push({ role: item.role, content: content.slice(-remaining) })
    characters += Math.min(content.length, remaining)
  }
  return result.reverse()
}

export function loadClaudeV1History(storage = globalThis.localStorage) {
  try {
    return boundClaudeV1History(JSON.parse(storage?.getItem(HISTORY_KEY) || '[]'))
  } catch {
    return []
  }
}

export function saveClaudeV1History(messages, storage = globalThis.localStorage) {
  const bounded = boundClaudeV1History(messages)
  try { storage?.setItem(HISTORY_KEY, JSON.stringify(bounded)) } catch {}
  return bounded
}

export const CLAUDE_V1_STORAGE_KEYS = Object.freeze({ THREAD_KEY, WINDOW_KEY, HISTORY_KEY })
