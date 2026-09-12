import pg from 'pg'

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

const LIVINGROOM_READ_SQL = `
  select coalesce(jsonb_agg(to_jsonb(room) order by room.created_at desc), '[]'::jsonb) as result
  from (
    select id, sender, message, created_at
    from public.livingroom
    where ($1::timestamptz is null or created_at > $1::timestamptz)
    order by created_at desc
    limit $2::integer
  ) room
`

const LIVINGROOM_WRITE_SQL = `
  with inserted as (
    insert into public.livingroom (sender, message)
    values ($1::text, $2::text)
    returning id, sender, message, created_at
  )
  select to_jsonb(inserted) as result
  from inserted
`

function parseLivingroomReadPath(path) {
  const [table, query = ''] = String(path || '').split('?', 2)
  if (table !== LIVINGROOM_TABLE) throw scopeViolation()
  const params = new URLSearchParams(query)
  for (const key of params.keys()) {
    if (!['order', 'limit', 'created_at'].includes(key)) throw scopeViolation()
  }
  if (params.get('order') !== 'created_at.desc') throw scopeViolation()
  const limit = Number.parseInt(params.get('limit'), 10)
  if (!Number.isInteger(limit) || limit < 1 || limit > 200) throw scopeViolation()
  const createdAt = params.get('created_at')
  if (createdAt !== null && !createdAt.startsWith('gt.')) throw scopeViolation()
  return { limit, since: createdAt === null ? null : createdAt.slice(3) }
}

/** Reads livingroom from PostgreSQL and leaves the existing Supabase write path intact. */
export function createPostgresLivingroomReadCutover({
  legacy,
  connectionString,
  pool = null,
  ssl = { rejectUnauthorized: false },
}) {
  if (!isLivingroomRest(legacy)) throw new TypeError('A fenced legacy livingroom REST function is required')
  if (!pool && !connectionString) throw new TypeError('LIFE_MEMORY_READ_DATABASE_URL is required')
  const postgresPool = pool || new pg.Pool({ connectionString, ssl, max: 5, idleTimeoutMillis: 30_000 })

  const livingroomRest = async function livingroomRest(method, path, body) {
    const normalizedMethod = String(method || '').toUpperCase()
    if (normalizedMethod === 'POST') return legacy(normalizedMethod, path, body)
    if (normalizedMethod !== 'GET') throw scopeViolation()
    const { since, limit } = parseLivingroomReadPath(path)
    const result = await postgresPool.query(LIVINGROOM_READ_SQL, [since, limit])
    return validateLivingroomRows(result.rows[0]?.result ?? [], { operation: 'read' })
  }

  Object.defineProperty(livingroomRest, LIVINGROOM_FENCE, { value: true })
  return livingroomRest
}

/** Writes livingroom to PostgreSQL and preserves the already-selected read path. */
export function createPostgresLivingroomWriteCutover({
  legacy,
  connectionString,
  pool = null,
  ssl = { rejectUnauthorized: false },
}) {
  if (!isLivingroomRest(legacy)) throw new TypeError('A fenced livingroom REST function is required')
  if (!pool && !connectionString) throw new TypeError('LIFE_MEMORY_READ_DATABASE_URL is required')
  const postgresPool = pool || new pg.Pool({ connectionString, ssl, max: 5, idleTimeoutMillis: 30_000 })

  const livingroomRest = async function livingroomRest(method, path, body) {
    const normalizedMethod = String(method || '').toUpperCase()
    if (normalizedMethod === 'GET') return legacy(normalizedMethod, path, body)
    if (normalizedMethod !== 'POST' || String(path || '') !== LIVINGROOM_TABLE) throw scopeViolation()
    if (!body || typeof body !== 'object' || Array.isArray(body)) throw scopeViolation()
    const result = await postgresPool.query(LIVINGROOM_WRITE_SQL, [body.sender, body.message])
    const row = result.rows[0]?.result
    return validateLivingroomRows(row ? [row] : [], {
      operation: 'write',
      requireSingleRow: true,
    })
  }

  Object.defineProperty(livingroomRest, LIVINGROOM_FENCE, { value: true })
  return livingroomRest
}
