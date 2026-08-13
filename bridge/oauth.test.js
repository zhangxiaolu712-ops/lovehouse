import test from 'node:test'
import assert from 'node:assert/strict'
import crypto from 'crypto'
import { mkdtemp, readFile, rm, stat, writeFile } from 'fs/promises'
import os from 'os'
import path from 'path'

import express from 'express'

import { installClaudeOAuth } from './oauth.js'
import {
  createFileRefreshTokenStore,
  createMemoryRefreshTokenStore,
  digestRefreshToken,
} from './oauthRefreshStore.js'

const oauthBase = 'https://tingtunehouse.example'
const resource = `${oauthBase}/api/mcp/claude`
const resourceMetadataUrl = `${oauthBase}/api/.well-known/oauth-protected-resource/mcp/claude`
const tokenSecret = 'test-oauth-signing-secret-that-is-long-enough'
const nativeFetch = globalThis.fetch

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
    refreshTokenStore: createMemoryRefreshTokenStore(),
    ...overrides,
  })
}

async function createServer(t, overrides = {}) {
  const app = express()
  app.use(express.json())
  app.use(express.urlencoded({ extended: false }))
  const verifyOAuth = installTestOAuth(app, overrides)
  app.get('/mcp/claude', verifyOAuth, (_req, res) => res.json({ ok: true }))
  const server = await new Promise(resolve => {
    const listening = app.listen(0, '127.0.0.1', () => resolve(listening))
  })
  t.after(() => new Promise(resolve => server.close(resolve)))
  return `http://127.0.0.1:${server.address().port}`
}

function mockOwnerLogin(t) {
  globalThis.fetch = async (input, init) => {
    if (String(input).startsWith('https://project.example.supabase.co/auth/v1/token')) {
      return new Response(JSON.stringify({ user: { id: 'owner-1' } }), {
        status: 200,
        headers: { 'Content-Type': 'application/json' },
      })
    }
    return nativeFetch(input, init)
  }
  t.after(() => { globalThis.fetch = nativeFetch })
}

function claudeCodeRegistration(overrides = {}) {
  return {
    client_name: 'Claude Code',
    redirect_uris: ['http://127.0.0.1:43123/callback'],
    grant_types: ['authorization_code', 'refresh_token'],
    response_types: ['code'],
    application_type: 'native',
    token_endpoint_auth_method: 'none',
    ...overrides,
  }
}

async function register(base, metadata = claudeCodeRegistration()) {
  const response = await nativeFetch(`${base}/oauth/register`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(metadata),
  })
  return { response, body: await response.json() }
}

async function authorize(base, registration, { scope = 'mcp:tools' } = {}) {
  const verifier = 'a'.repeat(64)
  const challenge = crypto.createHash('sha256').update(verifier).digest('base64url')
  const fields = {
    response_type: 'code',
    client_id: registration.client_id,
    redirect_uri: registration.redirect_uris[0],
    code_challenge: challenge,
    code_challenge_method: 'S256',
    resource,
    scope,
    state: 'test-state',
  }
  const page = await nativeFetch(`${base}/oauth/authorize?${new URLSearchParams(fields)}`)
  assert.equal(page.status, 200)
  assert.match(await page.text(), /Claude Code|Claude/)

  const approval = await nativeFetch(`${base}/oauth/authorize`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ ...fields, email: 'owner@example.com', password: 'correct' }),
    redirect: 'manual',
  })
  assert.equal(approval.status, 302)
  const callback = new URL(approval.headers.get('location'))
  assert.equal(callback.searchParams.get('state'), 'test-state')
  return { code: callback.searchParams.get('code'), verifier }
}

async function exchangeCode(base, registration, authorization, overrides = {}) {
  const response = await nativeFetch(`${base}/oauth/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'authorization_code',
      client_id: registration.client_id,
      redirect_uri: registration.redirect_uris[0],
      code: authorization.code,
      code_verifier: authorization.verifier,
      resource,
      ...overrides,
    }),
  })
  return { response, body: await response.json() }
}

async function refresh(base, registration, refreshToken, overrides = {}) {
  const response = await nativeFetch(`${base}/oauth/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({
      grant_type: 'refresh_token',
      client_id: registration.client_id,
      refresh_token: refreshToken,
      resource,
      ...overrides,
    }),
  })
  return { response, body: await response.json() }
}

