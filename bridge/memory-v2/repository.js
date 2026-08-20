const ACTORS = new Set(['gpt', 'claude'])

function fixedActor(actor) {
  if (!ACTORS.has(actor)) throw new TypeError('A fixed Memory V2 actor is required')
  return actor
}

function boundedLimit(value, fallback, maximum) {
  const parsed = Number.parseInt(value, 10)
  if (!Number.isFinite(parsed) || parsed < 1) return fallback
  return Math.min(parsed, maximum)
}

function finiteVector(value) {
  if (!Array.isArray(value) || value.length !== 1536) {
    throw new TypeError('Memory V2 embeddings must contain exactly 1536 values')
  }
  const vector = value.map(Number)
  if (!vector.every(Number.isFinite)) throw new TypeError('Memory V2 embedding is not finite')
  return vector
}

export class SupabaseMemoryV2Repository {
  constructor({ rest, ownerId }) {
    if (typeof rest !== 'function') throw new TypeError('A Supabase REST function is required')
    if (!ownerId) throw new TypeError('A fixed owner id is required')
    this.rest = rest
    this.ownerId = ownerId
  }

  async rpc(name, body) {
    const payload = await this.rest('POST', `rpc/${name}`, body)
    return Array.isArray(payload) ? payload[0] : payload
  }

  remember(actor, content, options = {}) {
    return this.rpc('memory_v2_remember', {
      p_owner_id: this.ownerId,
      p_actor: fixedActor(actor),
      p_content: content,
      p_options: options,
    })
  }

  revise(actor, memoryId, content, options = {}) {
    return this.rpc('memory_v2_revise', {
      p_owner_id: this.ownerId,
      p_actor: fixedActor(actor),
      p_memory_id: memoryId,
      p_content: content,
      p_options: options,
    })
  }

  recallLexical(actor, { query = '', limit = 30 } = {}) {
    return this.rpc('memory_v2_recall_lexical', {
      p_owner_id: this.ownerId,
      p_actor: fixedActor(actor),
      p_query: String(query),
      p_limit: boundedLimit(limit, 30, 50),
    })
  }

  recallSemantic(actor, { vector, model, limit = 30 }) {
    return this.rpc('memory_v2_recall_semantic', {
      p_owner_id: this.ownerId,
      p_actor: fixedActor(actor),
      p_query_embedding: finiteVector(vector),
      p_model: String(model || ''),
      p_limit: boundedLimit(limit, 30, 50),
    })
  }

  storeEmbedding(actor, revisionId, { vector, model }) {
    return this.rpc('memory_v2_store_embedding', {
      p_owner_id: this.ownerId,
      p_actor: fixedActor(actor),
      p_revision_id: revisionId,
      p_model: String(model || ''),
      p_embedding: finiteVector(vector),
    })
  }

  recordRecall(actor, memoryIds, recalledAt) {
    return this.rpc('memory_v2_record_recall', {
      p_owner_id: this.ownerId,
      p_actor: fixedActor(actor),
      p_memory_ids: memoryIds,
      p_recalled_at: recalledAt,
    })
  }

  history(actor, memoryId) {
    return this.rpc('memory_v2_history', {
      p_owner_id: this.ownerId,
      p_actor: fixedActor(actor),
      p_memory_id: memoryId,
    })
  }

  expandSource(actor, sourceId) {
    return this.rpc('memory_v2_expand_source', {
      p_owner_id: this.ownerId,
      p_actor: fixedActor(actor),
      p_source_id: sourceId,
    })
  }

  approveShared(sourceMemoryId) {
    return this.rpc('memory_v2_approve_shared', {
      p_owner_id: this.ownerId,
      p_source_memory_id: sourceMemoryId,
    })
  }
}

