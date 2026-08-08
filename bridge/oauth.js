import crypto from 'crypto'

import {
  hashPkceVerifier,
  issueAccessToken,
  safeEqual,
  validatePkce,
  validateRedirectUris,
  verifyAccessToken,
} from './security.js'

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

/**
 * CC's OAuth/PKCE flow, extracted from the old monolithic Bridge so the MCP
 * transport remains an adapter rather than becoming the Memory System.
 */
export function installClaudeOAuth(app, {
  oauthBase,
  resource,
  supabaseUrl,
  supabaseAnonKey,
  ownerUserId,
  tokenSecret,
  checkRate,
  tokenTtlSeconds = 30 * 24 * 60 * 60,
}) {
  const clients = new Map()
  const codes = new Map()

  const cleanupTimer = setInterval(() => {
    const now = Date.now()
    for (const [code, value] of codes) {
      if (value.expires_at < now) codes.delete(code)
    }
    for (const [clientId, value] of clients) {
      if (value.created_at < now - 60 * 60_000) clients.delete(clientId)
    }
  }, 5 * 60_000)
  cleanupTimer.unref?.()

  function getValidClient(clientId, redirectUri) {
    const client = clients.get(clientId)
    if (!client || !client.redirect_uris.includes(redirectUri)) return null
    return client
  }

  function validateAuthorizationRequest(input) {
    if (input.response_type !== 'code') return 'unsupported_response_type'
    if (!getValidClient(input.client_id, input.redirect_uri)) return 'invalid_client'
    if (!validatePkce(input.code_challenge, input.code_challenge_method)) return 'invalid_request'
    if (input.resource !== resource) return 'invalid_target'
    return null
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
      grant_types_supported: ['authorization_code'],
      code_challenge_methods_supported: ['S256'],
      token_endpoint_auth_methods_supported: ['none', 'client_secret_post'],
      scopes_supported: ['mcp:tools'],
    })
  })

  function protectedResourceMetadata(_req, res) {
    res.json({
      resource,
      authorization_servers: [oauthBase],
      bearer_methods_supported: ['header'],
      scopes_supported: ['mcp:tools'],
    })
  }
  app.get('/.well-known/oauth-protected-resource', protectedResourceMetadata)
  app.get('/.well-known/oauth-protected-resource/mcp/claude', protectedResourceMetadata)

  app.post('/oauth/register', (req, res) => {
    if (!checkRate(`oauth-register:${req.ip}`, 10, 15 * 60_000)) {
      return oauthError(res, 429, 'too_many_requests', 'try again later')
    }
    if (!validateRedirectUris(req.body.redirect_uris)) {
      return oauthError(res, 400, 'invalid_redirect_uri', 'redirect_uris must contain 1-5 HTTPS or loopback URLs')
    }
    if (req.body.grant_types && !req.body.grant_types.every(value => value === 'authorization_code')) {
      return oauthError(res, 400, 'invalid_client_metadata', 'only authorization_code is supported')
    }
    if (req.body.response_types && !req.body.response_types.every(value => value === 'code')) {
      return oauthError(res, 400, 'invalid_client_metadata', 'only code response type is supported')
    }
    const tokenAuthMethod = req.body.token_endpoint_auth_method || 'none'
    if (!['none', 'client_secret_post'].includes(tokenAuthMethod)) {
      return oauthError(res, 400, 'invalid_client_metadata', 'unsupported token endpoint auth method')
    }

    const clientId = `lh_${crypto.randomBytes(16).toString('hex')}`
    const clientSecret = tokenAuthMethod === 'client_secret_post'
      ? crypto.randomBytes(32).toString('hex')
      : null
    const client = {
      client_id: clientId,
      client_secret: clientSecret,
      client_secret_expires_at: 0,
      client_name: String(req.body.client_name || 'MCP client').slice(0, 120),
      redirect_uris: req.body.redirect_uris,
      grant_types: ['authorization_code'],
      response_types: ['code'],
      application_type: req.body.application_type || 'native',
      token_endpoint_auth_method: tokenAuthMethod,
      created_at: Date.now(),
    }
    clients.set(clientId, client)
    const registration = {
      client_id: client.client_id,
      client_name: client.client_name,
      redirect_uris: client.redirect_uris,
      grant_types: client.grant_types,
      response_types: client.response_types,
      application_type: client.application_type,
      token_endpoint_auth_method: client.token_endpoint_auth_method,
    }
    if (clientSecret) {
      registration.client_secret = clientSecret
      registration.client_secret_expires_at = 0
    }
    return res.status(201).json(registration)
  })

  app.get('/oauth/authorize', (req, res) => {
    const validationError = validateAuthorizationRequest(req.query)
    if (validationError) return oauthError(res, 400, validationError, 'invalid authorization request')
    if (typeof req.query.state === 'string' && req.query.state.length > 2048) {
      return oauthError(res, 400, 'invalid_request', 'state is too long')
    }
    const client = clients.get(req.query.client_id)
    const redirectOrigin = new URL(req.query.redirect_uri).origin
    const hidden = Object.entries({
      client_id: req.query.client_id,
      redirect_uri: req.query.redirect_uri,
      state: req.query.state,
      code_challenge: req.query.code_challenge,
      code_challenge_method: req.query.code_challenge_method,
      resource: req.query.resource,
      response_type: 'code',
    }).map(([name, value]) => `<input type="hidden" name="${name}" value="${escapeHtml(value)}">`).join('')

    return res.send(`<!DOCTYPE html><html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1"><title>LoveHouse 授权</title>
    <style>body{font-family:Georgia,"Noto Serif SC",serif;background:#f7f1e7;color:#302a24;display:flex;justify-content:center;align-items:center;min-height:100vh;margin:0}.card{background:rgba(255,253,248,.94);border:1px solid #d8cdbd;border-radius:18px;padding:34px;box-shadow:0 12px 36px rgba(58,48,38,.09);max-width:380px;width:86%}h2{text-align:center}.info{background:#f2ebdf;border-radius:12px;padding:14px;margin:18px 0;font-size:14px}label{display:block;font-size:13px;margin:12px 0 6px}input[type=email],input[type=password]{width:100%;box-sizing:border-box;border:1px solid #d8cdbd;border-radius:10px;padding:12px;background:#fffdf8}button{width:100%;margin-top:20px;border:0;border-radius:11px;padding:13px;background:#9c7a3d;color:white;font-size:15px}</style></head><body><div class="card"><h2>LoveHouse</h2><div class="info"><b>${escapeHtml(client.client_name)}</b> 请求连接 Claude MCP。授权只确认连接身份，具体记忆空间仍由服务端固定权限决定。<br><br>完成后返回：<b>${escapeHtml(redirectOrigin)}</b></div><form method="POST" action="/oauth/authorize">${hidden}<label>LoveHouse 登录邮箱</label><input type="email" name="email" autocomplete="username" required><label>账号密码</label><input type="password" name="password" autocomplete="current-password" required><button type="submit">允许访问</button></form></div></body></html>`)
  })

  app.post('/oauth/authorize', async (req, res) => {
    const validationError = validateAuthorizationRequest(req.body)
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
      expires_at: Date.now() + 600_000,
    })
    const redirect = new URL(req.body.redirect_uri)
    redirect.searchParams.set('code', code)
    if (req.body.state) redirect.searchParams.set('state', req.body.state)
    return res.redirect(redirect.toString())
  })

  app.post('/oauth/token', (req, res) => {
    if (req.body.grant_type !== 'authorization_code') {
      return oauthError(res, 400, 'unsupported_grant_type')
    }
    const stored = codes.get(req.body.code)
    codes.delete(req.body.code)
    if (!stored || stored.expires_at < Date.now()) return oauthError(res, 400, 'invalid_grant')
    if (stored.client_id !== req.body.client_id || stored.redirect_uri !== req.body.redirect_uri) {
      return oauthError(res, 400, 'invalid_grant', 'client or redirect_uri does not match')
    }
    if (req.body.resource !== stored.resource || stored.resource !== resource) {
      return oauthError(res, 400, 'invalid_target', 'resource does not match')
    }
    const client = clients.get(req.body.client_id)
    const invalidClient = !client
      || (client.token_endpoint_auth_method === 'client_secret_post'
        && (!req.body.client_secret || !safeEqual(client.client_secret, req.body.client_secret)))
      || (client.token_endpoint_auth_method === 'none' && req.body.client_secret)
    if (invalidClient) return oauthError(res, 401, 'invalid_client', 'client authentication failed')

    const verifierHash = hashPkceVerifier(req.body.code_verifier)
    if (!verifierHash || !safeEqual(verifierHash, stored.code_challenge)) {
      return oauthError(res, 400, 'invalid_grant', 'PKCE verification failed')
    }
    try {
      return res.json({
        access_token: issueAccessToken({
          clientId: req.body.client_id,
          ownerUserId,
          audience: resource,
          secret: tokenSecret,
          ttlSeconds: tokenTtlSeconds,
        }),
        token_type: 'Bearer',
        expires_in: tokenTtlSeconds,
        scope: 'mcp:tools',
      })
    } catch (error) {
      console.error('[oauth token error]', error.message)
      return oauthError(res, 503, 'temporarily_unavailable', 'token service is not configured')
    }
  })

  return function verifyOAuthToken(req, res, next) {
    const metadata = `${oauthBase}/.well-known/oauth-protected-resource/mcp/claude`
    const auth = req.headers.authorization
    if (!auth?.startsWith('Bearer ')) {
      res.setHeader('WWW-Authenticate', `Bearer resource_metadata="${metadata}"`)
      return res.status(401).json({ error: 'unauthorized' })
    }
    const payload = verifyAccessToken(auth.slice(7), tokenSecret, resource)
    if (!payload || payload.sub !== ownerUserId) {
      res.setHeader('WWW-Authenticate', `Bearer error="invalid_token", resource_metadata="${metadata}"`)
      return res.status(401).json({ error: 'invalid_token' })
    }
    if (!checkRate(`claude-mcp:${payload.client_id}`)) {
      return res.status(429).json({ error: 'too many requests' })
    }
    req.oauth = payload
    return next()
  }
}
