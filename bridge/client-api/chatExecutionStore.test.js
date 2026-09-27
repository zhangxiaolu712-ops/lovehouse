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

test('verified App Account identity is fixed at first reserve without changing execution fingerprint', async t => {
  const { filePath, store } = await fixture(t)
  const identity = {
    ownerUserId: OWNER,
    executionId: '77777777-7777-4777-8777-777777777777',
    threadId: THREAD,
    provider: 'claude',
    inputFingerprint: chatExecutionFingerprint({ message: 'same logical request' }),
  }
  await store.reserve({ ...identity, appAccountId: 'account-a' })
  const repeated = await store.reserve(identity)
  assert.equal(repeated.created, false)
  assert.equal(repeated.record.app_account_id, 'account-a')
  await assert.rejects(
    store.reserve({ ...identity, appAccountId: 'account-b' }),
    error => error.code === 'EXECUTION_APP_ACCOUNT_CONFLICT' && error.status === 409,
  )
  const persisted = await readFile(filePath, 'utf8')
  assert.equal(persisted.includes('account-a'), true)
  assert.equal(persisted.includes('fake-session-secret'), false)
})

test('execution created without verified identity never backfills owner on reconnect', async t => {
  const { store } = await fixture(t)
  const identity = {
    ownerUserId: OWNER,
    executionId: '88888888-8888-4888-8888-888888888888',
    threadId: THREAD,
    provider: 'codex',
    inputFingerprint: chatExecutionFingerprint({ message: 'same logical request' }),
  }
  await store.reserve(identity)
  const repeated = await store.reserve({ ...identity, appAccountId: 'account-a' })
  assert.equal(repeated.created, false)
  assert.equal(repeated.record.app_account_id, undefined)
})

test('authoritative completion produces one owner-scoped attention event without exposing reply content', async t => {
  const { store } = await fixture(t)
  const deliveries = []
  const producer = {
    async produce(value) {
      deliveries.push(value)
      return { id: 'event-1' }
    },
  }
  const coordinator = new ChatExecutionCoordinator({ store, replyCompletedProducer: producer, log: {} })
  const input = {
    ownerUserId: OWNER,
    appAccountId: 'account-a',
    executionId: '99999999-9999-4999-8999-999999999999',
    threadId: THREAD,
    provider: 'claude',
    inputFingerprint: chatExecutionFingerprint({ turn: 2 }),
    async execute() {
      return { text: 'private assistant reply', hidden: 'private provider payload' }
    },
  }
  const first = await coordinator.observeOrStart(input)
  await first.finished
  const duplicate = await coordinator.observeOrStart(input)
  await duplicate.finished

  assert.deepEqual(deliveries, [{
    appAccountId: 'account-a',
    executionId: input.executionId,
    threadId: THREAD,
  }])
  assert.equal(JSON.stringify(deliveries).includes('private assistant reply'), false)
  assert.equal(JSON.stringify(deliveries).includes('private provider payload'), false)
})

test('ownerless completion skips event production', async t => {
  const { store } = await fixture(t)
  let deliveries = 0
  const coordinator = new ChatExecutionCoordinator({
    store,
    replyCompletedProducer: { async produce() { deliveries += 1 } },
    log: {},
  })
  const observed = await coordinator.observeOrStart({
    ownerUserId: OWNER,
    executionId: 'aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa',
    threadId: THREAD,
    provider: 'codex',
    inputFingerprint: chatExecutionFingerprint({ turn: 3 }),
    async execute() { return { text: 'done' } },
  })
  await observed.finished
  assert.equal(deliveries, 0)
})

test('producer delivery failure cannot change completed Chat or rerun Provider', async t => {
  const { store } = await fixture(t)
  const warnings = []
  let providerCalls = 0
  let producerCalls = 0
  const coordinator = new ChatExecutionCoordinator({
    store,
    replyCompletedProducer: {
      async produce() {
        producerCalls += 1
        throw Object.assign(new Error('backend unavailable'), { code: 'REPLY_COMPLETED_DELIVERY_FAILED' })
      },
    },
    log: { info() {}, warn(_label, payload) { warnings.push(payload) } },
  })
  const input = {
    ownerUserId: OWNER,
    appAccountId: 'account-a',
    executionId: 'bbbbbbbb-bbbb-4bbb-8bbb-bbbbbbbbbbbb',
    threadId: THREAD,
    provider: 'codex',
    inputFingerprint: chatExecutionFingerprint({ turn: 4 }),
    async execute() {
      providerCalls += 1
      return { text: 'canonical Chat result' }
    },
  }
  const first = await coordinator.observeOrStart(input)
  const completed = await first.finished
  const duplicate = await coordinator.observeOrStart(input)
  await duplicate.finished

  assert.equal(completed.status, 'completed')
  assert.equal(completed.result.text, 'canonical Chat result')
  assert.equal((await store.get({ ownerUserId: OWNER, executionId: input.executionId })).status, 'completed')
  assert.equal(providerCalls, 1)
  assert.equal(producerCalls, 1)
  assert.equal(warnings.some(value => value.includes('delivery_failed')), true)
  assert.equal(warnings.some(value => value.includes('canonical Chat result')), false)
})

test('failed Provider execution never produces reply-completed', async t => {
  const { store } = await fixture(t)
  let deliveries = 0
  const coordinator = new ChatExecutionCoordinator({
    store,
    replyCompletedProducer: { async produce() { deliveries += 1 } },
    log: {},
  })
  const input = {
    ownerUserId: OWNER,
    appAccountId: 'account-a',
    executionId: 'cccccccc-cccc-4ccc-8ccc-cccccccccccc',
    threadId: THREAD,
    provider: 'claude',
    inputFingerprint: chatExecutionFingerprint({ turn: 5 }),
    async execute() { throw Object.assign(new Error('provider failed'), { code: 'PROVIDER_FAILED' }) },
  }
  const observed = await coordinator.observeOrStart(input)
  const failed = await observed.finished

  assert.equal(failed.status, 'failed')
  assert.equal(deliveries, 0)
})
