const DEFAULT_ENDPOINT = '/api/codex/chat'

function clientError(type, code, message, retryable = false) {
  const error = new Error(message)
  error.detail = { type, code, message, retryable }
  return error
}

async function getSupabaseAccessToken() {
  const { supabase } = await import('../../core/supabase')
  const { data, error } = await supabase.auth.getSession()
  if (error) {
    throw clientError('auth', 'auth_session_unavailable', '登录状态读取失败，请重新登录后再试。', true)
  }
  const token = data?.session?.access_token
  if (!token) {
    throw clientError('auth', 'auth_required', '登录状态已失效，请重新登录后再试。')
  }
  return token
}

function normalizeError(value, fallback = {}) {
  const source = value && typeof value === 'object' ? value : {}
  return {
    type: typeof source.type === 'string' ? source.type : (fallback.type || 'transport'),
    code: typeof source.code === 'string' ? source.code : (fallback.code || 'transport_failed'),
    message: typeof source.message === 'string' ? source.message : (fallback.message || 'Codex Chat 请求失败'),
    retryable: typeof source.retryable === 'boolean' ? source.retryable : fallback.retryable === true,
  }
}

function parseSseBlock(block, onEvent) {
  let event = 'message'
  const data = []
  for (const line of block.split(/\r?\n/)) {
    if (line.startsWith('event:')) event = line.slice(6).trim()
    if (line.startsWith('data:')) data.push(line.slice(5).trimStart())
  }
  if (!data.length) return
  let payload
  try {
    payload = JSON.parse(data.join('\n'))
  } catch {
    throw clientError('transport', 'transport_sse_invalid', 'Codex Chat 返回了无法解析的数据。', true)
  }
  onEvent(event, payload)
}

export async function streamCodexChat(
  { message, windowId, recentHistory = [], signal },
  callbacks = {},
  { fetchImpl = globalThis.fetch, getAccessToken = getSupabaseAccessToken, endpoint = DEFAULT_ENDPOINT } = {},
) {
  const token = await getAccessToken()
  const response = await fetchImpl(endpoint, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify({
      window_id: windowId,
      message,
      ...(recentHistory.length ? { recent_history: recentHistory } : {}),
    }),
    signal,
  })

  if (!response.ok) {
    let payload
    try { payload = await response.json() } catch { payload = null }
    const detail = normalizeError(payload?.error, {
      code: `transport_http_${response.status}`,
      message: `Codex Chat 请求失败（HTTP ${response.status}）`,
      retryable: response.status >= 500,
    })
    const error = new Error(detail.message)
    error.detail = detail
    throw error
  }
  if (!response.body) {
    throw clientError('transport', 'transport_stream_missing', 'Codex Chat 没有返回数据流。', true)
  }

  let buffer = ''
  let fullText = ''
  let session = null
  let streamError = null
  let completion = null

  const dispatch = (event, payload) => {
    if (event === 'session') {
      session = payload
      callbacks.onSession?.(payload)
      return
    }
    if (event === 'text' && typeof payload?.text === 'string') {
      fullText += payload.text
      callbacks.onText?.(payload.text, fullText)
      return
    }
    if (event === 'error') {
      streamError = normalizeError(payload, {
        type: 'provider', code: 'provider_failed', message: 'Codex 暂时无法回复', retryable: true,
      })
      callbacks.onError?.(streamError)
      return
    }
    if (event === 'done') {
      completion = payload
      callbacks.onDone?.(payload)
    }
  }

  const drain = flush => {
    const blocks = buffer.split(/\r?\n\r?\n/)
    buffer = blocks.pop() || ''
    for (const block of blocks) {
      if (block.trim()) parseSseBlock(block, dispatch)
    }
    if (flush && buffer.trim()) {
      parseSseBlock(buffer, dispatch)
      buffer = ''
    }
  }

  const reader = response.body.getReader()
  const decoder = new TextDecoder()
  for (;;) {
    const { done, value } = await reader.read()
    if (done) break
    buffer += decoder.decode(value, { stream: true })
    drain(false)
  }
  buffer += decoder.decode()
  drain(true)

  if (!completion || typeof completion.ok !== 'boolean') {
    throw clientError('transport', 'transport_done_missing', 'Codex Chat 数据流未正常结束。', true)
  }
  if (!completion.ok && !streamError) {
    streamError = normalizeError(null, {
      type: 'provider', code: 'provider_failed', message: 'Codex 暂时无法回复', retryable: true,
    })
    callbacks.onError?.(streamError)
  }

  return {
    ok: completion.ok,
    text: fullText,
    session,
    error: streamError,
  }
}
