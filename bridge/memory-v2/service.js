const ACTORS = new Set(['gpt', 'claude'])
const RESERVED_FIELDS = new Set([
  'owner', 'owner_id', 'actor', 'space', 'space_key', 'scope',
  'shared_status', 'created_by_actor', 'permissions',
])
const OPTIONAL_METADATA_FIELDS = ['tag', 'tags', 'project', 'type', 'mood', 'stance']
export const RECALL_IMPORTANCE_WEIGHTS = Object.freeze({ ai: 0.7, human: 0.3 })
const STARTER_PACK_TOTAL_LIMIT = 15

function fixedActor(actor) {
  if (!ACTORS.has(actor)) throw new TypeError('A fixed Memory V2 actor is required')
  return actor
}
function clamp(value, minimum = 0, maximum = 1) {
  return Math.min(maximum, Math.max(minimum, Number(value) || 0))
}

function boundedInteger(value, fallback, maximum) {
  const parsed = Number.parseInt(value, 10)
  if (!Number.isFinite(parsed) || parsed < 1) return fallback
  return Math.min(parsed, maximum)
}

function parseDate(value) {
  if (!value) return null
  const date = value instanceof Date ? value : new Date(value)
  return Number.isFinite(date.getTime()) ? date : null
}

export function formatCurrentTime(date, utcOffsetMinutes = 480) {
  const instant = parseDate(date)
  if (!instant) throw new TypeError('Current time is unavailable')
  const offset = Number(utcOffsetMinutes)
  if (!Number.isInteger(offset) || Math.abs(offset) > 14 * 60) {
    throw new TypeError('UTC offset is invalid')
  }
  const sign = offset >= 0 ? '+' : '-'
  const absolute = Math.abs(offset)
  const hours = String(Math.floor(absolute / 60)).padStart(2, '0')
  const minutes = String(absolute % 60).padStart(2, '0')
  const shifted = new Date(instant.getTime() + offset * 60_000)
  return `${shifted.toISOString().slice(0, -1)}${sign}${hours}:${minutes}`
}

function safeCurrentTime(clock, utcOffsetMinutes) {
  try {
    return formatCurrentTime(clock(), utcOffsetMinutes)
  } catch {
    return null
  }
}

function assertNoAuthorityFields(input) {
  for (const key of Object.keys(input || {})) {
    if (RESERVED_FIELDS.has(key)) {
      const error = new TypeError(`${key} is server controlled`)
      error.code = 'MEMORY_V2_AUTHORITY_FIELD_REJECTED'
      throw error
    }
  }
}

function boundedContent(value) {
  const content = String(value || '').trim()
  if (!content || content.length > 50_000) {
    throw new TypeError('Memory content is required and must not exceed 50000 characters')
  }
  return content
}

function normalizeSources(value) {
  if (value === undefined) return undefined
  if (!Array.isArray(value) || value.length > 20) throw new TypeError('sources must be a bounded array')
  return value.map(source => {
    if (!source || typeof source !== 'object' || Array.isArray(source)) {
      throw new TypeError('source must be an object')
    }
    if (source.sourceId) return { source_id: String(source.sourceId) }
    const sourceKind = String(source.sourceKind || '').trim()
    if (!sourceKind || sourceKind.length > 80) throw new TypeError('sourceKind is required')
    return {
      source_kind: sourceKind,
      locator: source.locator && typeof source.locator === 'object' ? source.locator : {},
      quote_text: source.quoteText == null ? null : String(source.quoteText).slice(0, 20_000),
      provenance: source.provenance && typeof source.provenance === 'object' ? source.provenance : {},
    }
  })
}

function normalizeOptions(input, { partial = false } = {}) {
  assertNoAuthorityFields(input)
  const metadata = input.metadata == null ? {} : input.metadata
  if (!metadata || typeof metadata !== 'object' || Array.isArray(metadata)) {
    throw new TypeError('metadata must be an object')
  }
  assertNoAuthorityFields(metadata)
  const mergedMetadata = { ...metadata }
  for (const field of OPTIONAL_METADATA_FIELDS) {
    if (input[field] !== undefined) mergedMetadata[field] = input[field]
  }

  const options = {}
  if (!partial || input.metadata !== undefined || OPTIONAL_METADATA_FIELDS.some(field => input[field] !== undefined)) {
    options.metadata = mergedMetadata
  }
  if (input.eventTime !== undefined) options.event_time = input.eventTime
  if (input.humanImportance !== undefined) options.human_importance = input.humanImportance
  if (input.aiImportance !== undefined) options.ai_importance = input.aiImportance
  if (input.reason !== undefined) options.reason = String(input.reason).slice(0, 1000)
  if (input.supersedesMemoryId !== undefined) options.supersedes_memory_id = input.supersedesMemoryId
  const sources = normalizeSources(input.sources)
  if (sources !== undefined) options.sources = sources
  return options
}

