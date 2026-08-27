import assert from 'node:assert/strict'
import test from 'node:test'

import {
  MemoryV2EmbeddingError,
  OllamaEmbeddingAdapter,
  createOllamaEmbeddingFromEnv,
} from './ollamaEmbedding.js'

const NOW = new Date('2026-08-20T14:30:00.000Z')

test('Ollama adapter calls /api/embed and returns only a finite configured vector', async () => {
  const requests = []
  const vector = Array(1536).fill(0.01)
  const adapter = new OllamaEmbeddingAdapter({
    url: 'http://100.105.116.50:11434',
    model: 'qwen3-embedding:4b',
    dimensions: 1536,
    clock: () => NOW,
    fetchImpl: async (url, options) => {
      requests.push({ url, options })
      return { ok: true, async json() { return { embeddings: [vector] } } }
    },
  })

  assert.deepEqual(await adapter.embed('  测试记忆  '), {
    vector,
    model: 'qwen3-embedding:4b',
  })
  assert.equal(requests[0].url, 'http://100.105.116.50:11434/api/embed')
  assert.deepEqual(JSON.parse(requests[0].options.body), {
    model: 'qwen3-embedding:4b',
    input: '测试记忆',
    dimensions: 1536,
  })
  assert.deepEqual(adapter.getStatus(), {
    mode: 'semantic',
    model: 'qwen3-embedding:4b',
    last_checked_at: '2026-08-20T14:30:00.000Z',
    error: null,
  })
})
test('invalid vectors and network failures expose lexical fallback status', async () => {
  const malformed = new OllamaEmbeddingAdapter({
    url: 'http://ollama.example.invalid:11434/api/embed',
    model: 'qwen3-embedding:4b',
    clock: () => NOW,
    fetchImpl: async () => ({ ok: true, async json() { return { embeddings: [[0.1]] } } }),
  })
  await assert.rejects(
    malformed.embed('query'),
    error => error.code === 'MEMORY_V2_EMBEDDING_VECTOR_INVALID'
  )
  assert.equal(malformed.getStatus().mode, 'lexical_fallback')

  const offline = new OllamaEmbeddingAdapter({
    url: 'http://ollama.example.invalid:11434',
    model: 'qwen3-embedding:4b',
    clock: () => NOW,
    fetchImpl: async () => { throw new Error('offline') },
  })
  await assert.rejects(
    offline.embed('query'),
    error => error.code === 'MEMORY_V2_EMBEDDING_NETWORK_ERROR'
  )
  assert.deepEqual(offline.getStatus(), {
    mode: 'lexical_fallback',
    model: 'qwen3-embedding:4b',
    last_checked_at: '2026-08-20T14:30:00.000Z',
    error: 'MEMORY_V2_EMBEDDING_NETWORK_ERROR',
  })
})

test('configuration stays in environment and dimensions fail closed at 1536', () => {
  assert.equal(createOllamaEmbeddingFromEnv({ env: {} }), null)
  const adapter = createOllamaEmbeddingFromEnv({
    env: {
      MEMORY_V2_EMBEDDING_URL: 'http://100.105.116.50:11434',
      MEMORY_V2_EMBEDDING_MODEL: 'qwen3-embedding:4b',
      MEMORY_V2_EMBEDDING_DIMENSIONS: '1536',
    },
    fetchImpl: async () => { throw new Error('not called') },
  })
  assert.equal(adapter.model, 'qwen3-embedding:4b')
  assert.throws(
    () => new OllamaEmbeddingAdapter({
      url: 'http://100.105.116.50:11434',
      model: 'qwen3-embedding:4b',
      dimensions: 1024,
    }),
    error => error instanceof MemoryV2EmbeddingError
      && error.code === 'MEMORY_V2_EMBEDDING_DIMENSIONS_INVALID'
  )
})

test('long-running backfill timeout remains bounded', () => {
  const adapter = new OllamaEmbeddingAdapter({
    url: 'http://127.0.0.1:11434',
    model: 'qwen3-embedding:4b',
    timeoutMs: 180_000,
  })
  assert.equal(adapter.timeoutMs, 120_000)
})
