const RISKS = new Set(['low', 'medium', 'high'])

const short = (value, fallback, limit = 80) => String(value || fallback).replace(/\s+/g, ' ').trim().slice(0, limit)

export function normalizeApprovalRisk(value) {
  const risk = String(value || '').trim().toLowerCase()
  return RISKS.has(risk) ? risk : 'high'
}

export function taskNotification(task, status, detail = {}) {
  const risk = normalizeApprovalRisk(detail.risk_level)
  const summary = short(detail.summary, task.request_summary || '任务')
  const base = {
    type: 'task_notification',
    task_id: task.id,
    thread_id: task.thread_id,
    status,
    title: 'LoveHouse · Codex',
    actions: [],
  }
  if (status === 'queued') return {
    ...base, surface: 'receipt', notification_key: `livingroom-task:${task.id}:receipt`,
    body: `Codex 已签收：${summary}`,
  }
  if (status === 'running') return {
    ...base, surface: 'workflow', notification_key: `livingroom-task:${task.id}:workflow`,
    body: `Codex 正在执行：${summary}`,
  }
  if (status === 'requires_local_user') return {
    ...base, surface: 'approval', notification_key: `livingroom-task:${task.id}:local-user`,
    body: `待办：${summary}`,
  }
  if (status === 'completed') return {
    ...base, surface: 'completion', notification_key: `livingroom-task:${task.id}:completion`,
    dismiss_notification_key: `livingroom-task:${task.id}:workflow`,
    body: `Codex 已完成：${summary}`,
  }
  if (status === 'failed') return {
    ...base, surface: 'completion', notification_key: `livingroom-task:${task.id}:completion`,
    dismiss_notification_key: `livingroom-task:${task.id}:workflow`,
    body: `Codex 执行失败：${summary}`,
  }
  if (status === 'waiting_approval') {
    const approvalId = short(detail.approval_id, '', 36)
    const labels = { low: '低风险', medium: '中风险', high: '高风险' }
    return {
      ...base,
      surface: 'approval',
      notification_key: `livingroom-task:${task.id}:approval:${approvalId}`,
      approval_id: approvalId,
      risk_level: risk,
      body: `${summary} · ${short(detail.impact, '影响当前任务')} · ${labels[risk]} · #${approvalId.slice(0, 8)}`,
      actions: risk === 'low' ? ['approve', 'reject'] : ['open_private_thread'],
    }
  }
  throw new TypeError(`unsupported task notification status: ${status}`)
}

export function workflowMilestone(task, milestone) {
  const summary = short(milestone?.summary, '继续处理任务', 160)
  const stage = String(milestone?.stage || 'working').replace(/[^a-z0-9_-]/gi, '').slice(0, 40) || 'working'
  return {
    type: 'workflow_milestone',
    task_id: task.id,
    thread_id: task.thread_id,
    stage,
    status: milestone?.status === 'failed' ? 'failed' : 'running',
    summary,
  }
}