function normalizeRememberInput(input) {
  if (typeof input === 'string') return { content: boundedContent(input), options: {} }
  if (!input || typeof input !== 'object' || Array.isArray(input)) {
    throw new TypeError('remember accepts content or an object with content')
  }
  return { content: boundedContent(input.content), options: normalizeOptions(input) }
}

function ageDays(current, past) {
  const then = parseDate(past)
  if (!current || !then) return null
  return Math.max(0, (current.getTime() - then.getTime()) / 86_400_000)
}

export function rankMemoryCandidates(candidates, currentTime) {
  const current = parseDate(currentTime)
  return (Array.isArray(candidates) ? candidates : []).map(candidate => {
    const relevance = clamp(candidate.relevance)
    const recentBase = candidate.last_recalled_at || candidate.event_time || candidate.created_at
    const recentAge = ageDays(current, recentBase)
    const recency = recentAge == null ? 0.5 : Math.exp(-recentAge / 30)
    const recallAge = ageDays(current, candidate.last_recalled_at)
    const usage = candidate.last_recalled_at
      ? Math.min(1, Math.log1p(Math.max(0, Number(candidate.recall_count) || 0)) / Math.log(11))
        * Math.exp(-(recallAge || 0) / 30)
      : 0
    const tide = clamp(0.7 * recency + 0.3 * usage)
    const human = clamp((Number(candidate.human_importance) || 0) / 5)
    const ai = clamp((Number(candidate.ai_importance) || 0) / 5)
    const importance = RECALL_IMPORTANCE_WEIGHTS.ai * ai
      + RECALL_IMPORTANCE_WEIGHTS.human * human
    const dynamicWeight = 0.75 + 0.15 * tide + 0.10 * importance
    return {
      ...candidate,
      relevance,
      tide_score: tide,
      importance_score: importance,
      dynamic_weight: dynamicWeight,
      rank_score: relevance * dynamicWeight,
    }
  }).sort((left, right) => {
    if (right.rank_score !== left.rank_score) return right.rank_score - left.rank_score
    return String(right.created_at || '').localeCompare(String(left.created_at || ''))
  })
}

function estimateTokens(value) {
  const text = String(value || '')
  let ascii = 0
  let other = 0
  for (const char of text) {
    if (char.codePointAt(0) <= 0x7f) ascii += 1
    else other += 1
  }
  return Math.max(1, Math.ceil(ascii / 4) + other)
}

function shortMemory(candidate, maximum = 240) {
  const supplied = typeof candidate.metadata?.summary === 'string'
    ? candidate.metadata.summary.trim()
    : ''
  const text = supplied || String(candidate.content || '').trim()
  return text.length <= maximum ? text : `${text.slice(0, maximum - 1)}…`
}

function starterItem(candidate, category) {
  return {
    memory_id: candidate.memory_id,
    revision_id: candidate.revision_id,
    summary: shortMemory(candidate),
    event_time: candidate.event_time || null,
    created_at: candidate.created_at,
    space_key: candidate.space_key,
    source_count: Number(candidate.source_count) || 0,
    starter_category: category,
  }
}

export class MemoryV2Service {
  constructor({
    repository,
    embedding = null,
    clock = () => new Date(),
    utcOffsetMinutes = 480,
    onEmbeddingError = () => {},
  }) {
    if (!repository) throw new TypeError('Memory V2 repository is required')
    this.repository = repository
    this.embedding = embedding
    this.clock = clock
    this.utcOffsetMinutes = utcOffsetMinutes
    this.onEmbeddingError = onEmbeddingError
  }

  forActor(actor) {
    const trustedActor = fixedActor(actor)
    return Object.freeze({
      remember: input => this.remember(trustedActor, input),
      recall: input => this.recall(trustedActor, input),
      revise: (memoryId, input) => this.revise(trustedActor, memoryId, input),
      history: memoryId => this.repository.history(trustedActor, memoryId),
      expandSource: sourceId => this.repository.expandSource(trustedActor, sourceId),
      starterPack: input => this.starterPack(trustedActor, input),
    })
  }

  currentTime() {
    return safeCurrentTime(this.clock, this.utcOffsetMinutes)
  }

  scheduleEmbedding(actor, revisionId, content) {
    if (!this.embedding || typeof this.embedding.embed !== 'function') return
    queueMicrotask(async () => {
      try {
        const result = await this.embedding.embed(content)
        await this.repository.storeEmbedding(actor, revisionId, result)
      } catch (error) {
        this.onEmbeddingError(error)
      }
    })
  }

