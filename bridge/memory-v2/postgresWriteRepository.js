import pg from 'pg'

import { fixedActor } from './repository.js'

const CALLS = Object.freeze({
  remember: 'select public.memory_v2_remember($1::uuid, $2::text, $3::text, $4::jsonb) as result',
  revise: 'select public.memory_v2_revise($1::uuid, $2::text, $3::uuid, $4::text, $5::jsonb) as result',
  storeEmbedding: 'select public.memory_v2_store_embedding($1::uuid, $2::text, $3::uuid, $4::text, $5::real[]) as result',
  recordRecall: 'select public.memory_v2_record_recall($1::uuid, $2::text, $3::uuid[], $4::timestamptz) as result',
  archive: 'select public.memory_v2_archive($1::uuid, $2::text, $3::uuid) as result',
  approveShared: 'select public.memory_v2_approve_shared($1::uuid, $2::uuid) as result',
})

function finiteVector(value) {
  if (!Array.isArray(value) || value.length !== 1536) {
    throw new TypeError('Memory V2 embeddings must contain exactly 1536 values')
  }
  const vector = value.map(Number)
  if (!vector.every(Number.isFinite)) throw new TypeError('Memory V2 embedding is not finite')
  return vector
}

/** Writes Life Memory V2 through the portable PostgreSQL function contract. */
export class PostgresMemoryV2WriteRepository {
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

  remember(actor, content, options = {}) {
    return this.call(CALLS.remember, [
      this.ownerId, fixedActor(actor), content, options,
    ])
  }

  revise(actor, memoryId, content, options = {}) {
    return this.call(CALLS.revise, [
      this.ownerId, fixedActor(actor), memoryId, content, options,
    ])
  }

  storeEmbedding(actor, revisionId, { vector, model }) {
    return this.call(CALLS.storeEmbedding, [
      this.ownerId, fixedActor(actor), revisionId, String(model || ''), finiteVector(vector),
    ])
  }

  recordRecall(actor, memoryIds, recalledAt) {
    return this.call(CALLS.recordRecall, [
      this.ownerId, fixedActor(actor), memoryIds, recalledAt,
    ])
  }

  archive(actor, memoryId) {
    return this.call(CALLS.archive, [this.ownerId, fixedActor(actor), memoryId])
  }

  approveShared(sourceMemoryId) {
    return this.call(CALLS.approveShared, [this.ownerId, sourceMemoryId])
  }
}

const WRITE_METHODS = Object.freeze([
  'remember',
  'revise',
  'storeEmbedding',
  'recordRecall',
  'archive',
  'approveShared',
])

const READ_METHODS = Object.freeze([
  'recallLexical',
  'starterPackCandidates',
  'recallSemantic',
  'history',
  'expandSource',
  'timeline',
])

/** Keeps the selected read repository while replacing the complete Life Memory write set. */
export class WriteCutoverMemoryV2Repository {
  constructor({ read, postgres }) {
    if (!read || !postgres) throw new TypeError('Read and PostgreSQL write repositories are required')
    this.read = read
    this.postgres = postgres
    for (const method of READ_METHODS) this[method] = (...args) => this.read[method](...args)
    for (const method of WRITE_METHODS) this[method] = (...args) => this.postgres[method](...args)
  }
}