async function issueClaudeCodeTokens(t, base, metadata) {
  mockOwnerLogin(t)
  const registrationResult = await register(base, metadata)
  assert.equal(registrationResult.response.status, 201)
  const authorization = await authorize(base, registrationResult.body)
  const tokenResult = await exchangeCode(base, registrationResult.body, authorization, {
    ...(registrationResult.body.client_secret
      ? { client_secret: registrationResult.body.client_secret }
      : {}),
  })
  assert.equal(tokenResult.response.status, 200)
  return { registration: registrationResult.body, tokens: tokenResult.body }
}

test('Claude OAuth discovery and protected-resource metadata match the implemented contract', async t => {
  const base = await createServer(t)
  const response = await nativeFetch(`${base}/mcp/claude`)

  assert.equal(response.status, 401)
  assert.equal(
    response.headers.get('www-authenticate'),
    `Bearer resource_metadata="${resourceMetadataUrl}", scope="mcp:tools"`,
  )
  assert.deepEqual(await response.json(), { error: 'unauthorized' })

  const invalid = await nativeFetch(`${base}/mcp/claude`, {
    headers: { Authorization: 'Bearer invalid-token' },
  })
  assert.equal(invalid.status, 401)
  assert.equal(
    invalid.headers.get('www-authenticate'),
    `Bearer error="invalid_token", resource_metadata="${resourceMetadataUrl}", scope="mcp:tools"`,
  )
  assert.deepEqual(await invalid.json(), { error: 'invalid_token' })

  const metadata = await nativeFetch(`${base}/.well-known/oauth-authorization-server`)
  assert.equal(metadata.status, 200)
  assert.deepEqual(await metadata.json(), {
    issuer: oauthBase,
    authorization_endpoint: `${oauthBase}/oauth/authorize`,
    token_endpoint: `${oauthBase}/oauth/token`,
    registration_endpoint: `${oauthBase}/oauth/register`,
    response_types_supported: ['code'],
    grant_types_supported: ['authorization_code', 'refresh_token'],
    code_challenge_methods_supported: ['S256'],
    token_endpoint_auth_methods_supported: ['none', 'client_secret_post'],
    scopes_supported: ['mcp:tools'],
  })

  const protectedMetadata = await nativeFetch(`${base}/.well-known/oauth-protected-resource/mcp/claude`)
  assert.deepEqual(await protectedMetadata.json(), {
    resource,
    authorization_servers: [oauthBase],
    bearer_methods_supported: ['header'],
    scopes_supported: ['mcp:tools'],
  })
})

test('dynamic registration preserves the existing authorization-code-only public client', async t => {
  const base = await createServer(t)
  const { response, body } = await register(base, {
    client_name: 'Claude',
    redirect_uris: ['https://claude.ai/api/mcp/auth_callback'],
    grant_types: ['authorization_code'],
    response_types: ['code'],
    application_type: 'web',
    token_endpoint_auth_method: 'none',
  })

  assert.equal(response.status, 201)
  assert.match(body.client_id, /^lh_[a-f0-9]{32}$/)
  assert.deepEqual(body.grant_types, ['authorization_code'])
  assert.equal('client_secret' in body, false)
})

test('dynamic registration accepts the real Claude Code auth-code plus refresh contract', async t => {
  const base = await createServer(t)
  const { response, body } = await register(base)

  assert.equal(response.status, 201)
  assert.match(body.client_id, /^lh_[a-f0-9]{32}$/)
  assert.equal(body.client_name, 'Claude Code')
  assert.deepEqual(body.redirect_uris, ['http://127.0.0.1:43123/callback'])
  assert.deepEqual(body.grant_types, ['authorization_code', 'refresh_token'])
  assert.deepEqual(body.response_types, ['code'])
  assert.equal(body.application_type, 'native')
  assert.equal(body.token_endpoint_auth_method, 'none')
  assert.equal('client_secret' in body, false)
})

