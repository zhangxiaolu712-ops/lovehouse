export const CODEX_VPS_ROUTE = Object.freeze({
  mention: 'codex',
  agent: 'codex',
  runtime: 'vps-cli',
  endpoint: 'codex-vps-primary',
})

export function createMentionRouter(routes) {
  const configured = routes.map(route => ({
    ...route,
    pattern: new RegExp(`(^|\\s)@${route.mention}(?=\\s|[，。！？,:;]|$)`, 'i'),
  }))
  return function routeLivingroomMessage(message) {
    if (typeof message !== 'string') return null
    const route = configured.find(candidate => candidate.pattern.test(message))
    if (!route) return null
    const prompt = message.replace(route.pattern, ' ').trim()
    const { pattern, mention, ...target } = route
    return { ...target, prompt: prompt || message.trim() }
  }
}

export const routeLivingroomMessage = createMentionRouter([CODEX_VPS_ROUTE])
