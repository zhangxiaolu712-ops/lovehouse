function stableOwnerIdentity(payload) {
  const id = payload?.id || payload?.user?.id
  return typeof id === 'string' && id ? Object.freeze({ id }) : null
}

export function assertOwnerAuthProvider(provider) {
  if (!provider
    || typeof provider.verifyBearer !== 'function'
    || typeof provider.verifyCredentials !== 'function') {
    throw new TypeError('OwnerAuthProvider must implement verifyBearer and verifyCredentials')
  }
  return provider
}

/**
 * Compatibility provider for the current Supabase sessions. Consumers depend
 * only on OwnerAuthProvider; this is the sole server-side Supabase Auth edge.
 */
export function createSupabaseOwnerAuthProvider({
  baseUrl,
  publishableKey,
  fetchImpl = (...args) => globalThis.fetch(...args),
}) {
  const normalizedBaseUrl = String(baseUrl || '').replace(/\/+$/, '')
  if (!normalizedBaseUrl.startsWith('https://')
    || !publishableKey
    || typeof fetchImpl !== 'function') {
    throw new TypeError('Supabase OwnerAuthProvider is not configured')
  }

  return Object.freeze({
    async verifyBearer(token) {
      if (typeof token !== 'string' || !token) return null
      const response = await fetchImpl(`${normalizedBaseUrl}/auth/v1/user`, {
        headers: {
          Authorization: `Bearer ${token}`,
          apikey: publishableKey,
        },
      })
      if (!response.ok) return null
      return stableOwnerIdentity(await response.json())
    },

    async verifyCredentials(email, password) {
      if (typeof email !== 'string' || typeof password !== 'string' || !email || !password) return null
      const response = await fetchImpl(`${normalizedBaseUrl}/auth/v1/token?grant_type=password`, {
        method: 'POST',
        headers: {
          apikey: publishableKey,
          'Content-Type': 'application/json',
        },
        body: JSON.stringify({ email, password }),
      })
      if (!response.ok) return null
      return stableOwnerIdentity(await response.json())
    },
  })
}
