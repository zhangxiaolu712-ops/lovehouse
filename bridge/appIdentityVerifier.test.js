import assert from 'node:assert/strict'
import test from 'node:test'
import { createAppIdentityVerifier } from './appIdentityVerifier.js'

test('App Identity verifier returns stable identity and keeps credentials in request body only', async () => {
  const calls = []
  const verifier = createAppIdentityVerifier({ endpoint: 'http://127.0.0.1:3005/internal/identity/verify-credentials', internalKey: 'internal-key', fetchImpl: async (url, init) => {
    calls.push({ url, init })
    return new Response(JSON.stringify({ authenticated: true, user: { id: 'app-user-id', email: 'owner@example.com' } }), { status: 200 })
  } })
  assert.deepEqual(await verifier.verifyCredentials('owner@example.com', 'password'), { id: 'app-user-id', email: 'owner@example.com' })
  assert.equal(calls[0].init.headers['X-LoveHouse-Internal-Key'], 'internal-key')
  assert.deepEqual(JSON.parse(calls[0].init.body), { email: 'owner@example.com', password: 'password' })
})

test('App Identity verifier maps invalid credentials to null and availability errors distinctly', async () => {
  const invalid = createAppIdentityVerifier({ endpoint: 'http://identity', internalKey: 'key', fetchImpl: async () => new Response('{}', { status: 401 }) })
  assert.equal(await invalid.verifyCredentials('unknown@example.com', 'wrong'), null)
  const unavailable = createAppIdentityVerifier({ endpoint: 'http://identity', internalKey: 'key', fetchImpl: async () => new Response('{}', { status: 503 }) })
  await assert.rejects(unavailable.verifyCredentials('owner@example.com', 'password'), /unavailable/)
})

test('App Identity verifier exchanges a session cookie through the internal contract without returning it', async () => {
  const credential = 'lovehouse_app_session=fake-session-secret'
  const calls = []
  const verifier = createAppIdentityVerifier({
    endpoint: 'http://127.0.0.1:3005/internal/identity/verify-credentials',
    internalKey: 'internal-key',
    fetchImpl: async (url, init) => {
      calls.push({ url, init })
      return new Response(JSON.stringify({
        authenticated: true,
        user: { id: 'stable-app-account-id', email: 'owner@example.com' },
      }), { status: 200 })
    },
  })

  assert.deepEqual(await verifier.verifySession(credential), {
    id: 'stable-app-account-id', email: 'owner@example.com',
  })
  assert.equal(calls[0].url, 'http://127.0.0.1:3005/internal/identity/verify-session')
  assert.equal(calls[0].init.headers.Cookie, credential)
  assert.equal(calls[0].init.headers['X-LoveHouse-Internal-Key'], 'internal-key')
  assert.equal(JSON.stringify(await verifier.verifySession(credential)).includes(credential), false)
})

test('App Identity session verification is bounded and invalid sessions return no identity', async () => {
  const invalid = createAppIdentityVerifier({
    endpoint: 'http://identity/internal/identity/verify-credentials', internalKey: 'key',
    fetchImpl: async () => new Response('{}', { status: 401 }),
  })
  assert.equal(await invalid.verifySession('lovehouse_app_session=invalid'), null)

  const timedOut = createAppIdentityVerifier({
    endpoint: 'http://identity/internal/identity/verify-credentials', internalKey: 'key', timeoutMs: 5,
    fetchImpl: async (_url, init) => new Promise((_resolve, reject) => {
      init.signal.addEventListener('abort', () => reject(Object.assign(new Error('aborted'), { name: 'AbortError' })), { once: true })
    }),
  })
  await assert.rejects(timedOut.verifySession('lovehouse_app_session=fake'), error => error.code === 'APP_IDENTITY_TIMEOUT')
})
