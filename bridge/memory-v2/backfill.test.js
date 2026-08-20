import assert from 'node:assert/strict'
import test from 'node:test'

import { collectCurrentTargets, runEmbeddingBackfill } from './backfill.js'

test('backfill deduplicates approved Shared, skips same model and writes through repository', async () => {
  const shared = { memory_id: 'shared-memory', revision_id: 'shared-revision', content: 'Shared', space_key: 'shared' }
  const repository = {
    async recallLexical(actor) {
      return actor === 'gpt'
        ? [{ memory_id: 'gpt-memory', revision_id: 'gpt-revision', content: 'GPT', space_key: 'gpt' }, shared]
        : [{ memory_id: 'claude-memory', revision_id: 'claude-revision', content: 'Claude', space_key: 'claude' }, shared]
    },
    calls: [],
    async storeEmbedding(actor, revisionId, generated) {
      this.calls.push([actor, revisionId, generated.model])
    },
  }
  const rest = async () => [{ revision_id: 'gpt-revision', model: 'qwen3-embedding:4b' }]
  const embedding = {
    model: 'qwen3-embedding:4b',
    async embed() { return { vector: Array(1536).fill(0.01), model: this.model } },
    getStatus() { return { mode: 'semantic', model: this.model, last_checked_at: 'now', error: null } },
  }

  const targets = await collectCurrentTargets(repository)
  assert.equal(targets.length, 3)
  const report = await runEmbeddingBackfill({ repository, rest, embedding })
  assert.deepEqual(report, {
    model: 'qwen3-embedding:4b', total: 3, success: 2, skipped: 1,
    failed: 0, stopped: false, failed_revision_id: null,
    embedding_status: { mode: 'semantic', model: 'qwen3-embedding:4b', last_checked_at: 'now', error: null },
  })
  assert.deepEqual(repository.calls, [
    ['gpt', 'shared-revision', 'qwen3-embedding:4b'],
    ['claude', 'claude-revision', 'qwen3-embedding:4b'],
  ])
})

test('backfill stops on first Ollama failure and reports resumable progress', async () => {
  const repository = {
    async recallLexical(actor) {
      return actor === 'gpt'
        ? [{ memory_id: 'one', revision_id: 'revision-one', content: 'one', space_key: 'gpt' }]
        : [{ memory_id: 'two', revision_id: 'revision-two', content: 'two', space_key: 'claude' }]
    },
    async storeEmbedding() {},
  }
  const embedding = {
    model: 'qwen3-embedding:4b',
    async embed() {
      throw Object.assign(new Error('offline'), { code: 'MEMORY_V2_EMBEDDING_NETWORK_ERROR' })
    },
    getStatus() { return { mode: 'lexical_fallback', model: this.model, last_checked_at: 'now', error: 'offline' } },
  }
  const report = await runEmbeddingBackfill({ repository, rest: async () => [], embedding })
  assert.equal(report.total, 2)
  assert.equal(report.failed, 1)
  assert.equal(report.success, 0)
  assert.equal(report.stopped, true)
  assert.equal(report.failed_revision_id, 'revision-one')
})

test('backfill refuses silently truncated current-revision enumeration', async () => {
  const repository = {
    async recallLexical(actor) {
      if (actor === 'claude') return []
      return Array.from({ length: 50 }, (_, index) => ({
        memory_id: `memory-${index}`,
        revision_id: `revision-${index}`,
        content: `content-${index}`,
        space_key: 'gpt',
      }))
    },
  }
  await assert.rejects(
    collectCurrentTargets(repository),
    error => error.code === 'MEMORY_V2_BACKFILL_TARGET_LIMIT'
  )
})
