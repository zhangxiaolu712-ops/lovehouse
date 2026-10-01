const SCHEMA_VERSION = 1

export const TOOL_DETAIL_LIMITS = Object.freeze({
  command: 8 * 1024,
  commandOutput: 32 * 1024,
  genericArguments: 32 * 1024,
  genericResult: 64 * 1024,
  envelope: 256 * 1024,
})

const SENSITIVE_KEYS = new Set([
  'authorization', 'password', 'passwd', 'secret', 'token', 'accesstoken',
  'refreshtoken', 'apikey', 'cookie', 'session', 'credential', 'privatekey',
  'clientsecret',
])
const COMMAND_STATUSES = new Set(['in_progress', 'completed', 'failed', 'running', 'success'])
const OMITTED = Object.freeze({ type: 'omitted', reason: '内容已省略' })

function byteLength(value) {
  return Buffer.byteLength(String(value ?? ''), 'utf8')
}

function safeOriginalLength(value) {
  try { return byteLength(JSON.stringify(value)) } catch { return 0 }
}

function truncateUtf8(value, maxBytes) {
  const source = String(value ?? '')
  const originalLength = byteLength(source)
  if (originalLength <= maxBytes) return { value: source, truncated: false, originalLength }
  let output = ''
  let used = 0
  for (const character of source) {
    const size = byteLength(character)
    if (used + size > maxBytes) break
    output += character
    used += size
  }
  return { value: output, truncated: true, originalLength }
}

function sensitiveKey(key) {
  return SENSITIVE_KEYS.has(String(key).toLowerCase().replace(/[^a-z0-9]/g, ''))
}

