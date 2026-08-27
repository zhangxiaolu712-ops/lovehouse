import assert from 'node:assert/strict'
import test from 'node:test'
import { collectCurrentTargetBatches, runEmbeddingBackfill } from './backfill.js'

function fixtureRest(entries, embedded = new Set()) {
  const revisions = new Map(entries.map(entry => [entry.current_revision_id, entry.content]))
  const calls = []
  const rest = async (_method, path) => {
    calls.push(path)
    if (path.startsWith('memory_v2_entries?')) {
      const space = path.match(/space_key=eq\.([^&]+)/)?.[1]
      const offset = Number(path.match(/offset=(\d+)/)?.[1])
      const limit = Number(path.match(/limit=(\d+)/)?.[1])
      return entries.filter(entry => entry.space_key === space).slice(offset, offset + limit)
    }
    const ids = new Set([...path.matchAll(/["(,]([\w-]+)[",)]/g)].map(match => match[1]))
    if (path.startsWith('memory_v2_revisions?')) return [...ids].flatMap(id => revisions.has(id) ? [{ id, content: revisions.get(id) }] : [])
    if (path.startsWith('memory_v2_embeddings?')) return [...ids].flatMap(id => embedded.has(id) ? [{ revision_id: id }] : [])
    throw new Error(`unexpected path: ${path}`)
  }
  rest.calls = calls
  return rest
}
function entry(index, spaceKey = 'claude') {
  return { id: `memory-${spaceKey}-${index}`, current_revision_id: `revision-${spaceKey}-${index}`,
    content: `content ${spaceKey} ${index}`, space_key: spaceKey }
}
const embedding = {
  model: 'qwen3-embedding:4b',
  async embed() { return { vector: Array(1536).fill(0.01), model: this.model } },
  getStatus() { return { mode: 'semantic', model: this.model, error: null } },
}

test('current targets are paged, actor-scoped and Shared is enumerated once', async () => {
  const rest = fixtureRest([...Array.from({ length: 55 }, (_, index) => entry(index)), entry(1, 'gpt'), entry(1, 'shared')])
  const batches = []
  for await (const batch of collectCurrentTargetBatches({ rest, ownerId: 'owner', actors: ['claude'], batchSize: 25 })) batches.push(batch)
  assert.deepEqual(batches.map(batch => batch.length), [25, 25, 5, 1])
  assert.equal(batches.flat().some(target => target.spaceKey === 'gpt'), false)
  assert.equal(batches.flat().find(target => target.spaceKey === 'shared').actor, 'claude')
  assert.ok(rest.calls.some(path => path.includes('offset=50&limit=25')))
})
test('same-model checks are per batch and rerun is idempotent', async () => {
  const entries = Array.from({ length: 53 }, (_, index) => entry(index))
  const embedded = new Set(['revision-claude-0', 'revision-claude-26'])
  const rest = fixtureRest(entries, embedded)
  const repository = { ownerId: 'owner', calls: [], async storeEmbedding(actor, id, generated) { this.calls.push([actor, id, generated.model]); embedded.add(id) } }
  const first = await runEmbeddingBackfill({ repository, rest, embedding, actors: ['claude'], batchSize: 25 })
  assert.deepEqual([first.total, first.batches, first.skipped, first.success], [53, 3, 2, 51])
  repository.calls.length = 0
  const second = await runEmbeddingBackfill({ repository, rest, embedding, actors: ['claude'], batchSize: 25 })
  assert.deepEqual([second.success, second.skipped, repository.calls.length], [0, 53, 0])
})

test('dry-run and max-batches never embed or write', async () => {
  const rest = fixtureRest(Array.from({ length: 60 }, (_, index) => entry(index)), new Set(['revision-claude-0']))
  let attempts = 0
  const adapter = { ...embedding, async embed() { attempts += 1 } }
  const repository = { ownerId: 'owner', async storeEmbedding() { throw new Error('must not write') } }
  const report = await runEmbeddingBackfill({ repository, rest, embedding: adapter, actors: ['claude'], batchSize: 25, maxBatches: 1, dryRun: true })
  assert.deepEqual([report.total, report.skipped, report.pending, report.batches, attempts], [25, 1, 24, 1, 0])
})

test('failures do not block later targets and rerun resumes from database state', async () => {
  const embedded = new Set()
  const rest = fixtureRest([entry(0), entry(1), entry(2)], embedded)
  const repository = { ownerId: 'owner', async storeEmbedding(_actor, id) { embedded.add(id) } }
  let attempts = 0
  const flaky = { ...embedding, async embed() { attempts += 1; if (attempts === 2) throw Object.assign(new Error('offline'), { code: 'MEMORY_V2_EMBEDDING_NETWORK_ERROR' }); return embedding.embed() } }
  const first = await runEmbeddingBackfill({ repository, rest, embedding: flaky, actors: ['claude'] })
  assert.deepEqual([first.success, first.failed, first.failed_revision_id], [2, 1, 'revision-claude-1'])
  assert.deepEqual(first.failed_revision_ids, ['revision-claude-1'])
  assert.deepEqual(first.errors, { MEMORY_V2_EMBEDDING_NETWORK_ERROR: 1 })
  const second = await runEmbeddingBackfill({ repository, rest, embedding, actors: ['claude'] })
  assert.deepEqual([second.skipped, second.success, embedded.size], [2, 1, 3])
})

test('batch size caps at 50 without a total target cap', async () => {
  const rest = fixtureRest(Array.from({ length: 101 }, (_, index) => entry(index)))
  const sizes = []
  for await (const batch of collectCurrentTargetBatches({ rest, ownerId: 'owner', actors: ['claude'], batchSize: 500 })) sizes.push(batch.length)
  assert.deepEqual(sizes, [50, 50, 1])
})
