const WINDOW_KEY = 'lovehouse_codex_chat_window_id'
const HISTORY_KEY = 'lovehouse_codex_chat_recent_history'
const MAX_MESSAGES = 12
const MAX_CHARACTERS = 16_000
const MAX_MESSAGE_CHARACTERS = 4_000

let volatileWindowId = ''

function createWindowId() {
  if (globalThis.crypto?.randomUUID) return globalThis.crypto.randomUUID()
  return `codex-${Date.now().toString(36)}-${Math.random().toString(36).slice(2)}`
}

export function getCodexWindowId(storage = globalThis.sessionStorage) {
  try {
    const stored = storage?.getItem(WINDOW_KEY)
    if (stored) return stored
    const created = createWindowId()
    storage?.setItem(WINDOW_KEY, created)
    return created
  } catch {
    if (!volatileWindowId) volatileWindowId = createWindowId()
    return volatileWindowId
  }
}

export function boundCodexRecentHistory(value) {
  if (!Array.isArray(value)) return []
  let characters = 0
  const result = []
  for (const item of value.slice(-MAX_MESSAGES).reverse()) {
    if (!item || !['user', 'assistant'].includes(item.role) || typeof item.content !== 'string') continue
    const content = item.content.trim().slice(-MAX_MESSAGE_CHARACTERS)
    if (!content) continue
    const remaining = MAX_CHARACTERS - characters
    if (remaining <= 0) break
    result.push({ role: item.role, content: content.slice(-remaining) })
    characters += Math.min(content.length, remaining)
  }
  return result.reverse()
}

export function loadCodexRecentHistory(storage = globalThis.sessionStorage) {
  try {
    return boundCodexRecentHistory(JSON.parse(storage?.getItem(HISTORY_KEY) || '[]'))
  } catch {
    return []
  }
}

export function saveCodexRecentHistory(messages, storage = globalThis.sessionStorage) {
  const bounded = boundCodexRecentHistory(messages)
  try { storage?.setItem(HISTORY_KEY, JSON.stringify(bounded)) } catch {}
  return bounded
}
