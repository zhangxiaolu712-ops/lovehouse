import test from 'node:test'
import assert from 'node:assert/strict'
import crypto from 'crypto'

import {
  hashPkceVerifier,
  issueAccessToken,
  validatePkce,
  validateRedirectUri,
  validateRedirectUris,
  verifyAccessToken,
} from './security.js'

test('redirect URI validation allows HTTPS and loopback HTTP only', () => {
  assert.equal(validateRedirectUri('https://claude.ai/api/mcp/callback'), true)
  assert.equal(validateRedirectUri('http://127.0.0.1:3000/callback'), true)
  assert.equal(validateRedirectUri('http://localhost:3000/callback'), true)
  assert.equal(validateRedirectUri('http://example.com/callback'), false)
  assert.equal(validateRedirectUri('javascript:alert(1)'), false)
  assert.equal(validateRedirectUris(['https://example.com/callback']), true)
  assert.equal(validateRedirectUris([]), false)
})

test('PKCE requires S256 and a valid verifier', () => {
  const verifier = crypto.randomBytes(48).toString('base64url')
  const challenge = hashPkceVerifier(verifier)
  assert.equal(validatePkce(challenge, 'S256'), true)
  assert.equal(validatePkce(challenge, 'plain'), false)
  assert.equal(hashPkceVerifier('short'), null)
})

test('signed access token checks signature, owner and audience data', () => {
  const secret = 'a-secure-test-secret-that-is-long-enough'
  const token = issueAccessToken({
    clientId: 'client-1',
    ownerUserId: 'owner-1',
    audience: 'https://example.com/api/mcp/claude',
    secret,
    ttlSeconds: 60,
  })
  const payload = verifyAccessToken(token, secret, 'https://example.com/api/mcp/claude')
  assert.equal(payload.sub, 'owner-1')
  assert.equal(payload.client_id, 'client-1')
  assert.equal(verifyAccessToken(token, secret, 'https://wrong.example'), null)
  assert.equal(verifyAccessToken(`${token}x`, secret, 'https://example.com/api/mcp/claude'), null)
})

test('token signing rejects weak secrets', () => {
  assert.throws(() => issueAccessToken({
    clientId: 'client',
    ownerUserId: 'owner',
    audience: 'audience',
    secret: 'short',
  }))
})
