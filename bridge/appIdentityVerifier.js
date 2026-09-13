export function createAppIdentityVerifier({ endpoint, internalKey, fetchImpl = globalThis.fetch }) {
  if (!endpoint || !internalKey || typeof fetchImpl !== 'function') throw new TypeError('App Identity verifier configuration is incomplete')
  return {
    async verifyCredentials(email, password) {
      const response = await fetchImpl(endpoint, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', 'X-LoveHouse-Internal-Key': internalKey },
        body: JSON.stringify({ email, password }),
      })
      if (response.status === 401) return null
      if (!response.ok) throw new Error(`App Identity unavailable: HTTP ${response.status}`)
      const payload = await response.json()
      if (payload?.authenticated !== true || typeof payload.user?.id !== 'string' || typeof payload.user?.email !== 'string') {
        throw new Error('App Identity returned an invalid response')
      }
      return { id: payload.user.id, email: payload.user.email }
    },
  }
}
