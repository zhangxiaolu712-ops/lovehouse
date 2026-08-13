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

function sanitizeEmbeddingIdentity(value, label) {
  const identity = String(value || '').trim()
  if (!/^[A-Za-z0-9][A-Za-z0-9._:/-]{0,99}$/.test(identity)) {
    const error = new Error(`A server-selected ${label} is required`)
    error.code = 'INVALID_MEMORY_EMBEDDING_IDENTITY'
    throw error
  }
  return identity
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

function fixedActor(actor) {
  if (!['gpt', 'claude'].includes(actor)) {
    const error = new Error('A fixed memory actor is required')
    error.code = 'INVALID_MEMORY_ACTOR'
    throw error
  }
  return actor
}

function sanitizeBoundedText(value, label, maximum) {
  const text = String(value || '').trim()
  if (!text || text.length > maximum) {
    const error = new Error(`${label} is required and must not exceed ${maximum} characters`)
    error.code = 'INVALID_MEMORY_BEHAVIOR_INPUT'
    throw error
  }
  return text
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

  async memoryBox({ scope, limit = 3, requestId }) {
    const actor = actorFromScope(scope)
    const envelope = this.unwrapEnvelope(await this.rest(
      'POST',
      this.runtimePath('memory_box', actor),
      {
        p_owner_id: this.requireOwnerId(),
        p_request_id: requestId,
        p_limit: clampLimit(limit, 3, 4),
      }
    ))
    return {
      actor: envelope.actor,
      mode: envelope.mode,
      items: envelope.items || [],
    }
  }

  async hybridSearch({
    scope,
    query,
    queryEmbedding,
    queryEmbeddingProfile,
    queryEmbeddingModel,
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
    const embeddingProfile = sanitizeEmbeddingIdentity(queryEmbeddingProfile, 'embedding profile')
    const embeddingModel = sanitizeEmbeddingIdentity(queryEmbeddingModel, 'embedding model')
    const profile = sanitizeProfile(rankingProfile, 'ranking_v1')
    const envelope = this.unwrapEnvelope(await this.rest(
      'POST',
      `rpc/memory_behavior_recall_${actor}`,
      {
        p_owner_id: this.requireOwnerId(),
        p_request_id: requestId,
        p_query: term,
        p_query_embedding: vector,
        p_query_embedding_profile: embeddingProfile,
        p_query_embedding_model: embeddingModel,
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

  async setAnchor(id, pinned, reason, { actor, requestId }) {
    const memoryId = Number.parseInt(id, 10)
    if (!Number.isSafeInteger(memoryId) || memoryId < 1) throw new TypeError('memory_id must be positive')
    const envelope = this.unwrapEnvelope(await this.rest(
      'POST',
      `rpc/memory_behavior_set_anchor_${fixedActor(actor)}`,
      {
        p_owner_id: this.requireOwnerId(),
        p_request_id: requestId,
        p_memory_id: memoryId,
        p_pinned: pinned === true,
        p_reason: sanitizeBoundedText(reason, 'anchor reason', 1_000),
      }
    ))
    return envelope.anchor
  }

  async listAnchors({ actor }) {
    const envelope = this.unwrapEnvelope(await this.rest(
      'POST',
      `rpc/memory_behavior_list_anchors_${fixedActor(actor)}`,
      { p_owner_id: this.requireOwnerId() }
    ))
    return envelope.items || []
  }

  async enqueueDream({ actor, requestId, perspective, limit = 4 }) {
    const envelope = this.unwrapEnvelope(await this.rest(
      'POST',
      `rpc/memory_behavior_enqueue_dream_${fixedActor(actor)}`,
      {
        p_owner_id: this.requireOwnerId(),
        p_request_id: requestId,
        p_perspective: sanitizeBoundedText(perspective, 'Dream perspective', 500),
        p_limit: clampLimit(limit, 4, 4),
      }
    ))
    return envelope.job || null
  }

  async claimDream({ actor, requestId, providerKey, model }) {
    const envelope = this.unwrapEnvelope(await this.rest(
      'POST',
      `rpc/memory_behavior_claim_dream_${fixedActor(actor)}`,
      {
        p_owner_id: this.requireOwnerId(),
        p_request_id: requestId,
        p_curator_provider: sanitizeEmbeddingIdentity(providerKey, 'Curator provider'),
        p_curator_model: sanitizeEmbeddingIdentity(model, 'Curator model'),
      }
    ))
    return envelope.job || null
  }

  async completeDream(id, outputs, { actor, requestId, providerKey, model }) {
    const jobId = Number.parseInt(id, 10)
    if (!Number.isSafeInteger(jobId) || jobId < 1) throw new TypeError('dream_job_id must be positive')
    if (!Array.isArray(outputs) || outputs.length < 1 || outputs.length > 3) {
      throw new TypeError('Dream completion requires 1-3 bounded outputs')
    }
    return this.unwrapEnvelope(await this.rest(
      'POST',
      `rpc/memory_behavior_complete_dream_${fixedActor(actor)}`,
      {
        p_owner_id: this.requireOwnerId(),
        p_request_id: requestId,
        p_job_id: jobId,
        p_curator_provider: sanitizeEmbeddingIdentity(providerKey, 'Curator provider'),
        p_curator_model: sanitizeEmbeddingIdentity(model, 'Curator model'),
        p_outputs: outputs,
      }
    ))
  }

  async failDream(id, reasonCode, { actor, requestId, providerKey, model }) {
    const jobId = Number.parseInt(id, 10)
    if (!Number.isSafeInteger(jobId) || jobId < 1) throw new TypeError('dream_job_id must be positive')
    return this.unwrapEnvelope(await this.rest(
      'POST',
      `rpc/memory_behavior_fail_dream_${fixedActor(actor)}`,
      {
        p_owner_id: this.requireOwnerId(),
        p_request_id: requestId,
        p_job_id: jobId,
        p_curator_provider: sanitizeEmbeddingIdentity(providerKey, 'Curator provider'),
        p_curator_model: sanitizeEmbeddingIdentity(model, 'Curator model'),
        p_reason_code: sanitizeEmbeddingIdentity(reasonCode || 'MEMORY_DREAM_CURATOR_FAILED', 'Dream reason code'),
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

  async expandSource(id, { actor, requestId }) {
    const sourceId = Number.parseInt(id, 10)
    if (!Number.isSafeInteger(sourceId) || sourceId < 1) {
      throw new TypeError('source_id must be a positive integer')
    }
    const envelope = this.unwrapEnvelope(await this.rest(
      'POST',
      this.runtimePath('expand_source', fixedActor(actor)),
      {
        p_owner_id: this.requireOwnerId(),
        p_request_id: requestId,
        p_source_id: sourceId,
      }
    ))
    return envelope.source
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
