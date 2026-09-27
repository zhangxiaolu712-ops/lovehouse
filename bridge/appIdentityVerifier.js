function siblingSessionEndpoint(endpoint) {
  const url = new URL(endpoint)
  url.pathname = url.pathname.endsWith('/verify-credentials')
    ? `${url.pathname.slice(0, -'/verify-credentials'.length)}/verify-session`
    : '/internal/identity/verify-session'
  return url.toString()
}

function verifiedUser(payload) {
  if (payload?.authenticated !== true || typeof payload.user?.id !== 'string' || !payload.user.id ||
    payload.user.id.length > 128 || typeof payload.user?.email !== 'string') {
    throw new Error('App Identity returned an invalid response')
  }
  return { id: payload.user.id, email: payload.user.email }
}

export function createAppIdentityVerifier({
  endpoint,
  internalKey,
  fetchImpl = globalThis.fetch,
  sessionEndpoint = siblingSessionEndpoint(endpoint),
  timeoutMs = 1_500,
}) {
  if (!endpoint || !internalKey || typeof fetchImpl !== 'function') throw new TypeError('App Identity verifier configuration is incomplete')
  if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) throw new TypeError('App Identity verifier timeout must be positive')
  return {
    async verifyCredentials(email, password) {
      const response = await fetchImpl(endpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-LoveHouse-Internal-Key': internalKey },
        body: JSON.stringify({ email, password }),
      })
      if (response.status === 401) return null
      if (!response.ok) throw new Error(`App Identity unavailable: HTTP ${response.status}`)
      return verifiedUser(await response.json())
    },
    async verifySession(cookieHeader) {
      if (typeof cookieHeader !== 'string' || !cookieHeader) return null
      const controller = new AbortController()
      let rejectTimeout
      const timedOut = new Promise((_resolve, reject) => { rejectTimeout = reject })
      const timeout = setTimeout(() => {
        controller.abort()
        rejectTimeout(Object.assign(new Error('App Identity verification timed out'), { code: 'APP_IDENTITY_TIMEOUT' }))
      }, timeoutMs)
      timeout.unref?.()
      try {
        const response = await Promise.race([
          fetchImpl(sessionEndpoint, {
            method: 'POST',
            headers: { Cookie: cookieHeader, 'X-LoveHouse-Internal-Key': internalKey },
            signal: controller.signal,
          }),
          timedOut,
        ])
        if (response.status === 401) return null
        if (!response.ok) throw new Error(`App Identity unavailable: HTTP ${response.status}`)
        return verifiedUser(await response.json())
      } catch (error) {
        if (controller.signal.aborted) {
          throw Object.assign(new Error('App Identity verification timed out'), { code: 'APP_IDENTITY_TIMEOUT' })
        }
        throw error
      } finally {
        clearTimeout(timeout)
      }
    },
  }
}
