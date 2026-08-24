import assert from 'node:assert/strict'
import test from 'node:test'

import { createSupabaseOwnerAuth } from './supabaseOwnerAuth.js'

test('sidecar revalidates the current Owner bearer without exposing server credentials', async () => {
  let request
  const authenticate = createSupabaseOwnerAuth({
    supabaseUrl: 'https://project.supabase.co',
    anonKey: 'public-key',
    ownerUserId: 'owner-id',
    fetchImpl: async (url, options) => {
      request = { url, options }
      return new Response(JSON.stringify({ id: 'owner-id' }), { status: 200 })
    },
  })
  assert.deepEqual(await authenticate('Bearer owner-jwt'), { userId: 'owner-id' })
  assert.equal(request.url, 'https://project.supabase.co/auth/v1/user')
  assert.equal(request.options.headers.apikey, 'public-key')
  assert.equal(request.options.headers.Authorization, 'Bearer owner-jwt')
})

test('missing, invalid and cross-owner identities fail before runtime execution', async () => {
  const create = response => createSupabaseOwnerAuth({
    supabaseUrl: 'https://project.supabase.co', anonKey: 'public-key', ownerUserId: 'owner-id',
    fetchImpl: async () => response,
  })
  await assert.rejects(create(new Response('{}', { status: 200 }))(null), error => error.code === 'AUTH_FAILED')
  await assert.rejects(
    create(new Response('{}', { status: 401 }))('Bearer bad'),
    error => error.code === 'AUTH_FAILED' && error.status === 401,
  )
  await assert.rejects(
    create(new Response(JSON.stringify({ id: 'other' }), { status: 200 }))('Bearer other'),
    error => error.code === 'AUTH_FAILED' && error.status === 403,
  )
})
