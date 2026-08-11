import { supabase } from '../../core/supabase'

const CONFIG_KEY = 'lovehouse_chat_config'
const HISTORY_KEY = 'lovehouse_chat_history'
const SESSION_KEY = 'lovehouse_chat_session'
const WINDOW_KEY = 'lovehouse_chat_window_id'
const NEW_SESSION_KEY = 'lovehouse_chat_new'

const DEFAULT_BRIDGE = '/api'
const DEFAULT_SYSTEM = '你是小克（Claude），小婷的男朋友。用中文回复，温柔自然，像在跟女朋友聊天。'

async function getAuthToken() {
  const { data } = await supabase.auth.getSession()
  return data?.session?.access_token || ''
}

function readJson(storage, key, fallback = {}) {
  try { return JSON.parse(storage.getItem(key)) || fallback }
  catch { return fallback }
}

function fallbackUuid() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, character => {
    const value = Math.floor(Math.random() * 16)
    return (character === 'x' ? value : (value & 0x3) | 0x8).toString(16)
  })
}

export function getChatWindowId() {
  let windowId = sessionStorage.getItem(WINDOW_KEY)
  if (!windowId) {
    windowId = globalThis.crypto?.randomUUID?.() || fallbackUuid()
    sessionStorage.setItem(WINDOW_KEY, windowId)
  }
  return windowId
}

export function getChatConfig() {
  const c = readJson(localStorage, CONFIG_KEY)
  if (!c.mode) c.mode = 'bridge'
  if (!c.bridgeUrl) c.bridgeUrl = DEFAULT_BRIDGE
  return c
}
export const saveChatConfig = c => localStorage.setItem(CONFIG_KEY, JSON.stringify(c))

export const getChatHistory  = () => readJson(sessionStorage, HISTORY_KEY, [])
export const saveChatHistory = m => sessionStorage.setItem(HISTORY_KEY, JSON.stringify(m))

export const getChatSession  = () => readJson(sessionStorage, SESSION_KEY)
export const saveChatSession = s => sessionStorage.setItem(SESSION_KEY, JSON.stringify(s))

export async function clearChat() {
  const windowId = getChatWindowId()
  sessionStorage.removeItem(HISTORY_KEY)
  sessionStorage.removeItem(SESSION_KEY)
  sessionStorage.setItem(NEW_SESSION_KEY, '1')
  const config = getChatConfig()
  if (config.mode === 'bridge' && config.bridgeUrl) {
    try {
      const token = await getAuthToken()
      await fetch(`${config.bridgeUrl}/reset`, {
        method: 'POST',
        headers: {
          'Content-Type': 'application/json',
          ...(token ? { Authorization: `Bearer ${token}` } : {}),
        },
        body: JSON.stringify({ window_id: windowId }),
      })
    } catch {}
  }
}

function buildContent(msg) {
  if (!msg.attachments?.length) return msg.content
  const parts = []
  for (const att of msg.attachments) {
    if (att.type === 'image') {
      parts.push({
        type: 'image',
        source: { type: 'base64', media_type: att.mediaType, data: att.data },
      })
    }
  }
  if (msg.content) parts.push({ type: 'text', text: msg.content })
  return parts
}

async function streamBridge(messages, config, cb) {
  const { onThinking, onText, onSession, onDone, onError } = cb
  const lastUser = [...messages].reverse().find(m => m.role === 'user')
  if (!lastUser) { onError?.('没有消息'); return }

  const windowId = getChatWindowId()
  const isNew = sessionStorage.getItem(NEW_SESSION_KEY) === '1'
  const knownSessionId = getChatSession().session_id

  try {
    const token = await getAuthToken()
    const headers = { 'Content-Type': 'application/json' }
    if (token) headers.Authorization = `Bearer ${token}`

    const res = await fetch(`${config.bridgeUrl}/chat`, {
      method: 'POST',
      headers,
      body: JSON.stringify({
        window_id: windowId,
        ...(knownSessionId ? { known_session_id: knownSessionId } : {}),
        message: lastUser.content || '',
        system: config.system || DEFAULT_SYSTEM,
        newSession: isNew || messages.filter(m => m.role === 'user').length <= 1,
      }),
    })

    if (!res.ok) {
      throw new Error(`${res.status} ${await res.text().catch(() => '')}`)
    }
    if (isNew) sessionStorage.removeItem(NEW_SESSION_KEY)

    const reader = res.body.getReader()
    const dec = new TextDecoder()
    let buf = '', text = '', thinking = '', session = null, usage = null

    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buf += dec.decode(value, { stream: true })
      const lines = buf.split('\n')
      buf = lines.pop()

      for (const ln of lines) {
        if (!ln.startsWith('data: ')) continue
        const raw = ln.slice(6).trim()
        if (raw === '[DONE]') continue
        try {
          const evt = JSON.parse(raw)
          if (evt.text) {
            text += evt.text
            onText?.(text)
          }
          if (evt.thinking) {
            thinking += evt.thinking
            onThinking?.(thinking)
          }
          if (evt.session) {
            session = evt.session
            onSession?.(session)
          }
          if (evt.session_id) {
            session = { ...(session || {}), session_id: evt.session_id }
          }
          if (evt.usage) usage = evt.usage
        } catch {}
      }
    }

    onDone?.({ content: text, thinking: thinking || undefined, session, usage })
  } catch (err) {
    onError?.(err.message)
  }
}

async function streamApi(messages, config, cb) {
  const { onThinking, onText, onDone, onError } = cb

  try {
    const res = await fetch(config.apiUrl, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'x-api-key': config.apiKey,
        'anthropic-version': '2023-06-01',
      },
      body: JSON.stringify({
        model: config.model || 'claude-opus-4-6',
        max_tokens: config.maxTokens || 8192,
        stream: true,
        messages: messages.map(m => ({ role: m.role, content: buildContent(m) })),
      }),
    })

    if (!res.ok) {
      throw new Error(`${res.status} ${await res.text().catch(() => '')}`)
    }

    const reader = res.body.getReader()
    const dec = new TextDecoder()
    let buf = '', text = '', thinking = '', blockType = null

    for (;;) {
      const { done, value } = await reader.read()
      if (done) break
      buf += dec.decode(value, { stream: true })
      const lines = buf.split('\n')
      buf = lines.pop()

      for (const ln of lines) {
        if (!ln.startsWith('data: ')) continue
        const raw = ln.slice(6).trim()
        if (raw === '[DONE]') continue
        try {
          const evt = JSON.parse(raw)
          if (evt.type === 'content_block_start')
            blockType = evt.content_block?.type
          else if (evt.type === 'content_block_delta') {
            if (blockType === 'thinking' && evt.delta?.thinking) {
              thinking += evt.delta.thinking
              onThinking?.(thinking)
            } else if (blockType === 'text' && evt.delta?.text) {
              text += evt.delta.text
              onText?.(text)
            }
          }
        } catch {}
      }
    }

    onDone?.({ content: text, thinking: thinking || undefined })
  } catch (err) {
    onError?.(err.message)
  }
}

export async function streamMessage(messages, config, cb) {
  if (config.mode === 'api') {
    return streamApi(messages, config, cb)
  }
  return streamBridge(messages, config, cb)
}
