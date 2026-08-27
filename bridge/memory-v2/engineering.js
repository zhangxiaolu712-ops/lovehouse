import { formatCurrentTime } from './service.js'
import { optionalImportance } from './importance.js'

const ENGINEERING_ACTORS = new Set(['gpt', 'claude', 'codex', 'owner'])
const RESERVED_FIELDS = new Set([
  'owner', 'owner_id', 'actor', 'space', 'space_key', 'scope',
  'shared_status', 'created_by_actor', 'permissions',
])

function fixedActor(actor) {
  if (!ENGINEERING_ACTORS.has(actor)) {
    throw new TypeError('A trusted Engineering Memory actor is required')
  }
  return actor
}

function boundedText(value, field, maximum) {
  const text = String(value || '').trim()
  if (!text || text.length > maximum) {
    throw new TypeError(`${field} is required and must not exceed ${maximum} characters`)
  }
  return text
}

function assertNoAuthorityFields(input) {
  for (const key of Object.keys(input || {})) {
    if (RESERVED_FIELDS.has(key)) throw new TypeError(`${key} is server controlled`)
  }
}

function normalizeSources(value) {
  if (value === undefined) return undefined
  if (!Array.isArray(value) || value.length > 20) {
    throw new TypeError('sources must be a bounded array')
  }
  return value.map(source => {
    if (!source || typeof source !== 'object' || Array.isArray(source)) {
      throw new TypeError('source must be an object')
    }
    if (source.sourceId) return { source_id: String(source.sourceId) }
    return {
      source_kind: boundedText(source.sourceKind, 'sourceKind', 80),
      locator: source.locator && typeof source.locator === 'object' && !Array.isArray(source.locator)
        ? source.locator : {},
      quote_text: source.quoteText == null ? null : String(source.quoteText).slice(0, 20_000),
      provenance: source.provenance && typeof source.provenance === 'object'
        && !Array.isArray(source.provenance) ? source.provenance : {},
    }
  })
}

function normalizeUpsert(input) {
  if (!input || typeof input !== 'object' || Array.isArray(input)) {
    throw new TypeError('Engineering Memory upsert input is required')
  }
  assertNoAuthorityFields(input)
  const metadataSupplied = input.metadata !== undefined
  if (metadataSupplied && (!input.metadata || typeof input.metadata !== 'object' || Array.isArray(input.metadata))) {
    throw new TypeError('metadata must be an object')
  }
  if (metadataSupplied) assertNoAuthorityFields(input.metadata)
  const options = {}
  if (metadataSupplied) options.metadata = input.metadata
  if (input.eventTime !== undefined) options.event_time = input.eventTime
  if (input.humanImportance !== undefined) {
    options.human_importance = optionalImportance(input.humanImportance, 'human_importance')
  }
  if (input.aiImportance !== undefined) {
    options.ai_importance = optionalImportance(input.aiImportance, 'ai_importance')
  }
  if (input.reason !== undefined) options.reason = String(input.reason).slice(0, 1000)
  const sources = normalizeSources(input.sources)
  if (sources !== undefined) options.sources = sources
  return {
    subjectKey: boundedText(input.subjectKey, 'subjectKey', 200),
    content: boundedText(input.content, 'content', 50_000),
    options,
  }
}

function boundedLimit(value, fallback = 30, maximum = 50) {
  const parsed = Number.parseInt(value, 10)
  if (!Number.isFinite(parsed) || parsed < 1) return fallback
  return Math.min(parsed, maximum)
}

export class EngineeringMemoryService {
  constructor({ repository, clock = () => new Date(), utcOffsetMinutes = 480 }) {
    if (!repository) throw new TypeError('Memory V2 repository is required')
    this.repository = repository
    this.clock = clock
    this.utcOffsetMinutes = utcOffsetMinutes
  }

  currentTime() {
    try {
      return formatCurrentTime(this.clock(), this.utcOffsetMinutes)
    } catch {
      return null
    }
  }

  forActor(actor) {
    const trustedActor = fixedActor(actor)
    return Object.freeze({
      upsertEngineeringFact: input => this.upsert(trustedActor, input),
      recallEngineering: input => this.recall(trustedActor, input),
      openEngineeringFact: subjectKey => this.open(trustedActor, subjectKey),
      expandEngineeringSource: sourceId => this.expandSource(trustedActor, sourceId),
      archiveEngineeringFact: subjectKey => this.archive(trustedActor, subjectKey),
      restoreEngineeringFact: subjectKey => this.restore(trustedActor, subjectKey),
    })
  }

  async upsert(actor, input) {
    const trustedActor = fixedActor(actor)
    const normalized = normalizeUpsert(input)
    const result = await this.repository.upsertEngineering(
      trustedActor, normalized.subjectKey, normalized.content, normalized.options,
    )
    const currentTime = this.currentTime()
    return { ...result, current_time: currentTime, time_status: currentTime ? 'available' : 'unavailable' }
  }

  async recall(actor, input = {}) {
    const trustedActor = fixedActor(actor)
    assertNoAuthorityFields(input)
    const query = String(input.query || '').trim()
    if (query.length > 1000) throw new TypeError('Engineering query must not exceed 1000 characters')
    const includeArchived = input.includeArchived === true
    if (includeArchived && trustedActor !== 'owner') {
      throw new TypeError('Only Owner may include archived Engineering Memory')
    }
    const items = await this.repository.recallEngineering(trustedActor, {
      query,
      limit: boundedLimit(input.limit),
      includeArchived,
    })
    const currentTime = this.currentTime()
    return {
      current_time: currentTime,
      time_status: currentTime ? 'available' : 'unavailable',
      mode: 'lexical',
      items: Array.isArray(items) ? items : [],
    }
  }

  open(actor, subjectKey) {
    return this.repository.openEngineering(
      fixedActor(actor), boundedText(subjectKey, 'subjectKey', 200),
    )
  }

  expandSource(actor, sourceId) {
    return this.repository.expandEngineeringSource(
      fixedActor(actor), boundedText(sourceId, 'sourceId', 200),
    )
  }

  archive(actor, subjectKey) {
    const trustedActor = fixedActor(actor)
    if (trustedActor !== 'owner') throw new TypeError('Only Owner may archive Engineering Memory')
    return this.repository.archiveEngineering(
      trustedActor, boundedText(subjectKey, 'subjectKey', 200),
    )
  }

  restore(actor, subjectKey) {
    const trustedActor = fixedActor(actor)
    if (trustedActor !== 'owner') throw new TypeError('Only Owner may restore Engineering Memory')
    return this.repository.restoreEngineering(
      trustedActor, boundedText(subjectKey, 'subjectKey', 200),
    )
  }
}
