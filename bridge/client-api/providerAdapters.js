import { ClientApiError, normalizeClientApiError } from './errors.js'

function providerErrorCode(message = '') {
  if (/(?:quota|credit|usage limit|out of extra usage|rate limit)/i.test(message)) {
    return 'PROVIDER_QUOTA_EXHAUSTED'
  }
  if (/(?:busy|already active|in progress)/i.test(message)) return 'PROVIDER_BUSY'
  return 'PROVIDER_UNAVAILABLE'
}

function asProviderError(error, runtime) {
  if (error instanceof ClientApiError) return error
  const code = providerErrorCode(error?.message)
  const message = code === 'PROVIDER_QUOTA_EXHAUSTED'
    ? `${runtime} usage is currently unavailable`
    : `${runtime} provider is unavailable`
  return new ClientApiError(code, message, {
    stage: 'provider',
    status: code === 'PROVIDER_BUSY' ? 409 : 503,
    retryable: code !== 'PROVIDER_QUOTA_EXHAUSTED',
    cause: error,
  })
}

export function createClaudeAdapter({
  sendMessage,
  abortWindow,
  resetSession,
  bindingStore,
  systemPrompt,
}) {
  if (typeof sendMessage !== 'function' || typeof abortWindow !== 'function' || typeof resetSession !== 'function') {
    throw new TypeError('Claude adapter requires the existing Claude session functions')
  }
  if (!bindingStore || ['get', 'save', 'delete'].some(method => typeof bindingStore[method] !== 'function')) {
    throw new TypeError('Claude adapter requires a runtime binding store')
  }

  return Object.freeze({
    runtime: 'claude',
    async health() {
      return { status: 'configured' }
    },
    async chat({ ownerUserId, threadId, text, onText, signal }) {
      const bindingKey = { ownerUserId, personaId: 'claude', threadId }
      const binding = await bindingStore.get(bindingKey)
      let bindingWrite = Promise.resolve()
      let settled = false

      return new Promise((resolve, reject) => {
        const finish = callback => value => {
          if (settled) return
          settled = true
          signal?.removeEventListener('abort', onAbort)
          callback(value)
        }
        const succeed = finish(resolve)
        const fail = finish(error => reject(asProviderError(error, 'Claude')))
        const onAbort = () => {
          abortWindow(threadId)
          fail(new ClientApiError('CLIENT_DISCONNECTED', 'Client disconnected', {
            stage: 'transport', status: 499,
          }))
        }
        signal?.addEventListener('abort', onAbort, { once: true })

        const accepted = sendMessage(threadId, text, systemPrompt, {
          onSession(session) {
            if (!session?.session_id) return
            bindingWrite = bindingStore.save({
              ...bindingKey,
              providerSessionId: session.session_id,
            })
          },
          onText(delta) {
            if (delta) onText?.(delta)
          },
          onDone(result) {
            bindingWrite
              .then(() => succeed({ usage: result?.usage || null }))
              .catch(error => fail(normalizeClientApiError(error, {
                code: 'RUNTIME_BINDING_WRITE_FAILED',
                message: 'Claude runtime binding could not be saved',
                stage: 'storage',
                status: 503,
                retryable: true,
              })))
          },
          onError(error) {
            fail(new Error(String(error || 'Claude provider failed')))
          },
        }, {
          knownSessionId: binding?.provider_session_id || null,
          sessionIntent: binding ? 'continue' : 'new',
        })

        if (accepted === false && !settled) {
          fail(new ClientApiError('PROVIDER_BUSY', 'Claude provider is busy', {
            stage: 'provider', status: 409, retryable: true,
          }))
        }
      })
    },
    async reset({ ownerUserId, threadId }) {
      resetSession(threadId)
      await bindingStore.delete({ ownerUserId, personaId: 'claude', threadId })
      return { reset: true }
    },
  })
}

function parseSseFrames(buffer, onFrame, flush = false) {
  const normalized = buffer.replace(/\r\n/g, '\n')
  const frames = normalized.split('\n\n')
  const remainder = flush ? '' : frames.pop()
  for (const frame of frames) {
    if (!frame.trim()) continue
    let event = 'message'
    const data = []
    for (const line of frame.split('\n')) {
      if (line.startsWith('event:')) event = line.slice(6).trim()
      if (line.startsWith('data:')) data.push(line.slice(5).trimStart())
    }
    if (!data.length) continue
    try {
      onFrame(event, JSON.parse(data.join('\n')))
    } catch (cause) {
      throw new ClientApiError('PROVIDER_STREAM_INVALID', 'Codex returned an invalid stream event', {
        stage: 'provider', status: 502, retryable: true, cause,
      })
    }
  }
  if (flush && normalized.trim() && !normalized.includes('\n\n')) {
    parseSseFrames(`${normalized}\n\n`, onFrame, false)
  }
  return remainder
}