test('dynamic registration rejects unsupported grants and invalid client metadata combinations', async t => {
  const base = await createServer(t)
  const invalidCases = [
    claudeCodeRegistration({ grant_types: ['client_credentials'] }),
    claudeCodeRegistration({ grant_types: ['refresh_token'] }),
    claudeCodeRegistration({ grant_types: ['authorization_code', 'refresh_token', 'client_credentials'] }),
    claudeCodeRegistration({ grant_types: 'authorization_code' }),
    claudeCodeRegistration({ response_types: ['token'] }),
    claudeCodeRegistration({ response_types: ['code', 'code'] }),
    claudeCodeRegistration({ token_endpoint_auth_method: 'client_secret_basic' }),
    claudeCodeRegistration({ token_endpoint_auth_method: 'client_secret_post' }),
    claudeCodeRegistration({ application_type: 'service' }),
    claudeCodeRegistration({ redirect_uris: ['http://remote.example/callback'] }),
  ]

  for (const metadata of invalidCases) {
    const { response, body } = await register(base, metadata)
    assert.equal(response.status, 400)
    assert.match(body.error, /invalid_client_metadata|invalid_redirect_uri/)
  }
})

test('authorization code with S256 PKCE issues a bound access token and rotating refresh token', async t => {
  const base = await createServer(t)
  const { registration, tokens } = await issueClaudeCodeTokens(t, base)

  assert.match(tokens.access_token, /^lh1\./)
  assert.match(tokens.refresh_token, /^lh_rt_/)
  assert.equal(tokens.scope, 'mcp:tools')
  const authorized = await nativeFetch(`${base}/mcp/claude`, {
    headers: { Authorization: `Bearer ${tokens.access_token}` },
  })
  assert.equal(authorized.status, 200)

  const refreshed = await refresh(base, registration, tokens.refresh_token)
  assert.equal(refreshed.response.status, 200)
  assert.match(refreshed.body.access_token, /^lh1\./)
  assert.match(refreshed.body.refresh_token, /^lh_rt_/)
  assert.notEqual(refreshed.body.refresh_token, tokens.refresh_token)
})

test('a replayed refresh token revokes its rotated token family', async t => {
  const base = await createServer(t)
  const { registration, tokens } = await issueClaudeCodeTokens(t, base)
  const firstRefresh = await refresh(base, registration, tokens.refresh_token)
  assert.equal(firstRefresh.response.status, 200)

  const replay = await refresh(base, registration, tokens.refresh_token)
  assert.equal(replay.response.status, 400)
  assert.equal(replay.body.error, 'invalid_grant')

  const revokedDescendant = await refresh(base, registration, firstRefresh.body.refresh_token)
  assert.equal(revokedDescendant.response.status, 400)
  assert.equal(revokedDescendant.body.error, 'invalid_grant')
})

test('refresh rejects invalid, revoked, expired, client, resource and scope mismatches without widening access', async t => {
  let now = Date.now()
  const store = createMemoryRefreshTokenStore({ now: () => now })
  const base = await createServer(t, { refreshTokenStore: store, refreshTokenTtlSeconds: 1 })
  const first = await issueClaudeCodeTokens(t, base)

  const invalid = await refresh(base, first.registration, 'lh_rt_not-a-real-token')
  assert.equal(invalid.response.status, 400)
  assert.equal(invalid.body.error, 'invalid_grant')

  const wrongClient = await refresh(base, { ...first.registration, client_id: 'lh_wrong' }, first.tokens.refresh_token)
  assert.equal(wrongClient.response.status, 400)
  const wrongResource = await refresh(base, first.registration, first.tokens.refresh_token, {
    resource: 'https://other.example/mcp',
  })
  assert.equal(wrongResource.response.status, 400)
  const wrongScope = await refresh(base, first.registration, first.tokens.refresh_token, { scope: 'admin' })
  assert.equal(wrongScope.response.status, 400)
  assert.equal(wrongScope.body.error, 'invalid_scope')

  const validAfterMismatches = await refresh(base, first.registration, first.tokens.refresh_token)
  assert.equal(validAfterMismatches.response.status, 200)
  await store.revoke(digestRefreshToken(validAfterMismatches.body.refresh_token, tokenSecret))
  const revoked = await refresh(base, first.registration, validAfterMismatches.body.refresh_token)
  assert.equal(revoked.response.status, 400)
  assert.equal(revoked.body.error, 'invalid_grant')

  const second = await issueClaudeCodeTokens(t, base)
  now += 2_000
  const expired = await refresh(base, second.registration, second.tokens.refresh_token)
  assert.equal(expired.response.status, 400)
  assert.equal(expired.body.error, 'invalid_grant')
})

