const APPROVAL_MARKER = /\[\[APPROVAL_REQUIRED:\s*([^\]]+)\]\]/i
const LOCAL_USER_MARKER = /\[\[LOCAL_USER_REQUIRED:\s*([^\]]+)\]\]/i
import { normalizeApprovalRisk } from './notifications.js'
import { workflowMilestone } from './notifications.js'

function parseApproval(value) {
  const [risk, summary, impact, ...detail] = value.split('|').map(part => part.trim())
  return { risk_level: normalizeApprovalRisk(risk), summary: summary || value.trim(), impact: impact || '影响当前任务', detail: detail.join('|') || value.trim() }
}

export class LivingroomTaskDispatcher {
  constructor({ repository, endpoint, resolveEndpoint, transientStore, pollMs = 3000, logger = console }) {
    this.repository = repository
    this.endpoint = endpoint
    this.resolveEndpoint = resolveEndpoint || (() => this.endpoint)
    this.transientStore = transientStore
    this.pollMs = pollMs
    this.logger = logger
    this.busy = false
    this.timer = null
  }

  start() {
    if (this.timer) return
    this.timer = setInterval(() => void this.tick(), this.pollMs)
    this.timer.unref?.()
    void this.tick()
  }

  stop() {
    if (this.timer) clearInterval(this.timer)
    this.timer = null
  }

  async tick() {
    if (this.busy) return false
    this.busy = true
    let task
    try {
      await this.repository.ingestMentions()
      task = await this.repository.claimQueued()
      if (!task) return false
      this.transientStore.append(task.thread_id, { type: 'status', status: 'running' })
      this.repository.publishNotification(task, 'running', { summary: '正在处理任务' })
      const resumed = Boolean(task.runtime_session_id)
      const request = this.transientStore.read(task.thread_id).find(event => event.type === 'request')?.content
        || task.request_summary
      const endpoint = this.resolveEndpoint({
        agent: task.target_agent,
        runtime: task.target_runtime,
        endpoint: task.target_endpoint,
      })
      if (!endpoint?.run) throw new Error('task endpoint is unavailable')
      const result = await endpoint.run({
        threadId: task.thread_id,
        sessionId: task.runtime_session_id || null,
        message: resumed
          ? `Approval granted. Resume this task from its checkpoint and finish it: ${request}`
          : request,
        onMilestone: milestone => {
          const event = workflowMilestone(task, milestone)
          this.transientStore.upsert(task.thread_id,
            item => item.type === 'workflow_milestone' && item.task_id === task.id,
            event)
          this.repository.publishNotification(task, 'running', { summary: event.summary })
        },
      })
      const approval = result.text.match(APPROVAL_MARKER)
      const localUser = result.text.match(LOCAL_USER_MARKER)
      if (localUser) {
        const request = localUser[1].trim()
        this.transientStore.append(task.thread_id, { type: 'requires_local_user', content: request })
        await this.repository.requireLocalUser(task, request, result.sessionId)
        this.repository.publishNotification(task, 'requires_local_user', { summary: request })
      } else if (approval) {
        const parsed = parseApproval(approval[1])
        this.transientStore.append(task.thread_id, { type: 'approval', content: parsed.detail, risk_level: parsed.risk_level })
        const requested = await this.repository.waitForApproval(task, parsed.detail, result.sessionId, parsed)
        this.repository.publishNotification(task, 'waiting_approval', { ...parsed, approval_id: requested.id })
      } else {
        this.transientStore.append(task.thread_id, { type: 'result', content: result.text })
        await this.repository.complete(task, result)
        this.repository.publishNotification(task, 'completed', { summary: result.text })
      }
      return true
    } catch (error) {
      this.logger.error?.('[livingroom-dispatcher]', error.message)
      if (task) {
        await this.repository.fail(task, error)
        this.repository.publishNotification(task, 'failed', { summary: error.message })
      }
      return false
    } finally {
      this.busy = false
    }
  }
}
