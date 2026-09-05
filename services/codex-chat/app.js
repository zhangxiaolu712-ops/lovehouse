import http from 'node:http'

import { createContextBreakdown, withReasoningContext } from './contextBreakdown.js'
import { ChatRuntimeError, publicRuntimeError } from './errors.js'
import { assertRuntimeAdapter } from './runtimeContract.js'
import { SessionStore } from './sessionStore.js'
import { InMemoryThreadBindingStore } from './threadBindingStore.js'
import { normalizeToolPreferenceIds } from '../../bridge/tool-center/catalog.js'

function json(res, status, body) {
  const payload = JSON.stringify(body)
  res.writeHead(status, {
    'Content-Type': 'application/json; charset=utf-8',
    'Content-Length': Buffer.byteLength(payload),
    'Cache-Control': 'no-store',
  })
  res.end(payload)
}

function sse(res, event, data) {
  res.write(`event: ${event}\ndata: ${JSON.stringify(data)}\n\n`)
}

async function readJson(req, limit = 64 * 1024) {
  let size = 0
  const chunks = []
  for await (const chunk of req) {
    size += chunk.length
    if (size > limit) {
      throw new ChatRuntimeError('STREAM_INTERRUPTED', 'Request body exceeds 64 KiB', {
        stage: 'transport', status: 413,
      })
    }
    chunks.push(chunk)
  }
  try {
    return JSON.parse(Buffer.concat(chunks).toString('utf8') || '{}')
  } catch (cause) {
    throw new ChatRuntimeError('STREAM_INTERRUPTED', 'Request body must be valid JSON', {
      stage: 'transport', status: 400, cause,
    })
  }
}

function normalizeBody(body) {
  if (typeof body?.message !== 'string' || !body.message.trim() || body.message.length > 16_000) {
    throw new ChatRuntimeError('STREAM_INTERRUPTED', 'message must contain 1-16000 characters', {
      stage: 'validation', status: 400,
    })
  }
  return {
    threadId: body.thread_id || body.window_id,
    message: body.message.trim(),
    recentHistory: body.recent_history,
    allowedToolIds: normalizeToolPreferenceIds(body.allowed_tool_ids),
  }
}