test('confidential web clients keep client-secret binding across refresh rotation', async t => {
  const base = await createServer(t)
  const { registration, tokens } = await issueClaudeCodeTokens(t, base, claudeCodeRegistration({
    redirect_uris: ['https://client.example/callback'],
    application_type: 'web',
    token_endpoint_auth_method: 'client_secret_post',
  }))
  assert.match(registration.client_secret, /^[a-f0-9]{64}$/)

  const missingSecret = await refresh(base, registration, tokens.refresh_token)
  assert.equal(missingSecret.response.status, 400)
  const refreshed = await refresh(base, registration, tokens.refresh_token, {
    client_secret: registration.client_secret,
  })
  assert.equal(refreshed.response.status, 200)
})

test('refresh token state persists for reuse without storing the raw token', async t => {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'lovehouse-oauth-'))
  const storePath = path.join(directory, 'refresh-tokens.json')
  t.after(() => rm(directory, { recursive: true, force: true }))

  const firstBase = await createServer(t, {
    refreshTokenStore: createFileRefreshTokenStore({ filePath: storePath }),
  })
  const { registration, tokens } = await issueClaudeCodeTokens(t, firstBase)
  const storedText = await readFile(storePath, 'utf8')
  assert.equal(storedText.includes(tokens.refresh_token), false)
  assert.equal(storedText.includes(tokenSecret), false)
  if (process.platform !== 'win32') {
    assert.equal((await stat(storePath)).mode & 0o777, 0o600)
    assert.equal((await stat(directory)).mode & 0o777, 0o700)
  }

  const restartedBase = await createServer(t, {
    refreshTokenStore: createFileRefreshTokenStore({ filePath: storePath }),
  })
  const reused = await refresh(restartedBase, registration, tokens.refresh_token)
  assert.equal(reused.response.status, 200)
  assert.match(reused.body.refresh_token, /^lh_rt_/)
})

test('independent file-store instances serialize rotation and prevent double issuance', async t => {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'lovehouse-oauth-lock-'))
  const storePath = path.join(directory, 'refresh-tokens.json')
  t.after(() => rm(directory, { recursive: true, force: true }))
  const firstStore = createFileRefreshTokenStore({ filePath: storePath })
  const secondStore = createFileRefreshTokenStore({ filePath: storePath })
  const now = Date.now()
  const original = {
    token_digest: 'original-digest',
    family_id: 'family-1',
    generation: 0,
    client_id: 'client-1',
    client_auth_method: 'none',
    owner_user_id: 'owner-1',
    resource,
    scope: 'mcp:tools',
    created_at: now,
    expires_at: now + 60_000,
  }
  await firstStore.issue(original)
  const replacement = suffix => current => ({
    ...current,
    token_digest: `replacement-${suffix}`,
    generation: 1,
    created_at: Date.now(),
  })

  const results = await Promise.all([
    firstStore.rotate('original-digest', replacement('first'), () => true),
    secondStore.rotate('original-digest', replacement('second'), () => true),
  ])
  assert.deepEqual(results.map(result => result.status).sort(), ['replayed', 'rotated'])
  const issued = results.find(result => result.status === 'rotated').record
  const revokedDescendant = await firstStore.rotate(
    issued.token_digest,
    current => ({ ...current, token_digest: 'next', generation: 2 }),
    () => true,
  )
  assert.equal(revokedDescendant.status, 'revoked')
})

