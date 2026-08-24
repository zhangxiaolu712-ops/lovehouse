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
    getCapabilities() {
      return {
        runtime_type: 'claude_cli',
        adapter_id: 'legacy-claude-frozen',
        enabled: true,
        capabilities: { stable_chat_v1: false },
      }
    },
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
        stage: detail?.stage || detail?.type || 'runtime',
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

const RUNTIME_ERROR_CODES = new Map([
  ['provider_unavailable', 'RUNTIME_UNAVAILABLE'],
  ['provider_resume_failed', 'SESSION_RECOVERY_FAILED'],
  ['provider_turn_failed', 'STREAM_INTERRUPTED'],
  ['provider_exit', 'STREAM_INTERRUPTED'],
  ['provider_aborted', 'STREAM_INTERRUPTED'],
  ['auth_required', 'AUTH_FAILED'],
])

function stableRuntimeErrorCode(value) {
  if (typeof value !== 'string') return 'STREAM_INTERRUPTED'
  if (/^[A-Z][A-Z0-9_]{2,63}$/.test(value)) return value
  return RUNTIME_ERROR_CODES.get(value) || 'STREAM_INTERRUPTED'
}

function finiteOrNull(value) {
  return Number.isFinite(value) ? value : null
}

function safeRuntimeEvent(event, data) {
  if (event === 'runtime_status') {
    return {
      status: ['ready', 'recovering', 'unavailable'].includes(data?.status) ? data.status : 'unavailable',
      runtime_type: data?.runtime_type === 'codex_cli' ? 'codex_cli' : 'codex_cli',
      adapter_id: data?.adapter_id === 'codex-cli-v1' ? 'codex-cli-v1' : 'codex-cli-v1',
      capabilities: data?.capabilities && typeof data.capabilities === 'object'
        ? {
            streaming_text: data.capabilities.streaming_text === true,
            reasoning_summary: ['detailed', 'conditional', 'unavailable'].includes(data.capabilities.reasoning_summary)
              ? data.capabilities.reasoning_summary
              : 'conditional',
            tool_events: data.capabilities.tool_events === true,
            actual_usage: data.capabilities.actual_usage === true,
            quota: data.capabilities.quota === true,
            context_breakdown: 'basic',
          }
        : null,
    }
  }
  if (event === 'reasoning_status') {
    return {
      available: data?.available === true,
      status: ['started', 'updated', 'streaming', 'completed', 'unavailable'].includes(data?.status)
        ? data.status
        : 'unavailable',
      summary: typeof data?.summary === 'string' ? data.summary.slice(0, 1_500) : null,
      source: 'codex_cli',
    }
  }
  if (['tool_call', 'tool_result', 'tool_error'].includes(event)) {
    return {
      call_id: typeof data?.call_id === 'string' ? data.call_id.slice(0, 128) : 'unknown',
      tool_type: ['command', 'file_change', 'mcp', 'web_search'].includes(data?.tool_type)
        ? data.tool_type
        : 'command',
      name: typeof data?.name === 'string' ? data.name.slice(0, 128) : 'tool',
      status: event === 'tool_call' ? 'running' : (event === 'tool_result' ? 'success' : 'failed'),
      lifecycle: ['started', 'updated', 'completed'].includes(data?.lifecycle)
        ? data.lifecycle
        : (event === 'tool_call' ? 'started' : 'completed'),
      ...(event === 'tool_call'
        ? {}
        : { summary: typeof data?.summary === 'string' ? data.summary.slice(0, 500) : null }),
    }
  }
  if (event === 'usage') {
    return {
      estimated_input_tokens: finiteOrNull(data?.estimated_input_tokens),
      actual_input_tokens: finiteOrNull(data?.actual_input_tokens),
      actual_output_tokens: finiteOrNull(data?.actual_output_tokens),
      total_tokens: finiteOrNull(data?.total_tokens),
      cumulative_input_tokens: finiteOrNull(data?.cumulative_input_tokens),
      cumulative_output_tokens: finiteOrNull(data?.cumulative_output_tokens),
      cumulative_total_tokens: finiteOrNull(data?.cumulative_total_tokens),
      previous_cumulative_input_tokens: finiteOrNull(data?.previous_cumulative_input_tokens),
      previous_cumulative_output_tokens: finiteOrNull(data?.previous_cumulative_output_tokens),
      baseline_status: ['known', 'establishing', 'reset', 'unavailable'].includes(data?.baseline_status)
        ? data.baseline_status
        : 'unavailable',
      usage_source: [
        'codex_cli_cumulative_delta', 'codex_cli_cumulative_baseline', 'codex_cli', 'estimate',
      ].includes(data?.usage_source) ? data.usage_source : 'estimate',
    }
  }
  if (event === 'quota') {
    return {
      status: ['available', 'exhausted', 'unknown'].includes(data?.status) ? data.status : 'unknown',
      remaining: finiteOrNull(data?.remaining),
      unit: typeof data?.unit === 'string' ? data.unit.slice(0, 32) : null,
      reset_at: typeof data?.reset_at === 'string' ? data.reset_at.slice(0, 64) : null,
      source: typeof data?.source === 'string' ? data.source.slice(0, 64) : 'codex_cli_unavailable',
    }
  }
  if (event === 'context_breakdown') {
    const section = (name, defaultEnabled = false) => ({
      enabled: data?.[name]?.enabled === true || defaultEnabled,
      available: data?.[name]?.available === true,
      estimated_tokens: finiteOrNull(data?.[name]?.estimated_tokens),
      ...(name === 'recent_chat' && typeof data?.[name]?.source === 'string'
        ? { source: data[name].source.slice(0, 64) }
        : {}),
    })
    return {
      recent_chat: section('recent_chat', true),
      memory: section('memory'),
      worldbook: section('worldbook'),
      persona: section('persona'),
      current_message: section('current_message', true),
      reasoning: {
        enabled: data?.reasoning?.enabled === true,
        available: data?.reasoning?.available === true
          ? true
          : (data?.reasoning?.available === false ? false : null),
        status: ['pending', 'resumed', 'started', 'updated', 'completed', 'unavailable']
          .includes(data?.reasoning?.status) ? data.reasoning.status : 'unavailable',
        summary: typeof data?.reasoning?.summary === 'string'
          ? data.reasoning.summary.slice(0, 1_500)
          : null,
        source: 'codex_native_thread',
        active_context: data?.reasoning?.active_context === true,
        resumes_with_thread: data?.reasoning?.resumes_with_thread === true,
        compaction: 'codex_native',
      },
      estimated_tokens: finiteOrNull(data?.estimated_tokens),
    }
  }
  return null
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
    getCapabilities() {
      return {
        runtime_type: 'codex_cli',
        adapter_id: 'codex-cli-v1',
        enabled: true,
        capabilities: {
          streaming_text: true,
          reasoning_summary: 'conditional',
          tool_events: true,
          actual_usage: true,
          quota: false,
          context_breakdown: 'basic',
        },
      }
    },
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
    async chat({ threadId, text, authorization, onText, onEvent, signal }) {
      let response
      try {
        response = await fetchImpl(`${normalizedBase}/chat`, {
          method: 'POST',
          headers: {
            'Content-Type': 'application/json',
            Authorization: authorization,
          },
          body: JSON.stringify({ thread_id: threadId, window_id: threadId, message: text }),
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
      let reasoningReported = false
      let quotaReported = false
      let contextReported = false
      const onFrame = (event, data) => {
        if (event === 'text' && data?.text) onText?.(data.text)
        if ([
          'runtime_status', 'reasoning_status', 'tool_call', 'tool_result', 'tool_error',
          'usage', 'quota', 'context_breakdown',
        ].includes(event)) {
          const safe = safeRuntimeEvent(event, data)
          if (safe) onEvent?.(event, safe)
          if (event === 'reasoning_status') reasoningReported = true
          if (event === 'quota') quotaReported = true
          if (event === 'context_breakdown') contextReported = true
        }
        if (event === 'error') {
          streamError = new ClientApiError(
            stableRuntimeErrorCode(data?.code),
            data?.message || 'Codex provider failed',
            {
              stage: data?.stage || data?.type || 'runtime',
              status: data?.code === 'QUOTA_EXHAUSTED' ? 429 : 503,
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
      if (!reasoningReported) {
        onEvent?.('reasoning_status', {
          available: false, status: 'unavailable', summary: null, source: 'codex_cli',
        })
      }
      if (!quotaReported) {
        onEvent?.('quota', {
          status: 'unknown', remaining: null, unit: null, reset_at: null,
          source: 'codex_cli_unavailable',
        })
      }
      if (!contextReported) {
        onEvent?.('context_breakdown', {
          recent_chat: { enabled: true, available: true, estimated_tokens: null },
          memory: { enabled: false, available: false, estimated_tokens: 0 },
          worldbook: { enabled: false, available: false, estimated_tokens: 0 },
          persona: { enabled: false, available: false, estimated_tokens: 0 },
          current_message: { enabled: true, available: true, estimated_tokens: null },
          reasoning: {
            enabled: true,
            available: false,
            status: 'unavailable',
            summary: null,
            source: 'codex_native_thread',
            active_context: true,
            resumes_with_thread: true,
            compaction: 'codex_native',
          },
          estimated_tokens: null,
        })
      }
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
    const adapter = byRuntime.get(persona.default_runtime)
    return {
      ...persona,
      runtime_status: persona.enabled && adapter
        ? 'configured'
        : 'unavailable',
      runtime: adapter?.getCapabilities?.() || null,
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
