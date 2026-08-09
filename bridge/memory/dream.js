import crypto from 'crypto'

import { memoryTypeFromInput } from './model.js'

const MAX_DREAM_SOURCES = 4
const MAX_SOURCE_CHARS = 6_000
const MAX_TOTAL_SOURCE_CHARS = 24_000
const MAX_DREAM_OUTPUTS = 3
const MAX_OUTPUT_CHARS = 12_000
const DEFAULT_TIMEOUT_MS = 20_000

export class DreamCuratorError extends Error {
  constructor(message, code = 'MEMORY_DREAM_CURATOR_FAILED', options = {}) {
    super(message, options)
    this.name = 'DreamCuratorError'
    this.code = code
  }
}

function boundedText(value, maximum, label) {
  const text = typeof value === 'string' ? value.trim() : ''
  if (!text || text.length > maximum) {
    throw new DreamCuratorError(`${label} is invalid`, 'MEMORY_DREAM_OUTPUT_INVALID')
  }
  return text
}

function normalizeSources(job) {
  if (!Array.isArray(job?.sources) || job.sources.length < 1 || job.sources.length > MAX_DREAM_SOURCES) {
    throw new DreamCuratorError('Dream sources are invalid', 'MEMORY_DREAM_INPUT_INVALID')
  }
  let total = 0
  return job.sources.map((source, index) => {
    const content = boundedText(source.content, MAX_SOURCE_CHARS, 'Dream source content')
    total += content.length
    if (total > MAX_TOTAL_SOURCE_CHARS) {
      throw new DreamCuratorError('Dream input exceeds the total source limit', 'MEMORY_DREAM_INPUT_INVALID')
    }
    const ordinal = Number.parseInt(source.ordinal, 10)
    if (ordinal !== index + 1) {
      throw new DreamCuratorError('Dream source order is invalid', 'MEMORY_DREAM_INPUT_INVALID')
    }
    return {
      ordinal,
      dream_actor: source.dream_actor,
      source_actor: source.source_actor,
      source_space: source.source_space,
      revision_hash: source.revision_hash,
      memory_type: source.memory_type,
      tags: Array.isArray(source.tags) ? source.tags.slice(0, 12) : [],
      importance: source.importance,
      title: typeof source.title === 'string' ? source.title.slice(0, 500) : null,
      content,
    }
  })
}

export function normalizeDreamOutputs(value) {
  const outputs = Array.isArray(value) ? value : value?.candidates
  if (!Array.isArray(outputs) || outputs.length < 1 || outputs.length > MAX_DREAM_OUTPUTS) {
    throw new DreamCuratorError('Curator must return 1-3 candidates', 'MEMORY_DREAM_OUTPUT_INVALID')
  }
  return outputs.map(output => {
    if (!output || typeof output !== 'object' || Array.isArray(output)) {
      throw new DreamCuratorError('Curator candidate must be an object', 'MEMORY_DREAM_OUTPUT_INVALID')
    }
    const proposalKind = output.proposal_kind || 'derived_memory'
    if (!['derived_memory', 'revision_suggestion', 'shared_candidate'].includes(proposalKind)) {
      throw new DreamCuratorError('Curator proposal kind is invalid', 'MEMORY_DREAM_OUTPUT_INVALID')
    }
    const memoryType = typeof output.memory_type === 'string' && output.memory_type.trim()
      ? output.memory_type.trim().slice(0, 80)
      : 'summary'
    const canonicalMemoryType = memoryTypeFromInput({
      memory_type: memoryType.toLowerCase(),
      tag: memoryType,
    })
    if (proposalKind !== 'revision_suggestion' && canonicalMemoryType === 'diary') {
      throw new DreamCuratorError(
        'Dream cannot create a first-person diary for an AI actor',
        'MEMORY_DREAM_DIARY_FORBIDDEN'
      )
    }
    const normalized = {
      proposal_kind: proposalKind,
      content: boundedText(output.content, MAX_OUTPUT_CHARS, 'Curator candidate content'),
      memory_type: memoryType,
      tags: Array.isArray(output.tags)
        ? output.tags.filter(tag => typeof tag === 'string' && tag.trim()).slice(0, 12).map(tag => tag.trim().slice(0, 80))
        : [],
      emotion: output.emotion && typeof output.emotion === 'object' && !Array.isArray(output.emotion)
        ? output.emotion
        : {},
      importance: Math.min(5, Math.max(1, Number.parseInt(output.importance, 10) || 1)),
    }
    if (Array.isArray(output.source_ordinals)) {
      const ordinals = output.source_ordinals.map(Number)
      if (ordinals.length < 1 || ordinals.length > MAX_DREAM_SOURCES
        || ordinals.some(ordinal => !Number.isInteger(ordinal) || ordinal < 1 || ordinal > MAX_DREAM_SOURCES)) {
        throw new DreamCuratorError('Curator source ordinals are invalid', 'MEMORY_DREAM_OUTPUT_INVALID')
      }
      normalized.source_ordinals = [...new Set(ordinals)]
    }
    if (proposalKind === 'revision_suggestion') {
      const target = Number.parseInt(output.target_source_ordinal, 10)
      if (!Number.isInteger(target) || target < 1 || target > MAX_DREAM_SOURCES) {
        throw new DreamCuratorError('Revision suggestion target is invalid', 'MEMORY_DREAM_OUTPUT_INVALID')
      }
      normalized.target_source_ordinal = target
    }
    return normalized
  })
}

