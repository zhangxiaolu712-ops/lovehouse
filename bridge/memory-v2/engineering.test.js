import assert from 'node:assert/strict'
import test from 'node:test'

import { EngineeringMemoryService } from './engineering.js'
import { MemoryV2Service } from './service.js'

const NOW = new Date('2026-08-25T02:00:00.000Z')

function createRepository() {
  const calls = []
  return {
    calls,
    async upsertEngineering(actor, subjectKey, content, options) {
      calls.push(['upsert', actor, subjectKey, content, options])
      return { action: 'created', memory_id: 'memory-1', revision_id: 'revision-1' }
    },
    async recallEngineering(actor, input) {
      calls.push(['recall', actor, input])
      return [{ subject_key: 'runtime.codex', content: 'current' }]
    },
    async openEngineering(actor, subjectKey) {
      calls.push(['open', actor, subjectKey])
      return { entry: { subject_key: subjectKey }, revisions: [] }
    },
    async expandEngineeringSource(actor, sourceId) {
      calls.push(['expand', actor, sourceId])
      return { source_id: sourceId, quote_text: 'evidence' }
    },
    async archiveEngineering(actor, subjectKey) {
      calls.push(['archive', actor, subjectKey])
      return { action: 'archived' }
    },
    async restoreEngineering(actor, subjectKey) {
      calls.push(['restore', actor, subjectKey])
      return { action: 'restored' }
    },
  }
}

test('GPT, Claude, Codex and Owner share only the dedicated Engineering facade', async () => {
  const repository = createRepository()
  const service = new EngineeringMemoryService({ repository, clock: () => NOW })

  for (const actor of ['gpt', 'claude', 'codex', 'owner']) {
    const result = await service.forActor(actor).upsertEngineeringFact({
      subjectKey: `runtime.${actor}`,
      content: `${actor} verified state`,
      metadata: { category: actor === 'owner' ? 'future-category' : 'runtime' },
      sources: [{
        sourceKind: 'git_commit',
        locator: { commit: 'abc123' },
        quoteText: 'bounded evidence',
      }],
    })
    assert.equal(result.action, 'created')
    assert.equal(result.current_time, '2026-08-25T10:00:00.000+08:00')
  }

  assert.deepEqual(repository.calls.map(call => call[1]), ['gpt', 'claude', 'codex', 'owner'])
  assert.throws(() => service.forActor('browser'), /trusted Engineering Memory actor/)

  const ordinary = new MemoryV2Service({ repository })
  assert.throws(() => ordinary.forActor('codex'), /fixed Memory V2 actor/)
  assert.throws(() => ordinary.forActor('owner'), /fixed Memory V2 actor/)
})

test('Engineering recall is lexical-only, bounded and does not accept authority overrides', async () => {
  const repository = createRepository()
  const service = new EngineeringMemoryService({ repository, clock: () => NOW })
  const gpt = service.forActor('gpt')

  const result = await gpt.recallEngineering({ query: 'runtime', limit: 500 })
  assert.equal(result.mode, 'lexical')
  assert.equal(result.items[0].subject_key, 'runtime.codex')
  assert.deepEqual(repository.calls[0], [
    'recall', 'gpt', { query: 'runtime', limit: 50, includeArchived: false },
  ])
  await assert.rejects(
    () => gpt.recallEngineering({ includeArchived: true }),
    /Only Owner/,
  )
  await assert.rejects(
    () => gpt.upsertEngineeringFact({
      subjectKey: 'runtime.codex', content: 'state', actor: 'owner',
    }),
    /server controlled/,
  )
})

test('archive and restore are Owner-only while history and source stay available through explicit calls', async () => {
  const repository = createRepository()
  const service = new EngineeringMemoryService({ repository })
  const codex = service.forActor('codex')
  const owner = service.forActor('owner')

  assert.deepEqual(await codex.openEngineeringFact('runtime.codex'), {
    entry: { subject_key: 'runtime.codex' }, revisions: [],
  })
  assert.equal((await codex.expandEngineeringSource('source-1')).quote_text, 'evidence')
  assert.throws(() => codex.archiveEngineeringFact('runtime.codex'), /Only Owner/)
  assert.throws(() => codex.restoreEngineeringFact('runtime.codex'), /Only Owner/)
  assert.equal((await owner.archiveEngineeringFact('runtime.codex')).action, 'archived')
  assert.equal((await owner.restoreEngineeringFact('runtime.codex')).action, 'restored')
})
