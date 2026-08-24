import crypto from 'node:crypto'

import { ClientApiError, normalizeClientApiError } from './errors.js'
import { SCENES } from './personas.js'

export const CLIENT_API_VERSION = 1
export const CLIENT_MESSAGE_TYPES = Object.freeze([
  'text',
  'audio',
  'image',
  'file',
  'location',
  'tool_result',
])

const WINDOW_ID_RE = /^[A-Za-z0-9_-]{8,128}$/
const THREAD_ID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const SOURCE_FIELDS = ['source_platform', 'source_conversation_id', 'source_message_id']

export function resolveDeploymentSha(cwd = process.cwd()) {
  const normalized = String(cwd || '').replace(/\\/g, '/')
  const match = normalized.match(/\/lovehouse-deployments\/([0-9a-f]{40})(?:\/|$)/i)
  return match?.[1]?.toLowerCase() || null
}

function publicError(error, requestId) {
  const normalized = normalizeClientApiError(error)
  return {
    ok: false,
    error: {
      code: normalized.code,
      message: normalized.message,
      stage: normalized.stage,
      request_id: requestId,
      retryable: normalized.retryable,
    },
  }
}

function sendJsonError(res, error, requestId) {
  const normalized = normalizeClientApiError(error)
  res.setHeader('Cache-Control', 'no-store')
  res.setHeader('X-Request-Id', requestId)
  return res.status(normalized.status).json(publicError(normalized, requestId))
}

function requestContext(req, res, next) {
  req.clientRequestId = crypto.randomUUID()
  res.setHeader('X-Request-Id', req.clientRequestId)
  next()
}

export function createClientOwnerAuth({ verifyOwnerToken, checkRate = () => true }) {
  if (typeof verifyOwnerToken !== 'function') throw new TypeError('Client API auth requires verifyOwnerToken')
  return async function clientOwnerAuth(req, res, next) {
    const auth = req.headers.authorization
    if (!auth?.startsWith('Bearer ')) {
      return sendJsonError(res, new ClientApiError('AUTH_REQUIRED', 'Owner bearer token required', {
        stage: 'auth', status: 401,
      }), req.clientRequestId)
    }
    try {
      const user = await verifyOwnerToken(auth.slice(7))
      if (!user) {
        return sendJsonError(res, new ClientApiError('AUTH_INVALID', 'Owner bearer token is invalid', {
          stage: 'auth', status: 401,
        }), req.clientRequestId)
      }
      if (!checkRate(user.id)) {
        return sendJsonError(res, new ClientApiError('RATE_LIMITED', 'Too many client requests', {
          stage: 'auth', status: 429, retryable: true,
        }), req.clientRequestId)
      }
      req.userId = user.id
      return next()
    } catch (cause) {
      return sendJsonError(res, new ClientApiError('AUTH_CHECK_FAILED', 'Owner authentication is unavailable', {
        stage: 'auth', status: 503, retryable: true, cause,
      }), req.clientRequestId)
    }
  }
}

function assertOptionalString(value, field, limit = 512) {
  if (value === undefined || value === null) return null
  if (typeof value !== 'string' || !value.trim() || value.length > limit) {
    throw new ClientApiError('INVALID_MESSAGE_SOURCE', `${field} is invalid`, {
      stage: 'validation', status: 400,
    })
  }
  return value.trim()
}

function normalizeArchiveSource(input) {
  const source = {}
  for (const field of SOURCE_FIELDS) {
    const value = assertOptionalString(input[field], field)
    if (value) source[field] = value
  }
  if (input.imported_at !== undefined && input.imported_at !== null) {
    const importedAt = assertOptionalString(input.imported_at, 'imported_at', 64)
    if (Number.isNaN(Date.parse(importedAt))) {
      throw new ClientApiError('INVALID_MESSAGE_SOURCE', 'imported_at must be an ISO timestamp', {
        stage: 'validation', status: 400,
      })
    }
    source.imported_at = importedAt
  }
  return source
}

