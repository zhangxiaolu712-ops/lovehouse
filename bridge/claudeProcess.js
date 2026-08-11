import crypto from 'crypto'
import { spawn } from 'child_process'

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const SESSION_MISSING_PATTERN = /(?:no conversation found|session(?: id)?.*(?:not found|does not exist|missing|invalid)|failed to (?:load|resume).*(?:session|conversation)|unable to resume)/i

export function isValidWindowId(value) {
  return typeof value === 'string' && UUID_PATTERN.test(value)
}

function shortId(value) {
  return typeof value === 'string' ? value.slice(0, 8) : 'unknown'
}

function resultFailureText(result) {
  if (!result || typeof result !== 'object') return ''
  return [result.subtype, result.result, result.error, result.error_message]
    .filter(value => typeof value === 'string')
    .join(' ')
}

function isMissingSessionFailure(stderr, result) {
  return SESSION_MISSING_PATTERN.test(`${stderr}\n${resultFailureText(result)}`)
}

function normalizeUsage(result, assistantUsage) {
  const usage = result?.usage && typeof result.usage === 'object'
    ? result.usage
    : assistantUsage
  return {
    ...(usage && typeof usage === 'object' ? usage : {}),
    ...(Number.isFinite(result?.total_cost_usd) ? { total_cost_usd: result.total_cost_usd } : {}),
    ...(Number.isFinite(result?.num_turns) ? { num_turns: result.num_turns } : {}),
  }
}

function parseStreamEvent(event, state) {
  if (!event || typeof event !== 'object') return
  if (typeof event.session_id === 'string') state.reportedSessionId = event.session_id

  if (event.type === 'assistant' && event.message?.usage) {
    state.assistantUsage = event.message.usage
  }
  if (event.type === 'result') {
    state.result = event
    return
  }
  if (event.type !== 'stream_event' || event.event?.type !== 'content_block_delta') return

  const delta = event.event.delta
  if (delta?.type === 'text_delta' && typeof delta.text === 'string') {
    state.fullText += delta.text
    state.callbacks.onText?.(delta.text)
  }
  if (delta?.type === 'thinking_delta' && typeof delta.thinking === 'string') {
    state.callbacks.onThinking?.(delta.thinking)
  }
}

function consumeJsonLines(state, chunk, flush = false) {
  state.stdoutBuffer += chunk
  const lines = state.stdoutBuffer.split('\n')
  const trailing = lines.pop()
  if (flush) {
    state.stdoutBuffer = ''
    if (trailing) lines.push(trailing)
  } else {
    state.stdoutBuffer = trailing
  }
  for (const line of lines) {
    if (!line.trim()) continue
    try {
      parseStreamEvent(JSON.parse(line), state)
    } catch {
      state.parseErrors += 1
    }
  }
}

