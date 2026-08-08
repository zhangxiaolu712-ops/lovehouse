import crypto from 'crypto'

const DEFAULT_TIMEOUT_MS = 8_000
const MAX_EMBEDDING_INPUT_CHARS = 12_000

export class EmbeddingProviderError extends Error {
  constructor(message, code = 'MEMORY_SEMANTIC_UNAVAILABLE', options = {}) {
    super(message, options)
    this.name = 'EmbeddingProviderError'
    this.code = code
    this.semanticFallbackAllowed = true
  }
}

function validVector(value, dimensions) {
  return Array.isArray(value)
    && value.length === dimensions
    && value.every(item => Number.isFinite(item))
}

export class HttpEmbeddingProvider {
  constructor({
    url,
    apiKey,
    model,
    profile = 'semantic-1536-v1',
    dimensions = 1536,
    timeoutMs = DEFAULT_TIMEOUT_MS,
    fetchImpl = fetch,
  }) {
    this.url = url
    this.apiKey = apiKey
    this.model = model
    this.profile = profile
    this.dimensions = Number.parseInt(dimensions, 10)
    this.timeoutMs = Math.min(Math.max(Number.parseInt(timeoutMs, 10) || DEFAULT_TIMEOUT_MS, 500), 30_000)
    this.fetchImpl = fetchImpl
  }

  assertConfigured() {
    if (!this.url || !this.model || !Number.isInteger(this.dimensions) || this.dimensions < 1) {
      throw new EmbeddingProviderError(
        'Embedding provider is not configured',
        'MEMORY_EMBEDDING_NOT_CONFIGURED'
      )
    }
  }

  async embed(input) {
    this.assertConfigured()
    if (typeof input !== 'string' || !input.trim()) {
      throw new EmbeddingProviderError('Embedding input is required', 'MEMORY_EMBEDDING_INPUT_INVALID')
    }

    const controller = new AbortController()
    const timeout = setTimeout(() => controller.abort(), this.timeoutMs)
    timeout.unref?.()
    try {
      const headers = { 'Content-Type': 'application/json' }
      if (this.apiKey) headers.Authorization = `Bearer ${this.apiKey}`
      const response = await this.fetchImpl(this.url, {
        method: 'POST',
        headers,
        signal: controller.signal,
        body: JSON.stringify({
          model: this.model,
          input: input.trim().slice(0, MAX_EMBEDDING_INPUT_CHARS),
          dimensions: this.dimensions,
          encoding_format: 'float',
        }),
      })
      if (!response.ok) {
        throw new EmbeddingProviderError(
          `Embedding provider returned ${response.status}`,
          response.status === 429 ? 'MEMORY_EMBEDDING_RATE_LIMITED' : 'MEMORY_EMBEDDING_UPSTREAM_ERROR'
        )
      }
      const payload = await response.json()
      const vector = payload?.data?.[0]?.embedding
      if (!validVector(vector, this.dimensions)) {
        throw new EmbeddingProviderError(
          'Embedding provider returned an invalid vector',
          'MEMORY_EMBEDDING_VECTOR_INVALID'
        )
      }
      return { vector, profile: this.profile, dimensions: this.dimensions }
    } catch (error) {
      if (error instanceof EmbeddingProviderError) throw error
      if (error?.name === 'AbortError') {
        throw new EmbeddingProviderError('Embedding provider timed out', 'MEMORY_EMBEDDING_TIMEOUT', { cause: error })
      }
      throw new EmbeddingProviderError('Embedding provider request failed', 'MEMORY_EMBEDDING_UNAVAILABLE', { cause: error })
    } finally {
      clearTimeout(timeout)
    }
  }
}

export function semanticFallbackAllowed(error) {
  return error?.semanticFallbackAllowed === true
    || ['MEMORY_VECTOR_UNAVAILABLE', 'MEMORY_VECTOR_INVALID'].includes(error?.code)
}

export class EmbeddingIndexer {
  constructor({ repository, provider, batchSize = 4 }) {
    if (!repository) throw new Error('MemoryRepository is required')
    if (typeof provider?.embed !== 'function') throw new Error('Embedding provider is required')
    this.repository = repository
    this.provider = provider
    this.batchSize = Math.min(Math.max(Number.parseInt(batchSize, 10) || 4, 1), 8)
  }

  async runOnce(actor) {
    const claimed = await this.repository.claimEmbeddings({
      actor,
      limit: this.batchSize,
      requestId: crypto.randomUUID(),
    })
    const results = []
    for (const item of claimed) {
      try {
        const generated = await this.provider.embed(item.input)
        if (
          generated.profile !== item.embedding_profile
          || generated.dimensions !== item.dimensions
        ) {
          throw new EmbeddingProviderError(
            'Embedding profile does not match the claimed lifecycle item',
            'MEMORY_EMBEDDING_PROFILE_MISMATCH'
          )
        }
        await this.repository.completeEmbedding(item.id, generated.vector, {
          actor,
          requestId: crypto.randomUUID(),
        })
        results.push({ id: item.id, status: 'ready' })
      } catch (error) {
        await this.repository.failEmbedding(item.id, error?.code || 'MEMORY_EMBEDDING_FAILED', {
          actor,
          requestId: crypto.randomUUID(),
        })
        results.push({ id: item.id, status: 'failed', code: error?.code || 'MEMORY_EMBEDDING_FAILED' })
      }
    }
    return results
  }
}
