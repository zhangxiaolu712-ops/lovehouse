import { ChatRuntimeError } from './errors.js'

export function createSupabaseOwnerAuth({ supabaseUrl, anonKey, ownerUserId, fetchImpl = globalThis.fetch }) {
  if (!supabaseUrl || !anonKey || !ownerUserId || typeof fetchImpl !== 'function') {
    throw new TypeError('Codex sidecar Owner auth is not configured')
  }
  const endpoint = `${supabaseUrl.replace(/\/+$/, '')}/auth/v1/user`
  return async function authenticate(authorization) {
    if (typeof authorization !== 'string' || !authorization.startsWith('Bearer ')) {
      throw new ChatRuntimeError('AUTH_FAILED', 'Owner bearer token required', {
        stage: 'auth', status: 401,
      })
    }
    let response
    try {
      response = await fetchImpl(endpoint, {
        headers: { apikey: anonKey, Authorization: authorization },
      })
    } catch (cause) {
      throw new ChatRuntimeError('AUTH_FAILED', 'Owner authentication is unavailable', {
        stage: 'auth', status: 503, retryable: true, cause,
      })
    }
    if (!response.ok) {
      throw new ChatRuntimeError('AUTH_FAILED', 'Owner bearer token is invalid', {
        stage: 'auth', status: 401,
      })
    }
    const user = await response.json()
    if (user?.id !== ownerUserId) {
      throw new ChatRuntimeError('AUTH_FAILED', 'Owner identity does not match', {
        stage: 'auth', status: 403,
      })
    }
    return { userId: user.id }
  }
}
