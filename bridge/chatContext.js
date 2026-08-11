const MAX_MESSAGES = 30
const MAX_CHARS_PER_MESSAGE = 2000

let history = []

export function addMessage(role, content) {
  if (typeof content !== 'string' || !content.trim()) return
  history.push({
    role,
    content: content.length > MAX_CHARS_PER_MESSAGE
      ? content.slice(0, MAX_CHARS_PER_MESSAGE) + '…'
      : content,
  })
  if (history.length > MAX_MESSAGES) {
    history.splice(0, history.length - MAX_MESSAGES)
  }
}

export function buildPrompt(message) {
  if (!history.length) return message
  const ctx = history
    .map(m => `${m.role === 'user' ? '小婷' : '小克'}: ${m.content}`)
    .join('\n')
  return `[最近的对话]\n${ctx}\n\n[现在]\n小婷: ${message}`
}

export function clear() {
  history = []
}

export function getStats() {
  return { count: history.length, max: MAX_MESSAGES }
}
