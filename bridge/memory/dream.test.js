import test from 'node:test'
import assert from 'node:assert/strict'

import {
  DreamCuratorError,
  DreamWorker,
  HttpDreamCuratorProvider,
  normalizeDreamOutputs,
} from './dream.js'

function exactSource(overrides = {}) {
  return {
    ordinal: 1,
    memory_id: 10,
    revision_id: 20,
    revision_hash: 'a'.repeat(64),
    source_space: 'gpt',
    dream_actor: 'gpt',
    source_actor: 'gpt',
    memory_type: 'feeling',
    tags: ['rose'],
    importance: 4,
    title: 'A traceable source',
    content: 'The original memory remains untouched.',
    ...overrides,
  }
}

test('Dream is completely inert while disabled', async () => {
  let touched = false
  const worker = new DreamWorker({
    enabled: false,
    repository: new Proxy({}, { get() { touched = true } }),
    curatorProvider: new Proxy({}, { get() { touched = true } }),
  })
  assert.deepEqual(await worker.runOnce('gpt'), { status: 'disabled' })
  assert.equal(touched, false)
})

test('Curator provider is replaceable without changing repository calls', async () => {
  for (const [providerKey, model] of [
    ['openai-compatible', 'gpt-curator'],
    ['deepseek-compatible', 'deepseek-curator'],
  ]) {
    const calls = []
    const repository = {
      async enqueueDream(input) { calls.push(['enqueue', input]); return { id: 7 } },
      async claimDream(input) {
        calls.push(['claim', input])
        return { id: 7, actor: 'gpt', perspective: 'test perspective', sources: [exactSource()] }
      },
      async completeDream(id, outputs, context) {
        calls.push(['complete', id, outputs, context])
        return { candidate_ids: [91] }
      },
      async failDream() { throw new Error('failure path was not expected') },
    }
    const curatorProvider = {
      providerKey,
      model,
      async curate(job) {
        assert.equal(job.sources[0].source_actor, 'gpt')
        return [{
          proposal_kind: 'derived_memory',
          content: 'A new pending candidate, not a rewritten source.',
          memory_type: 'summary',
          source_ordinals: [1],
        }]
      },
    }
    const worker = new DreamWorker({ repository, curatorProvider, enabled: true })
    const result = await worker.runOnce('gpt')
    assert.equal(result.status, 'completed')
    assert.equal(calls[1][1].providerKey, providerKey)
    assert.equal(calls[1][1].model, model)
    assert.equal(calls[2][3].providerKey, providerKey)
    assert.equal(calls[2][3].model, model)
  }
})

test('Curator failure records queue failure and never completes outputs', async () => {
  const calls = []
  const repository = {
    async enqueueDream() {},
    async claimDream() {
      return { id: 8, actor: 'claude', perspective: 'claude perspective', sources: [exactSource({
        source_space: 'claude', dream_actor: 'claude', source_actor: 'claude',
      })] }
    },
    async completeDream() { calls.push('complete') },
    async failDream(id, code, context) { calls.push(['fail', id, code, context.actor]) },
  }
  const curatorProvider = {
    providerKey: 'replaceable',
    model: 'failed-model',
    async curate() { throw new DreamCuratorError('provider failed', 'MEMORY_DREAM_CURATOR_UPSTREAM_5XX') },
  }
  const worker = new DreamWorker({ repository, curatorProvider, enabled: true })
  assert.deepEqual(await worker.runOnce('claude'), {
    status: 'failed', job_id: 8, code: 'MEMORY_DREAM_CURATOR_UPSTREAM_5XX',
  })
  assert.deepEqual(calls, [['fail', 8, 'MEMORY_DREAM_CURATOR_UPSTREAM_5XX', 'claude']])
})

