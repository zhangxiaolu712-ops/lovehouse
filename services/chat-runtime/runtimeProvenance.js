const SAFE_FIELDS = Object.freeze([
  'trace_id', 'request_id', 'thread_id', 'provider', 'persona_id', 'persona_version',
  'reanchor_intent', 'session_mode', 'materialization_attempted', 'materialization_succeeded',
  'instructions_present', 'background_present', 'connection_count', 'call_id', 'mcp_rpc_id',
  'connection_id', 'tool_name', 'validation_stage', 'downstream_status_category',
  'normalized_error_code', 'reason_category', 'status', 'argument_keys', 'tool_count',
])

function safeText(value, limit = 128) {
  return typeof value === 'string' && value.length > 0 ? value.slice(0, limit) : undefined
}

function safeInteger(value) {
  return Number.isSafeInteger(value) && value >= 0 ? value : undefined
}

export function safeRuntimeProvenance(fields = {}) {
  const safe = {}
  for (const key of SAFE_FIELDS) {
    const value = fields[key]
    if (typeof value === 'boolean') safe[key] = value
    else if (Number.isSafeInteger(value) && value >= 0) safe[key] = value
    else if (typeof value === 'string' && value) safe[key] = value.slice(0, key === 'reason_category' ? 64 : 128)
    else if (key === 'argument_keys' && Array.isArray(value)) {
      safe[key] = value.filter(item => typeof item === 'string').slice(0, 32).map(item => item.slice(0, 64))
    }
  }
  return safe
}

export function normalizeRuntimeTrace(value, fallback = {}) {
  const persona = fallback.personaRuntime || null
  return safeRuntimeProvenance({
    trace_id: safeText(value?.trace_id || fallback.traceId),
    request_id: safeText(fallback.requestId),
    thread_id: safeText(value?.thread_id || fallback.threadId),
    provider: safeText(value?.provider || fallback.provider, 64),
    persona_id: safeText(value?.persona_id || persona?.persona_id, 64),
    persona_version: safeInteger(value?.persona_version ?? persona?.persona_version),
    reanchor_intent: typeof (value?.reanchor_intent ?? persona?.reanchor_intent) === 'boolean'
      ? (value?.reanchor_intent ?? persona?.reanchor_intent) : undefined,
    session_mode: ['new', 'resume', 'recovery'].includes(value?.session_mode) ? value.session_mode : undefined,
    instructions_present: persona ? Boolean(persona.instructions) : value?.instructions_present,
    background_present: persona ? Boolean(persona.background) : value?.background_present,
    connection_count: persona?.connection_ids?.length ?? safeInteger(value?.connection_count),
  })
}

export function emitRuntimeProvenance(stage, fields, logger = console.log) {
  logger('[runtime-provenance]', JSON.stringify({ stage: String(stage).slice(0, 64), ...safeRuntimeProvenance(fields) }))
}

export function runtimeFailureCategory(error) {
  if (error?.name === 'AbortError' || error?.code === 'ABORT_ERR') return 'cancelled'
  if (error?.name === 'TimeoutError' || error?.code === 'ETIMEDOUT') return 'timeout'
  if (error?.code === 'RUNTIME_AUTHENTICATION_FAILED') return 'grant_invalid_expired'
  if (error?.code === 'PERSONA_RUNTIME_INVALIDATED') return 'persona_version_invalidated'
  if (error?.code === 'PERSONA_BINDING_INVALIDATED') return 'persona_binding_invalidated'
  if (error?.code === 'MCP_CONNECTION_UNAVAILABLE') return 'connection_unavailable'
  if (['MCP_TOOL_NOT_ALLOWED', 'MCP_TOOL_NO_LONGER_ALLOWED'].includes(error?.code)) return 'tool_membership_failure'
  if (error?.stage === 'validation') return 'sidecar_validation_failure'
  if (error?.stage === 'transport') return 'app_backend_transport_failure'
  if (['provider', 'runtime', 'tool'].includes(error?.stage)) return 'provider_cli_failure'
  return 'unknown'
}
