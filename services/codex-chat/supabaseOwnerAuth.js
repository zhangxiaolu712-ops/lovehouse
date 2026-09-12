import { ChatRuntimeError } from './errors.js'
import {
  assertOwnerAuthProvider,
  createSupabaseOwnerAuthProvider,
} from '../../bridge/auth/ownerAuthProvider.js'

export function createOwnerAuthenticator({ ownerAuthProvider, ownerUserId }) {
  const provider = assertOwnerAuthProvider(ownerAuthProvider)
  if (!ownerUserId) throw new TypeError('A fixed Owner id is required')
  return async function authenticate(authorization) {
    if (typeof authorization !== 'string' || !authorization.startsWith('Bearer ')) {
      throw new ChatRuntimeError('AUTH_FAILED', 'Owner bearer token required', {
        stage: 'auth', status: 401,
      })
    }
    let response
    try {
      const identity = await provider.verifyBearer(authorization.slice(7))
      if (!identity) {
        throw new ChatRuntimeError('AUTH_FAILED', 'Owner bearer token is invalid', {
          stage: 'auth', status: 401,
        })
      }
      if (identity.id !== ownerUserId) {
        throw new ChatRuntimeError('AUTH_FAILED', 'Owner identity does not match', {
          stage: 'auth', status: 403,
        })
      }
      return { userId: identity.id }
    } catch (cause) {
      if (cause instanceof ChatRuntimeError) throw cause
      throw new ChatRuntimeError('AUTH_FAILED', 'Owner authentication is unavailable', {
        stage: 'auth', status: 503, retryable: true, cause,
      })
    }
  }
}

/** Backward-compatible construction for callers not yet on provider wiring. */
export function createSupabaseOwnerAuth({ supabaseUrl, anonKey, ownerUserId, fetchImpl }) {
  return createOwnerAuthenticator({
    ownerAuthProvider: createSupabaseOwnerAuthProvider({
      baseUrl: supabaseUrl,
      publishableKey: anonKey,
      ...(fetchImpl ? { fetchImpl } : {}),
    }),
    ownerUserId,
  })
}
