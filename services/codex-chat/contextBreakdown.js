function estimateTokens(text) {
  return Math.max(0, Math.ceil(Array.from(String(text || '')).length / 4))
}

export function createContextBreakdown({
  history = [], message, resumed, runtimeType = 'codex_cli',
}) {
  const nativeContext = runtimeType === 'claude_cli'
    ? 'claude_native_session'
    : 'codex_native_thread'
  const nativeCompaction = runtimeType === 'claude_cli'
    ? 'claude_native'
    : 'codex_native'
  const recentChatTokens = history.reduce((sum, item) => sum + estimateTokens(item?.content), 0)
  const currentMessageTokens = estimateTokens(message)
  return {
    recent_chat: {
      enabled: true,
      available: true,
      source: resumed ? nativeContext : 'bounded_client_fallback',
      message_count: resumed ? null : history.length,
      estimated_tokens: resumed ? null : recentChatTokens,
    },
    memory: { enabled: false, available: false, estimated_tokens: 0 },
    worldbook: { enabled: false, available: false, estimated_tokens: 0 },
    persona: { enabled: false, available: false, estimated_tokens: 0 },
    current_message: {
      enabled: true,
      available: true,
      estimated_tokens: currentMessageTokens,
    },
    reasoning: {
      enabled: true,
      available: null,
      status: resumed ? 'resumed' : 'pending',
      summary: null,
      source: nativeContext,
      active_context: true,
      resumes_with_thread: true,
      compaction: nativeCompaction,
    },
    estimated_tokens: resumed ? currentMessageTokens : recentChatTokens + currentMessageTokens,
  }
}

export function withReasoningContext(context, reasoning) {
  return {
    ...context,
    reasoning: {
      ...context.reasoning,
      available: reasoning?.available === true,
      status: typeof reasoning?.status === 'string' ? reasoning.status : 'unavailable',
      summary: typeof reasoning?.summary === 'string' ? reasoning.summary : null,
    },
  }
}

export { estimateTokens }