function assertDiaryRevisionSources(outputs, sources) {
  const sourceByOrdinal = new Map(
    (Array.isArray(sources) ? sources : [])
      .map(source => [Number.parseInt(source?.ordinal, 10), source])
  )
  for (const output of outputs) {
    if (output.proposal_kind !== 'revision_suggestion') continue
    const canonicalMemoryType = memoryTypeFromInput({
      memory_type: output.memory_type.toLowerCase(),
      tag: output.memory_type,
    })
    if (canonicalMemoryType !== 'diary') continue
    const targetSource = sourceByOrdinal.get(output.target_source_ordinal)
    if (targetSource?.memory_type !== 'diary') {
      throw new DreamCuratorError(
        'Dream diary revisions require an existing diary source revision',
        'MEMORY_DREAM_DIARY_SOURCE_INVALID'
      )
    }
  }
  return outputs
}

/**
 * OpenAI-compatible HTTP implementation of the Curator provider contract.
 * GPT, DeepSeek or another provider can be selected through configuration;
 * custom providers only need to implement `curate(job)` and expose identity.
 */
export class HttpDreamCuratorProvider {
  constructor({
    providerKey,
    url,
    apiKey,
    model,
    timeoutMs = DEFAULT_TIMEOUT_MS,
    fetchImpl = fetch,
  }) {
    this.providerKey = providerKey
    this.url = url
    this.apiKey = apiKey
    this.model = model
    this.timeoutMs = Math.min(Math.max(Number.parseInt(timeoutMs, 10) || DEFAULT_TIMEOUT_MS, 1_000), 60_000)
    this.fetchImpl = fetchImpl
  }

  assertConfigured() {
    if (!this.providerKey || !this.url || !this.model) {
      throw new DreamCuratorError('Dream Curator is not configured', 'MEMORY_DREAM_CURATOR_NOT_CONFIGURED')
    }
  }

