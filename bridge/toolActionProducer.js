function endpointFor(identityEndpoint) {
  const url = new URL(identityEndpoint)
  url.pathname = '/internal/tool-actions/upsert'
  url.search = ''
  url.hash = ''
  return url.toString()
}

export function createToolActionProducer({ identityEndpoint, internalKey,
  endpoint = endpointFor(identityEndpoint), fetchImpl = globalThis.fetch, timeoutMs = 1_500 } = {}) {
  if (!identityEndpoint || !internalKey || typeof fetchImpl !== 'function') {
    throw new TypeError('tool-action producer configuration is incomplete')
  }
  return {
    async produce(action) {
      const controller = new AbortController()
      const timer = setTimeout(() => controller.abort(), timeoutMs)
      timer.unref?.()
      try {
        const response = await fetchImpl(endpoint, { method: 'POST', signal: controller.signal,
          headers: { 'Content-Type': 'application/json', 'X-LoveHouse-Internal-Key': internalKey },
          body: JSON.stringify(action) })
        if (!response.ok) throw Object.assign(new Error(`tool-action delivery failed: HTTP ${response.status}`),
          { code: 'TOOL_ACTION_DELIVERY_FAILED', status: response.status })
        const payload = await response.json()
        if (payload?.tool_action?.tool_action_id !== action.tool_action_id) {
          throw Object.assign(new Error('tool-action delivery returned invalid identity'),
            { code: 'TOOL_ACTION_INVALID_RESPONSE' })
        }
        return payload.tool_action
      } finally { clearTimeout(timer) }
    },
  }
}