export function createCodexChatHandler({
  authenticate,
  runtime,
  sessions = new SessionStore(),
  threadBindings = new InMemoryThreadBindingStore(),
  routePrefix = '/api/codex',
  serviceName = 'lovehouse-codex-chat',
  taskRepository = null,
  transientStore = null,
}) {
  assertRuntimeAdapter(runtime)
  if (typeof authenticate !== 'function') throw new TypeError('Chat runtime requires Owner auth')

  return async function handler(req, res) {
    const pathname = new URL(req.url, 'http://localhost').pathname
    if (pathname === `${routePrefix}/health` && req.method === 'GET') {
      return json(res, 200, {
        ok: true,
        service: serviceName,
        runtime: runtime.getCapabilities(),
      })
    }
    const threadMatch = pathname.match(new RegExp(`^${routePrefix}/livingroom/threads/([0-9a-f-]+)$`, 'i'))
    const manualApprovalMatch = pathname.match(new RegExp(`^${routePrefix}/livingroom/tasks/([0-9a-f-]+)/approval$`, 'i'))
    const decisionMatch = pathname.match(new RegExp(`^${routePrefix}/livingroom/approvals/([0-9a-f-]+)/decision$`, 'i'))
    const localResumeMatch = pathname.match(new RegExp(`^${routePrefix}/livingroom/tasks/([0-9a-f-]+)/local-user/resume$`, 'i'))
    if (taskRepository && (threadMatch || manualApprovalMatch || decisionMatch || localResumeMatch)) {
      try {
        await authenticate(req.headers.authorization)
        if (threadMatch && req.method === 'GET') {
          const task = await taskRepository.getThread(threadMatch[1])
          return json(res, task ? 200 : 404, task ? {
            thread_id: threadMatch[1], task, transient_events: transientStore?.read(threadMatch[1]) || [],
          } : { error: { code: 'THREAD_NOT_FOUND' } })
        }
        const body = await readJson(req)
        if (manualApprovalMatch && req.method === 'POST') {
          if (typeof body.request !== 'string' || !body.request.trim()) return json(res, 400, { error: { code: 'APPROVAL_REQUEST_REQUIRED' } })
          const approval = await taskRepository.createManualApproval(manualApprovalMatch[1], body.request.trim())
          return json(res, approval ? 201 : 409, approval || { error: { code: 'TASK_NOT_RUNNING' } })
        }
        if (decisionMatch && req.method === 'POST') {
          if (!['approved', 'rejected', 'expired'].includes(body.decision)) return json(res, 400, { error: { code: 'APPROVAL_DECISION_INVALID' } })
          const approval = await taskRepository.decideApproval(decisionMatch[1], body.decision)
          return json(res, approval ? 200 : 409, approval || { error: { code: 'APPROVAL_NOT_PENDING' } })
        }
        if (localResumeMatch && req.method === 'POST') {
          const task = await taskRepository.resumeLocalUser(localResumeMatch[1])
          return json(res, task ? 200 : 409, task || { error: { code: 'LOCAL_ACTION_NOT_PENDING' } })
        }
        return json(res, 405, { error: { code: 'METHOD_NOT_ALLOWED' } })
      } catch (error) {
        return json(res, error.status || 500, { error: publicRuntimeError(error) })
      }
    }
    if (pathname !== `${routePrefix}/chat`) {
      return json(res, 404, { error: publicRuntimeError(new ChatRuntimeError(
        'UNKNOWN_RUNTIME', 'Not found', { stage: 'routing', status: 404 },
      )) })
    }
    if (req.method !== 'POST') {
      res.setHeader('Allow', 'POST')
      return json(res, 405, { error: publicRuntimeError(new ChatRuntimeError(
        'UNKNOWN_RUNTIME', 'POST required', { stage: 'routing', status: 405 },
      )) })
    }

    let owner
    let input
    let session
    let persisted
    try {
      owner = await authenticate(req.headers.authorization)
      input = normalizeBody(await readJson(req))
      persisted = await threadBindings.get({
        ownerUserId: owner.userId,
        threadId: input.threadId,
      })
      session = sessions.resolve({
        ownerUserId: owner.userId,
        threadId: input.threadId,
        runtimeSessionId: persisted?.runtime_session_id || null,
        recentHistory: input.recentHistory,
      })
      sessions.acquire(session.key)
    } catch (error) {
      return json(res, error.status || 500, { error: publicRuntimeError(error) })
    }

    res.writeHead(200, {
      'Content-Type': 'text/event-stream; charset=utf-8',
      'Cache-Control': 'no-cache, no-transform',
      Connection: 'keep-alive',
      'X-Accel-Buffering': 'no',
    })
    const controller = new AbortController()
    let clientClosed = false
    let runtimeSessionId = session.runtimeSessionId
    let bindingWrite = Promise.resolve()
    req.on('aborted', () => {
      clientClosed = true
      controller.abort()
    })
    const emit = (event, payload) => {
      if (!clientClosed) sse(res, event, payload)
    }

    const capabilities = runtime.getCapabilities()
    emit('runtime_status', {
      status: 'ready',
      runtime_type: capabilities.runtime_type,
      adapter_id: capabilities.adapter_id,
      capabilities: capabilities.capabilities,
    })
    emit('quota', runtime.getQuota())
    let contextBreakdown = createContextBreakdown({
      history: session.history,
      message: input.message,
      resumed: session.resumed,
      runtimeType: capabilities.runtime_type,
    })
    emit('context_breakdown', contextBreakdown)

    try {
      const result = await runtime.streamEvents({
        message: input.message,
        history: session.history,
        sessionId: runtimeSessionId,
        previousUsage: persisted?.cumulative_usage || null,
        signal: controller.signal,
        getContinuationContext: async () => session.history,
        allowedToolIds: input.allowedToolIds,
        authorization: req.headers.authorization,
        threadId: input.threadId,
        onRuntimeBinding: value => {
          runtimeSessionId = value
          sessions.bind(session.key, value)
          bindingWrite = threadBindings.save({
            ownerUserId: owner.userId,
            threadId: input.threadId,
            runtimeSessionId: value,
          })
          // Compatibility event for the existing direct sidecar client. The
          // /api/v1 Bridge adapter consumes it but never exposes this id.
          emit('session', {
            session_id: value,
            window_id: input.threadId,
            resumed: session.resumed,
          })
        },
        onText: text => emit('text', { text }),
        onThinking: thinking => emit('thinking', { thinking }),
        onEvent: (event, payload) => {
          emit(event, payload)
          if (event === 'reasoning_status') {
            contextBreakdown = withReasoningContext(contextBreakdown, payload)
            emit('context_breakdown', contextBreakdown)
          }
        },
      })
      if (Number.isFinite(result.usage?.cumulative_input_tokens)
        || Number.isFinite(result.usage?.cumulative_output_tokens)) {
        bindingWrite = bindingWrite.then(() => threadBindings.save({
          ownerUserId: owner.userId,
          threadId: input.threadId,
          runtimeSessionId: result.sessionId,
          cumulativeUsage: {
            input_tokens: result.usage.cumulative_input_tokens,
            output_tokens: result.usage.cumulative_output_tokens,
            cached_input_tokens: result.usage.cumulative_cached_input_tokens,
            reasoning_output_tokens: result.usage.cumulative_reasoning_output_tokens,
          },
        }))
      }
      await bindingWrite
      sessions.bind(session.key, result.sessionId)
      sessions.complete(session.key, input.message, result.text)
      emit('done', {
        ok: true,
        session_id: result.sessionId,
        ...(typeof result.model === 'string' && result.model ? { model: result.model } : {}),
      })
      if (!clientClosed) res.end()
    } catch (error) {
      if (error?.code === 'QUOTA_EXHAUSTED') {
        emit('quota', {
          status: 'exhausted',
          remaining: 0,
          unit: null,
          reset_at: null,
          source: `${capabilities.runtime_type}_error`,
        })
      }
      emit('error', publicRuntimeError(error))
      emit('done', { ok: false, session_id: runtimeSessionId })
      if (!clientClosed) res.end()
    } finally {
      sessions.release(session.key)
    }
  }
}

export function createCodexChatServer(dependencies) {
  return http.createServer(createCodexChatHandler(dependencies))
}

// Backward-compatible generic names for additional runtime sidecars. Codex
// keeps its existing exports and behavior.
export const createChatRuntimeHandler = createCodexChatHandler
export const createChatRuntimeServer = createCodexChatServer
