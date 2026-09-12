import assert from 'node:assert/strict'
import test from 'node:test'

import {
  assertOwnerAuthProvider,
  createSupabaseOwnerAuthProvider,
} from './ownerAuthProvider.js'

test('Supabase compatibility provider implements the provider-neutral Owner identity contract', async () => {
  const calls = []
  const provider = createSupabaseOwnerAuthProvider({
    baseUrl: 'https://project.example.supabase.co/',
    publishableKey: 'public-key',
    fetchImpl: async (url, options) => {
      calls.push({ url, options })
      return new Response(JSON.stringify(
        url.endsWith('/user') ? { id: 'owner-1', ignored: 'profile' } : { user: { id: 'owner-1' } },
      ), { status: 200 })
    },
  })

  assert.equal(assertOwnerAuthProvider(provider), provider)
  assert.deepEqual(await provider.verifyBearer('access-token'), { id: 'owner-1' })
  assert.deepEqual(await provider.verifyCredentials('owner@example.com', 'password'), { id: 'owner-1' })
  assert.equal(calls[0].url, 'https://project.example.supabase.co/auth/v1/user')
  assert.equal(calls[0].options.headers.Authorization, 'Bearer access-token')
  assert.equal(calls[0].options.headers.apikey, 'public-key')
  assert.equal(calls[1].url, 'https://project.example.supabase.co/auth/v1/token?grant_type=password')
  assert.deepEqual(JSON.parse(calls[1].options.body), {
    email: 'owner@example.com', password: 'password',
  })
})

test('OwnerAuthProvider fails closed for invalid, cross-owner and malformed identities', async () => {
  const create = response => createSupabaseOwnerAuthProvider({
    baseUrl: 'https://project.example.supabase.co',
    publishableKey: 'public-key',
    fetchImpl: async () => response,
  })

  assert.equal(await create(new Response('{}', { status: 401 })).verifyBearer('bad'), null)
  assert.deepEqual(await create(new Response(JSON.stringify({ id: 'other' }), { status: 200 }))
    .verifyBearer('cross-owner'), { id: 'other' })
  assert.equal(await create(new Response('{}', { status: 200 }))
    .verifyCredentials('owner@example.com', 'password'), null)
  assert.equal(await create(new Response('{}', { status: 200 })).verifyBearer(''), null)
  assert.throws(() => assertOwnerAuthProvider({ verifyBearer() {} }), /OwnerAuthProvider/)
})
