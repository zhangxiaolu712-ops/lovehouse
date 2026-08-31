import { routeLivingroomMessage } from './routing.js'
import { taskNotification } from './notifications.js'

const q = value => encodeURIComponent(String(value))

export class SupabaseLivingroomTaskRepository {
  constructor({ rest, ownerId, route, transientStore = null }) {
    if (typeof rest !== 'function' || !ownerId || !route) throw new TypeError('task repository is not configured')
    this.rest = rest
    this.ownerId = ownerId
    this.route = route
    this.transientStore = transientStore
  }

  async ingestMentions() {
    const messages = await this.rest('GET', 'livingroom?order=created_at.desc&limit=100')
    let created = 0
    for (const message of messages.slice().reverse()) {
      const target = routeLivingroomMessage(message.message)
      if (!target || target.agent !== this.route.agent) continue
      const existing = await this.rest('GET', `livingroom_tasks?source_message_id=eq.${q(message.id)}&limit=1&select=id`)
      if (existing?.length) continue
      const task = (await this.rest('POST', 'livingroom_tasks', {
        owner_id: this.ownerId,
        source_message_id: message.id,
        target_agent: target.agent,
        target_runtime: target.runtime,
        target_endpoint: target.endpoint,
        request_summary: target.prompt.slice(0, 1000),
      }))?.[0]
      if (task?.thread_id) this.transientStore?.append(task.thread_id, { type: 'request', content: target.prompt })
      if (task?.thread_id) this.publishNotification(task, 'queued')
      created += 1
    }
    return created
  }

  publishNotification(task, status, detail) {
    const event = taskNotification(task, status, detail)
    this.transientStore?.upsert(task.thread_id, item => item.type === 'task_notification' && item.task_id === task.id, event)
    return event
  }

  async claimQueued() {
    const path = `livingroom_tasks?target_agent=eq.${q(this.route.agent)}&target_runtime=eq.${q(this.route.runtime)}&target_endpoint=eq.${q(this.route.endpoint)}&status=eq.queued&order=created_at.asc&limit=1&select=*`
    const task = (await this.rest('GET', path))?.[0]
    if (!task) return null
    const rows = await this.rest('PATCH', `livingroom_tasks?id=eq.${q(task.id)}&status=eq.queued`, {
      status: 'running', updated_at: new Date().toISOString(),
    })
    return rows?.[0] || null
  }

  async waitForApproval(task, request, sessionId, metadata = {}) {
    return this.rest('POST', 'rpc/livingroom_request_approval', {
      p_owner_id: task.owner_id,
      p_task_id: task.id,
      p_request_summary: request.slice(0, 1000),
      p_runtime_session_id: sessionId,
      p_expires_at: null,
      p_risk_level: metadata.risk_level,
      p_action_summary: metadata.summary,
      p_impact_summary: metadata.impact,
    })
  }

  requireLocalUser(task, request, sessionId) {
    return this.rest('POST', 'rpc/livingroom_require_local_user', {
      p_owner_id: task.owner_id, p_task_id: task.id,
      p_request_summary: request.slice(0, 1000), p_runtime_session_id: sessionId,
    })
  }

  async decideApproval(approvalId, decision) {
    return this.rest('POST', 'rpc/livingroom_decide_approval', {
      p_owner_id: this.ownerId,
      p_approval_id: approvalId,
      p_decision: decision,
    })
  }

  resumeLocalUser(taskId) {
    return this.rest('POST', 'rpc/livingroom_resume_local_user', {
      p_owner_id: this.ownerId, p_task_id: taskId,
    })
  }

  async getThread(threadId) {
    return (await this.rest('GET', `livingroom_tasks?thread_id=eq.${q(threadId)}&limit=1&select=*`))?.[0] || null
  }

  async createManualApproval(taskId, request) {
    const task = (await this.rest('GET', `livingroom_tasks?id=eq.${q(taskId)}&limit=1&select=*`))?.[0]
    if (!task || task.status !== 'running') return null
    return this.waitForApproval(task, request, task.runtime_session_id)
  }

  complete(task, result) {
    const now = new Date().toISOString()
    return this.rest('PATCH', `livingroom_tasks?id=eq.${q(task.id)}&status=eq.running`, {
      status: 'completed', runtime_session_id: result.sessionId,
      final_result_summary: result.text.trim().slice(0, 1000), updated_at: now, completed_at: now,
    })
  }

  fail(task, error) {
    return this.rest('PATCH', `livingroom_tasks?id=eq.${q(task.id)}&status=eq.running`, {
      status: 'failed', last_error_summary: String(error?.message || error).slice(0, 1000),
      updated_at: new Date().toISOString(),
    })
  }
}
