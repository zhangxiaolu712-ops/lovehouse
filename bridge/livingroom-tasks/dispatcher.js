const APPROVAL_MARKER = /\[\[APPROVAL_REQUIRED:\s*([^\]]+)\]\]/i

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
      })
      const approval = result.text.match(APPROVAL_MARKER)
      if (approval) {
        this.transientStore.append(task.thread_id, { type: 'approval', content: approval[1].trim() })
        await this.repository.waitForApproval(task, approval[1].trim(), result.sessionId)
      } else {
        this.transientStore.append(task.thread_id, { type: 'result', content: result.text })
        await this.repository.complete(task, result)
      }
      return true
    } catch (error) {
      this.logger.error?.('[livingroom-dispatcher]', error.message)
      if (task) await this.repository.fail(task, error)
      return false
    } finally {
      this.busy = false
    }
  }
}
