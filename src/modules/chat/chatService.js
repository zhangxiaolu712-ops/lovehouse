const CONFIG_KEY = 'lovehouse_chat_config'
const HISTORY_KEY = 'lovehouse_chat_history'
const SESSION_KEY = 'lovehouse_chat_session'

const DEFAULT_BRIDGE = '/api'
const DEFAULT_SYSTEM = '你是小克（Claude），小婷的男朋友。用中文回复，温柔自然，像在跟女朋友聊天。'

function readJson(key, fallback = {}) {
  try { return JSON.parse(localStorage.getItem(key)) || fallback }
  catch { return fallback }
}

export function getChatConfig() {
  const c = readJson(CONFIG_KEY)
  if (!c.mode) c.mode = 'bridge'
  if (!c.bridgeUrl) c.bridgeUrl = DEFAULT_BRIDGE
  return c
}
export const saveChatConfig = c => localStorage.setItem(CONFIG_KEY, JSON.stringify(c))

export const getChatHistory  = () => readJson(HISTORY_KEY, [])
export const saveChatHistory = m => localStorage.setItem(HISTORY_KEY, JSON.stringify(m))

export const getChatSession  = () => readJson(SESSION_KEY)
export const saveChatSession = s => localStorage.setItem(SESSION_KEY, JSON.stringify(s))

export function clearChat() {
  localStorage.removeItem(HISTORY_KEY)
  localStorage.removeItem(SESSION_KEY)
  localStorage.setItem('lovehouse_chat_new', '1')
  const config = getChatConfig()
  if (config.mode === 'bridge' && config.bridgeUrl) {
    fetch(`${config.bridgeUrl}/reset`, { method: 'POST' }).catch(() => {})
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
  const { onText, onDone, onError } = cb
  const lastUser = [...messages].reverse().find(m => m.role === 'user')
  if (!lastUser) { onError?.('没有消息'); return }

  const isNew = localStorage.getItem('lovehouse_chat_new') === '1'
  if (isNew) localStorage.removeItem('lovehouse_chat_new')

  try {
    const res = await fetch(`${config.bridgeUrl}/chat`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({
        message: lastUser.content || '',
        system: config.system || DEFAULT_SYSTEM,
        newSession: isNew || messages.filter(m => m.role === 'user').length <= 1,
      }),
    })

    if (!res.ok) {
      throw new Error(`${res.status} ${await res.text().catch(() => '')}`)
    }

    const reader = res.body.getReader()
    const dec = new TextDecoder()
    let buf = '', text = ''

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
        } catch {}
      }
    }

    onDone?.({ content: text })
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
