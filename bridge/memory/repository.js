function clampLimit(value, fallback, maximum) {
  const parsed = Number.parseInt(value, 10)
  if (!Number.isFinite(parsed) || parsed < 1) return fallback
  return Math.min(parsed, maximum)
}

function sanitizeSearchTerm(value) {
  return String(value || '').trim().replace(/[,*()]/g, ' ').slice(0, 500)
}

function sanitizeProfile(value, fallback) {
  const profile = String(value || fallback || '').trim()
  if (!/^[a-z0-9][a-z0-9._-]{0,79}$/.test(profile)) {
    const error = new Error('A server-selected memory behavior profile is required')
    error.code = 'INVALID_MEMORY_BEHAVIOR_PROFILE'
    throw error
  }
  return profile
}

function sanitizeVector(value) {
  if (!Array.isArray(value) || value.length < 1 || value.length > 2_000) {
    const error = new Error('A bounded server-generated query embedding is required')
    error.code = 'INVALID_MEMORY_QUERY_EMBEDDING'
    throw error
  }
  const vector = value.map(Number)
  if (!vector.every(Number.isFinite)) {
    const error = new Error('Query embedding contains a non-finite value')
    error.code = 'INVALID_MEMORY_QUERY_EMBEDDING'
    throw error
  }
  return vector
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
 * Repository for the canonical Memory Runtime. It never queries a memory table
 * directly; every operation goes through a server-selected fixed-actor RPC.
 */
export class SupabaseMemoryRepository {
  transactionalAudit = true

  constructor({ rest, ownerId }) {
    if (typeof rest !== 'function') throw new Error('A Supabase REST function is required')
    this.rest = rest
    this.ownerId = ownerId
  }

  requireOwnerId() {
    if (!this.ownerId) {
      const error = new Error('OWNER_USER_ID is required for canonical memory access')
      error.code = 'MEMORY_OWNER_NOT_CONFIGURED'
      throw error
    }
    return this.ownerId
  }

  unwrapEnvelope(payload) {
    const envelope = Array.isArray(payload) ? payload[0] : payload
    if (!envelope || typeof envelope !== 'object') {
      const error = new Error('Memory Runtime returned an invalid response')
      error.code = 'INVALID_MEMORY_RUNTIME_RESPONSE'
      throw error
    }
    if (envelope.ok === false) {
      const error = new Error(envelope.message || 'Memory Runtime rejected the operation')
      error.code = envelope.error_code || 'MEMORY_OPERATION_FAILED'
      error.auditPersisted = envelope.audit_persisted === true
      throw error
    }
    return envelope
  }

  runtimePath(operation, actor) {
    return `rpc/memory_runtime_${operation}_${actor}`
  }

  async remember(entry, { actor, requestId }) {
    const envelope = this.unwrapEnvelope(await this.rest(
      'POST',
      this.runtimePath('remember', actor),
      {
        p_owner_id: this.requireOwnerId(),
        p_request_id: requestId,
        p_memory: entry,
      }
    ))
    return envelope.memory
  }

  async getById(id, { scope, requestId }) {
    const actor = actorFromScope(scope)
    const envelope = this.unwrapEnvelope(await this.rest('POST', this.runtimePath('get', actor), {
      p_owner_id: this.requireOwnerId(),
      p_request_id: requestId,
      p_memory_id: id,
    }))
    return envelope.memory || null
  }

  async list({ scope, limit = 20, cursorId = null, memoryType, tags = [], retention, requestId }) {
    const actor = actorFromScope(scope)
    const safeLimit = clampLimit(limit, 20, 50)
    const envelope = this.unwrapEnvelope(await this.rest('POST', this.runtimePath('list', actor), {
      p_owner_id: this.requireOwnerId(),
      p_request_id: requestId,
      p_limit: safeLimit,
      p_cursor_id: cursorId,
      p_memory_type: memoryType || null,
      p_tags: tags,
      p_retention: retention || null,
    }))
    return envelope.items || []
  }

  async search({ scope, query, limit = 5, cursorId = null, tags = [], requestId }) {
    const actor = actorFromScope(scope)
    const safeLimit = clampLimit(limit, 5, 10)
    const term = sanitizeSearchTerm(query)
    const envelope = this.unwrapEnvelope(await this.rest('POST', this.runtimePath('recall', actor), {
      p_owner_id: this.requireOwnerId(),
      p_request_id: requestId,
      p_query: term,
      p_limit: safeLimit,
      p_cursor_id: cursorId,
      p_tags: tags,
    }))
    return envelope.items || []
  }

  async hybridSearch({
    scope,
    query,
    queryEmbedding,
    rankingProfile = 'ranking_v1',
    limit = 5,
    cursorId = null,
    tags = [],
    requestId,
  }) {
    const actor = actorFromScope(scope)
    const safeLimit = clampLimit(limit, 5, 10)
    const term = sanitizeSearchTerm(query)
    const vector = sanitizeVector(queryEmbedding)
    const profile = sanitizeProfile(rankingProfile, 'ranking_v1')
    const envelope = this.unwrapEnvelope(await this.rest(
      'POST',
      `rpc/memory_behavior_recall_${actor}`,
      {
        p_owner_id: this.requireOwnerId(),
        p_request_id: requestId,
        p_query: term,
        p_query_embedding: vector,
        p_ranking_profile: profile,
        p_limit: safeLimit,
        p_cursor_id: cursorId,
        p_tags: tags,
      }
    ))
    return envelope.items || []
  }

  async claimEmbeddings({ actor, limit = 4, requestId }) {
    if (!['gpt', 'claude'].includes(actor)) throw new Error('A fixed memory actor is required')
    const envelope = this.unwrapEnvelope(await this.rest(
      'POST',
      `rpc/memory_behavior_claim_embeddings_${actor}`,
      {
        p_owner_id: this.requireOwnerId(),
        p_request_id: requestId,
        p_limit: clampLimit(limit, 4, 8),
      }
    ))
    return envelope.items || []
  }

  async completeEmbedding(id, vector, { actor, requestId }) {
    if (!['gpt', 'claude'].includes(actor)) throw new Error('A fixed memory actor is required')
    return this.unwrapEnvelope(await this.rest(
      'POST',
      `rpc/memory_behavior_complete_embedding_${actor}`,
      {
        p_owner_id: this.requireOwnerId(),
        p_request_id: requestId,
        p_embedding_id: id,
        p_embedding: sanitizeVector(vector),
      }
    ))
  }

  async failEmbedding(id, reasonCode, { actor, requestId }) {
    if (!['gpt', 'claude'].includes(actor)) throw new Error('A fixed memory actor is required')
    return this.unwrapEnvelope(await this.rest(
      'POST',
      `rpc/memory_behavior_fail_embedding_${actor}`,
      {
        p_owner_id: this.requireOwnerId(),
        p_request_id: requestId,
        p_embedding_id: id,
        p_reason_code: String(reasonCode || 'MEMORY_EMBEDDING_FAILED').slice(0, 100),
      }
    ))
  }

  async revise(id, patch, reason, { actor, requestId }) {
    const envelope = this.unwrapEnvelope(await this.rest(
      'POST',
      this.runtimePath('revise', actor),
      {
        p_owner_id: this.requireOwnerId(),
        p_request_id: requestId,
        p_memory_id: id,
        p_patch: patch,
        p_reason: reason,
      }
    ))
    return envelope.memory
  }

  async proposeShared(id, reason, { actor, requestId }) {
    const envelope = this.unwrapEnvelope(await this.rest(
      'POST',
      this.runtimePath('propose_shared', actor),
      {
        p_owner_id: this.requireOwnerId(),
        p_request_id: requestId,
        p_memory_id: id,
        p_reason: reason,
      }
    ))
    return envelope.memory
  }
}
