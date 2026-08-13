import crypto from 'crypto'

import { MEMORY_BOX_SCHEMA_VERSION, memoryTypeFromInput, SHARED_STATES } from './model.js'
import { MemoryAccessPolicy } from './accessPolicy.js'
import { NullMemoryAuditSink } from './audit.js'
import { semanticFallbackAllowed } from './embedding.js'
import { bundledHouseRulesProvider, STARTER_PACK_SCHEMA_VERSION } from './houseRules.js'

const unavailableSourceResolver = Object.freeze({
  async resolve() {
    const error = new Error('Memory source expansion is not configured')
    error.code = 'MEMORY_SOURCE_RESOLVER_NOT_CONFIGURED'
    throw error
  },
})

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

function portableLocator(value, label) {
  const encoded = JSON.stringify(value)
  if (encoded.length > 4_000) throw new TypeError(`${label} is too large`)
  const forbiddenKey = /^(window_?id|component_?state|local_?storage|session_?storage|supabase_?(url|path))$/i
  const visit = item => {
    if (Array.isArray(item)) return item.forEach(visit)
    if (!item || typeof item !== 'object') {
      if (typeof item === 'string' && /\.supabase\.co\/rest\/v1/i.test(item)) {
        throw new TypeError(`${label} cannot contain a Supabase REST path`)
      }
      return
    }
    for (const [key, nested] of Object.entries(item)) {
      if (forbiddenKey.test(key)) throw new TypeError(`${label} contains browser or storage state`)
      visit(nested)
    }
  }
  visit(value)
  return structuredClone(value)
}

