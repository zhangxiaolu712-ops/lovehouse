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
    notification_key: `livingroom-task:${task.id}`,
    task_id: task.id,
    thread_id: task.thread_id,
    status,
    title: 'LoveHouse · Codex',
    actions: [],
  }
  if (status === 'queued') return { ...base, body: 'Codex 已接到任务' }
  if (status === 'running') return { ...base, body: `Codex 正在执行：${summary}` }
  if (status === 'requires_local_user') return { ...base, body: `待办：${summary}` }
  if (status === 'completed') return { ...base, body: `Codex 已完成：${summary}` }
  if (status === 'failed') return { ...base, body: `Codex 执行失败：${summary}` }
  if (status === 'waiting_approval') {
    const approvalId = short(detail.approval_id, '', 36)
    const labels = { low: '低风险', medium: '中风险', high: '高风险' }
    return {
      ...base,
      approval_id: approvalId,
      risk_level: risk,
      body: `${summary} · ${short(detail.impact, '影响当前任务')} · ${labels[risk]} · #${approvalId.slice(0, 8)}`,
      actions: risk === 'low' ? ['approve', 'reject'] : ['open_private_thread'],
    }
  }
  throw new TypeError(`unsupported task notification status: ${status}`)
}