  async curate(job) {
    this.assertConfigured()
    const perspective = boundedText(job?.perspective, 500, 'Dream perspective')
    const sources = normalizeSources(job)
    const controller = new AbortController()
    const timeout = setTimeout(() => controller.abort(), this.timeoutMs)
    timeout.unref?.()
    try {
      const headers = { 'Content-Type': 'application/json' }
      if (this.apiKey) headers.Authorization = `Bearer ${this.apiKey}`
      let response
      try {
        response = await this.fetchImpl(this.url, {
          method: 'POST',
          headers,
          signal: controller.signal,
          body: JSON.stringify({
            model: this.model,
            temperature: 0.2,
            max_tokens: 1800,
            response_format: { type: 'json_object' },
            messages: [
              {
                role: 'system',
                content: 'You are a memory curator. Return JSON {"candidates":[]} only. Never rewrite or delete source memories. Preserve every real participant and the dream_actor, source_actor and exact source ordinals in every proposal. Never invent a first-person diary on behalf of an AI; diary revision suggestions must remain traceable to an existing source diary.',
              },
              {
                role: 'user',
                content: JSON.stringify({ perspective, sources }),
              },
            ],
          }),
        })
      } catch (error) {
        if (error?.name === 'AbortError') {
          throw new DreamCuratorError('Dream Curator timed out', 'MEMORY_DREAM_CURATOR_TIMEOUT', { cause: error })
        }
        throw new DreamCuratorError('Dream Curator request failed', 'MEMORY_DREAM_CURATOR_NETWORK_ERROR', { cause: error })
      }
      if (!response.ok) {
        throw new DreamCuratorError(
          `Dream Curator returned ${response.status}`,
          response.status >= 500
            ? 'MEMORY_DREAM_CURATOR_UPSTREAM_5XX'
            : 'MEMORY_DREAM_CURATOR_REJECTED'
        )
      }
      let payload
      try {
        payload = await response.json()
      } catch (error) {
        throw new DreamCuratorError('Dream Curator returned invalid JSON', 'MEMORY_DREAM_OUTPUT_INVALID', { cause: error })
      }
      let output = payload?.outputs || payload
      const content = payload?.choices?.[0]?.message?.content
      if (typeof content === 'string') {
        try {
          output = JSON.parse(content)
        } catch (error) {
          throw new DreamCuratorError('Dream Curator content is not JSON', 'MEMORY_DREAM_OUTPUT_INVALID', { cause: error })
        }
      }
      return normalizeDreamOutputs(output)
    } finally {
      clearTimeout(timeout)
    }
  }
}

export class DreamWorker {
  constructor({
    repository,
    curatorProvider,
    enabled = false,
    sourceLimit = MAX_DREAM_SOURCES,
    perspective = 'Review recent memories from this actor perspective and propose only traceable candidates.',
  }) {
    if (!repository) throw new Error('MemoryRepository is required')
    this.repository = repository
    this.curatorProvider = curatorProvider
    this.enabled = enabled === true
    this.sourceLimit = Math.min(Math.max(Number.parseInt(sourceLimit, 10) || MAX_DREAM_SOURCES, 1), MAX_DREAM_SOURCES)
    this.perspective = perspective
  }

  async runOnce(actor) {
    if (!this.enabled) return { status: 'disabled' }
    if (typeof this.curatorProvider?.curate !== 'function') {
      throw new DreamCuratorError('Dream Curator is unavailable', 'MEMORY_DREAM_CURATOR_NOT_CONFIGURED')
    }
    const providerKey = boundedText(this.curatorProvider.providerKey, 100, 'Curator provider')
    const model = boundedText(this.curatorProvider.model, 150, 'Curator model')
    await this.repository.enqueueDream({
      actor,
      requestId: crypto.randomUUID(),
      perspective: this.perspective,
      limit: this.sourceLimit,
    })
    const job = await this.repository.claimDream({
      actor,
      requestId: crypto.randomUUID(),
      providerKey,
      model,
    })
    if (!job) return { status: 'idle' }
    try {
      // Every replaceable provider crosses the same deterministic boundary.
      // Provider-specific prompts or adapters are never the authority layer.
      const outputs = assertDiaryRevisionSources(
        normalizeDreamOutputs(await this.curatorProvider.curate(job)),
        job.sources
      )
      const result = await this.repository.completeDream(job.id, outputs, {
        actor,
        requestId: crypto.randomUUID(),
        providerKey,
        model,
      })
      return { status: 'completed', job_id: job.id, ...result }
    } catch (error) {
      await this.repository.failDream(job.id, error?.code || 'MEMORY_DREAM_CURATOR_FAILED', {
        actor,
        requestId: crypto.randomUUID(),
        providerKey,
        model,
      })
      return { status: 'failed', job_id: job.id, code: error?.code || 'MEMORY_DREAM_CURATOR_FAILED' }
    }
  }
}