test('HTTP Curator receives bounded exact-source narrative and returns candidates only', async () => {
  const requests = []
  const provider = new HttpDreamCuratorProvider({
    providerKey: 'openai-compatible',
    url: 'https://curator.example.invalid/v1/chat/completions',
    apiKey: 'test-key',
    model: 'curator-test',
    fetchImpl: async (_url, options) => {
      requests.push(options)
      return {
        ok: true,
        async json() {
          return {
            choices: [{ message: { content: JSON.stringify({
              candidates: [{
                proposal_kind: 'revision_suggestion',
                target_source_ordinal: 1,
                source_ordinals: [1],
                content: 'A suggestion that still requires review.',
                memory_type: 'reflection',
                tags: ['traceable'],
                importance: 3,
              }],
            }) } }],
          }
        },
      }
    },
  })
  const outputs = await provider.curate({
    perspective: 'Keep both AI identities visible.',
    sources: [exactSource()],
  })
  assert.equal(outputs.length, 1)
  assert.equal(outputs[0].proposal_kind, 'revision_suggestion')
  assert.equal(outputs[0].target_source_ordinal, 1)
  const body = JSON.parse(requests[0].body)
  const prompt = JSON.parse(body.messages[1].content)
  assert.equal(prompt.sources[0].dream_actor, 'gpt')
  assert.equal(prompt.sources[0].source_actor, 'gpt')
  assert.equal(prompt.sources[0].revision_hash, 'a'.repeat(64))
  assert.equal(body.model, 'curator-test')
  assert.equal(requests[0].headers.Authorization, 'Bearer test-key')
  assert.match(body.messages[0].content, /Preserve every real participant/)
  assert.match(body.messages[0].content, /Never invent a first-person diary/)
})

test('Dream cannot create a new AI diary but may suggest a traceable diary revision', () => {
  for (const [proposalKind, memoryType] of [
    ['derived_memory', 'diary'],
    ['derived_memory', '日记'],
    ['shared_candidate', 'diary'],
  ]) {
    assert.throws(
      () => normalizeDreamOutputs([{
        proposal_kind: proposalKind,
        content: 'A curator must not impersonate an AI diary author.',
        memory_type: memoryType,
        source_ordinals: [1],
      }]),
      error => error.code === 'MEMORY_DREAM_DIARY_FORBIDDEN'
    )
  }

  const suggestion = normalizeDreamOutputs([{
    proposal_kind: 'revision_suggestion',
    target_source_ordinal: 1,
    source_ordinals: [1],
    content: 'A traceable suggestion for the existing diary author to review.',
    memory_type: 'diary',
  }])[0]
  assert.equal(suggestion.proposal_kind, 'revision_suggestion')
  assert.equal(suggestion.memory_type, 'diary')
  assert.equal(suggestion.target_source_ordinal, 1)
})

test('Dream Worker applies the diary boundary to every replaceable provider', async () => {
  const calls = []
  const repository = {
    async enqueueDream() {},
    async claimDream() {
      return { id: 81, actor: 'gpt', perspective: 'gpt perspective', sources: [exactSource()] }
    },
    async completeDream() { calls.push('complete') },
    async failDream(id, code) { calls.push(['fail', id, code]) },
  }
  const curatorProvider = {
    providerKey: 'custom-replaceable-provider',
    model: 'custom-model',
    async curate() {
      return [{
        proposal_kind: 'derived_memory',
        content: 'I am a fabricated diary entry.',
        memory_type: 'diary',
        source_ordinals: [1],
      }]
    },
  }
  const worker = new DreamWorker({ repository, curatorProvider, enabled: true })
  assert.deepEqual(await worker.runOnce('gpt'), {
    status: 'failed', job_id: 81, code: 'MEMORY_DREAM_DIARY_FORBIDDEN',
  })
  assert.deepEqual(calls, [['fail', 81, 'MEMORY_DREAM_DIARY_FORBIDDEN']])
})

test('Dream output limits reject oversized or authority-shaped results', () => {
  const normalized = normalizeDreamOutputs([{
    content: 'Authority fields must be discarded.',
    actor: 'claude', owner_id: 'forged', space_key: 'claude', revision_id: 999,
  }])[0]
  assert.equal('actor' in normalized, false)
  assert.equal('owner_id' in normalized, false)
  assert.equal('space_key' in normalized, false)
  assert.equal('revision_id' in normalized, false)
  assert.throws(
    () => normalizeDreamOutputs(new Array(4).fill({ content: 'too many' })),
    error => error.code === 'MEMORY_DREAM_OUTPUT_INVALID'
  )
  assert.throws(
    () => normalizeDreamOutputs([{
      proposal_kind: 'revision_suggestion',
      content: 'missing exact target',
    }]),
    error => error.code === 'MEMORY_DREAM_OUTPUT_INVALID'
  )
  assert.throws(
    () => normalizeDreamOutputs([{ content: 'x'.repeat(12_001) }]),
    error => error.code === 'MEMORY_DREAM_OUTPUT_INVALID'
  )
})
