import test from 'node:test'
import assert from 'node:assert/strict'
import { SupabaseLivingroomTaskRepository } from './repository.js'
import { CODEX_VPS_ROUTE } from './routing.js'

test('mention ingestion stores one minimal routed task and no message/event log rows', async () => {
  const calls = []
  const rest = async (method, path, body) => {
    calls.push([method, path, body])
    if (path.startsWith('livingroom?')) return [{ id: 7, message: '@Codex run smoke' }]
    if (path.startsWith('livingroom_tasks?source_message_id')) return []
    if (method === 'POST') return [{ id: 'task', thread_id: 'thread' }]
    return []
  }
  const transientEvents = []
  const repository = new SupabaseLivingroomTaskRepository({
    rest, ownerId: 'owner', route: CODEX_VPS_ROUTE,
    transientStore: { append: (...args) => transientEvents.push(args), upsert: (...args) => transientEvents.push(args) },
  })
  assert.equal(await repository.ingestMentions(), 1)
  const insert = calls.find(call => call[0] === 'POST')
  assert.equal(insert[1], 'livingroom_tasks')
  assert.deepEqual({
    agent: insert[2].target_agent,
    runtime: insert[2].target_runtime,
    endpoint: insert[2].target_endpoint,
  }, {
    agent: CODEX_VPS_ROUTE.agent,
    runtime: CODEX_VPS_ROUTE.runtime,
    endpoint: CODEX_VPS_ROUTE.endpoint,
  })
  assert.deepEqual(calls.filter(call => call[0] === 'POST').map(call => call[1]), ['livingroom_tasks'])
  assert.equal(transientEvents[0][1].content, 'run smoke')
  assert.equal(transientEvents[1][2].status, 'queued')
})

test('approval decision uses one request-specific transactional RPC', async () => {
  const calls = []
  const rest = async (method, path, body) => {
    calls.push([method, path, body])
    return { id: 'approval', task_id: 'task', status: 'approved' }
  }
  const repository = new SupabaseLivingroomTaskRepository({ rest, ownerId: 'owner', route: CODEX_VPS_ROUTE })
  await repository.decideApproval('approval', 'approved')
  assert.deepEqual(calls, [[
    'POST', 'rpc/livingroom_decide_approval',
    { p_owner_id: 'owner', p_approval_id: 'approval', p_decision: 'approved' },
  ]])
})

test('conditional claim is idempotent when two dispatchers race', async () => {
  let status = 'queued'
  let wakes = 0
  const rest = async (method, path, body) => {
    if (method === 'GET') return status === 'queued'
      ? [{ id: 'task', status, target_agent: 'codex', target_runtime: 'vps-cli', target_endpoint: 'codex-vps-primary' }]
      : []
    if (method === 'PATCH' && path.includes('status=eq.queued') && status === 'queued') {
      status = body.status
      return [{ id: 'task', status }]
    }
    return []
  }
  const first = new SupabaseLivingroomTaskRepository({ rest, ownerId: 'owner', route: CODEX_VPS_ROUTE })
  const second = new SupabaseLivingroomTaskRepository({ rest, ownerId: 'owner', route: CODEX_VPS_ROUTE })
  for (const claimed of await Promise.all([first.claimQueued(), second.claimQueued()])) {
    if (claimed) wakes += 1
  }
  assert.equal(wakes, 1)
})

test('thread reads are fixed-owner scoped and return not found for missing or cross-owner threads', async () => {
  const calls = []
  const tasks = [
    { id: 'task-owner', thread_id: 'thread-owner', owner_id: 'owner' },
    { id: 'task-other', thread_id: 'thread-other', owner_id: 'other-owner' },
  ]
  const rest = async (method, path) => {
    calls.push([method, path])
    const threadId = decodeURIComponent(path.match(/thread_id=eq\.([^&]+)/)?.[1] || '')
    const ownerId = decodeURIComponent(path.match(/owner_id=eq\.([^&]+)/)?.[1] || '')
    return tasks.filter(task => task.thread_id === threadId && task.owner_id === ownerId)
  }
  const repository = new SupabaseLivingroomTaskRepository({
    rest, ownerId: 'owner', route: CODEX_VPS_ROUTE,
  })

  assert.equal((await repository.getThread('thread-owner')).id, 'task-owner')
  assert.equal(await repository.getThread('thread-missing'), null)
  assert.equal(await repository.getThread('thread-other'), null)
  assert.equal(calls.every(([, path]) => path.includes('owner_id=eq.owner')), true)
})
