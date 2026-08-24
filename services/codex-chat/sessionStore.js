import { ChatRuntimeError } from './errors.js'

export function boundedHistory(value, { maxMessages = 12, maxChars = 16_000 } = {}) {
  if (value == null) return []
  if (!Array.isArray(value)) {
    throw new ChatRuntimeError('SESSION_RECOVERY_FAILED', 'recent_history must be an array', {
      stage: 'session', status: 400,
    })
  }
  let chars = 0
  const result = []
  for (const item of value.slice(-maxMessages).reverse()) {
    if (!item || !['user', 'assistant'].includes(item.role) || typeof item.content !== 'string') continue
    const content = item.content.trim().slice(-4_000)
    if (!content) continue
    const remaining = maxChars - chars
    if (remaining <= 0) break
    result.push({ role: item.role, content: content.slice(-remaining) })
    chars += Math.min(content.length, remaining)
  }
  return result.reverse()
}

export class SessionStore {
  #sessions = new Map()
  #busy = new Set()

  resolve({ ownerUserId, threadId, runtimeSessionId, recentHistory }) {
    const key = `${ownerUserId}:${threadId}`
    const current = this.#sessions.get(key)
    if (current && runtimeSessionId && current.runtimeSessionId !== runtimeSessionId) {
      throw new ChatRuntimeError('SESSION_RECOVERY_FAILED', 'Runtime binding changed during an active process', {
        stage: 'session', status: 409, retryable: true,
      })
    }
    if (current) return { key, ...current, resumed: true }
    const session = {
      runtimeSessionId: runtimeSessionId || null,
      history: boundedHistory(recentHistory),
    }
    this.#sessions.set(key, session)
    return { key, ...session, resumed: Boolean(runtimeSessionId) }
  }

  bind(key, runtimeSessionId) {
    const session = this.#sessions.get(key)
    if (session) session.runtimeSessionId = runtimeSessionId
  }

  acquire(key) {
    if (this.#busy.has(key)) {
      throw new ChatRuntimeError('RUNTIME_UNAVAILABLE', 'Thread already has an active request', {
        stage: 'runtime', status: 409, retryable: true,
      })
    }
    this.#busy.add(key)
  }

  complete(key, message, response) {
    const session = this.#sessions.get(key)
    if (!session) return
    session.history = boundedHistory([
      ...session.history,
      { role: 'user', content: message },
      { role: 'assistant', content: response },
    ])
  }

  release(key) {
    this.#busy.delete(key)
  }
}