function redactText(value) {
  let text = String(value ?? '')
  let redacted = false
  const replace = (pattern, replacement) => {
    text = text.replace(pattern, (...args) => {
      redacted = true
      return typeof replacement === 'function' ? replacement(...args) : replacement
    })
  }
  replace(/-----BEGIN [^-\r\n]*PRIVATE KEY-----[\s\S]*?-----END [^-\r\n]*PRIVATE KEY-----/gi, '[REDACTED]')
  replace(/\bBearer\s+[^\s"']+/gi, 'Bearer [REDACTED]')
  replace(/\bsb_secret_[A-Za-z0-9._-]+/gi, '[REDACTED]')
  replace(/\b(?:sk|key)-[A-Za-z0-9._-]{6,}/gi, '[REDACTED]')
  replace(/\b(postgres(?:ql)?|mysql|mongodb(?:\+srv)?|redis):\/\/[^\s"']+/gi,
    (_match, scheme) => `${scheme}://[REDACTED]`)
  replace(/([?&](?:x-amz-signature|x-goog-signature|signature|sig|token|key|secret|code)=)[^&#\s]*/gi,
    (_match, prefix) => `${prefix}[REDACTED]`)
  return { value: text, redacted }
}

function safeValue(value, state, depth = 0) {
  if (depth > 6 || state.entries >= 128) {
    state.truncated = true
    return { ...OMITTED }
  }
  if (value === null) return { type: 'null' }
  if (typeof value === 'string') {
    const redacted = redactText(value)
    const bounded = truncateUtf8(redacted.value, state.stringLimit)
    state.truncated ||= redacted.redacted || bounded.truncated
    return { type: 'text', text: bounded.value }
  }
  if (typeof value === 'number' && Number.isFinite(value)) return { type: 'number', value }
  if (typeof value === 'boolean') return { type: 'boolean', value }
  if (Array.isArray(value)) {
    const items = []
    for (const item of value) {
      if (state.entries >= 128) { state.truncated = true; break }
      state.entries += 1
      items.push(safeValue(item, state, depth + 1))
    }
    return { type: 'list', items }
  }
  if (typeof value === 'object') {
    const fields = []
    for (const [key, fieldValue] of Object.entries(value)) {
      if (state.entries >= 128) { state.truncated = true; break }
      state.entries += 1
      const safeKey = truncateUtf8(key, 256)
      state.truncated ||= safeKey.truncated
      fields.push({
        key: safeKey.value,
        value: sensitiveKey(key)
          ? (state.truncated = true, { type: 'text', text: '[REDACTED]' })
          : safeValue(fieldValue, state, depth + 1),
      })
    }
    return { type: 'object', fields }
  }
  state.truncated = true
  return { ...OMITTED }
}

function boundedSafeValue(value, maxBytes) {
  const state = { truncated: false, entries: 0, stringLimit: maxBytes }
  let normalized = safeValue(value, state)
  if (byteLength(JSON.stringify(normalized)) > maxBytes) {
    normalized = { ...OMITTED }
    state.truncated = true
  }
  return { value: normalized, truncated: state.truncated, originalLength: safeOriginalLength(value) }
}

function common(callId, detailKind, now) {
  if (typeof callId !== 'string' || !callId.trim()) return null
  return {
    schema_version: SCHEMA_VERSION,
    call_id: callId.slice(0, 128),
    detail_kind: detailKind,
    created_at: now().toISOString(),
  }
}

function genericEnvelope(callId, { argumentsValue, resultValue, isError = false } = {}, prior = null, now = () => new Date()) {
  const base = common(callId, 'generic_tool', now)
  if (!base) return null
  const previous = prior?.detail_kind === 'generic_tool' && prior.call_id === base.call_id ? prior : null
  const argumentsDetail = argumentsValue === undefined
    ? null : boundedSafeValue(argumentsValue, TOOL_DETAIL_LIMITS.genericArguments)
  const resultDetail = resultValue === undefined
    ? null : boundedSafeValue(resultValue, TOOL_DETAIL_LIMITS.genericResult)
  const envelope = {
    ...base,
    created_at: previous?.created_at || base.created_at,
    truncated: Boolean(previous?.truncated)
      || argumentsDetail?.truncated === true || resultDetail?.truncated === true,
    original_length: (argumentsDetail?.originalLength
      ?? (Number.isInteger(previous?.original_length) ? previous.original_length : 0))
      + (resultDetail?.originalLength || 0),
    ...(argumentsDetail ? { arguments: argumentsDetail.value } : previous?.arguments ? { arguments: previous.arguments } : {}),
    ...(resultDetail ? { result: resultDetail.value } : previous?.result ? { result: previous.result } : {}),
    ...(isError || previous?.is_error === true ? { is_error: true } : {}),
  }
  return byteLength(JSON.stringify(envelope)) <= TOOL_DETAIL_LIMITS.envelope ? envelope : null
}

export function normalizeClaudeToolInput(block, now = () => new Date()) {
  if (block?.type !== 'tool_use') return null
  const base = common(block.id, 'generic_tool', now)
  if (!base) return null
  const argumentsValue = boundedSafeValue(block.input ?? {}, TOOL_DETAIL_LIMITS.genericArguments)
  return {
    ...base,
    truncated: argumentsValue.truncated,
    original_length: argumentsValue.originalLength,
    arguments: argumentsValue.value,
  }
}

export function normalizeClaudeToolResult(block, prior = null, now = () => new Date()) {
  if (block?.type !== 'tool_result') return null
  const base = common(block.tool_use_id, 'generic_tool', now)
  if (!base) return null
  const result = boundedSafeValue(block.content ?? '', TOOL_DETAIL_LIMITS.genericResult)
  const previous = prior?.detail_kind === 'generic_tool' && prior.call_id === base.call_id ? prior : null
  const envelope = {
    ...base,
    created_at: previous?.created_at || base.created_at,
    truncated: Boolean(previous?.truncated) || result.truncated,
    original_length: (Number.isInteger(previous?.original_length) ? previous.original_length : 0)
      + result.originalLength,
    ...(previous?.arguments ? { arguments: previous.arguments } : {}),
    result: result.value,
    is_error: block.is_error === true,
  }
  return byteLength(JSON.stringify(envelope)) <= TOOL_DETAIL_LIMITS.envelope ? envelope : null
}

export function normalizeCodexCommand(item, now = () => new Date()) {
  if (item?.type !== 'command_execution') return null
  const base = common(item.id, 'command', now)
  if (!base) return null
  const command = truncateUtf8(typeof item.command === 'string' ? redactText(item.command).value : '', TOOL_DETAIL_LIMITS.command)
  const output = truncateUtf8(typeof item.aggregated_output === 'string'
    ? redactText(item.aggregated_output).value : '', TOOL_DETAIL_LIMITS.commandOutput)
  const originalLength = byteLength(item.command || '') + byteLength(item.aggregated_output || '')
  const envelope = {
    ...base,
    truncated: command.truncated || output.truncated
      || redactText(item.command || '').redacted || redactText(item.aggregated_output || '').redacted,
    original_length: originalLength,
    ...(typeof item.command === 'string' ? { command: command.value } : {}),
    ...(typeof item.aggregated_output === 'string' ? { output: output.value } : {}),
    ...(Number.isInteger(item.exit_code) ? { exit_code: item.exit_code } : {}),
    ...(COMMAND_STATUSES.has(item.status) ? { status: item.status } : {}),
  }
  return byteLength(JSON.stringify(envelope)) <= TOOL_DETAIL_LIMITS.envelope ? envelope : null
}

export function normalizeCodexMcpTool(item, prior = null, now = () => new Date()) {
  if (item?.type !== 'mcp_tool_call') return null
  const content = Array.isArray(item.result?.content)
    ? item.result.content.flatMap(block => {
        if (!block || typeof block !== 'object' || Array.isArray(block)) return []
        if (block.type === 'text' && typeof block.text === 'string') {
          return [{ type: 'text', text: block.text }]
        }
        if (block.type === 'resource_link' && typeof block.name === 'string' && typeof block.uri === 'string') {
          return [{
            type: 'resource_link', name: block.name, uri: block.uri,
            ...(typeof block.title === 'string' ? { title: block.title } : {}),
            ...(typeof block.description === 'string' ? { description: block.description } : {}),
            ...(typeof block.mimeType === 'string' ? { mime_type: block.mimeType } : {}),
            ...(Number.isSafeInteger(block.size) && block.size >= 0 ? { size: block.size } : {}),
          }]
        }
        if (block.type === 'resource' && block.resource && typeof block.resource === 'object'
          && typeof block.resource.uri === 'string') {
          return [{
            type: 'resource',
            resource: {
              uri: block.resource.uri,
              ...(typeof block.resource.mimeType === 'string' ? { mime_type: block.resource.mimeType } : {}),
              ...(typeof block.resource.text === 'string' ? { text: block.resource.text } : {}),
            },
          }]
        }
        return []
      })
    : null
  const result = item.result && typeof item.result === 'object' && !Array.isArray(item.result)
    ? {
        ...(content ? { content } : {}),
        ...(item.result.structured_content !== undefined
          ? { structured_content: item.result.structured_content } : {}),
      }
    : item.error && typeof item.error === 'object' && typeof item.error.message === 'string'
      ? { error: item.error.message }
      : undefined
  return genericEnvelope(item.id, {
    ...(item.arguments !== undefined ? { argumentsValue: item.arguments } : {}),
    ...(result !== undefined ? { resultValue: result } : {}),
    isError: item.status === 'failed' || Boolean(item.error),
  }, prior, now)
}

export function normalizeCodexFileChange(item, prior = null, now = () => new Date()) {
  if (item?.type !== 'file_change') return null
  const changes = Array.isArray(item.changes)
    ? item.changes.flatMap(change => {
        if (!change || typeof change.path !== 'string'
          || !['add', 'delete', 'update'].includes(change.kind)) return []
        return [{ path: change.path, kind: change.kind }]
      })
    : []
  return genericEnvelope(item.id, {
    argumentsValue: { changes },
    ...(typeof item.status === 'string' ? { resultValue: { status: item.status } } : {}),
    isError: item.status === 'failed',
  }, prior, now)
}

export function normalizeCodexWebSearch(item, prior = null, now = () => new Date()) {
  if (item?.type !== 'web_search' || typeof item.query !== 'string') return null
  return genericEnvelope(item.id, {
    argumentsValue: { query: item.query },
  }, prior, now)
}

function validateSafeValue(value, depth = 0) {
  if (!value || typeof value !== 'object' || Array.isArray(value) || depth > 6) return null
  if (value.type === 'null') return { type: 'null' }
  if (value.type === 'text' && typeof value.text === 'string') {
    const redacted = redactText(value.text)
    const bounded = truncateUtf8(redacted.value, TOOL_DETAIL_LIMITS.genericResult)
    return { type: 'text', text: bounded.value }
  }
  if (value.type === 'number' && Number.isFinite(value.value)) return { type: 'number', value: value.value }
  if (value.type === 'boolean' && typeof value.value === 'boolean') return { type: 'boolean', value: value.value }
  if (value.type === 'omitted') return { ...OMITTED }
  if (value.type === 'list' && Array.isArray(value.items) && value.items.length <= 128) {
    const items = value.items.map(item => validateSafeValue(item, depth + 1))
    return items.every(Boolean) ? { type: 'list', items } : null
  }
  if (value.type === 'object' && Array.isArray(value.fields) && value.fields.length <= 128) {
    const fields = value.fields.map(field => {
      if (!field || typeof field.key !== 'string' || byteLength(field.key) > 256) return null
      const normalized = sensitiveKey(field.key)
        ? { type: 'text', text: '[REDACTED]' }
        : validateSafeValue(field.value, depth + 1)
      return normalized ? { key: field.key, value: normalized } : null
    })
    return fields.every(Boolean) ? { type: 'object', fields } : null
  }
  return null
}

export function validateToolDetailEnvelope(value, expectedCallId = null) {
  try {
    if (!value || typeof value !== 'object' || Array.isArray(value)) return null
    if (value.schema_version !== SCHEMA_VERSION) return null
    if (typeof value.call_id !== 'string' || !value.call_id.trim() || byteLength(value.call_id) > 128) return null
    if (expectedCallId && value.call_id !== expectedCallId) return null
    if (typeof value.created_at !== 'string' || !Number.isFinite(Date.parse(value.created_at))) return null
    if (typeof value.truncated !== 'boolean') return null
    if (!Number.isSafeInteger(value.original_length) || value.original_length < 0) return null
    let envelope
    if (value.detail_kind === 'generic_tool') {
      const argumentsValue = value.arguments === undefined ? null : validateSafeValue(value.arguments)
      const result = value.result === undefined ? null : validateSafeValue(value.result)
      if (value.arguments !== undefined && !argumentsValue) return null
      if (value.result !== undefined && !result) return null
      if (argumentsValue && byteLength(JSON.stringify(argumentsValue)) > TOOL_DETAIL_LIMITS.genericArguments) return null
      if (result && byteLength(JSON.stringify(result)) > TOOL_DETAIL_LIMITS.genericResult) return null
      envelope = {
        schema_version: SCHEMA_VERSION, call_id: value.call_id, detail_kind: 'generic_tool',
        created_at: value.created_at, truncated: value.truncated, original_length: value.original_length,
        ...(argumentsValue ? { arguments: argumentsValue } : {}),
        ...(result ? { result } : {}),
        ...(typeof value.is_error === 'boolean' ? { is_error: value.is_error } : {}),
      }
    } else if (value.detail_kind === 'command') {
      if (value.command !== undefined && typeof value.command !== 'string') return null
      if (value.output !== undefined && typeof value.output !== 'string') return null
      const command = value.command === undefined ? null : truncateUtf8(redactText(value.command).value, TOOL_DETAIL_LIMITS.command)
      const output = value.output === undefined ? null : truncateUtf8(redactText(value.output).value, TOOL_DETAIL_LIMITS.commandOutput)
      envelope = {
        schema_version: SCHEMA_VERSION, call_id: value.call_id, detail_kind: 'command',
        created_at: value.created_at,
        truncated: value.truncated || command?.truncated === true || output?.truncated === true,
        original_length: value.original_length,
        ...(command ? { command: command.value } : {}),
        ...(output ? { output: output.value } : {}),
        ...(Number.isInteger(value.exit_code) ? { exit_code: value.exit_code } : {}),
        ...(COMMAND_STATUSES.has(value.status) ? { status: value.status } : {}),
      }
    } else return null
    return byteLength(JSON.stringify(envelope)) <= TOOL_DETAIL_LIMITS.envelope ? envelope : null
  } catch {
    return null
  }
}
