import test from 'node:test'
import assert from 'node:assert/strict'
import { normalizeApprovalRisk, taskNotification } from './notifications.js'

const task = { id: 'task-1', thread_id: 'thread-1', request_summary: '部署服务' }

test('receipt, workflow and completion are separate task-aware notification surfaces', () => {
  const receipt = taskNotification(task, 'queued')
  const workflow = taskNotification(task, 'running')
  const completion = taskNotification(task, 'completed')
  assert.match(receipt.body, /部署服务/)
  assert.deepEqual([receipt.surface, workflow.surface, completion.surface], ['receipt', 'workflow', 'completion'])
  assert.equal(new Set([receipt.notification_key, workflow.notification_key, completion.notification_key]).size, 3)
})

test('approval risk is explicit and unknown risk fails closed to high', () => {
  assert.equal(normalizeApprovalRisk('low'), 'low')
  assert.equal(normalizeApprovalRisk('unclear'), 'high')
  const low = taskNotification(task, 'waiting_approval', { risk_level: 'low', approval_id: 'abcdef123', summary: '部署', impact: '重启服务' })
  const unknown = taskNotification(task, 'waiting_approval', { risk_level: 'unknown', approval_id: 'abcdef123', summary: '部署', impact: '重启服务' })
  assert.deepEqual(low.actions, ['approve', 'reject'])
  assert.equal(unknown.risk_level, 'high')
  assert.deepEqual(unknown.actions, ['open_private_thread'])
})

test('requires_local_user never exposes remote approval controls', () => {
  const event = taskNotification(task, 'requires_local_user', { summary: '完成 UAC 安全桌面确认' })
  assert.match(event.body, /^待办：/)
  assert.equal(event.actions.includes('approve'), false)
})
