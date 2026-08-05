const BRIDGE = 'http://139.180.146.26:3000'

export default {
  async fetch(request, env, ctx) {
    const url = new URL(request.url)

    if (url.pathname.startsWith('/api/')) {
      const target = BRIDGE + url.pathname.replace('/api', '')
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
      respHeaders.set('Access-Control-Allow-Headers', 'Content-Type')

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
          'Access-Control-Allow-Headers': 'Content-Type',
        },
      })
    }

    return env.ASSETS.fetch(request)
  },
}
