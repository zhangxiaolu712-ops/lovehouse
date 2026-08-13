import crypto from 'crypto'
import { spawn } from 'child_process'

import {
  assertClaudeToolPolicyMatchesBridge,
  buildClaudeChildEnv,
  buildClaudePolicyArgs,
  inspectClaudeMcpInit,
  resolveClaudeMcpUrl,
} from './claudePolicy.js'

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-8][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const SESSION_MISSING_PATTERN = /(?:no conversation found|session(?: id)?.*(?:not found|does not exist|missing|invalid)|failed to (?:load|resume).*(?:session|conversation)|unable to resume)/i

export const RECENT_HISTORY_LIMITS = Object.freeze({
  messages: 30,
  messageCharacters: 2_000,
  totalCharacters: 20_000,
  totalBytes: 32_000,
})

export function isValidWindowId(value) {
  return typeof value === 'string' && UUID_PATTERN.test(value)
}

function characterLength(value) {
  return [...value].length
}

export function normalizeRecentHistory(value, currentMessage = '') {
  if (value === undefined || value === null) return []
  if (!Array.isArray(value)) throw new Error('recent_history must be an array')
  if (value.length > RECENT_HISTORY_LIMITS.messages) {
    throw new Error(`recent_history cannot exceed ${RECENT_HISTORY_LIMITS.messages} messages`)
  }

  let totalCharacters = 0
  let totalBytes = 0
  const normalized = value.map((entry, index) => {
    if (!entry || typeof entry !== 'object') {
      throw new Error(`recent_history[${index}] must be an object`)
    }
    if (entry.role !== 'user' && entry.role !== 'assistant') {
      throw new Error(`recent_history[${index}] has an invalid role`)
    }
    if (typeof entry.content !== 'string' || !entry.content.trim()) {
      throw new Error(`recent_history[${index}] requires text content`)
    }
    const content = entry.content.trim()
    const characters = characterLength(content)
    if (characters > RECENT_HISTORY_LIMITS.messageCharacters) {
      throw new Error(`recent_history[${index}] content is too long`)
    }
    totalCharacters += characters
    totalBytes += Buffer.byteLength(content, 'utf8')
    return { role: entry.role, content }
  })

  if (totalCharacters > RECENT_HISTORY_LIMITS.totalCharacters) {
    throw new Error('recent_history exceeds the total character limit')
  }
  if (totalBytes > RECENT_HISTORY_LIMITS.totalBytes) {
    throw new Error('recent_history exceeds the total byte limit')
  }
  const last = normalized.at(-1)
  if (last?.role === 'user' && last.content === currentMessage.trim()) {
    throw new Error('recent_history must not repeat the current message')
  }
  return normalized
}

function buildFallbackPrompt(message, recentHistory) {
  if (!recentHistory.length) return message
  const transcript = recentHistory
    .map(entry => `<message role="${entry.role}">\n${entry.content}\n</message>`)
    .join('\n')
  return [
    '<recent_history_bootstrap>',
    'The following bounded transcript was supplied by the current LoveHouse window only for one-time session recovery context.',
    transcript,
    '</recent_history_bootstrap>',
    '<current_user_message>',
    message,
    '</current_user_message>',
  ].join('\n')
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

  const mcpInit = inspectClaudeMcpInit(event)
  if (mcpInit) {
    state.mcpInitialized = true
    state.mcpReady = mcpInit.ready
    if (!mcpInit.ready) state.mcpFailure = mcpInit.error
    return
  }

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
    if (state.mcpReady) state.callbacks.onText?.(delta.text)
  }
  if (delta?.type === 'thinking_delta' && typeof delta.thinking === 'string') {
    if (state.mcpReady) state.callbacks.onThinking?.(delta.thinking)
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
  sourceEnv = process.env,
  mcpUrl = resolveClaudeMcpUrl(sourceEnv),
} = {}) {
  assertClaudeToolPolicyMatchesBridge()
  const childEnv = buildClaudeChildEnv(sourceEnv)
  const policyArgs = buildClaudePolicyArgs({ mcpUrl })
  const windows = new Map()
  const resetWindows = new Set()

  function createWindow(windowId, sessionId = createSessionId(), resumeNext = false) {
    const state = {
      sessionId,
      resumeNext,
      active: null,
      turns: 0,
      fallbackCount: 0,
      lastUsage: {},
      lastActive: Date.now(),
    }
    windows.set(windowId, state)
    return state
  }

  function spawnAttempt(
    windowId,
    windowState,
    message,
    systemPrompt,
    callbacks,
    resume,
    { mode, fallback = null, recentHistory = [] },
  ) {
    const prompt = fallback ? buildFallbackPrompt(message, recentHistory) : message
    const args = [
      '-p', prompt,
      '--output-format', 'stream-json',
      '--include-partial-messages',
      '--verbose',
      '--system-prompt', systemPrompt,
      ...policyArgs,
    ]
    if (resume) args.push('--resume', windowState.sessionId)
    else args.push('--session-id', windowState.sessionId)

    callbacks.onSession?.({
      session_id: windowState.sessionId,
      mode,
      fallback: Boolean(fallback),
      ...(fallback ? { fallback_reason: fallback.reason } : {}),
      ...(fallback ? { history_bootstrapped: recentHistory.length > 0 } : {}),
    })

    const proc = spawnProcess(claudePath, args, { env: childEnv })
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
      mcpInitialized: false,
      mcpReady: false,
      mcpFailure: null,
      finished: false,
    }

    proc.stdout.on('data', chunk => {
      consumeJsonLines(attempt, chunk.toString())
      if (attempt.mcpFailure && !attempt.finished) {
        finish(null, new Error(attempt.mcpFailure))
        proc.kill()
      }
    })
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
        windowState.resumeNext = false
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
          {
            mode: 'fallback_new_session',
            fallback: { previousSessionId, reason: 'session_not_found' },
            recentHistory,
          },
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
      if (!attempt.mcpInitialized || !attempt.mcpReady) {
        callbacks.onError?.('LoveHouse MCP initialization was not confirmed')
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
      windowState.resumeNext = true
      windowState.lastActive = Date.now()
      windowState.lastUsage = normalizeUsage(attempt.result, attempt.assistantUsage)
      callbacks.onDone?.({
        text: attempt.fullText,
        session_id: windowState.sessionId,
        usage: windowState.lastUsage,
        fallback: Boolean(fallback),
        session_mode: mode,
      })
    }

    proc.on('error', error => finish(null, error))
    proc.on('close', code => finish(code))
  }

  function sendMessage(
    windowId,
    message,
    systemPrompt,
    callbacks = {},
    { knownSessionId = null, recentHistory = [], sessionIntent = 'new' } = {},
  ) {
    if (!isValidWindowId(windowId)) {
      callbacks.onError?.('invalid window_id')
      return false
    }
    const existing = windows.get(windowId)
    if (existing?.active) {
      callbacks.onError?.('busy')
      return false
    }
    if (sessionIntent !== 'new' && sessionIntent !== 'continue') {
      callbacks.onError?.('invalid session intent')
      return false
    }
    let normalizedHistory
    try {
      normalizedHistory = normalizeRecentHistory(recentHistory, message)
    } catch (error) {
      callbacks.onError?.(error.message)
      return false
    }

    const resetPending = resetWindows.delete(windowId)
    if (existing) {
      spawnAttempt(
        windowId,
        existing,
        message,
        systemPrompt,
        callbacks,
        existing.resumeNext,
        { mode: existing.resumeNext ? 'resumed_session' : 'new_session' },
      )
      return true
    }

    if (sessionIntent === 'continue' && !resetPending && isValidWindowId(knownSessionId)) {
      const recoveredState = createWindow(windowId, knownSessionId, true)
      spawnAttempt(
        windowId,
        recoveredState,
        message,
        systemPrompt,
        callbacks,
        true,
        { mode: 'resumed_known_session', recentHistory: normalizedHistory },
      )
      return true
    }

    const windowState = createWindow(windowId)
    let fallback = null
    if (sessionIntent === 'continue' && !resetPending) {
      const knownProvided = knownSessionId !== undefined
        && knownSessionId !== null
        && knownSessionId !== ''
      fallback = {
        previousSessionId: knownProvided ? knownSessionId : null,
        reason: knownProvided ? 'known_session_invalid' : 'known_session_missing',
      }
      windowState.fallbackCount += 1
      const loggedSession = knownProvided ? 'invalid' : 'missing'
      logger.error?.(
        `[claude session fallback] window=${shortId(windowId)} session=${loggedSession} reason=${fallback.reason}`
      )
    }
    spawnAttempt(
      windowId,
      windowState,
      message,
      systemPrompt,
      callbacks,
      false,
      {
        mode: fallback ? 'fallback_new_session' : 'new_session',
        fallback,
        recentHistory: normalizedHistory,
      },
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
    resetWindows.add(windowId)
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