function normalizeSources(input) {
  if (!Object.prototype.hasOwnProperty.call(input, 'sources')) return undefined
  if (!Array.isArray(input.sources) || input.sources.length > 8) {
    throw new TypeError('sources must be an array with at most eight items')
  }
  return input.sources.map((source, index) => {
    if (!source || typeof source !== 'object' || Array.isArray(source)) {
      throw new TypeError(`sources[${index}] must be an object`)
    }
    if (source.source_id !== undefined) {
      return { source_id: positiveId(source.source_id, `sources[${index}].source_id`) }
    }

    const sourceChannel = stringOrNull(source.source_channel, 80)
    const sourceKind = stringOrNull(source.source_kind, 80)
    if (!sourceChannel) throw new TypeError(`sources[${index}].source_channel is required`)
    if (!/^[a-z][a-z0-9_]{0,79}$/.test(sourceKind || '')) {
      throw new TypeError(`sources[${index}].source_kind is invalid`)
    }
    const locator = source.locator === undefined ? {} : source.locator
    if (!locator || typeof locator !== 'object' || Array.isArray(locator)) {
      throw new TypeError(`sources[${index}].locator must be an object`)
    }
    if (source.quote_text !== undefined && source.quote_text !== null
      && String(source.quote_text).trim().length > 10_000) {
      throw new TypeError(`sources[${index}].quote_text is too long`)
    }
    const quoteText = stringOrNull(source.quote_text, 10_000)
    if (sourceKind === 'lovehouse_message') {
      positiveId(locator.message_id, `sources[${index}].locator.message_id`)
      if (quoteText) throw new TypeError('LoveHouse sources cannot provide quote_text')
    } else if (['lovehouse_message_range', 'lovehouse_range'].includes(sourceKind)) {
      const start = positiveId(locator.start_message_id, `sources[${index}].locator.start_message_id`)
      const end = positiveId(locator.end_message_id, `sources[${index}].locator.end_message_id`)
      if (end < start || end - start > 49) throw new TypeError('LoveHouse source range must span at most 50 ids')
      if (quoteText) throw new TypeError('LoveHouse sources cannot provide quote_text')
    } else if (sourceKind === 'manual_quote' && !quoteText) {
      throw new TypeError('manual_quote requires quote_text')
    } else if (sourceKind === 'manual_summary' && quoteText) {
      throw new TypeError('manual_summary cannot provide quote_text')
    } else if (!['manual_quote', 'manual_summary'].includes(sourceKind) && quoteText) {
      throw new TypeError('Only manual_quote sources can provide quote_text')
    }

    return {
      source_channel: sourceChannel,
      source_kind: sourceKind,
      locator: portableLocator(locator, `sources[${index}].locator`),
      ...(quoteText ? { quote_text: quoteText } : {}),
    }
  })
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
  if (!partial || has('summary')) {
    if (input.summary !== undefined && input.summary !== null
      && String(input.summary).trim().length > 2_000) throw new TypeError('summary is too long')
    memory.summary = stringOrNull(input.summary, 2000)
  }
  if (!partial || has('memory_type') || has('memoryType') || has('kind') || has('tag')) {
    memory.memory_type = memoryTypeFromInput(input)
  }
  if (!partial || has('tags') || has('tag') || has('category')) memory.tags = normalizeTags(input)
  if (!partial || has('emotion') || has('mood') || has('feeling')) memory.emotion = normalizeEmotion(input)
  if (!partial || has('importance')) memory.importance = normalizeImportance(input.importance)
  if (!partial || has('retention') || has('level')) memory.retention = normalizeRetention(input)
  if (!partial && (has('source_ref') || has('sourceRef'))) {
    memory.source_ref = stringOrNull(input.source_ref || input.sourceRef, 500)
  }
  const sources = normalizeSources(input)
  if (sources !== undefined) memory.sources = sources
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
    houseRulesProvider = bundledHouseRulesProvider,
    sourceResolver = unavailableSourceResolver,
    clock = () => new Date(),
  }) {
    if (!repository) throw new Error('MemoryRepository is required')
    if (typeof auditSink?.record !== 'function') throw new Error('Memory audit sink is required')
    if (typeof houseRulesProvider?.getRules !== 'function') throw new Error('House Rules provider is required')
    this.repository = repository
    this.accessPolicy = accessPolicy
    this.auditSink = auditSink
    // A caller cannot enable writes with a flag alone. Phase-one writes are
    // available only when an explicitly persistent audit sink is installed.
    this.writeEnabled = writeEnabled === true && auditSink.persistent === true
    this.semanticRecallEnabled = semanticRecallEnabled === true
    this.embeddingProvider = embeddingProvider
    this.rankingProfile = rankingProfile
    this.houseRulesProvider = houseRulesProvider
    if (typeof sourceResolver?.resolve !== 'function') throw new Error('Memory source resolver is required')
    this.sourceResolver = sourceResolver
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

      const entry = normalizedMemory(input)
      if (entry.memory_type === 'diary') entry.author = actor
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
            throw error
          }
          const generated = await this.embeddingProvider.embed(search.query)
          rows = await this.repository.hybridSearch({
            ...search,
            queryEmbedding: generated.vector,
            queryEmbeddingProfile: generated.profile,
            queryEmbeddingModel: generated.model,
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

  async memoryBox(actor, input = {}, context = {}) {
    return this.audited(actor, 'memory_box', context, async trusted => {
      this.accessPolicy.assertActor(actor)
      this.accessPolicy.assertNoSpaceOverride(input)
      const result = await this.repository.memoryBox({
        scope: this.accessPolicy.readScopeFor(actor),
        limit: input.limit,
        requestId: trusted.requestId,
      })
      const items = (result?.items || [])
        .filter(memory => this.accessPolicy.canRead(actor, memory))
      return {
        schema_version: MEMORY_BOX_SCHEMA_VERSION,
        actor,
        mode: 'random_history',
        items,
      }
    })
  }

  async expandSource(actor, input = {}, context = {}) {
    return this.audited(actor, 'expand_source', context, async trusted => {
      this.accessPolicy.assertActor(actor)
      this.accessPolicy.assertNoSpaceOverride(input)
      const sourceId = positiveId(input.source_id ?? input.sourceId, 'source_id')
      const source = await this.repository.expandSource(sourceId, {
        actor,
        requestId: trusted.requestId,
      })
      return this.sourceResolver.resolve(source, {
        cursorMessageId: input.cursor_message_id ?? input.cursorMessageId ?? null,
        limit: input.limit,
      })
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
      // A memory converted into a diary receives the same trusted author rule
      // as a newly created diary. Existing diary revisions omit author from the
      // patch, so the repository carries the original author forward.
      if (patch.memory_type === 'diary') patch.author = actor
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
    // House Rules are loaded before memories so a broken or missing rule source
    // cannot silently return an incomplete session-start contract.
    const houseRules = await this.houseRulesProvider.getRules()
    const memories = await this.list(actor, { limit: input.limit || 10 }, context)
    return {
      schema_version: STARTER_PACK_SCHEMA_VERSION,
      actor,
      house_rules: houseRules,
      private_memories: memories.filter(memory => memory.space_key === actor),
      shared_memories: memories.filter(memory => (
        memory.space_key === 'shared'
        && memory.shared_status === SHARED_STATES.APPROVED
      )),
    }
  }
}
