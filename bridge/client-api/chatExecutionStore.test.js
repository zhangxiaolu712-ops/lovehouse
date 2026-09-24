import assert from 'node:assert/strict'
import { mkdtemp, readFile, readdir, rm, stat } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'

import {
  ChatExecutionCoordinator,
  FileChatExecutionStore,
  chatExecutionFingerprint,
} from './chatExecutionStore.js'

const OWNER = 'owner-user'
const EXECUTION = '11111111-1111-4111-8111-111111111111'
const THREAD = '22222222-2222-4222-8222-222222222222'

async function fixture(t, options = {}) {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'lovehouse-chat-execution-'))
  t.after(() => rm(directory, { recursive: true, force: true }))
  const filePath = path.join(directory, 'chat-executions.json')
  return { directory, filePath, store: new FileChatExecutionStore({ filePath, ...options }) }
}

test('chat execution store writes atomically with private permissions and bounded terminal retention', async t => {
  let now = Date.parse('2026-09-24T00:00:00Z')
  const { directory, filePath, store } = await fixture(t, { now: () => now, terminalTtlMs: 100 })
  const inputFingerprint = chatExecutionFingerprint({ message: 'digest only' })
  await store.reserve({ ownerUserId: OWNER, executionId: EXECUTION, threadId: THREAD, provider: 'claude', inputFingerprint })
  await store.complete({ ownerUserId: OWNER, executionId: EXECUTION }, { text: 'final answer' })

  const payload = JSON.parse(await readFile(filePath, 'utf8'))
  assert.equal(payload.version, 1)
  assert.equal(Object.values(payload.records)[0].result.text, 'final answer')
  assert.equal(Object.values(payload.records)[0].input_fingerprint, inputFingerprint)
  assert.equal(JSON.stringify(payload).includes('digest only'), false)
  assert.deepEqual((await readdir(directory, { withFileTypes: true })).filter(entry => entry.name.endsWith('.tmp')), [])
  if (process.platform !== 'win32') {
    assert.equal((await stat(directory)).mode & 0o777, 0o700)
    assert.equal((await stat(filePath)).mode & 0o777, 0o600)
  }

  now += 101
  const another = '33333333-3333-4333-8333-333333333333'
  await store.reserve({ ownerUserId: OWNER, executionId: another, threadId: THREAD, provider: 'claude', inputFingerprint })
  assert.equal(await store.get({ ownerUserId: OWNER, executionId: EXECUTION }), null)
})

test('runtime restart converges an orphaned running execution to an explicit failure', async t => {
  const { filePath, store } = await fixture(t)
  const inputFingerprint = chatExecutionFingerprint({ turn: 1 })
  await store.reserve({ ownerUserId: OWNER, executionId: EXECUTION, threadId: THREAD, provider: 'codex', inputFingerprint })

  const restarted = new FileChatExecutionStore({ filePath })
  const record = await restarted.get({ ownerUserId: OWNER, executionId: EXECUTION })
  assert.equal(record.status, 'failed')
  assert.equal(record.error.code, 'EXECUTION_INTERRUPTED_BY_RUNTIME_RESTART')
})

test('detaching the observer does not cancel execution and duplicate execution ids never rerun provider', async t => {
  const { store } = await fixture(t)
  const coordinator = new ChatExecutionCoordinator({ store, log: {} })
  let providerCalls = 0
  let finish
  const provider = new Promise(resolve => { finish = resolve })
  const input = {
    ownerUserId: OWNER,
    executionId: EXECUTION,
    threadId: THREAD,
    provider: 'claude',
    inputFingerprint: chatExecutionFingerprint({ turn: 1 }),
    async execute(publish) {
      providerCalls += 1
      await provider
      publish({ event: 'text_delta', payload: { delta: 'done' } })
      return { text: 'done', runtime: 'claude_cli', adapter_id: 'claude-cli-v1' }
    },
  }
  const first = await coordinator.observeOrStart({ ...input, onEvent() {} })
  first.unsubscribe()
  const duplicate = await coordinator.observeOrStart({ ...input, onEvent() {} })
  assert.equal(providerCalls, 1)
  finish()
  await Promise.all([first.finished, duplicate.finished])
  assert.equal(providerCalls, 1)
  assert.equal((await store.get({ ownerUserId: OWNER, executionId: EXECUTION })).status, 'completed')
})
