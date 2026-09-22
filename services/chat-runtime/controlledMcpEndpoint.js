/** Provider-neutral endpoint for the App Backend's ticket-scoped MCP executor. */
export function normalizeControlledMcpUrl(value) {
  if (!value) return null
  let target
  try { target = new URL(value) } catch { throw new TypeError('Controlled App Backend MCP URL is invalid') }
  const production = target.protocol === 'https:' && target.hostname === 'app.b612.fyi'
  const local = target.protocol === 'http:' && ['127.0.0.1', 'localhost'].includes(target.hostname)
  if ((!production && !local) || target.pathname !== '/api/mcp/runtime' ||
      target.username || target.password || target.search || target.hash) {
    throw new TypeError('Controlled App Backend MCP URL is invalid')
  }
  return target.href
}
