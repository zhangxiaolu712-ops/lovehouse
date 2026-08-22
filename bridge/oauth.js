import crypto from 'crypto'

import {
  hashPkceVerifier,
  issueAccessToken,
  safeEqual,
  validatePkce,
  validateRedirectUris,
  verifyAccessToken,
} from './security.js'
import {
  createRefreshToken,
  digestClientSecret,
  digestRefreshToken,
} from './oauthRefreshStore.js'

const AUTHORIZATION_CODE = 'authorization_code'
const REFRESH_TOKEN = 'refresh_token'
const MCP_SCOPE = 'mcp:tools'

export const OAUTH_AUTHORIZE_CONTENT_SECURITY_POLICY = "default-src 'none'; style-src 'unsafe-inline'; form-action 'self' https://claude.ai; frame-ancestors 'none'; base-uri 'none'"

function escapeHtml(value) {
  return String(value || '')
    .replaceAll('&', '&amp;')
    .replaceAll('<', '&lt;')
    .replaceAll('>', '&gt;')
    .replaceAll('"', '&quot;')
}

function oauthError(res, status, error, description) {
  const payload = { error }
  if (description) payload.error_description = description
  return res.status(status).json(payload)
}

function authorizationFailurePage(message) {
  return `<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
  <title>LoveHouse 授权失败</title><style>body{font-family:-apple-system,sans-serif;background:#f7f1e7;display:flex;justify-content:center;align-items:center;min-height:100vh;margin:0}.card{background:#fffdf8;border:1px solid #d8cdbd;border-radius:18px;padding:32px;text-align:center;box-shadow:0 10px 32px rgba(58,48,38,.09);max-width:360px;width:86%}a{color:#947235}</style></head>
  <body><div class="card"><h2>没有打开门</h2><p>${escapeHtml(message)}</p><p>请关闭本页，再从 CC 重新点一次授权。</p></div></body></html>`
}

function validGrantTypes(value) {
  if (value === undefined) return [AUTHORIZATION_CODE]
  if (!Array.isArray(value) || value.length < 1 || value.length > 2) return null
  if (new Set(value).size !== value.length) return null
  if (!value.includes(AUTHORIZATION_CODE)) return null
  if (value.some(grant => ![AUTHORIZATION_CODE, REFRESH_TOKEN].includes(grant))) return null
  return value
}

function validResponseTypes(value) {
  if (value === undefined) return ['code']
  return Array.isArray(value) && value.length === 1 && value[0] === 'code' ? value : null
}

function requestedScope(value) {
  if (value === undefined || value === '') return MCP_SCOPE
  return value === MCP_SCOPE ? value : null
}

/**
 * CC's OAuth/PKCE flow, extracted from the old monolithic Bridge so the MCP
 * transport remains an adapter rather than becoming the Memory System.
 */