async function responseError(response) {
  try {
    const payload = await response.json()
    const detail = payload?.error
    return new ClientApiError(
      detail?.code || 'PROVIDER_UNAVAILABLE',
      detail?.message || 'Codex provider rejected the request',
      {
        stage: detail?.type || 'provider',
        status: response.status,
        retryable: detail?.retryable === true,
      },
    )
  } catch (cause) {
    return new ClientApiError('PROVIDER_UNAVAILABLE', 'Codex provider rejected the request', {
      stage: 'provider', status: response.status || 503, retryable: true, cause,
    })
  }
}

export function createCodexAdapter({
  baseUrl = 'http://127.0.0.1:3002/api/codex',
  fetchImpl = globalThis.fetch,
  healthTimeoutMs = 2_000,
} = {}) {
  if (typeof fetchImpl !== 'function') throw new TypeError('Codex adapter requires fetch')
  const normalizedBase = baseUrl.replace(/\/+$/, '')

  return Object.freeze({
    runtime: 'codex',
    async health() {
      const controller = new AbortController()
      const timeout = setTimeout(() => controller.abort(), healthTimeoutMs)
      timeout.unref?.()
      try {
        const response = await fetchImpl(`${normalizedBase}/health`, { signal: controller.signal })
        return { status: response.ok ? 'available' : 'unavailable' }
      } catch {
        return { status: 'unavailable' }
      } finally {
        clearTimeout(timeout)
      }
    },
    async chat({ threadId, text, authorization, onText, signal }) {
      let response
      try {
        response = await fetchImpl(`${normalizedBase}/chat`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            Authorization: authorization,
          },
          body: JSON.stringify({ window_id: threadId, message: text }),
          signal,
        })
      } catch (error) {
        throw asProviderError(error, 'Codex')
      }
      if (!response.ok) throw await responseError(response)
      if (!response.body) {
        throw new ClientApiError('PROVIDER_STREAM_MISSING', 'Codex did not return a response stream', {
          stage: 'provider', status: 502, retryable: true,
        })
      }

      const decoder = new TextDecoder()
      let buffer = ''
      let done = false
      let streamError = null
      const onFrame = (event, data) => {
        if (event === 'text' && data?.text) onText?.(data.text)
        if (event === 'error') {
          streamError = new ClientApiError(
            data?.code || 'PROVIDER_UNAVAILABLE',
            data?.message || 'Codex provider failed',
            {
              stage: data?.type || 'provider',
              status: 503,
              retryable: data?.retryable === true,
            },
          )
        }
        if (event === 'done') done = true
      }

      for await (const chunk of response.body) {
        buffer += decoder.decode(chunk, { stream: true })
        buffer = parseSseFrames(buffer, onFrame)
      }
      buffer += decoder.decode()
      parseSseFrames(buffer, onFrame, true)
      if (streamError) throw streamError
      if (!done) {
        throw new ClientApiError('PROVIDER_STREAM_INCOMPLETE', 'Codex stream ended unexpectedly', {
          stage: 'provider', status: 502, retryable: true,
        })
      }
      return { usage: null }
    },
    async reset() {
      // Codex sidecar owns its persistent runtime binding. The Client API
      // rotates to a new LoveHouse thread id instead of mutating that store.
      return { reset: true }
    },
  })
}

export function createProviderRouter({ personaRegistry, adapters }) {
  if (!personaRegistry || typeof personaRegistry.get !== 'function') {
    throw new TypeError('Provider router requires a persona registry')
  }
  const byRuntime = new Map(Object.entries(adapters || {}))

  function describe(persona) {
    return {
      ...persona,
      runtime_status: persona.enabled && byRuntime.has(persona.default_runtime)
        ? 'configured'
        : 'unavailable',
    }
  }

  return Object.freeze({
    listPersonas() {
      return personaRegistry.list().map(describe)
    },
    resolve(personaId) {
      const persona = personaRegistry.get(personaId)
      if (!persona) {
        throw new ClientApiError('UNKNOWN_PERSONA', 'Unknown persona_id', {
          stage: 'routing', status: 400,
        })
      }
      const adapter = byRuntime.get(persona.default_runtime)
      if (!persona.enabled || !adapter) {
        throw new ClientApiError('PROVIDER_UNAVAILABLE', `${persona.display_name} chat is not configured`, {
          stage: 'routing', status: 503,
        })
      }
      return { persona: describe(persona), adapter }
    },
    async health() {
      const values = []
      for (const persona of personaRegistry.list()) {
        const adapter = byRuntime.get(persona.default_runtime)
        const runtime = adapter && persona.enabled
          ? await adapter.health().catch(() => ({ status: 'unavailable' }))
          : { status: 'unavailable' }
        values.push({ id: persona.id, enabled: persona.enabled, status: runtime.status })
      }
      return values
    },
  })
}
