import test from 'node:test'
import assert from 'node:assert/strict'

import express from 'express'

import { installClaudeOAuth } from './oauth.js'

const oauthBase = 'https://tingtunehouse.example'
const resource = `${oauthBase}/api/mcp/claude`
const resourceMetadataUrl = `${oauthBase}/api/.well-known/oauth-protected-resource/mcp/claude`
const tokenSecret = 'test-oauth-signing-secret-that-is-long-enough'

function installTestOAuth(app, overrides = {}) {
  return installClaudeOAuth(app, {
    oauthBase,
    resource,
    resourceMetadataUrl,
    supabaseUrl: 'https://project.example.supabase.co',
    supabaseAnonKey: 'test-anon-key',
    ownerUserId: 'owner-1',
    tokenSecret,
    checkRate: () => true,
    ...overrides,
  })
}

async function createServer(t) {
  const app = express()
  app.use(express.json())
  app.use(express.urlencoded({ extended: false }))
  const verifyOAuth = installTestOAuth(app)
  app.get('/mcp/claude', verifyOAuth, (_req, res) => res.json({ ok: true }))
  const server = await new Promise(resolve => {
    const listening = app.listen(0, '127.0.0.1', () => resolve(listening))
  })
  t.after(() => new Promise(resolve => server.close(resolve)))
  return `http://127.0.0.1:${server.address().port}`
}

test('Claude OAuth points 401 discovery at the existing proxied metadata URL', async t => {
  const base = await createServer(t)
  const response = await fetch(`${base}/mcp/claude`)

  assert.equal(response.status, 401)
  assert.equal(
    response.headers.get('www-authenticate'),
    `Bearer resource_metadata="${resourceMetadataUrl}", scope="mcp:tools"`,
  )
  assert.deepEqual(await response.json(), { error: 'unauthorized' })

  const invalid = await fetch(`${base}/mcp/claude`, {
    headers: { Authorization: 'Bearer invalid-token' },
  })
  assert.equal(invalid.status, 401)
  assert.equal(
    invalid.headers.get('www-authenticate'),
    `Bearer error="invalid_token", resource_metadata="${resourceMetadataUrl}", scope="mcp:tools"`,
  )
  assert.deepEqual(await invalid.json(), { error: 'invalid_token' })

  const metadata = await fetch(`${base}/.well-known/oauth-protected-resource/mcp/claude`)
  assert.equal(metadata.status, 200)
  assert.deepEqual(await metadata.json(), {
    resource,
    authorization_servers: [oauthBase],
    bearer_methods_supported: ['header'],
    scopes_supported: ['mcp:tools'],
  })
})

test('dynamic registration accepts the hosted Claude public client contract', async t => {
  const base = await createServer(t)
  const response = await fetch(`${base}/oauth/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      client_name: 'Claude',
      redirect_uris: ['https://claude.ai/api/mcp/auth_callback'],
      grant_types: ['authorization_code'],
      response_types: ['code'],
      application_type: 'web',
      token_endpoint_auth_method: 'none',
    }),
  })
  const registration = await response.json()

  assert.equal(response.status, 201)
  assert.match(registration.client_id, /^lh_[a-f0-9]{32}$/)
  assert.equal(registration.client_name, 'Claude')
  assert.deepEqual(registration.redirect_uris, ['https://claude.ai/api/mcp/auth_callback'])
  assert.deepEqual(registration.grant_types, ['authorization_code'])
  assert.deepEqual(registration.response_types, ['code'])
  assert.equal(registration.token_endpoint_auth_method, 'none')
  assert.equal('client_secret' in registration, false)
})

test('OAuth refuses to advertise a token service without a strong signing secret', () => {
  const app = express()
  assert.throws(
    () => installTestOAuth(app, { tokenSecret: '' }),
    /OAUTH_TOKEN_SECRET must contain at least 32 characters/,
  )
})

test('OAuth refuses an unsafe protected-resource metadata URL', () => {
  const app = express()
  assert.throws(
    () => installTestOAuth(app, { resourceMetadataUrl: 'http://example.com/metadata' }),
    /MCP_RESOURCE_METADATA_URL must be a valid HTTPS URL/,
  )
})
