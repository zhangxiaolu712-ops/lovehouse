import test from 'node:test'
import assert from 'node:assert/strict'

import {
  EmbeddingIndexer,
  EmbeddingProviderError,
  HttpEmbeddingProvider,
  semanticFallbackAllowed,
} from './embedding.js'

test('HTTP embedding provider returns only a finite vector with its server profile', async () => {
  const requests = []
  const provider = new HttpEmbeddingProvider({
    url: 'https://embedding.example.invalid/v1/embeddings',
    apiKey: 'test-key',
    model: 'test-model',
    profile: 'semantic-test-v1',
    dimensions: 3,
    fetchImpl: async (url, options) => {
      requests.push({ url, options })
      return {
        ok: true,
        async json() { return { data: [{ embedding: [0.1, 0.2, 0.3] }] } },
      }
    },
  })

  const result = await provider.embed('  rose memory  ')
  assert.deepEqual(result, {
    vector: [0.1, 0.2, 0.3],
    profile: 'semantic-test-v1',
    model: 'test-model',
    dimensions: 3,
  })
  assert.equal(requests[0].url, 'https://embedding.example.invalid/v1/embeddings')
  assert.equal(requests[0].options.headers.Authorization, 'Bearer test-key')
  assert.equal(JSON.parse(requests[0].options.body).input, 'rose memory')
  assert.equal(JSON.parse(requests[0].options.body).dimensions, 3)
})

test('provider failures and malformed vectors are explicitly eligible for keyword fallback', async () => {
  const unavailable = new HttpEmbeddingProvider({
    url: 'https://embedding.example.invalid',
    model: 'test-model',
    dimensions: 3,
    fetchImpl: async () => { throw new Error('offline') },
  })
  await assert.rejects(
    unavailable.embed('query'),
    error => error.code === 'MEMORY_EMBEDDING_NETWORK_ERROR' && semanticFallbackAllowed(error)
  )

  const malformed = new HttpEmbeddingProvider({
    url: 'https://embedding.example.invalid',
    model: 'test-model',
    dimensions: 3,
    fetchImpl: async () => ({
      ok: true,
      async json() { return { data: [{ embedding: [0.1, Number.NaN, 0.3] }] } },
    }),
  })
  await assert.rejects(
    malformed.embed('query'),
    error => error.code === 'MEMORY_EMBEDDING_VECTOR_INVALID' && semanticFallbackAllowed(error)
  )
})

test('fallback allowlist accepts only transient/provider-vector failures', async () => {
  for (const [status, code, allowed] of [
    [400, 'MEMORY_EMBEDDING_CLIENT_ERROR', false],
    [401, 'MEMORY_EMBEDDING_AUTHORIZATION_FAILED', false],
    [403, 'MEMORY_EMBEDDING_AUTHORIZATION_FAILED', false],
    [408, 'MEMORY_EMBEDDING_TIMEOUT', true],
    [429, 'MEMORY_EMBEDDING_RATE_LIMITED', true],
    [500, 'MEMORY_EMBEDDING_UPSTREAM_5XX', true],
    [503, 'MEMORY_EMBEDDING_UPSTREAM_5XX', true],
  ]) {
    const provider = new HttpEmbeddingProvider({
      url: 'https://embedding.example.invalid',
      model: 'test-model',
      dimensions: 3,
      fetchImpl: async () => ({ ok: false, status }),
    })
    await assert.rejects(
      provider.embed('query'),
      error => error.code === code && semanticFallbackAllowed(error) === allowed
    )
  }

  for (const code of [
    'MEMORY_EMBEDDING_TIMEOUT',
    'MEMORY_EMBEDDING_NETWORK_ERROR',
    'MEMORY_EMBEDDING_RATE_LIMITED',
    'MEMORY_EMBEDDING_UPSTREAM_5XX',
    'MEMORY_EMBEDDING_VECTOR_INVALID',
    'MEMORY_VECTOR_INVALID',
  ]) assert.equal(semanticFallbackAllowed({ code }), true, code)

  for (const code of [
    'MEMORY_EMBEDDING_NOT_CONFIGURED',
    'MEMORY_EMBEDDING_INPUT_INVALID',
    'MEMORY_EMBEDDING_AUTHORIZATION_FAILED',
    'MEMORY_EMBEDDING_CLIENT_ERROR',
    'MEMORY_EMBEDDING_RESPONSE_INVALID',
    'MEMORY_RANKING_PROFILE_INVALID',
    'MEMORY_EMBEDDING_IDENTITY_MISMATCH',
    'MEMORY_ACCESS_DENIED',
  ]) assert.equal(semanticFallbackAllowed({ code, semanticFallbackAllowed: true }), false, code)

  const invalidResponse = new HttpEmbeddingProvider({
    url: 'https://embedding.example.invalid',
    model: 'test-model',
    dimensions: 3,
    fetchImpl: async () => ({
      ok: true,
      async json() { throw new SyntaxError('invalid JSON') },
    }),
  })
  await assert.rejects(
    invalidResponse.embed('query'),
    error => error.code === 'MEMORY_EMBEDDING_RESPONSE_INVALID'
      && !semanticFallbackAllowed(error)
  )
})

test('embedding indexer completes good items and records bounded failures', async () => {
  const calls = []
  const repository = {
    async claimEmbeddings(input) {
      calls.push(['claim', input])
      return [
        { id: 1, input: 'one', embedding_profile: 'semantic-test-v1', embedding_model: 'test-model', dimensions: 2 },
        { id: 2, input: 'two', embedding_profile: 'semantic-test-v1', embedding_model: 'test-model', dimensions: 2 },
      ]
    },
    async completeEmbedding(id, vector, context) { calls.push(['complete', id, vector, context.actor]) },
    async failEmbedding(id, code, context) { calls.push(['fail', id, code, context.actor]) },
  }
  const provider = {
    async embed(input) {
      if (input === 'two') {
        throw new EmbeddingProviderError('upstream failed', 'MEMORY_EMBEDDING_UPSTREAM_5XX')
      }
      return { vector: [0.4, 0.6], profile: 'semantic-test-v1', model: 'test-model', dimensions: 2 }
    },
  }

  const indexer = new EmbeddingIndexer({ repository, provider, batchSize: 99 })
  const result = await indexer.runOnce('gpt')
  assert.equal(calls[0][1].limit, 8)
  assert.deepEqual(calls.slice(1).map(call => call.slice(0, 4)), [
    ['complete', 1, [0.4, 0.6], 'gpt'],
    ['fail', 2, 'MEMORY_EMBEDDING_UPSTREAM_5XX', 'gpt'],
  ])
  assert.deepEqual(result, [
    { id: 1, status: 'ready' },
    { id: 2, status: 'failed', code: 'MEMORY_EMBEDDING_UPSTREAM_5XX' },
  ])
})
