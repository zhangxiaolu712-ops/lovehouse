import assert from 'node:assert/strict'
import test from 'node:test'

import { createHealthSnapshot } from './health.js'

class SupabaseMemoryRepository {}
class DisabledMemoryRepository {}

function snapshot(overrides = {}) {
  return createHealthSnapshot({
    bridgeStartedAt: '2026-08-20T10:00:00.000Z',
    claudeProcess: { windows: 2, busy: 0 },
    memorySystemEnabled: true,
    memorySemanticEnabled: false,
    memoryEmbeddingProviderConfigured: true,
    memoryDreamEnabled: false,
    memoryDreamCuratorConfigured: false,
    memoryDreamCuratorProvider: '',
    memoryRankingProfile: 'ranking_v1',
    memoryWritesEnabled: true,
    memoryRepository: new SupabaseMemoryRepository(),
    ...overrides,
  })
}

test('health reports injected runtime facts and the selected repository', () => {
  const health = snapshot()

  assert.equal(health.status, 'ok')
  assert.equal(health.bridge_started_at, '2026-08-20T10:00:00.000Z')
  assert.equal(health.memory_system_enabled, true)
  assert.equal(health.memory_writes_enabled, true)
  assert.equal(health.memory_semantic_enabled, false)
  assert.equal(health.memory_dream_enabled, false)
  assert.deepEqual(health.memory_repository, {
    type: 'SupabaseMemoryRepository',
    status: 'selected',
  })
})

test('health exposes unavailable release identity and never invents schema readiness', () => {
  process.env.LOVEHOUSE_DEPLOY_COMMIT = 'stale-commit-that-must-not-be-reported'
  try {
    const health = snapshot()
    assert.deepEqual(health.deployment_release, { status: 'unavailable', commit: null })
    assert.equal('memory_system' in health, false)
    assert.equal('database_migration' in health, false)
    assert.equal('schema_ready' in health, false)
    assert.equal('memory_ready' in health, false)
    assert.equal(JSON.stringify(health).includes(process.env.LOVEHOUSE_DEPLOY_COMMIT), false)
  } finally {
    delete process.env.LOVEHOUSE_DEPLOY_COMMIT
  }
})

test('health distinguishes the disabled runtime repository without probing storage', () => {
  const health = snapshot({
    memorySystemEnabled: false,
    memoryWritesEnabled: false,
    memoryRepository: new DisabledMemoryRepository(),
  })

  assert.deepEqual(health.memory_repository, {
    type: 'DisabledMemoryRepository',
    status: 'disabled',
  })
})