function normalizeMessage(message) {
  if (!message || typeof message !== 'object' || Array.isArray(message)) {
    throw new ClientApiError('INVALID_MESSAGE', 'message must be an object', {
      stage: 'validation', status: 400,
    })
  }
  if (!CLIENT_MESSAGE_TYPES.includes(message.type)) {
    throw new ClientApiError('INVALID_MESSAGE_TYPE', 'Unknown message type', {
      stage: 'validation', status: 400,
    })
  }
  if (message.type !== 'text') {
    throw new ClientApiError('UNSUPPORTED_MESSAGE_TYPE', `${message.type} is reserved but not implemented`, {
      stage: 'validation', status: 415,
    })
  }
  if (typeof message.text !== 'string' || !message.text.trim() || message.text.length > 16_000) {
    throw new ClientApiError('INVALID_MESSAGE', 'text message must contain 1-16000 characters', {
      stage: 'validation', status: 400,
    })
  }

  const source = normalizeArchiveSource(message)
  return { type: 'text', text: message.text.trim(), source }
}

function normalizeThread(body, { requireThread = false } = {}) {
  if (!body || typeof body !== 'object' || Array.isArray(body)) {
    throw new ClientApiError('INVALID_REQUEST', 'JSON request object required', {
      stage: 'validation', status: 400,
    })
  }
  if (typeof body.persona_id !== 'string' || !body.persona_id) {
    throw new ClientApiError('UNKNOWN_PERSONA', 'persona_id is required', {
      stage: 'routing', status: 400,
    })
  }
  if (body.thread_id !== undefined && body.thread_id !== null && !THREAD_ID_RE.test(body.thread_id)) {
    throw new ClientApiError('INVALID_THREAD_ID', 'thread_id must be a UUID', {
      stage: 'validation', status: 400,
    })
  }
  if (requireThread && !body.thread_id) {
    throw new ClientApiError('INVALID_THREAD_ID', 'thread_id is required for reset', {
      stage: 'validation', status: 400,
    })
  }
  if (body.window_id !== undefined && body.window_id !== null && !WINDOW_ID_RE.test(body.window_id)) {
    throw new ClientApiError('INVALID_WINDOW_ID', 'window_id must contain 8-128 URL-safe characters', {
      stage: 'validation', status: 400,
    })
  }
  if (!requireThread && !body.window_id) {
    throw new ClientApiError('INVALID_WINDOW_ID', 'window_id is required', {
      stage: 'validation', status: 400,
    })
  }
  if (body.scene !== undefined && !SCENES.has(body.scene)) {
    throw new ClientApiError('INVALID_SCENE', 'scene is invalid', {
      stage: 'validation', status: 400,
    })
  }
  return {
    personaId: body.persona_id,
    threadId: body.thread_id || crypto.randomUUID(),
    windowId: body.window_id || null,
    requestedScene: body.scene || null,
    source: normalizeArchiveSource(body),
  }
}

