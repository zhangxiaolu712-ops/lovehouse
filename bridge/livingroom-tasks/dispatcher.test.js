import test from 'node:test'
import assert from 'node:assert/strict'
import { LivingroomTaskDispatcher } from './dispatcher.js'
import { TransientThreadStore } from './transientStore.js'

const task = { id: 'task', thread_id: 'thread', request_summary: 'do it', runtime_session_id: null }
const notifications = () => ({ publishNotification() {} })

test('idle dispatcher does not wake the Codex endpoint', async () => {
  let runs = 0
  const dispatcher = new LivingroomTaskDispatcher({
    repository: { ingestMentions: async () => {}, claimQueued: async () => null },
    endpoint: { run: async () => { runs += 1 } },
    transientStore: new TransientThreadStore(),
  })
  assert.equal(await dispatcher.tick(), false)
  assert.equal(runs, 0)
})

test('queued task wakes Codex and keeps full result only in transient thread', async () => {
  const calls = []
  const store = new TransientThreadStore()
  const dispatcher = new LivingroomTaskDispatcher({
    repository: {
      ingestMentions: async () => {}, claimQueued: async () => task,
      ...notifications(), complete: async (_task, result) => calls.push(['complete', result.text]),
      fail: async () => {},
    },
    endpoint: { run: async () => ({ text: 'full result', sessionId: 'session' }) },
    transientStore: store,
  })
  assert.equal(await dispatcher.tick(), true)
  assert.deepEqual(calls, [['complete', 'full result']])
  assert.equal(store.read('thread').at(-1).content, 'full result')
})

test('runtime milestones update one transient workflow event and workflow notification', async () => {
  const store = new TransientThreadStore()
  const calls = []
  const dispatcher = new LivingroomTaskDispatcher({
    repository: {
      ingestMentions: async () => {}, claimQueued: async () => task,
      publishNotification: (_task, status, detail) => calls.push([status, detail?.summary]),
      complete: async () => {}, fail: async () => {},
    },
    endpoint: { run: async input => {
      input.onMilestone({ stage: 'tool', summary: '运行 targeted tests' })
      input.onMilestone({ stage: 'tool', summary: '运行 full Bridge tests' })
      return { text: 'done', sessionId: 'session' }
    } },
    transientStore: store,
  })
  await dispatcher.tick()
  const milestones = store.read('thread').filter(event => event.type === 'workflow_milestone')
  assert.deepEqual(milestones.map(event => event.summary), ['运行 full Bridge tests'])
  assert.equal(calls.some(call => call[0] === 'running' && call[1] === '运行 full Bridge tests'), true)
})

test('approval marker checkpoints and exits; approved queued task resumes same thread', async () => {
  const calls = []
  const sessions = []
  let activeRuntime = 0
  const queued = { ...task, runtime_session_id: '0199a213-81c0-7800-8aa1-bbab2a035a53' }
  let current = task
  const dispatcher = new LivingroomTaskDispatcher({
    repository: {
      ingestMentions: async () => {}, claimQueued: async () => current,
      ...notifications(), waitForApproval: async (_task, request, sessionId) => {
        calls.push(['waiting', request, sessionId]); return { id: 'approval' }
      },
      complete: async () => calls.push(['completed']), fail: async () => {},
    },
    endpoint: { run: async ({ message, sessionId }) => {
      activeRuntime += 1
      sessions.push(sessionId)
      const result = message.startsWith('Approval granted')
        ? ({ text: 'done', sessionId: queued.runtime_session_id })
        : ({ text: '[[APPROVAL_REQUIRED: deploy?]]', sessionId: queued.runtime_session_id })
      activeRuntime -= 1
      return result
    } },
    transientStore: new TransientThreadStore(),
  })
  await dispatcher.tick()
  assert.equal(activeRuntime, 0)
  current = queued
  await dispatcher.tick()
  assert.deepEqual(calls, [
    ['waiting', 'deploy?', queued.runtime_session_id],
    ['completed'],
  ])
  assert.deepEqual(sessions, [null, queued.runtime_session_id])
})

test('local-user checkpoint has no approval and resumes the same runtime session', async () => {
  const calls = []
  const repository = {
    ingestMentions: async () => {}, claimQueued: async () => task,
    requireLocalUser: async (_task, request, session) => calls.push(['local', request, session]),
    publishNotification: (_task, status, detail) => calls.push(['notify', status, detail.summary]),
    fail: async () => {},
  }
  const dispatcher = new LivingroomTaskDispatcher({
    repository,
    endpoint: { run: async () => ({ text: '[[LOCAL_USER_REQUIRED: 请完成 2FA]]', sessionId: 'runtime-1' }) },
    transientStore: new TransientThreadStore(),
  })
  assert.equal(await dispatcher.tick(), true)
  assert.deepEqual(calls.filter(call => call[0] === 'local'), [['local', '请完成 2FA', 'runtime-1']])
  assert.equal(calls.some(call => call.includes('waiting_approval')), false)
})
