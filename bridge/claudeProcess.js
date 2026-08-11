import { spawn } from 'child_process'

let proc = null
let lineBuf = ''
let pending = null
let lastText = ''
let lastThinking = ''

function start(systemPrompt) {
  if (proc) {
    proc.kill('SIGTERM')
    proc = null
  }
  lineBuf = ''
  lastText = ''
  lastThinking = ''

  const args = [
    '--input-format', 'stream-json',
    '--output-format', 'stream-json',
    '--verbose',
  ]
  if (systemPrompt) args.push('--system-prompt', systemPrompt)

  proc = spawn('/usr/bin/claude', args, { stdio: ['pipe', 'pipe', 'pipe'] })

  proc.stdout.on('data', chunk => {
    lineBuf += chunk.toString()
    const lines = lineBuf.split('\n')
    lineBuf = lines.pop()
    for (const line of lines) {
      if (!line.trim()) continue
      try { handleEvent(JSON.parse(line)) }
      catch (e) { console.error('[claude parse]', e.message) }
    }
  })

  proc.stderr.on('data', chunk => console.error('[claude stderr]', chunk.toString()))

  proc.on('close', code => {
    console.log(`[claude process] exited code=${code}`)
    proc = null
    if (pending) {
      pending.onError?.(`claude process exited unexpectedly (code ${code})`)
      pending = null
      lastText = ''
      lastThinking = ''
    }
  })

  console.log('[claude process] started pid=' + proc.pid)
}

function handleEvent(evt) {
  if (evt.type === 'system') return
  if (!pending) return

  if (evt.type === 'assistant' && evt.message?.content) {
    let textNow = ''
    let thinkingNow = ''
    for (const block of evt.message.content) {
      if (block.type === 'text') textNow += block.text || ''
      else if (block.type === 'thinking') thinkingNow += block.thinking || ''
    }
    if (textNow.length > lastText.length) {
      pending.onText?.(textNow.slice(lastText.length))
      lastText = textNow
    }
    if (thinkingNow.length > lastThinking.length) {
      pending.onThinking?.(thinkingNow.slice(lastThinking.length))
      lastThinking = thinkingNow
    }
  }

  if (evt.type === 'result') {
    if (!lastText && evt.result) {
      pending.onText?.(evt.result)
      lastText = evt.result
    }
    pending.onDone?.({ text: lastText, cost: evt.cost_usd, duration: evt.duration_ms })
    pending = null
    lastText = ''
    lastThinking = ''
  }

  if (evt.type === 'error') {
    pending.onError?.(evt.error?.message || 'claude error')
    pending = null
    lastText = ''
    lastThinking = ''
  }
}

export function sendMessage(message, systemPrompt, callbacks) {
  if (pending) {
    callbacks.onError?.('busy')
    return false
  }

  if (!proc || proc.killed) start(systemPrompt)

  if (!proc) {
    callbacks.onError?.('failed to start claude process')
    return false
  }

  pending = callbacks
  lastText = ''
  lastThinking = ''

  const input = JSON.stringify({
    type: 'user',
    message: { role: 'user', content: message },
    session_id: 'default',
    parent_tool_use_id: null,
  }) + '\n'

  try { proc.stdin.write(input) }
  catch (e) {
    pending = null
    callbacks.onError?.(e.message)
    return false
  }

  return true
}

export function resetSession() {
  if (proc) {
    proc.kill('SIGTERM')
    proc = null
  }
  if (pending) {
    pending.onError?.('session reset')
    pending = null
  }
  lastText = ''
  lastThinking = ''
  lineBuf = ''
}

export function getStats() {
  return { alive: proc !== null && !proc.killed, busy: pending !== null, pid: proc?.pid || null }
}
