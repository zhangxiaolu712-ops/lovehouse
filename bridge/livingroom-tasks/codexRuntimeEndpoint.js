export class CodexRuntimeEndpoint {
  constructor({ runtime }) { this.runtime = runtime }

  async run({ threadId, message, sessionId = null, onMilestone = () => {} }) {
    let text = ''
    let boundSessionId = sessionId
    const result = await this.runtime.streamEvents({
      message, history: [], sessionId, previousUsage: null,
      getContinuationContext: async () => [],
      onRuntimeBinding: value => { boundSessionId = value },
      onText: delta => { text += delta },
      onEvent(event, payload = {}) {
        if (event === 'reasoning_status' && payload.summary) {
          onMilestone({ stage: 'reasoning', status: 'running', summary: payload.summary })
        } else if (event === 'tool_call') {
          onMilestone({ stage: 'tool', status: 'running', summary: `正在使用 ${payload.name || payload.tool_type || '工具'}` })
        } else if (event === 'tool_error') {
          onMilestone({ stage: 'tool', status: 'failed', summary: payload.summary || '工具执行失败' })
        }
      },
    })
    return { text: result.text || text, sessionId: result.sessionId || boundSessionId, threadId }
  }
}
