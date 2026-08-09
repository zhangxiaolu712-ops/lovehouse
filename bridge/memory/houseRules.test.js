import test from 'node:test'
import assert from 'node:assert/strict'

import {
  bundledHouseRulesProvider,
  FileHouseRulesProvider,
  HOUSE_RULES_SCHEMA_VERSION,
  HouseRulesConfigurationError,
  MemoryService,
  STARTER_PACK_SCHEMA_VERSION,
  validateHouseRulesDocument,
} from './index.js'

const seed = [
  { id: 1, space_key: 'gpt', content: 'gpt private' },
  { id: 2, space_key: 'claude', content: 'claude private' },
  { id: 3, space_key: 'shared', shared_status: 'approved', content: 'approved shared' },
  { id: 4, space_key: 'shared', shared_status: 'candidate', content: 'unapproved shared' },
  { id: 5, space_key: 'legacy_pending', content: 'legacy pending' },
]

test('bundled House Rules V1 are short, complete and classification-flexible', async () => {
  const document = await bundledHouseRulesProvider.getRules()
  assert.equal(document.schema_version, HOUSE_RULES_SCHEMA_VERSION)
  assert.equal(document.revision, 2)
  assert.ok(document.rules.length <= 8)
  assert.equal(new Set(document.rules.map(rule => rule.id)).size, document.rules.length)
  assert.ok(document.rules.every(rule => rule.text.length <= 220))
  assert.match(document.rules.find(rule => rule.id === 'preserve-participants').text, /所有真实参与者及其关系/)
  assert.match(document.rules.find(rule => rule.id === 'preserve-participants').text, /不必机械列出未参与者/)
  assert.match(document.rules.find(rule => rule.id === 'remember-ai-perspective').text, /当前 AI 对自身经历、判断、感受与变化的第一人称记录/)
  assert.match(document.rules.find(rule => rule.id === 'remember-ai-perspective').text, /不替小婷写她的日记/)
  assert.match(document.rules.find(rule => rule.id === 'keep-classification-flexible').text, /tag 可缺省、组合、微调或新增/)
  assert.match(document.memory_usage.save, /save_memory/)
  assert.match(document.memory_usage.revise, /revise_memory/)
})

test('House Rules validation rejects duplicate rules and oversized manuals', () => {
  const valid = {
    schema_version: HOUSE_RULES_SCHEMA_VERSION,
    revision: 1,
    title: 'House Rules',
    purpose: 'Read once.',
    rules: [{ id: 'one', text: 'First.' }],
    memory_usage: {
      session_start: 'Start.',
      recall: 'Recall.',
      save: 'Save.',
      revise: 'Revise.',
    },
  }
  assert.throws(
    () => validateHouseRulesDocument({ ...valid, rules: [...valid.rules, ...valid.rules] }),
    error => error instanceof HouseRulesConfigurationError && /Duplicate/.test(error.message)
  )
  assert.throws(
    () => validateHouseRulesDocument({ ...valid, rules: [{ id: 'one', text: 'x'.repeat(221) }] }),
    error => error instanceof HouseRulesConfigurationError && /too long/.test(error.message)
  )
})

for (const actor of ['gpt', 'claude']) {
  test(`${actor} starter pack returns the same House Rules and only allowed memories`, async () => {
    const service = new MemoryService({
      repository: { async list() { return seed } },
      auditSink: { async record() {} },
    })
    const pack = await service.starterPack(actor)
    assert.equal(pack.schema_version, STARTER_PACK_SCHEMA_VERSION)
    assert.equal(pack.actor, actor)
    assert.equal(pack.house_rules.schema_version, HOUSE_RULES_SCHEMA_VERSION)
    assert.deepEqual(pack.private_memories.map(memory => memory.space_key), [actor])
    assert.deepEqual(pack.shared_memories.map(memory => memory.id), [3])
    assert.equal(JSON.stringify(pack).includes('legacy pending'), false)
    assert.equal(JSON.stringify(pack).includes(`${actor === 'gpt' ? 'claude' : 'gpt'} private`), false)
    assert.equal(JSON.stringify(pack).includes('unapproved shared'), false)
  })
}

test('starter pack fails closed before reading memories when House Rules cannot load', async () => {
  let listCalls = 0
  const service = new MemoryService({
    repository: { async list() { listCalls += 1; return [] } },
    houseRulesProvider: { async getRules() { throw new HouseRulesConfigurationError('missing') } },
    auditSink: { async record() {} },
  })
  await assert.rejects(
    service.starterPack('gpt'),
    error => error.code === 'HOUSE_RULES_CONFIGURATION_INVALID'
  )
  assert.equal(listCalls, 0)
})

test('file provider reports malformed JSON as a configuration error', async () => {
  const provider = new FileHouseRulesProvider({ filePath: new URL('./houseRules.test.js', import.meta.url) })
  await assert.rejects(
    provider.getRules(),
    error => error instanceof HouseRulesConfigurationError
  )
})