test('missing or corrupt file state never makes an old refresh token valid', async t => {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'lovehouse-oauth-invalid-'))
  const storePath = path.join(directory, 'refresh-tokens.json')
  t.after(() => rm(directory, { recursive: true, force: true }))
  const missingStore = createFileRefreshTokenStore({ filePath: storePath })
  const missing = await missingStore.rotate('unknown', () => assert.fail('must not rotate'), () => true)
  assert.equal(missing.status, 'invalid')

  await writeFile(storePath, '{not-json', 'utf8')
  const corruptStore = createFileRefreshTokenStore({ filePath: storePath })
  await assert.rejects(
    corruptStore.rotate('unknown', () => assert.fail('must not rotate'), () => true),
    /JSON|OAuth refresh token store is invalid/,
  )
})

test('rotating OAUTH_TOKEN_SECRET invalidates existing access and refresh tokens', async t => {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'lovehouse-oauth-secret-'))
  const storePath = path.join(directory, 'refresh-tokens.json')
  t.after(() => rm(directory, { recursive: true, force: true }))
  const firstBase = await createServer(t, {
    refreshTokenStore: createFileRefreshTokenStore({ filePath: storePath }),
  })
  const { registration, tokens } = await issueClaudeCodeTokens(t, firstBase)
  const rotatedSecret = 'rotated-oauth-signing-secret-that-is-long-enough'
  const rotatedBase = await createServer(t, {
    tokenSecret: rotatedSecret,
    refreshTokenStore: createFileRefreshTokenStore({ filePath: storePath }),
  })

  const oldAccess = await nativeFetch(`${rotatedBase}/mcp/claude`, {
    headers: { Authorization: `Bearer ${tokens.access_token}` },
  })
  assert.equal(oldAccess.status, 401)
  const oldRefresh = await refresh(rotatedBase, registration, tokens.refresh_token)
  assert.equal(oldRefresh.response.status, 400)
  assert.equal(oldRefresh.body.error, 'invalid_grant')
})

test('authorization-code-only clients do not receive a refresh token', async t => {
  mockOwnerLogin(t)
  const base = await createServer(t)
  const registrationResult = await register(base, {
    client_name: 'Existing client',
    redirect_uris: ['https://client.example/callback'],
    grant_types: ['authorization_code'],
    response_types: ['code'],
    application_type: 'web',
    token_endpoint_auth_method: 'none',
  })
  const authorization = await authorize(base, registrationResult.body)
  const tokenResult = await exchangeCode(base, registrationResult.body, authorization)
  assert.equal(tokenResult.response.status, 200)
  assert.equal('refresh_token' in tokenResult.body, false)
})

test('unsupported token grants and invalid PKCE fail closed', async t => {
  mockOwnerLogin(t)
  const base = await createServer(t)
  const registrationResult = await register(base)
  const unsupported = await nativeFetch(`${base}/oauth/token`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    body: new URLSearchParams({ grant_type: 'client_credentials' }),
  })
  assert.equal(unsupported.status, 400)
  assert.equal((await unsupported.json()).error, 'unsupported_grant_type')

  const authorization = await authorize(base, registrationResult.body)
  const invalidPkce = await exchangeCode(base, registrationResult.body, authorization, {
    code_verifier: 'b'.repeat(64),
  })
  assert.equal(invalidPkce.response.status, 400)
  assert.equal(invalidPkce.body.error, 'invalid_grant')
})

test('OAuth refuses unsafe startup configuration', () => {
  const app = express()
  assert.throws(
    () => installTestOAuth(app, { tokenSecret: '' }),
    /OAUTH_TOKEN_SECRET must contain at least 32 characters/,
  )
  assert.throws(
    () => installTestOAuth(app, { resourceMetadataUrl: 'http://example.com/metadata' }),
    /MCP_RESOURCE_METADATA_URL must be a valid HTTPS URL/,
  )
  assert.throws(
    () => installTestOAuth(app, { refreshTokenStore: null }),
    /OAuth refresh token store is required/,
  )
  assert.throws(
    () => createFileRefreshTokenStore({ filePath: 'release-relative/refresh-tokens.json' }),
    /OAUTH_REFRESH_STORE_PATH must be an absolute path/,
  )
})
