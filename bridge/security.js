import crypto from 'crypto'

const BASE64URL_RE = /^[A-Za-z0-9_-]+$/
const PKCE_VERIFIER_RE = /^[A-Za-z0-9._~-]+$/

export function safeEqual(left, right) {
  const leftHash = crypto.createHash('sha256').update(String(left || '')).digest()
  const rightHash = crypto.createHash('sha256').update(String(right || '')).digest()
  return crypto.timingSafeEqual(leftHash, rightHash)
}

export function validateRedirectUri(value) {
  if (typeof value !== 'string' || value.length < 8 || value.length > 2048) return false
  try {
    const url = new URL(value)
    if (url.hash || url.username || url.password) return false
    if (url.protocol === 'https:') return true
    const loopback = url.hostname === 'localhost'
      || url.hostname === '127.0.0.1'
      || url.hostname === '[::1]'
    return url.protocol === 'http:' && loopback
  } catch {
    return false
  }
}
export function validateRedirectUris(values) {
  return Array.isArray(values)
    && values.length > 0
    && values.length <= 5
    && values.every(validateRedirectUri)
}

export function validatePkce(challenge, method) {
  return method === 'S256'
    && typeof challenge === 'string'
    && challenge.length >= 43
    && challenge.length <= 128
    && BASE64URL_RE.test(challenge)
}

export function hashPkceVerifier(verifier) {
  if (typeof verifier !== 'string' || verifier.length < 43 || verifier.length > 128) return null
  if (!PKCE_VERIFIER_RE.test(verifier)) return null
  return crypto.createHash('sha256').update(verifier).digest('base64url')
}

export function issueAccessToken({
  clientId,
  ownerUserId,
  audience,
  secret,
  scope = 'mcp:tools',
  ttlSeconds = 30 * 24 * 60 * 60,
}) {
  if (!secret || secret.length < 32) {
    throw new Error('OAUTH_TOKEN_SECRET must contain at least 32 characters')
  }
  if (!audience) throw new Error('access token audience is required')
  const now = Math.floor(Date.now() / 1000)
  const payload = {
    iss: 'lovehouse-bridge',
    sub: ownerUserId,
    aud: audience,
    client_id: clientId,
    scope,
    iat: now,
    exp: now + ttlSeconds,
    jti: crypto.randomBytes(16).toString('hex'),
  }
  const encoded = Buffer.from(JSON.stringify(payload)).toString('base64url')
  const signature = crypto.createHmac('sha256', secret).update(encoded).digest('base64url')
  return `lh1.${encoded}.${signature}`
}

export function verifyAccessToken(token, secret, expectedAudience) {
  if (!secret || secret.length < 32 || typeof token !== 'string') return null
  const [version, encoded, signature, extra] = token.split('.')
  if (version !== 'lh1' || !encoded || !signature || extra) return null
  const expected = crypto.createHmac('sha256', secret).update(encoded).digest('base64url')
  if (!safeEqual(signature, expected)) return null

  try {
    const payload = JSON.parse(Buffer.from(encoded, 'base64url').toString('utf8'))
    const now = Math.floor(Date.now() / 1000)
    if (payload.iss !== 'lovehouse-bridge') return null
    if (!expectedAudience || payload.aud !== expectedAudience) return null
    const scopes = typeof payload.scope === 'string' ? payload.scope.split(/\s+/).filter(Boolean) : []
    if (!payload.sub || !payload.client_id || !scopes.includes('mcp:tools')) return null
    if (scopes.some(scope => !['mcp:tools', 'offline_access'].includes(scope))) return null
    if (!Number.isInteger(payload.iat) || !Number.isInteger(payload.exp)) return null
    if (payload.iat > now + 60 || payload.exp <= now) return null
    return payload
  } catch {
    return null
  }
}