export function installClaudeOAuth(app, {
  oauthBase,
  resource,
  resourceMetadataUrl,
  supabaseUrl,
  supabaseAnonKey,
  ownerUserId,
  tokenSecret,
  checkRate,
  clientRegistry,
  refreshTokenStore,
  tokenTtlSeconds = 30 * 24 * 60 * 60,
  refreshTokenTtlSeconds = 90 * 24 * 60 * 60,
}) {
  if (!tokenSecret || tokenSecret.length < 32) {
    throw new Error('OAUTH_TOKEN_SECRET must contain at least 32 characters')
  }
  let metadataUrl
  try {
    metadataUrl = new URL(resourceMetadataUrl)
  } catch {
    throw new Error('MCP_RESOURCE_METADATA_URL must be a valid HTTPS URL')
  }
  if (metadataUrl.protocol !== 'https:' || metadataUrl.hash || metadataUrl.username || metadataUrl.password) {
    throw new Error('MCP_RESOURCE_METADATA_URL must be a valid HTTPS URL')
  }
  if (!refreshTokenStore
    || typeof refreshTokenStore.issue !== 'function'
    || typeof refreshTokenStore.rotate !== 'function') {
    throw new Error('OAuth refresh token store is required')
  }
  if (!clientRegistry
    || typeof clientRegistry.register !== 'function'
    || typeof clientRegistry.get !== 'function') {
    throw new Error('OAuth client registry is required')
  }
  const protectedResourceMetadataUrl = metadataUrl.toString()
  const codes = new Map()

  const cleanupTimer = setInterval(() => {
    const now = Date.now()
    for (const [code, value] of codes) {
      if (value.expires_at < now) codes.delete(code)
    }
  }, 5 * 60_000)
  cleanupTimer.unref?.()

  async function getActiveClient(clientId) {
    const client = await clientRegistry.get(clientId)
    const now = Date.now()
    if (!client
      || client.revoked_at !== null
      || (client.expires_at !== null && client.expires_at <= now)) return null
    return client
  }

  async function getValidClient(clientId, redirectUri) {
    const client = await getActiveClient(clientId)
    if (!client || !client.redirect_uris.includes(redirectUri)) return null
    return client
  }

  async function validateAuthorizationRequest(input) {
    if (input.response_type !== 'code') return 'unsupported_response_type'
    const client = await getValidClient(input.client_id, input.redirect_uri)
    if (!client || !client.grant_types.includes(AUTHORIZATION_CODE)) return 'invalid_client'
    if (!validatePkce(input.code_challenge, input.code_challenge_method)) return 'invalid_request'
    if (input.resource !== resource) return 'invalid_target'
    if (!requestedScope(input.scope)) return 'invalid_scope'
    return null
  }

  function validClientAuthentication(clientLike, body) {
    if (!clientLike || clientLike.client_id !== body.client_id) return false
    if (clientLike.client_auth_method === 'client_secret_post'
      || clientLike.token_endpoint_auth_method === 'client_secret_post') {
      const expectedDigest = clientLike.client_secret_digest
        || digestClientSecret(clientLike.client_secret, tokenSecret)
      const presentedDigest = digestClientSecret(body.client_secret, tokenSecret)
      return Boolean(expectedDigest && presentedDigest && safeEqual(expectedDigest, presentedDigest))
    }
    return (clientLike.client_auth_method === 'none'
      || clientLike.token_endpoint_auth_method === 'none') && !body.client_secret
  }

  async function verifyOwnerCredentials(email, password) {
    if (!ownerUserId || !supabaseAnonKey) throw new Error('owner OAuth is not configured')
    if (typeof email !== 'string' || typeof password !== 'string' || !email || !password) return false
    const response = await fetch(`${supabaseUrl}/auth/v1/token?grant_type=password`, {
      method: 'POST',
      headers: {
        apikey: supabaseAnonKey,
        'Content-Type': 'application/json',
      },
      body: JSON.stringify({ email, password }),
    })
    if (!response.ok) return false
    const payload = await response.json()
    return payload.user?.id === ownerUserId
  }

  app.get('/.well-known/oauth-authorization-server', (_req, res) => {
    res.json({
      issuer: oauthBase,
      authorization_endpoint: `${oauthBase}/oauth/authorize`,
      token_endpoint: `${oauthBase}/oauth/token`,
      registration_endpoint: `${oauthBase}/oauth/register`,
      response_types_supported: ['code'],
      grant_types_supported: [AUTHORIZATION_CODE, REFRESH_TOKEN],
      code_challenge_methods_supported: ['S256'],
      token_endpoint_auth_methods_supported: ['none', 'client_secret_post'],
      scopes_supported: [MCP_SCOPE],
    })
  })

  function protectedResourceMetadata(_req, res) {
    res.json({
      resource,
      authorization_servers: [oauthBase],
      bearer_methods_supported: ['header'],
      scopes_supported: [MCP_SCOPE],
    })
  }
  app.get('/.well-known/oauth-protected-resource', protectedResourceMetadata)
  app.get('/.well-known/oauth-protected-resource/mcp/claude', protectedResourceMetadata)

  app.post('/oauth/register', async (req, res) => {
    if (!checkRate(`oauth-register:${req.ip}`, 10, 15 * 60_000)) {
      return oauthError(res, 429, 'too_many_requests', 'try again later')
    }
    if (!validateRedirectUris(req.body.redirect_uris)) {
      return oauthError(res, 400, 'invalid_redirect_uri', 'redirect_uris must contain 1-5 HTTPS or loopback URLs')
    }
    const grantTypes = validGrantTypes(req.body.grant_types)
    if (!grantTypes) {
      return oauthError(res, 400, 'invalid_client_metadata', 'only authorization_code with optional refresh_token is supported')
    }
    const responseTypes = validResponseTypes(req.body.response_types)
    if (!responseTypes) {
      return oauthError(res, 400, 'invalid_client_metadata', 'only code response type is supported')
    }
    const tokenAuthMethod = req.body.token_endpoint_auth_method || 'none'
    if (!['none', 'client_secret_post'].includes(tokenAuthMethod)) {
      return oauthError(res, 400, 'invalid_client_metadata', 'unsupported token endpoint auth method')
    }
    const applicationType = req.body.application_type || 'native'
    if (!['native', 'web'].includes(applicationType)) {
      return oauthError(res, 400, 'invalid_client_metadata', 'unsupported application type')
    }
    if (applicationType === 'native' && tokenAuthMethod !== 'none') {
      return oauthError(res, 400, 'invalid_client_metadata', 'native clients must use token endpoint auth method none')
    }

    const clientId = `lh_${crypto.randomBytes(16).toString('hex')}`
    const clientSecret = tokenAuthMethod === 'client_secret_post'
      ? crypto.randomBytes(32).toString('hex')
      : null
    const storedClient = {
      client_id: clientId,
      client_secret_digest: clientSecret ? digestClientSecret(clientSecret, tokenSecret) : null,
      client_secret_expires_at: 0,
      client_name: String(req.body.client_name || 'MCP client').slice(0, 120),
      redirect_uris: req.body.redirect_uris,
      grant_types: grantTypes,
      response_types: responseTypes,
      application_type: applicationType,
      token_endpoint_auth_method: tokenAuthMethod,
      created_at: Date.now(),
      expires_at: null,
      revoked_at: null,
    }
    try {
      await clientRegistry.register(storedClient)
    } catch (error) {
      console.error('[oauth client registry error]', error.message)
      return oauthError(res, 503, 'temporarily_unavailable', 'client registration service is unavailable')
    }
    const registration = {
      client_id: storedClient.client_id,
      client_name: storedClient.client_name,
      redirect_uris: storedClient.redirect_uris,
      grant_types: storedClient.grant_types,
      response_types: storedClient.response_types,
      application_type: storedClient.application_type,
      token_endpoint_auth_method: storedClient.token_endpoint_auth_method,
    }
    if (clientSecret) {
      registration.client_secret = clientSecret
      registration.client_secret_expires_at = 0
    }
    return res.status(201).json(registration)
  })

  app.get('/oauth/authorize', async (req, res) => {
    let validationError
    let client
    try {
      validationError = await validateAuthorizationRequest(req.query)
      if (!validationError) client = await clientRegistry.get(req.query.client_id)
    } catch (error) {
      console.error('[oauth client registry error]', error.message)
      return oauthError(res, 503, 'temporarily_unavailable', 'authorization service is unavailable')
    }
    if (validationError) return oauthError(res, 400, validationError, 'invalid authorization request')
    if (typeof req.query.state === 'string' && req.query.state.length > 2048) {
      return oauthError(res, 400, 'invalid_request', 'state is too long')
    }
    const redirectOrigin = new URL(req.query.redirect_uri).origin
    const hidden = Object.entries({
      client_id: req.query.client_id,
      redirect_uri: req.query.redirect_uri,
      state: req.query.state,
      code_challenge: req.query.code_challenge,
      code_challenge_method: req.query.code_challenge_method,
      resource: req.query.resource,
      scope: requestedScope(req.query.scope),
      response_type: 'code',
    }).map(([name, value]) => `<input type="hidden" name="${name}" value="${escapeHtml(value)}">`).join('')

    return res.send(`<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>LoveHouse 授权</title>
    <style>body{font-family:Georgia,"Noto Serif SC",serif;background:#f7f1e7;color:#302a24;display:flex;justify-content:center;align-items:center;min-height:100vh;margin:0}.card{background:rgba(255,253,248,.94);border:1px solid #d8cdbd;border-radius:18px;padding:34px;box-shadow:0 12px 36px rgba(58,48,38,.09);max-width:380px;width:86%}h2{text-align:center}.info{background:#f2ebdf;border-radius:12px;padding:14px;margin:18px 0;font-size:14px}label{display:block;font-size:13px;margin:12px 0 6px}input[type=email],input[type=password]{width:100%;box-sizing:border-box;border:1px solid #d8cdbd;border-radius:10px;padding:12px;background:#fffdf8}button{width:100%;margin-top:20px;border:0;border-radius:11px;padding:13px;background:#9c7a3d;color:white;font-size:15px}</style></head><body><div class="card"><h2>LoveHouse</h2><div class="info"><b>${escapeHtml(client.client_name)}</b> 请求连接 Claude MCP。授权只确认连接身份，具体记忆空间仍由服务端固定权限决定。<br><br>完成后返回：<b>${escapeHtml(redirectOrigin)}</b></div><form method="POST" action="/oauth/authorize">${hidden}<label>LoveHouse 登录邮箱</label><input type="email" name="email" autocomplete="username" required><label>账号密码</label><input type="password" name="password" autocomplete="current-password" required><button type="submit">允许访问</button></form></div></body></html>`)
  })

  app.post('/oauth/authorize', async (req, res) => {
    let validationError
    try {
      validationError = await validateAuthorizationRequest(req.body)
    } catch (error) {
      console.error('[oauth client registry error]', error.message)
      return oauthError(res, 503, 'temporarily_unavailable', 'authorization service is unavailable')
    }
    if (validationError) return oauthError(res, 400, validationError, 'invalid authorization request')
    if (typeof req.body.state === 'string' && req.body.state.length > 2048) {
      return oauthError(res, 400, 'invalid_request', 'state is too long')
    }
    if (!checkRate(`oauth-approval:${req.ip}`, 5, 15 * 60_000)) {
      return res.status(429).send(authorizationFailurePage('尝试次数太多，请十五分钟后再试。'))
    }
    try {
      const approved = await verifyOwnerCredentials(req.body.email, req.body.password)
      if (!approved) return res.status(401).send(authorizationFailurePage('账号或密码不正确。'))
    } catch (error) {
      console.error('[oauth approval error]', error.message)
      return res.status(503).send(authorizationFailurePage('授权服务暂时没有配置好。'))
    }

    const code = crypto.randomBytes(32).toString('hex')
    codes.set(code, {
      client_id: req.body.client_id,
      redirect_uri: req.body.redirect_uri,
      code_challenge: req.body.code_challenge,
      resource: req.body.resource,
      scope: requestedScope(req.body.scope),
      expires_at: Date.now() + 600_000,
    })
    const redirect = new URL(req.body.redirect_uri)
    redirect.searchParams.set('code', code)
    if (req.body.state) redirect.searchParams.set('state', req.body.state)
    return res.redirect(redirect.toString())
  })

  function refreshRecord({ rawToken, client, familyId, generation, expiresAt }) {
    return {
      token_digest: digestRefreshToken(rawToken, tokenSecret),
      family_id: familyId,
      generation,
      client_id: client.client_id,
      client_auth_method: client.token_endpoint_auth_method,
      client_secret_digest: client.client_secret_digest
        || (client.client_secret ? digestClientSecret(client.client_secret, tokenSecret) : null),
      owner_user_id: ownerUserId,
      resource,
      scope: MCP_SCOPE,
      created_at: Date.now(),
      expires_at: expiresAt,
    }
  }

  async function exchangeAuthorizationCode(req, res) {
    const stored = codes.get(req.body.code)
    codes.delete(req.body.code)
    if (!stored || stored.expires_at < Date.now()) return oauthError(res, 400, 'invalid_grant')
    if (stored.client_id !== req.body.client_id || stored.redirect_uri !== req.body.redirect_uri) {
      return oauthError(res, 400, 'invalid_grant', 'client or redirect_uri does not match')
    }
    if (req.body.resource !== stored.resource || stored.resource !== resource) {
      return oauthError(res, 400, 'invalid_target', 'resource does not match')
    }
    if (requestedScope(req.body.scope) !== stored.scope) {
      return oauthError(res, 400, 'invalid_scope', 'scope does not match')
    }
    let client
    try {
      client = await getValidClient(req.body.client_id, req.body.redirect_uri)
    } catch (error) {
      console.error('[oauth client registry error]', error.message)
      return oauthError(res, 503, 'temporarily_unavailable', 'token service is unavailable')
    }
    if (!validClientAuthentication(client, req.body)) {
      return oauthError(res, 401, 'invalid_client', 'client authentication failed')
    }

    const verifierHash = hashPkceVerifier(req.body.code_verifier)
    if (!verifierHash || !safeEqual(verifierHash, stored.code_challenge)) {
      return oauthError(res, 400, 'invalid_grant', 'PKCE verification failed')
    }
    try {
      const response = {
        access_token: issueAccessToken({
          clientId: req.body.client_id,
          ownerUserId,
          audience: resource,
          secret: tokenSecret,
          ttlSeconds: tokenTtlSeconds,
        }),
        token_type: 'Bearer',
        expires_in: tokenTtlSeconds,
        scope: MCP_SCOPE,
      }
      if (client.grant_types.includes(REFRESH_TOKEN)) {
        const rawToken = createRefreshToken()
        const expiresAt = Date.now() + refreshTokenTtlSeconds * 1000
        await refreshTokenStore.issue(refreshRecord({
          rawToken,
          client,
          familyId: crypto.randomBytes(16).toString('hex'),
          generation: 0,
          expiresAt,
        }))
        response.refresh_token = rawToken
        response.refresh_token_expires_in = refreshTokenTtlSeconds
      }
      return res.json(response)
    } catch (error) {
      console.error('[oauth token error]', error.message)
      return oauthError(res, 503, 'temporarily_unavailable', 'token service is not configured')
    }
  }

  async function exchangeRefreshToken(req, res) {
    const tokenDigest = digestRefreshToken(req.body.refresh_token, tokenSecret)
    const scope = requestedScope(req.body.scope)
    if (!tokenDigest) return oauthError(res, 400, 'invalid_grant')
    if (!scope) return oauthError(res, 400, 'invalid_scope')
    let registeredClient
    try {
      registeredClient = await getActiveClient(req.body.client_id)
    } catch (error) {
      console.error('[oauth client registry error]', error.message)
      return oauthError(res, 503, 'temporarily_unavailable', 'token service is unavailable')
    }
    if (!registeredClient
      || !registeredClient.grant_types.includes(REFRESH_TOKEN)
      || !validClientAuthentication(registeredClient, req.body)) {
      return oauthError(res, 400, 'invalid_grant')
    }
    const requestedResource = req.body.resource || resource
    const replacementToken = createRefreshToken()
    try {
      const rotated = await refreshTokenStore.rotate(
        tokenDigest,
        current => refreshRecord({
          rawToken: replacementToken,
          client: {
            client_id: current.client_id,
            token_endpoint_auth_method: current.client_auth_method,
            client_secret: null,
            client_secret_digest: current.client_secret_digest,
          },
          familyId: current.family_id,
          generation: current.generation + 1,
          expiresAt: current.expires_at,
        }),
        current => current.owner_user_id === ownerUserId
          && current.resource === resource
          && requestedResource === current.resource
          && current.scope === MCP_SCOPE
          && scope === current.scope
          && current.client_id === registeredClient.client_id
          && validClientAuthentication(current, req.body),
      )
      if (rotated.status !== 'rotated') return oauthError(res, 400, 'invalid_grant')

      return res.json({
        access_token: issueAccessToken({
          clientId: rotated.record.client_id,
          ownerUserId,
          audience: rotated.record.resource,
          secret: tokenSecret,
          ttlSeconds: tokenTtlSeconds,
        }),
        token_type: 'Bearer',
        expires_in: tokenTtlSeconds,
        scope: rotated.record.scope,
        refresh_token: replacementToken,
        refresh_token_expires_in: Math.max(
          0,
          Math.floor((rotated.record.expires_at - Date.now()) / 1000),
        ),
      })
    } catch (error) {
      console.error('[oauth refresh error]', error.message)
      return oauthError(res, 503, 'temporarily_unavailable', 'token service is not configured')
    }
  }

  app.post('/oauth/token', async (req, res) => {
    if (req.body.grant_type === AUTHORIZATION_CODE) return exchangeAuthorizationCode(req, res)
    if (req.body.grant_type === REFRESH_TOKEN) return exchangeRefreshToken(req, res)
    return oauthError(res, 400, 'unsupported_grant_type')
  })

  return function verifyOAuthToken(req, res, next) {
    const auth = req.headers.authorization
    if (!auth?.startsWith('Bearer ')) {
      res.setHeader('WWW-Authenticate', `Bearer resource_metadata="${protectedResourceMetadataUrl}", scope="mcp:tools"`)
      return res.status(401).json({ error: 'unauthorized' })
    }
    const payload = verifyAccessToken(auth.slice(7), tokenSecret, resource)
    if (!payload || payload.sub !== ownerUserId) {
      res.setHeader('WWW-Authenticate', `Bearer error="invalid_token", resource_metadata="${protectedResourceMetadataUrl}", scope="mcp:tools"`)
      return res.status(401).json({ error: 'invalid_token' })
    }
    if (!checkRate(`claude-mcp:${payload.client_id}`)) {
      return res.status(429).json({ error: 'too many requests' })
    }
    req.oauth = payload
    return next()
  }
}
