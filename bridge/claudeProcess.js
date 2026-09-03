import { spawn } from 'child_process'
import { addMessage, buildPrompt, clear, getStats as getContextStats } from './chatContext.js'

let active = null

export function createStreamParser(callbacks) {
  let buf = ''
  let blockType = null
  let fullText = ''

  function processLine(line) {
    const trimmed = line.trim()
    if (!trimmed) return

    let obj
    try { obj = JSON.parse(trimmed) } catch { return }

    if (obj.type !== 'stream_event') return
    const evt = obj.event
    if (!evt) return

    try {
      if (evt.type === 'content_block_start') {
        blockType = evt.content_block?.type || null
      } else if (evt.type === 'content_block_delta') {
        if (blockType === 'thinking' && evt.delta?.thinking != null) {
          callbacks.onThinking?.(evt.delta.thinking)
        } else if (blockType === 'text' && evt.delta?.text != null) {
          fullText += evt.delta.text
          callbacks.onText?.(evt.delta.text)
        }
      } else if (evt.type === 'content_block_stop') {
        blockType = null
      }
    } catch { /* parser fault must not kill the stream */ }
  }

  function feed(data) {
    buf += data
    const lines = buf.split('\n')
    buf = lines.pop()
    for (const line of lines) processLine(line)
  }

  function flush() {
    if (buf.trim()) processLine(buf)
    buf = ''
  }

  return { feed, flush, getText: () => fullText }
}

export function sendMessage(message, systemPrompt, callbacks) {
  if (active) {
    callbacks.onError?.('busy')
    return false
  }

  addMessage('user', message)
  const prompt = buildPrompt(message)

  const proc = spawn('/usr/bin/claude', [
    '-p', prompt,
    '--output-format', 'stream-json',
    '--verbose',
    '--system-prompt', systemPrompt,
  ])
  active = proc

  const parser = createStreamParser(callbacks)

  proc.stdout.on('data', chunk => {
    parser.feed(chunk.toString())
  })

  proc.stderr.on('data', chunk => console.error('[claude stderr]', chunk.toString()))

  proc.on('close', code => {
    active = null
    parser.flush()
    const text = parser.getText()
    if (code === 0 && text.trim()) {
      addMessage('assistant', text.trim())
    }
    if (code !== 0) callbacks.onError?.(`claude exited ${code}`)
    callbacks.onDone?.({ text })
  })

  return true
}

export function abortActive() {
  if (active) { active.kill(); active = null }
}

export function resetSession() {
  abortActive()
  clear()
}

export function getStats() {
  return { ...getContextStats(), busy: active !== null }
}
