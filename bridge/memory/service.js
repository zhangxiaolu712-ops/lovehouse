import crypto from 'crypto'

import { memoryTypeFromInput, SHARED_STATES } from './model.js'
import { MemoryAccessPolicy } from './accessPolicy.js'
import { NullMemoryAuditSink } from './audit.js'
import { semanticFallbackAllowed } from './embedding.js'

function stringOrNull(value, maximum = 500) {
  if (typeof value !== 'string') return null
  const trimmed = value.trim()
  return trimmed ? trimmed.slice(0, maximum) : null
}

function normalizeTags(input) {
  const values = [
    ...(Array.isArray(input.tags) ? input.tags : []),
    input.tag,
    input.category,
  ]
  return [...new Set(values
    .filter(value => typeof value === 'string' && value.trim())
    .map(value => value.trim().slice(0, 80)))]
}

function normalizeImportance(value) {
  const parsed = Number.parseInt(value, 10)
  if (!Number.isFinite(parsed)) return 1
  return Math.min(5, Math.max(1, parsed))
}

function normalizeEmotion(input) {
  const provided = input.emotion && typeof input.emotion === 'object'
    ? input.emotion
    : {}
  return {
    label: stringOrNull(provided.label || input.mood, 80),
    note: stringOrNull(provided.note || input.feeling, 1000),
    intensity: Number.isFinite(provided.intensity)
      ? Math.min(1, Math.max(0, provided.intensity))
      : null,
  }
}

function normalizeRetention(input) {
  const value = stringOrNull(input.retention || input.level, 40)
  const legacyMap = {
    '固定': 'fixed',
    '长期': 'long',
    '短期': 'short',
    '临时': 'temporary',
  }
  const normalized = legacyMap[value] || value
  return ['fixed', 'long', 'short', 'temporary'].includes(normalized)
    ? normalized
    : null
}

function positiveId(value, label = 'memory_id') {
  const parsed = Number.parseInt(value, 10)
  if (!Number.isSafeInteger(parsed) || parsed < 1) throw new TypeError(`${label} must be a positive integer`)
  return parsed
}

function normalizedMemory(input, { partial = false } = {}) {
  const memory = {}
  const has = key => Object.prototype.hasOwnProperty.call(input, key)
  if (!partial || has('content')) {
    if (typeof input.content !== 'string' || !input.content.trim()) {
      throw new TypeError('content is required')
    }
    if (input.content.length > 50_000) throw new TypeError('content is too long')
    memory.content = input.content.trim()
  }
  if (!partial || has('title')) memory.title = stringOrNull(input.title, 500)
  if (!partial || has('memory_type') || has('memoryType') || has('kind') || has('tag')) {
    memory.memory_type = memoryTypeFromInput(input)
  }
  if (!partial || has('tags') || has('tag') || has('category')) memory.tags = normalizeTags(input)
  if (!partial || has('emotion') || has('mood') || has('feeling')) memory.emotion = normalizeEmotion(input)
  if (!partial || has('importance')) memory.importance = normalizeImportance(input.importance)
  if (!partial || has('retention') || has('level')) memory.retention = normalizeRetention(input)
  if (!partial || has('author')) memory.author = stringOrNull(input.author, 200)
  if (!partial && (has('source_ref') || has('sourceRef'))) {
    memory.source_ref = stringOrNull(input.source_ref || input.sourceRef, 500)
  }
  return memory
}

export class MemoryService {
  constructor({
    repository,
    accessPolicy = new MemoryAccessPolicy(),
    auditSink = new NullMemoryAuditSink(),
    writeEnabled = false,
    semanticRecallEnabled = false,
    embeddingProvider = null,
    rankingProfile = 'ranking_v1',
    clock = () => new Date(),
  }) {
    if (!repository) throw new Error('MemoryRepository is required')
    if (typeof auditSink?.record !== 'function') throw new Error('Memory audit sink is required')
    this.repository = repository
    this.accessPolicy = accessPolicy
    this.auditSink = auditSink
    // A caller cannot enable writes with a flag alone. Phase-one writes are
    // available only when an explicitly persistent audit sink is installed.
    this.writeEnabled = writeEnabled === true && auditSink.persistent === true
    this.semanticRecallEnabled = semanticRecallEnabled === true
    this.embeddingProvider = embeddingProvider
    this.rankingProfile = rankingProfile
    this.clock = clock
  }

  requestContext(context = {}) {
    return {
      requestId: context.requestId || crypto.randomUUID(),
    }
  }

  async audited(actor, action, context, operation) {
    const trusted = this.requestContext(context)
    try {
      const result = await operation(trusted)
      const rows = Array.isArray(result) ? result : result ? [result] : []
      if (this.repository.transactionalAudit !== true) {
        await this.auditSink.record({
          actor,
          action,
          allowed: true,
          request_id: trusted.requestId,
          memory_id: rows.length === 1 ? rows[0]?.id || null : null,
          result_count: rows.length,
          result_spaces: [...new Set(rows.map(row => row?.space_key).filter(Boolean))],
          occurred_at: this.clock().toISOString(),
        })
      }
      return result
    } catch (error) {
      if (error?.auditPersisted !== true) {
        await this.auditSink.record({
          actor,
          action,
          allowed: false,
          result: 'error',
          request_id: trusted.requestId,
          memory_id: error?.audit?.memory_id || null,
          target_space: error?.audit?.target_space || null,
          reason_code: error?.code || error?.name || 'MEMORY_OPERATION_FAILED',
          occurred_at: this.clock().toISOString(),
        })
      }
      throw error
    }
  }

