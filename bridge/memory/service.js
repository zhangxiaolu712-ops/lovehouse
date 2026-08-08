import { memoryTypeFromInput, SHARED_STATES } from './model.js'
import { MemoryAccessPolicy } from './accessPolicy.js'
import { NullMemoryAuditSink } from './audit.js'

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
  return stringOrNull(input.retention || input.level, 40)
}

export class MemoryService {
  constructor({
    repository,
    accessPolicy = new MemoryAccessPolicy(),
    auditSink = new NullMemoryAuditSink(),
    clock = () => new Date(),
  }) {
    if (!repository) throw new Error('MemoryRepository is required')
    if (typeof auditSink?.record !== 'function') throw new Error('Memory audit sink is required')
    this.repository = repository
    this.accessPolicy = accessPolicy
    this.auditSink = auditSink
    this.clock = clock
  }

  async audited(actor, action, operation) {
    try {
      const result = await operation()
      const rows = Array.isArray(result) ? result : result ? [result] : []
      await this.auditSink.record({
        actor,
        action,
        allowed: true,
        memory_id: rows.length === 1 ? rows[0]?.id || null : null,
        result_count: rows.length,
        result_spaces: [...new Set(rows.map(row => row?.space_key).filter(Boolean))],
        occurred_at: this.clock().toISOString(),
      })
      return result
    } catch (error) {
      await this.auditSink.record({
        actor,
        action,
        allowed: false,
        reason_code: error?.code || error?.name || 'MEMORY_OPERATION_FAILED',
        occurred_at: this.clock().toISOString(),
      })
      throw error
    }
  }

  async write(actor, input = {}) {
    return this.audited(actor, 'write', async () => {
      this.accessPolicy.assertActor(actor)
      this.accessPolicy.assertNoSpaceOverride(input)
      if (typeof input.content !== 'string' || !input.content.trim()) {
        throw new TypeError('content is required')
      }
      if (input.content.length > 50_000) throw new TypeError('content is too long')

      const now = this.clock().toISOString()
      const entry = {
        space_key: this.accessPolicy.privateSpaceFor(actor),
        shared_status: null,
        content: input.content.trim(),
        title: stringOrNull(input.title, 500),
        memory_type: memoryTypeFromInput(input),
        tags: normalizeTags(input),
        emotion: normalizeEmotion(input),
        importance: normalizeImportance(input.importance),
        retention: normalizeRetention(input),
        decay_score: 1,
        decay_updated_at: now,
        source: {
          source_type: 'mcp',
          source_model: actor,
          source_ref: stringOrNull(input.source_ref || input.sourceRef, 500),
        },
        revision_number: 1,
        revision_of: null,
        created_by_actor: actor,
        created_at: now,
        updated_at: now,
      }
      return this.repository.insert(entry)
    })
  }

  async get(actor, id) {
    return this.audited(actor, 'read', async () => {
      this.accessPolicy.assertActor(actor)
      const memory = await this.repository.getById(id)
      if (!memory) return null
      this.accessPolicy.assertCanRead(actor, memory)
      return memory
    })
  }

  async list(actor, input = {}) {
    return this.audited(actor, 'list', async () => {
      this.accessPolicy.assertActor(actor)
      this.accessPolicy.assertNoSpaceOverride(input)
      const rows = await this.repository.list({
        scope: this.accessPolicy.readScopeFor(actor),
        limit: input.limit,
        memoryType: input.memory_type || input.memoryType,
        tags: normalizeTags(input),
        retention: normalizeRetention(input),
      })
      return rows.filter(row => this.accessPolicy.canRead(actor, row))
    })
  }

  async recall(actor, input = {}) {
    return this.audited(actor, 'recall', async () => {
      this.accessPolicy.assertActor(actor)
      this.accessPolicy.assertNoSpaceOverride(input)
      if (typeof input.query !== 'string' || !input.query.trim()) {
        throw new TypeError('query is required')
      }
      const rows = await this.repository.search({
        scope: this.accessPolicy.readScopeFor(actor),
        query: input.query.trim(),
        limit: input.limit,
        tags: normalizeTags(input),
      })
      // Defense in depth: never trust a repository/backend filter by itself.
      return rows.filter(row => this.accessPolicy.canRead(actor, row))
    })
  }

  async starterPack(actor, input = {}) {
    const memories = await this.list(actor, { limit: input.limit || 20 })
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
