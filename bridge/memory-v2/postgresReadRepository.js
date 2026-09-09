import pg from 'pg'

import { boundedLimit, fixedActor } from './repository.js'

const CALLS = Object.freeze({
  recallLexical: 'select public.memory_v2_recall_lexical($1::uuid, $2::text, $3::text, $4::integer) as result',
  starterPack: 'select public.memory_v2_starter_pack_candidates($1::uuid, $2::text) as result',
  recallSemantic: 'select public.memory_v2_recall_semantic($1::uuid, $2::text, $3::real[], $4::text, $5::integer) as result',
  history: 'select public.memory_v2_history($1::uuid, $2::text, $3::uuid) as result',
  expandSource: 'select public.memory_v2_expand_source($1::uuid, $2::text, $3::uuid) as result',
  timeline: 'select public.memory_v2_timeline($1::uuid, $2::text, $3::text, $4::integer) as result',
})

function finiteVector(value) {
  if (!Array.isArray(value) || value.length !== 1536) {
    throw new TypeError('Memory V2 embeddings must contain exactly 1536 values')
  }
  const vector = value.map(Number)
  if (!vector.every(Number.isFinite)) throw new TypeError('Memory V2 embedding is not finite')
  return vector
}

export class PostgresMemoryV2ReadRepository {
  constructor({ connectionString, ownerId, pool = null, ssl = { rejectUnauthorized: false } }) {
    if (!ownerId) throw new TypeError('A fixed owner id is required')
    if (!pool && !connectionString) throw new TypeError('LIFE_MEMORY_READ_DATABASE_URL is required')
    this.ownerId = ownerId
    this.pool = pool || new pg.Pool({ connectionString, ssl, max: 5, idleTimeoutMillis: 30_000 })
  }

  async call(sql, values) {
    const result = await this.pool.query(sql, values)
    return result.rows[0]?.result ?? null
  }

  recallLexical(actor, { query = '', limit = 30 } = {}) {
    return this.call(CALLS.recallLexical, [
      this.ownerId, fixedActor(actor), String(query), boundedLimit(limit, 30, 50),
    ])
  }

  async starterPackCandidates(actor) {
    const payload = await this.call(CALLS.starterPack, [this.ownerId, fixedActor(actor)])
    if (!Array.isArray(payload)) return []
    return payload.length === 1 && Array.isArray(payload[0]) ? payload[0] : payload
  }

  recallSemantic(actor, { vector, model, limit = 30 }) {
    return this.call(CALLS.recallSemantic, [
      this.ownerId, fixedActor(actor), finiteVector(vector), String(model || ''),
      boundedLimit(limit, 30, 50),
    ])
  }

  history(actor, memoryId) {
    return this.call(CALLS.history, [this.ownerId, fixedActor(actor), memoryId])
  }

  expandSource(actor, sourceId) {
    return this.call(CALLS.expandSource, [this.ownerId, fixedActor(actor), sourceId])
  }

  timeline(actor, { limit = 60, query = '' } = {}) {
    return this.call(CALLS.timeline, [
      this.ownerId, fixedActor(actor), String(query || '').trim().slice(0, 500),
      boundedLimit(limit, 60, 100),
    ])
  }
}

const READ_METHODS = Object.freeze([
  'recallLexical',
  'starterPackCandidates',
  'recallSemantic',
  'history',
  'expandSource',
  'timeline',
])

/** Keeps every mutation on the legacy repository while replacing only reads. */
export class ReadCutoverMemoryV2Repository {
  constructor({ legacy, postgres }) {
    if (!legacy || !postgres) throw new TypeError('Legacy and PostgreSQL repositories are required')
    this.legacy = legacy
    this.postgres = postgres
    for (const method of READ_METHODS) this[method] = (...args) => this.postgres[method](...args)
  }

  remember(...args) { return this.legacy.remember(...args) }
  revise(...args) { return this.legacy.revise(...args) }
  storeEmbedding(...args) { return this.legacy.storeEmbedding(...args) }
  recordRecall(...args) { return this.legacy.recordRecall(...args) }
  archive(...args) { return this.legacy.archive(...args) }
  approveShared(...args) { return this.legacy.approveShared(...args) }
}