  async remember(actor, input) {
    const trustedActor = fixedActor(actor)
    const { content, options } = normalizeRememberInput(input)
    const currentTime = this.currentTime()
    const stored = await this.repository.remember(trustedActor, content, options)
    this.scheduleEmbedding(trustedActor, stored.revision_id, content)
    return { ...stored, current_time: currentTime, time_status: currentTime ? 'available' : 'unavailable' }
  }

  async revise(actor, memoryId, input) {
    const trustedActor = fixedActor(actor)
    if (!input || typeof input !== 'object' || Array.isArray(input)) throw new TypeError('revision input is required')
    const content = boundedContent(input.content)
    const options = normalizeOptions(input, { partial: true })
    const currentTime = this.currentTime()
    const stored = await this.repository.revise(trustedActor, memoryId, content, options)
    this.scheduleEmbedding(trustedActor, stored.revision_id, content)
    return { ...stored, current_time: currentTime, time_status: currentTime ? 'available' : 'unavailable' }
  }

  async recall(actor, input = {}) {
    const trustedActor = fixedActor(actor)
    const query = String(input.query || '').trim()
    if (!query || query.length > 1000) throw new TypeError('A bounded recall query is required')
    assertNoAuthorityFields(input)
    const limit = boundedInteger(input.limit, 5, 10)
    const candidateLimit = Math.min(50, Math.max(limit * 4, 20))
    const currentTime = this.currentTime()
    let candidates
    let mode = 'lexical_fallback'
    let semanticError = 'embedding_not_configured'

    if (this.embedding && typeof this.embedding.embed === 'function') {
      try {
        const embedded = await this.embedding.embed(query)
        candidates = await this.repository.recallSemantic(trustedActor, {
          vector: embedded.vector,
          model: embedded.model,
          limit: candidateLimit,
        })
        mode = 'semantic'
        semanticError = null
        if (!Array.isArray(candidates) || candidates.length === 0) {
          candidates = await this.repository.recallLexical(trustedActor, { query, limit: candidateLimit })
          mode = 'lexical_fallback'
          semanticError = 'semantic_no_results'
        }
      } catch (error) {
        semanticError = error?.code || error?.message || 'embedding_unavailable'
        candidates = await this.repository.recallLexical(trustedActor, { query, limit: candidateLimit })
        mode = 'lexical_fallback'
      }
    } else {
      candidates = await this.repository.recallLexical(trustedActor, { query, limit: candidateLimit })
    }

    const items = rankMemoryCandidates(candidates, currentTime).slice(0, limit)
    if (currentTime && items.length && typeof this.repository.recordRecall === 'function') {
      void Promise.resolve(this.repository.recordRecall(
        trustedActor,
        items.map(item => item.memory_id),
        currentTime
      )).catch(() => {})
    }
    return {
      current_time: currentTime,
      time_status: currentTime ? 'available' : 'unavailable',
      mode,
      semantic_error: semanticError,
      items,
    }
  }

  async starterPack(actor, input = {}) {
    const trustedActor = fixedActor(actor)
    assertNoAuthorityFields(input)
    const softLimit = boundedInteger(input.softLimit, STARTER_PACK_TOTAL_LIMIT, STARTER_PACK_TOTAL_LIMIT)
    const tokenBudget = boundedInteger(input.tokenBudget, 1600, 4000)
    const currentTime = this.currentTime()
    const candidates = await this.repository.starterPackCandidates(trustedActor)
    const selections = candidates.map(candidate => starterItem(candidate, candidate.starter_category))
    const items = []
    let estimatedTokens = 0
    for (const item of selections) {
      if (items.length >= softLimit) break
      const itemTokens = estimateTokens(JSON.stringify(item))
      if (estimatedTokens + itemTokens > tokenBudget) break
      items.push(item)
      estimatedTokens += itemTokens
    }
    return {
      current_time: currentTime,
      time_status: currentTime ? 'available' : 'unavailable',
      soft_limit: softLimit,
      token_budget: tokenBudget,
      estimated_tokens: estimatedTokens,
      items,
    }
  }

  async ownerApproveShared(sourceMemoryId, context = {}) {
    if (context.ownerApproved !== true) {
      const error = new Error('Explicit owner approval is required')
      error.code = 'MEMORY_V2_OWNER_APPROVAL_REQUIRED'
      throw error
    }
    const currentTime = this.currentTime()
    const shared = await this.repository.approveShared(sourceMemoryId)
    return { ...shared, current_time: currentTime, time_status: currentTime ? 'available' : 'unavailable' }
  }
}