export function createClaudeSessionManager({
  spawnProcess = spawn,
  createSessionId = () => crypto.randomUUID(),
  claudePath = '/usr/bin/claude',
  logger = console,
} = {}) {
  const windows = new Map()

  function getOrCreateWindow(windowId) {
    let state = windows.get(windowId)
    if (!state) {
      state = {
        sessionId: createSessionId(),
        active: null,
        turns: 0,
        fallbackCount: 0,
        lastUsage: {},
        lastActive: Date.now(),
      }
      windows.set(windowId, state)
    }
    return state
  }

  function spawnAttempt(windowId, windowState, message, systemPrompt, callbacks, resume, fallback = null) {
    const args = [
      '-p', message,
      '--output-format', 'stream-json',
      '--include-partial-messages',
      '--verbose',
      '--system-prompt', systemPrompt,
    ]
    if (resume) args.push('--resume', windowState.sessionId)
    else args.push('--session-id', windowState.sessionId)

    callbacks.onSession?.({
      session_id: windowState.sessionId,
      mode: resume ? 'resumed' : 'created',
      fallback: Boolean(fallback),
      ...(fallback ? { fallback_reason: fallback.reason } : {}),
    })

    const proc = spawnProcess(claudePath, args)
    windowState.active = proc
    windowState.lastActive = Date.now()
    const attempt = {
      callbacks,
      stdoutBuffer: '',
      stderr: '',
      fullText: '',
      result: null,
      reportedSessionId: null,
      assistantUsage: null,
      parseErrors: 0,
      finished: false,
    }

    proc.stdout.on('data', chunk => consumeJsonLines(attempt, chunk.toString()))
    proc.stderr.on('data', chunk => { attempt.stderr += chunk.toString() })

    const finish = (code, spawnError = null) => {
      if (attempt.finished) return
      attempt.finished = true
      consumeJsonLines(attempt, '', true)
      if (windowState.active === proc) windowState.active = null

      const resultFailed = attempt.result?.is_error === true
        || (attempt.result?.subtype && attempt.result.subtype !== 'success')
      const failed = Boolean(spawnError) || code !== 0 || resultFailed
      if (resume && failed && !attempt.fullText && isMissingSessionFailure(attempt.stderr, attempt.result)) {
        const previousSessionId = windowState.sessionId
        windowState.sessionId = createSessionId()
        windowState.fallbackCount += 1
        logger.error?.(
          `[claude session fallback] window=${shortId(windowId)} session=${shortId(previousSessionId)} reason=session_not_found`
        )
        return spawnAttempt(
          windowId,
          windowState,
          message,
          systemPrompt,
          callbacks,
          false,
          { previousSessionId, reason: 'session_not_found' },
        )
      }

      if (failed) {
        const detail = spawnError?.message
          || resultFailureText(attempt.result)
          || attempt.stderr.trim()
          || `claude exited ${code}`
        callbacks.onError?.(detail)
        return
      }
      if (attempt.parseErrors && !attempt.result) {
        callbacks.onError?.('Claude stream output was not valid JSON')
        return
      }
      if (attempt.reportedSessionId && attempt.reportedSessionId !== windowState.sessionId) {
        callbacks.onError?.('Claude returned a mismatched session_id')
        return
      }

      if (!attempt.fullText && typeof attempt.result?.result === 'string') {
        attempt.fullText = attempt.result.result
        if (attempt.fullText) callbacks.onText?.(attempt.fullText)
      }
      windowState.turns += 1
      windowState.lastActive = Date.now()
      windowState.lastUsage = normalizeUsage(attempt.result, attempt.assistantUsage)
      callbacks.onDone?.({
        text: attempt.fullText,
        session_id: windowState.sessionId,
        usage: windowState.lastUsage,
        fallback: Boolean(fallback),
      })
    }

    proc.on('error', error => finish(null, error))
    proc.on('close', code => finish(code))
  }

  function sendMessage(windowId, message, systemPrompt, callbacks = {}, { knownSessionId = null } = {}) {
    if (!isValidWindowId(windowId)) {
      callbacks.onError?.('invalid window_id')
      return false
    }
    const existing = windows.get(windowId)
    if (existing?.active) {
      callbacks.onError?.('busy')
      return false
    }
    const windowState = getOrCreateWindow(windowId)
    let fallback = null
    if (!existing && isValidWindowId(knownSessionId)) {
      fallback = { previousSessionId: knownSessionId, reason: 'bridge_state_lost' }
      windowState.fallbackCount += 1
      logger.error?.(
        `[claude session fallback] window=${shortId(windowId)} session=${shortId(knownSessionId)} reason=bridge_state_lost`
      )
    }
    spawnAttempt(
      windowId,
      windowState,
      message,
      systemPrompt,
      callbacks,
      windowState.turns > 0,
      fallback,
    )
    return true
  }

  function abortWindow(windowId) {
    const state = windows.get(windowId)
    if (!state?.active) return false
    const proc = state.active
    state.active = null
    proc.kill()
    return true
  }

  function resetSession(windowId) {
    if (!isValidWindowId(windowId)) return false
    const existed = windows.has(windowId)
    abortWindow(windowId)
    windows.delete(windowId)
    return existed
  }

  function getStats() {
    const states = [...windows.values()]
    return {
      windows: states.length,
      busy: states.filter(state => state.active).length,
      turns: states.reduce((total, state) => total + state.turns, 0),
      fallbacks: states.reduce((total, state) => total + state.fallbackCount, 0),
    }
  }

  return { sendMessage, abortWindow, resetSession, getStats }
}

const defaultManager = createClaudeSessionManager()

export const sendMessage = defaultManager.sendMessage
export const abortWindow = defaultManager.abortWindow
export const resetSession = defaultManager.resetSession
export const getStats = defaultManager.getStats
