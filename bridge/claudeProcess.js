import { spawn } from 'child_process'
import { addMessage, buildPrompt, clear, getStats as getContextStats } from './chatContext.js'

let active = null

export function sendMessage(message, systemPrompt, callbacks) {
  if (active) {
    callbacks.onError?.('busy')
    return false
  }

  addMessage('user', message)
  const prompt = buildPrompt(message)
  let fullResponse = ''

  const proc = spawn('/usr/bin/claude', [
    '-p', prompt,
    '--output-format', 'text',
    '--system-prompt', systemPrompt,
  ])
  active = proc

  proc.stdout.on('data', chunk => {
    const text = chunk.toString()
    fullResponse += text
    callbacks.onText?.(text)
  })

  proc.stderr.on('data', chunk => console.error('[claude stderr]', chunk.toString()))

  proc.on('close', code => {
    active = null
    if (code === 0 && fullResponse.trim()) {
      addMessage('assistant', fullResponse.trim())
    }
    if (code !== 0) callbacks.onError?.(`claude exited ${code}`)
    callbacks.onDone?.({ text: fullResponse })
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
