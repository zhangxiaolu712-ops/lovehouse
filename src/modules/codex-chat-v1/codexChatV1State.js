const THREAD_KEY = 'lovehouse_codex_v1_thread_id'
const WINDOW_KEY = 'lovehouse_codex_v1_window_id'
const HISTORY_KEY = 'lovehouse_codex_v1_recent_history'
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

export function getCodexV1Identity(storage = globalThis.localStorage) {
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

export function boundCodexV1History(value) {
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

export function loadCodexV1History(storage = globalThis.localStorage) {
  try {
    return boundCodexV1History(JSON.parse(storage?.getItem(HISTORY_KEY) || '[]'))
  } catch {
    return []
  }
}

export function saveCodexV1History(messages, storage = globalThis.localStorage) {
  const bounded = boundCodexV1History(messages)
  try { storage?.setItem(HISTORY_KEY, JSON.stringify(bounded)) } catch {}
  return bounded
}

function tokenDelta(current, previous) {
  if (!Number.isFinite(current) || !Number.isFinite(previous) || current < previous) return null
  return current - previous
}

export function deriveCurrentTurnUsage(value) {
  if (!value || typeof value !== 'object') return null
  const hasCumulative = Number.isFinite(value.cumulative_input_tokens)
    || Number.isFinite(value.cumulative_output_tokens)
  if (!hasCumulative) return { ...value }
  const actualInput = tokenDelta(
    value.cumulative_input_tokens,
    value.previous_cumulative_input_tokens,
  )
  const actualOutput = tokenDelta(
    value.cumulative_output_tokens,
    value.previous_cumulative_output_tokens,
  )
  const cachedInput = tokenDelta(
    value.cumulative_cached_input_tokens,
    value.previous_cumulative_cached_input_tokens,
  )
  const reasoningOutput = tokenDelta(
    value.cumulative_reasoning_output_tokens,
    value.previous_cumulative_reasoning_output_tokens,
  )
  return {
    ...value,
    actual_input_tokens: actualInput,
    cached_input_tokens: cachedInput,
    actual_output_tokens: actualOutput,
    reasoning_output_tokens: reasoningOutput,
    total_tokens: actualInput !== null && actualOutput !== null
      ? actualInput + actualOutput
      : null,
  }
}

export const CODEX_V1_STORAGE_KEYS = Object.freeze({ THREAD_KEY, WINDOW_KEY, HISTORY_KEY })
