const DEFAULT_ENDPOINT = '/api/v1/chat'

function clientError(code, message, { stage = 'transport', retryable = false } = {}) {
  const error = new Error(message)
  error.detail = { code, message, stage, retryable }
  return error
}

async function getSupabaseAccessToken() {
  const { supabase } = await import('../../core/supabase')
  const { data, error } = await supabase.auth.getSession()
  if (error) throw clientError('AUTH_FAILED', '登录状态读取失败，请重新登录后再试。', {
    stage: 'auth', retryable: true,
  })
  const token = data?.session?.access_token
  if (!token) throw clientError('AUTH_FAILED', '登录状态已失效，请重新登录后再试。', { stage: 'auth' })
  return token
}

function parseBlock(block) {
  let event = 'message'
  const data = []
  for (const line of block.split(/\r?\n/)) {
    if (line.startsWith('event:')) event = line.slice(6).trim()
    if (line.startsWith('data:')) data.push(line.slice(5).trimStart())
  }
  if (!data.length) return null
  try {
    return { event, data: JSON.parse(data.join('\n')) }
  } catch (cause) {
    throw clientError('STREAM_INTERRUPTED', 'Chat 返回了无法解析的事件。', {
      stage: 'transport', retryable: true, cause,
    })
  }
}

export async function streamCodexV1({
  threadId,
  windowId,
  message,
  signal,
  scene = 'work',
}, callbacks = {}, {
  fetchImpl = globalThis.fetch,
  getAccessToken = getSupabaseAccessToken,
  endpoint = DEFAULT_ENDPOINT,
} = {}) {
  const token = await getAccessToken()
  const response = await fetchImpl(endpoint, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify({
      persona_id: 'codex',
      thread_id: threadId,
      window_id: windowId,
      scene,
      message: { type: 'text', text: message },
    }),
    signal,
  })
  if (!response.ok) {
    let payload
    try { payload = await response.json() } catch { payload = null }
    const detail = payload?.error || {}
    throw clientError(
      detail.code || `HTTP_${response.status}`,
      detail.message || `Chat 请求失败（HTTP ${response.status}）`,
      {
        stage: detail.stage || 'transport',
        retryable: detail.retryable === true || response.status >= 500,
      },
    )
  }
  if (!response.body) throw clientError('STREAM_INTERRUPTED', 'Chat 没有返回数据流。', {
    stage: 'transport', retryable: true,
  })

  let buffer = ''
  let fullText = ''
  let ended = false
  let streamError = null
  const dispatch = item => {
    if (!item) return
    callbacks.onEvent?.(item.event, item.data)
    if (item.event === 'text_delta' && typeof item.data?.delta === 'string') {
      fullText += item.data.delta
      callbacks.onText?.(item.data.delta, fullText)
    }
    if (item.event === 'error') {
      streamError = item.data?.error || {
        code: 'STREAM_INTERRUPTED', message: 'Chat runtime failed', stage: 'runtime', retryable: true,
      }
      callbacks.onError?.(streamError)
    }
    if (item.event === 'message_end') ended = true
  }
  const drain = flush => {
    const blocks = buffer.split(/\r?\n\r?\n/)
    buffer = blocks.pop() || ''
    for (const block of blocks) if (block.trim()) dispatch(parseBlock(block))
    if (flush && buffer.trim()) {
      dispatch(parseBlock(buffer))
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
  if (!ended) throw clientError('STREAM_INTERRUPTED', 'Chat 数据流未正常结束。', {
    stage: 'transport', retryable: true,
  })
  return { ok: !streamError, text: fullText, error: streamError }
}
