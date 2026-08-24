const BRIDGE = 'http://139.180.146.26:3000'
const CODEX_BRIDGE = 'https://tingtunehouse.duckdns.org'

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url)

    if (url.pathname.startsWith('/api/')) {
      const isCodex = url.pathname.startsWith('/api/codex/')
      const target = isCodex
        ? CODEX_BRIDGE + url.pathname
        : BRIDGE + url.pathname.replace('/api', '')
      const headers = new Headers(request.headers)
      headers.delete('host')

      const res = await fetch(target, {
        method: request.method,
        headers,
        body: request.method !== 'GET' ? request.body : undefined,
        redirect: 'follow',
      })

      const respHeaders = new Headers(res.headers)
      respHeaders.set('Access-Control-Allow-Origin', '*')
      respHeaders.set('Access-Control-Allow-Methods', 'GET, POST, OPTIONS')
      respHeaders.set('Access-Control-Allow-Headers', 'Content-Type, Authorization')

      return new Response(res.body, {
        status: res.status,
        headers: respHeaders,
      })
    }

    if (request.method === 'OPTIONS' && url.pathname.startsWith('/api/')) {
      return new Response(null, {
        headers: {
          'Access-Control-Allow-Origin': '*',
          'Access-Control-Allow-Methods': 'GET, POST, OPTIONS',
          'Access-Control-Allow-Headers': 'Content-Type, Authorization',
        },
      })
    }

    return env.ASSETS.fetch(request)
  },
}
