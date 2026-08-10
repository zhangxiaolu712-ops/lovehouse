const LIVINGROOM_TABLE = 'livingroom'
const LIVINGROOM_FENCE = Symbol('lovehouse.livingroom.fence')

function scopeViolation() {
  const error = new Error('Livingroom access is restricted to the livingroom table')
  error.code = 'LIVINGROOM_SCOPE_VIOLATION'
  return error
}

function invalidResponse(operation, payload) {
  const nestedError = payload && typeof payload === 'object' ? payload.error : null
  const detail = typeof nestedError === 'string'
    ? nestedError
    : nestedError?.message || payload?.message || ''
  const status = Number(payload?.status || nestedError?.status)
  const error = new Error(detail
    ? `Livingroom ${operation} failed: ${detail}`
    : `Livingroom ${operation} returned an invalid response`)
  error.code = detail ? 'LIVINGROOM_UPSTREAM_ERROR' : 'LIVINGROOM_INVALID_RESPONSE'
  if (Number.isInteger(status) && status >= 400 && status <= 599) error.status = status
  if (payload?.code || nestedError?.code) error.upstreamCode = payload?.code || nestedError.code
  return error
}

function unconfirmedWrite() {
  const error = new Error('Livingroom write was not confirmed by Supabase')
  error.code = 'LIVINGROOM_WRITE_NOT_CONFIRMED'
  return error
}

export function validateLivingroomRows(payload, { operation, requireSingleRow = false }) {
  if (!Array.isArray(payload) || payload.some(row => !row || typeof row !== 'object' || Array.isArray(row))) {
    throw invalidResponse(operation, payload)
  }
  if (requireSingleRow && payload.length !== 1) throw unconfirmedWrite()
  return payload
}

export function isLivingroomRest(value) {
  return typeof value === 'function' && value[LIVINGROOM_FENCE] === true
}

/**
 * Restricts the privileged Supabase REST client to the two operations required
 * by the livingroom. Authentication and sender selection happen before this
 * adapter; callers cannot select another table or add another mutation method.
 */
export function createLivingroomRest({ rest }) {
  if (typeof rest !== 'function') throw new TypeError('Supabase REST function is required')

  const livingroomRest = async function livingroomRest(method, path, body) {
    const normalizedMethod = String(method || '').toUpperCase()
    const normalizedPath = String(path || '')
    const readsLivingroom = normalizedMethod === 'GET'
      && (normalizedPath === LIVINGROOM_TABLE || normalizedPath.startsWith(`${LIVINGROOM_TABLE}?`))
    const writesLivingroom = normalizedMethod === 'POST' && normalizedPath === LIVINGROOM_TABLE

    if (!readsLivingroom && !writesLivingroom) throw scopeViolation()
    const payload = await rest(normalizedMethod, normalizedPath, body)
    return validateLivingroomRows(payload, {
      operation: writesLivingroom ? 'write' : 'read',
      requireSingleRow: writesLivingroom,
    })
  }

  Object.defineProperty(livingroomRest, LIVINGROOM_FENCE, { value: true })
  return livingroomRest
}
