const DEFAULT_TABLE = 'memory_entries'

function clampLimit(value, fallback, maximum) {
  const parsed = Number.parseInt(value, 10)
  if (!Number.isFinite(parsed) || parsed < 1) return fallback
  return Math.min(parsed, maximum)
}

function sanitizeSearchTerm(value) {
  return String(value || '').trim().replace(/[,*()]/g, ' ').slice(0, 500)
}

function actorFromScope(scope) {
  if (
    !['gpt', 'claude'].includes(scope?.privateSpace)
    || scope?.sharedSpace !== 'shared'
    || scope?.requiredSharedState !== 'approved'
  ) {
    const error = new Error('A canonical server-created memory scope is required')
    error.code = 'INVALID_MEMORY_SCOPE'
    throw error
  }
  return scope.privateSpace
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
  constructor({ rest, ownerId, table = DEFAULT_TABLE }) {
    if (typeof rest !== 'function') throw new Error('A Supabase REST function is required')
    this.rest = rest
    this.ownerId = ownerId
    this.table = table
  }

  requireOwnerId() {
    if (!this.ownerId) {
      const error = new Error('OWNER_USER_ID is required for canonical memory access')
      error.code = 'MEMORY_OWNER_NOT_CONFIGURED'
      throw error
    }
    return this.ownerId
  }

  async insert(entry) {
    const ownerId = this.requireOwnerId()
    const rows = await this.rest('POST', this.table, {
      ...entry,
      owner_id: ownerId,
    })
    return Array.isArray(rows) ? rows[0] : rows
  }

  async getById(id, { scope }) {
    const actor = actorFromScope(scope)
    const rows = await this.rest('POST', `rpc/memory_get_${actor}`, {
      p_owner_id: this.requireOwnerId(),
      p_memory_id: id,
    })
    return rows?.[0] || null
  }

  async list({ scope, limit = 100, memoryType, tags = [], retention }) {
    const actor = actorFromScope(scope)
    const safeLimit = clampLimit(limit, 100, 200)
    return await this.rest('POST', `rpc/memory_list_${actor}`, {
      p_owner_id: this.requireOwnerId(),
      p_limit: safeLimit,
      p_memory_type: memoryType || null,
      p_tags: tags,
      p_retention: retention || null,
    }) || []
  }

  async search({ scope, query, limit = 20, tags = [] }) {
    const actor = actorFromScope(scope)
    const safeLimit = clampLimit(limit, 20, 50)
    const term = sanitizeSearchTerm(query)
    return await this.rest('POST', `rpc/memory_recall_${actor}`, {
      p_owner_id: this.requireOwnerId(),
      p_query: term,
      p_limit: safeLimit,
      p_tags: tags,
    }) || []
  }
}
