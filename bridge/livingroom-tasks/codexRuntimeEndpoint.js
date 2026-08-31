export class CodexRuntimeEndpoint {
  constructor({ runtime }) { this.runtime = runtime }

  async run({ threadId, message, sessionId = null }) {
    let text = ''
    let boundSessionId = sessionId
    const result = await this.runtime.streamEvents({
      message, history: [], sessionId, previousUsage: null,
      getContinuationContext: async () => [],
      onRuntimeBinding: value => { boundSessionId = value },
      onText: delta => { text += delta },
      onEvent() {},
    })
    return { text: result.text || text, sessionId: result.sessionId || boundSessionId, threadId }
  }
}
