import http from 'node:http'

import { createContextBreakdown } from './contextBreakdown.js'
import { ChatRuntimeError, publicRuntimeError } from './errors.js'
import { assertRuntimeAdapter } from './runtimeContract.js'
import { SessionStore } from './sessionStore.js'
import { InMemoryThreadBindingStore } from './threadBindingStore.js'

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
  }
}

export function createCodexChatHandler({
  authenticate,
  runtime,
  sessions = new SessionStore(),
  threadBindings = new InMemoryThreadBindingStore(),
}) {
  assertRuntimeAdapter(runtime)
  if (typeof authenticate !== 'function') throw new TypeError('Codex Chat requires Owner auth')

  return async function handler(req, res) {
    const pathname = new URL(req.url, 'http://localhost').pathname
    if (pathname === '/api/codex/health' && req.method === 'GET') {
      return json(res, 200, {
        ok: true,
        service: 'lovehouse-codex-chat',
        runtime: runtime.getCapabilities(),
      })
    }
    if (pathname !== '/api/codex/chat') {
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
    try {
      owner = await authenticate(req.headers.authorization)
      input = normalizeBody(await readJson(req))
      const persisted = await threadBindings.get({
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
    emit('context_breakdown', createContextBreakdown({
      history: session.history,
      message: input.message,
      resumed: session.resumed,
    }))

    try {
      const result = await runtime.streamEvents({
        message: input.message,
        history: session.history,
        sessionId: runtimeSessionId,
        signal: controller.signal,
        getContinuationContext: async () => session.history,
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
        onEvent: emit,
      })
      await bindingWrite
      sessions.bind(session.key, result.sessionId)
      sessions.complete(session.key, input.message, result.text)
      emit('done', { ok: true, session_id: result.sessionId })
      if (!clientClosed) res.end()
    } catch (error) {
      if (error?.code === 'QUOTA_EXHAUSTED') {
        emit('quota', {
          status: 'exhausted',
          remaining: 0,
          unit: null,
          reset_at: null,
          source: 'codex_cli_error',
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
