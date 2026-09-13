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