function emitSse(res, event, data) {
  res.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`)
}

function safeFeatures(features = {}) {
  return {
    chat: true,
    memory: features.memory === true,
    livingroom: features.livingroom === true,
    worldbook: false,
    voice_tts: false,
    voice_stt: false,
    realtime_voice: false,
    raw_chat_archive: false,
    external_chat_import: false,
    device_credentials: false,
  }
}

export function installClientApi(app, {
  verifyOwner,
  providerRouter,
  startedAt,
  deploymentSha = resolveDeploymentSha(),
  features,
}) {
  if (!app || typeof app.use !== 'function') throw new TypeError('Client API requires an Express app')
  if (typeof verifyOwner !== 'function') throw new TypeError('Client API requires Owner auth middleware')
  if (!providerRouter || typeof providerRouter.resolve !== 'function') {
    throw new TypeError('Client API requires a provider router')
  }
  const started = startedAt || new Date().toISOString()
  const featureSet = safeFeatures(features)

  app.use('/v1', requestContext, verifyOwner)

  app.get('/v1/bootstrap', (req, res) => {
    res.setHeader('Cache-Control', 'no-store')
    return res.json({
      ok: true,
      api_version: CLIENT_API_VERSION,
      request_id: req.clientRequestId,
      server: {
        deployment_sha: deploymentSha,
        started_at: started,
      },
      auth: {
        mode: 'supabase_owner_bearer',
        device_credentials: false,
        device_revocation: false,
      },
      features: featureSet,
      personas: providerRouter.listPersonas(),
    })
  })

  app.get('/v1/health', async (req, res) => {
    const personas = await providerRouter.health()
    const enabledUnavailable = personas.some(persona => persona.enabled && persona.status === 'unavailable')
    res.setHeader('Cache-Control', 'no-store')
    return res.json({
      ok: true,
      status: enabledUnavailable ? 'degraded' : 'ok',
      api_version: CLIENT_API_VERSION,
      request_id: req.clientRequestId,
      server: { deployment_sha: deploymentSha, started_at: started },
      personas,
    })
  })

  app.get('/v1/personas', (req, res) => {
    res.setHeader('Cache-Control', 'no-store')
    return res.json({
      ok: true,
      api_version: CLIENT_API_VERSION,
      request_id: req.clientRequestId,
      personas: providerRouter.listPersonas(),
    })
  })

  app.post('/v1/chat', async (req, res) => {
    let normalized
    let resolved
    try {
      normalized = normalizeThread(req.body)
      resolved = providerRouter.resolve(normalized.personaId)
      normalized.message = normalizeMessage(req.body.message)
    } catch (error) {
      return sendJsonError(res, error, req.clientRequestId)
    }

    const scene = normalized.requestedScene || resolved.persona.scene
    res.status(200)
    res.setHeader('Content-Type', 'text/event-stream; charset=utf-8')
    res.setHeader('Cache-Control', 'no-cache, no-transform')
    res.setHeader('Connection', 'keep-alive')
    res.setHeader('X-Accel-Buffering', 'no')
    res.flushHeaders?.()

    const controller = new AbortController()
    let ended = false
    res.on('close', () => {
      if (!ended) controller.abort()
    })

    const base = {
      request_id: req.clientRequestId,
      thread_id: normalized.threadId,
      persona_id: resolved.persona.id,
    }
    emitSse(res, 'message_start', {
      ...base,
      runtime: resolved.persona.default_runtime,
      scene,
      message_type: normalized.message.type,
    })

    try {
      const result = await resolved.adapter.chat({
        ownerUserId: req.userId,
        threadId: normalized.threadId,
        windowId: normalized.windowId,
        scene,
        text: normalized.message.text,
        source: normalized.message.source,
        threadSource: normalized.source,
        authorization: req.headers.authorization,
        signal: controller.signal,
        onText(delta) {
          if (!ended) emitSse(res, 'text_delta', { ...base, delta })
        },
      })
      if (result?.usage && !ended) emitSse(res, 'usage', { ...base, usage: result.usage })
      if (!ended) emitSse(res, 'message_end', { ...base, ok: true })
    } catch (error) {
      if (!ended) {
        emitSse(res, 'error', publicError(error, req.clientRequestId))
        emitSse(res, 'message_end', { ...base, ok: false })
      }
    } finally {
      ended = true
      res.end()
    }
  })

  app.post('/v1/chat/reset', async (req, res) => {
    try {
      const normalized = normalizeThread(req.body, { requireThread: true })
      const resolved = providerRouter.resolve(normalized.personaId)
      await resolved.adapter.reset({
        ownerUserId: req.userId,
        threadId: normalized.threadId,
        windowId: normalized.windowId,
      })
      return res.json({
        ok: true,
        api_version: CLIENT_API_VERSION,
        request_id: req.clientRequestId,
        persona_id: resolved.persona.id,
        previous_thread_id: normalized.threadId,
        thread_id: crypto.randomUUID(),
        scene: normalized.requestedScene || resolved.persona.scene,
      })
    } catch (error) {
      return sendJsonError(res, error, req.clientRequestId)
    }
  })
}
