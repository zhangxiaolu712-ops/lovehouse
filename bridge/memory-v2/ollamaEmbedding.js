const DEFAULT_TIMEOUT_MS = 8_000
const MAX_INPUT_CHARS = 12_000

export class MemoryV2EmbeddingError extends Error {
  constructor(message, code, options = {}) {
    super(message, options)
    this.name = 'MemoryV2EmbeddingError'
    this.code = code
  }
}

function normalizeDimensions(value) {
  const dimensions = Number.parseInt(value, 10)
  if (dimensions !== 1536) {
    throw new MemoryV2EmbeddingError(
      'Memory V2 embedding dimensions must be 1536',
      'MEMORY_V2_EMBEDDING_DIMENSIONS_INVALID'
    )
  }
  return dimensions
}

function normalizeEndpoint(value) {
  let endpoint
  try {
    endpoint = new URL(String(value || ''))
  } catch {
    throw new MemoryV2EmbeddingError(
      'Memory V2 embedding URL is not configured',
      'MEMORY_V2_EMBEDDING_NOT_CONFIGURED'
    )
  }
  if (!['http:', 'https:'].includes(endpoint.protocol)) {
    throw new MemoryV2EmbeddingError(
      'Memory V2 embedding URL must use HTTP or HTTPS',
      'MEMORY_V2_EMBEDDING_NOT_CONFIGURED'
    )
  }
  const basePath = endpoint.pathname.replace(/\/+$/, '')
  endpoint.pathname = basePath.endsWith('/api/embed')
    ? basePath
    : `${basePath}/api/embed`
  endpoint.search = ''
  endpoint.hash = ''
  return endpoint.toString()
}

function finiteVector(value, dimensions) {
  return Array.isArray(value)
    && value.length === dimensions
    && value.every(component => Number.isFinite(component))
}

export class OllamaEmbeddingAdapter {
  constructor({
    url,
    model,
    dimensions = 1536,
    timeoutMs = DEFAULT_TIMEOUT_MS,
    fetchImpl = fetch,
    clock = () => new Date(),
  }) {
    this.url = normalizeEndpoint(url)
    this.model = String(model || '').trim()
    if (!this.model || this.model.length > 120) {
      throw new MemoryV2EmbeddingError(
        'Memory V2 embedding model is not configured',
        'MEMORY_V2_EMBEDDING_NOT_CONFIGURED'
      )
    }
    this.dimensions = normalizeDimensions(dimensions)
    this.timeoutMs = Math.min(
      Math.max(Number.parseInt(timeoutMs, 10) || DEFAULT_TIMEOUT_MS, 500),
      30_000
    )
    this.fetchImpl = fetchImpl
    this.clock = clock
    this.status = {
      mode: 'unavailable',
      model: this.model,
      last_checked_at: null,
      error: 'not_checked',
    }
  }

  checkedAt() {
    try {
      return this.clock().toISOString()
    } catch {
      return null
    }
  }

  setStatus(mode, error) {
    this.status = {
      mode,
      model: this.model,
      last_checked_at: this.checkedAt(),
      error,
    }
  }

  getStatus() {
    return { ...this.status }
  }

  async embed(input) {
    const text = String(input || '').trim()
    if (!text) {
      throw new MemoryV2EmbeddingError(
        'Memory V2 embedding input is required',
        'MEMORY_V2_EMBEDDING_INPUT_INVALID'
      )
    }

    const controller = new AbortController()
    const timeout = setTimeout(() => controller.abort(), this.timeoutMs)
    timeout.unref?.()
    try {
      let response
      try {
        response = await this.fetchImpl(this.url, {
          method: 'POST',
          headers: { 'Content-Type': 'application/json' },
          signal: controller.signal,
          body: JSON.stringify({
            model: this.model,
            input: text.slice(0, MAX_INPUT_CHARS),
            dimensions: this.dimensions,
          }),
        })
      } catch (error) {
        const code = error?.name === 'AbortError'
          ? 'MEMORY_V2_EMBEDDING_TIMEOUT'
          : 'MEMORY_V2_EMBEDDING_NETWORK_ERROR'
        this.setStatus('lexical_fallback', code)
        throw new MemoryV2EmbeddingError(
          error?.name === 'AbortError'
            ? 'Ollama embedding request timed out'
            : 'Ollama embedding request failed',
          code,
          { cause: error }
        )
      }

      if (!response.ok) {
        const code = response.status >= 500
          ? 'MEMORY_V2_EMBEDDING_UPSTREAM_5XX'
          : 'MEMORY_V2_EMBEDDING_HTTP_ERROR'
        this.setStatus('lexical_fallback', code)
        throw new MemoryV2EmbeddingError(
          `Ollama embedding returned ${response.status}`,
          code
        )
      }

      let payload
      try {
        payload = await response.json()
      } catch (error) {
        this.setStatus('lexical_fallback', 'MEMORY_V2_EMBEDDING_RESPONSE_INVALID')
        throw new MemoryV2EmbeddingError(
          'Ollama embedding returned invalid JSON',
          'MEMORY_V2_EMBEDDING_RESPONSE_INVALID',
          { cause: error }
        )
      }
      const vector = payload?.embeddings?.[0]
      if (!finiteVector(vector, this.dimensions)) {
        this.setStatus('lexical_fallback', 'MEMORY_V2_EMBEDDING_VECTOR_INVALID')
        throw new MemoryV2EmbeddingError(
          'Ollama embedding returned an invalid vector',
          'MEMORY_V2_EMBEDDING_VECTOR_INVALID'
        )
      }

      this.setStatus('semantic', null)
      return { vector, model: this.model }
    } finally {
      clearTimeout(timeout)
    }
  }
}

export function createOllamaEmbeddingFromEnv({
  env = process.env,
  fetchImpl = fetch,
  clock = () => new Date(),
} = {}) {
  const url = String(env.MEMORY_V2_EMBEDDING_URL || '').trim()
  const model = String(env.MEMORY_V2_EMBEDDING_MODEL || '').trim()
  if (!url || !model) return null
  return new OllamaEmbeddingAdapter({
    url,
    model,
    dimensions: env.MEMORY_V2_EMBEDDING_DIMENSIONS || 1536,
    timeoutMs: env.MEMORY_V2_EMBEDDING_TIMEOUT_MS || DEFAULT_TIMEOUT_MS,
    fetchImpl,
    clock,
  })
}
