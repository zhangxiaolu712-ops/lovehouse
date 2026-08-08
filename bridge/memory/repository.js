const DEFAULT_TABLE = 'memory_entries'

function clampLimit(value, fallback, maximum) {
  const parsed = Number.parseInt(value, 10)
  if (!Number.isFinite(parsed) || parsed < 1) return fallback
  return Math.min(parsed, maximum)
}

function sanitizeSearchTerm(value) {
  return String(value || '').trim().replace(/[,*()]/g, ' ').slice(0, 500)
}

function scopeFilter(scope) {
  return `or=(space_key.eq.${scope.privateSpace},and(space_key.eq.${scope.sharedSpace},shared_status.eq.${scope.requiredSharedState}))`
}

export function createSupabaseRest({ url, serverKey, fetchImpl = fetch }) {
  return async function supabaseRest(method, path, body) {
    if (!url) throw new Error('SUPABASE_URL is not configured')
    if (!serverKey) {
      throw new Error('SUPABASE_SECRET_KEY or SUPABASE_SERVICE_ROLE_KEY is not configured')
    }

    const headers = {
      apikey: serverKey,
      'Content-Type': 'application/json',
    }
    if (!serverKey.startsWith('sb_secret_')) {
      headers.Authorization = `Bearer ${serverKey}`
    }
    if (method === 'GET') headers.Accept = 'application/json'
    if (method === 'POST' || method === 'PATCH') {
      headers.Prefer = 'return=representation'
    }

    const response = await fetchImpl(`${url}/rest/v1/${path}`, {
      method,
      headers,
      body: body === undefined ? undefined : JSON.stringify(body),
    })
    const raw = await response.text()
    let payload = null
    if (raw) {
      try {
        payload = JSON.parse(raw)
      } catch {
        throw new Error(`Supabase returned invalid JSON (${response.status})`)
      }
    }
    if (!response.ok) {
      const detail = payload?.message || payload?.error || 'request failed'
      throw new Error(`Supabase ${method} failed (${response.status}): ${detail}`)
    }
    return payload
  }
}

/**
 * Repository for the future canonical memory_entries table.
 * Phase one intentionally does not create or access that table in production.
 * Old brain/memories tables will later be treated as legacy sources, never as
 * two independent memory engines.
 */
export class SupabaseMemoryRepository {
  constructor({ rest, table = DEFAULT_TABLE }) {
    if (typeof rest !== 'function') throw new Error('A Supabase REST function is required')
    this.rest = rest
    this.table = table
  }

  async insert(entry) {
    const rows = await this.rest('POST', this.table, entry)
    return Array.isArray(rows) ? rows[0] : rows
  }

  async getById(id) {
    const rows = await this.rest('GET', `${this.table}?id=eq.${encodeURIComponent(id)}&limit=1`)
    return rows?.[0] || null
  }

  async list({ scope, limit = 100, memoryType, tags = [], retention }) {
    const safeLimit = clampLimit(limit, 100, 200)
    let path = `${this.table}?${scopeFilter(scope)}&order=created_at.desc&limit=${safeLimit}`
    if (memoryType) path += `&memory_type=eq.${encodeURIComponent(memoryType)}`
    if (tags.length) path += `&tags=cs.${encodeURIComponent(JSON.stringify(tags))}`
    if (retention) path += `&retention=eq.${encodeURIComponent(retention)}`
    return await this.rest('GET', path) || []
  }

  async search({ scope, query, limit = 20, tags = [] }) {
    const safeLimit = clampLimit(limit, 20, 50)
    const term = sanitizeSearchTerm(query)
    let path = `${this.table}?${scopeFilter(scope)}&order=importance.desc,created_at.desc&limit=${safeLimit}`
    if (term) path += `&content=ilike.${encodeURIComponent(`%${term}%`)}`
    if (tags.length) path += `&tags=cs.${encodeURIComponent(JSON.stringify(tags))}`
    return await this.rest('GET', path) || []
  }
}
