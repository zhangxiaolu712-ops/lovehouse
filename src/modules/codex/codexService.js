import { supabase } from '../../core/supabase'

const HISTORY_KEY = 'lovehouse_codex_history'
const SESSION_KEY = 'lovehouse_codex_session'
const WINDOW_KEY = 'lovehouse_codex_window_id'

function readJson(key, fallback) {
  try { return JSON.parse(localStorage.getItem(key)) || fallback }
  catch { return fallback }
}

async function getAuthToken() {
  const { data } = await supabase.auth.getSession()
  return data?.session?.access_token || ''
}

export function getCodexHistory() { return readJson(HISTORY_KEY, []) }
export function saveCodexHistory(messages) { localStorage.setItem(HISTORY_KEY, JSON.stringify(messages)) }
export function getCodexSession() { return readJson(SESSION_KEY, {}) }
export function saveCodexSession(session) { localStorage.setItem(SESSION_KEY, JSON.stringify(session)) }
export function clearCodexChat() {
  localStorage.removeItem(HISTORY_KEY)
  localStorage.removeItem(SESSION_KEY)
  localStorage.removeItem(WINDOW_KEY)
}

export function getCodexWindowId() {
  let id = localStorage.getItem(WINDOW_KEY)
  if (!id) {
    id = `codex-${crypto.randomUUID()}`
    localStorage.setItem(WINDOW_KEY, id)
  }
  return id
}

export async function streamCodexMessage(messages, callbacks = {}) {
  const token = await getAuthToken()
  if (!token) throw new Error('需要先登录')

  const lastUser = [...messages].reverse().find(message => message.role === 'user')
  if (!lastUser?.content?.trim()) throw new Error('没有消息')

  const session = getCodexSession()
  const recent = messages.slice(-12).map(message => ({ role: message.role, content: message.content || '' }))
  const response = await fetch('/api/codex/chat', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify({
      message: lastUser.content.trim(),
      window_id: getCodexWindowId(),
      known_session_id: session.sessionId || undefined,
      recent_history: recent,
    }),
  })

  if (!response.ok) {
    let message = `${response.status} ${response.statusText}`
    try {
      const payload = await response.json()
      if (payload?.error?.message) message = payload.error.message
    } catch {}
    throw new Error(message)
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  let buffer = ''
  let currentEvent = 'message'
  let fullText = ''
  let thinking = ''
  let sessionId = session.sessionId || null

  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    const lines = buffer.split('\n')
    buffer = lines.pop()

    for (const line of lines) {
      if (line.startsWith('event:')) {
        currentEvent = line.slice(6).trim()
        continue
      }
      if (!line.startsWith('data:')) continue
      const raw = line.slice(5).trim()
      if (!raw) continue
      const payload = JSON.parse(raw)
      if (currentEvent === 'session' && payload.session_id) {
        sessionId = payload.session_id
        saveCodexSession({ sessionId, updatedAt: Date.now() })
      } else if (currentEvent === 'text' && payload.text) {
        fullText += payload.text
        callbacks.onText?.(fullText)
      } else if (currentEvent === 'thinking' && payload.thinking) {
        thinking += payload.thinking
        callbacks.onThinking?.(thinking)
      } else if (currentEvent === 'error') {
        throw new Error(payload.message || 'Codex 请求失败')
      }
      currentEvent = 'message'
    }
  }

  callbacks.onDone?.({ content: fullText, thinking: thinking || undefined, sessionId })
  return { content: fullText, thinking: thinking || undefined, sessionId }
}