  async write(actor, input = {}, context = {}) {
    return this.audited(actor, 'remember', context, async trusted => {
      if (!this.writeEnabled) {
        const error = new Error('Memory writes require persistent audit storage')
        error.code = 'MEMORY_WRITES_DISABLED'
        throw error
      }
      this.accessPolicy.assertActor(actor)
      this.accessPolicy.assertNoSpaceOverride(input)

      const entry = {
        ...normalizedMemory(input),
      }
      return this.repository.remember(entry, {
        actor,
        requestId: trusted.requestId,
      })
    })
  }

  async get(actor, id, context = {}) {
    return this.audited(actor, 'get', context, async trusted => {
      this.accessPolicy.assertActor(actor)
      const memoryId = positiveId(id)
      const memory = await this.repository.getById(memoryId, {
        scope: this.accessPolicy.readScopeFor(actor),
        requestId: trusted.requestId,
      })
      if (!memory) return null
      try {
        this.accessPolicy.assertCanRead(actor, memory)
      } catch (error) {
        error.audit = {
          memory_id: memoryId,
          target_space: memory.space_key,
        }
        throw error
      }
      return memory
    })
  }

  async list(actor, input = {}, context = {}) {
    return this.audited(actor, 'list', context, async trusted => {
      this.accessPolicy.assertActor(actor)
      this.accessPolicy.assertNoSpaceOverride(input)
      const rows = await this.repository.list({
        scope: this.accessPolicy.readScopeFor(actor),
        limit: input.limit,
        cursorId: input.cursor ? positiveId(input.cursor, 'cursor') : null,
        memoryType: input.memory_type || input.memoryType,
        tags: normalizeTags(input),
        retention: normalizeRetention(input),
        requestId: trusted.requestId,
      })
      return rows.filter(row => this.accessPolicy.canRead(actor, row))
    })
  }

  async recall(actor, input = {}, context = {}) {
    return this.audited(actor, 'recall', context, async trusted => {
      this.accessPolicy.assertActor(actor)
      this.accessPolicy.assertNoSpaceOverride(input)
      if (typeof input.query !== 'string' || !input.query.trim()) {
        throw new TypeError('query is required')
      }
      const search = {
        scope: this.accessPolicy.readScopeFor(actor),
        query: input.query.trim(),
        limit: input.limit,
        cursorId: input.cursor ? positiveId(input.cursor, 'cursor') : null,
        tags: normalizeTags(input),
        requestId: trusted.requestId,
      }
      let rows
      if (this.semanticRecallEnabled) {
        try {
          if (typeof this.embeddingProvider?.embed !== 'function') {
            const error = new Error('Embedding provider is unavailable')
            error.code = 'MEMORY_EMBEDDING_NOT_CONFIGURED'
            error.semanticFallbackAllowed = true
            throw error
          }
          const generated = await this.embeddingProvider.embed(search.query)
          rows = await this.repository.hybridSearch({
            ...search,
            queryEmbedding: generated.vector,
            rankingProfile: this.rankingProfile,
          })
        } catch (error) {
          if (!semanticFallbackAllowed(error)) throw error
          await this.auditSink.record({
            actor,
            action: 'recall_semantic_fallback',
            allowed: true,
            result: 'allowed',
            request_id: trusted.requestId,
            reason_code: error.code || 'MEMORY_SEMANTIC_UNAVAILABLE',
            result_count: 0,
            result_spaces: [],
            occurred_at: this.clock().toISOString(),
          })
          rows = await this.repository.search(search)
        }
      } else {
        rows = await this.repository.search(search)
      }
      // Defense in depth: never trust a repository/backend filter by itself.
      return rows.filter(row => this.accessPolicy.canRead(actor, row))
    })
  }

  async revise(actor, input = {}, context = {}) {
    return this.audited(actor, 'revise', context, async trusted => {
      if (!this.writeEnabled) {
        const error = new Error('Memory writes require persistent audit storage')
        error.code = 'MEMORY_WRITES_DISABLED'
        throw error
      }
      this.accessPolicy.assertActor(actor)
      this.accessPolicy.assertNoSpaceOverride(input)
      const memoryId = positiveId(input.memory_id || input.memoryId)
      const reason = stringOrNull(input.reason, 1000)
      if (!reason) throw new TypeError('reason is required')
      const patch = normalizedMemory(input, { partial: true })
      if (Object.keys(patch).length === 0) throw new TypeError('a memory change is required')
      return this.repository.revise(memoryId, patch, reason, {
        actor,
        requestId: trusted.requestId,
      })
    })
  }

  async proposeShared(actor, input = {}, context = {}) {
    return this.audited(actor, 'propose_shared', context, async trusted => {
      if (!this.writeEnabled) {
        const error = new Error('Memory writes require persistent audit storage')
        error.code = 'MEMORY_WRITES_DISABLED'
        throw error
      }
      this.accessPolicy.assertActor(actor)
      this.accessPolicy.assertNoSpaceOverride(input)
      const memoryId = positiveId(input.memory_id || input.memoryId)
      const reason = stringOrNull(input.reason, 1000)
      if (!reason) throw new TypeError('reason is required')
      return this.repository.proposeShared(memoryId, reason, {
        actor,
        requestId: trusted.requestId,
      })
    })
  }

  async starterPack(actor, input = {}, context = {}) {
    const memories = await this.list(actor, { limit: input.limit || 10 }, context)
    return {
      actor,
      private_memories: memories.filter(memory => memory.space_key === actor),
      shared_memories: memories.filter(memory => (
        memory.space_key === 'shared'
        && memory.shared_status === SHARED_STATES.APPROVED
      )),
    }
  }
}
