function estimateTokens(text) {
  return Math.max(0, Math.ceil(Array.from(String(text || '')).length / 4))
}

export function createContextBreakdown({ history = [], message, resumed }) {
  const recentChatTokens = history.reduce((sum, item) => sum + estimateTokens(item?.content), 0)
  const currentMessageTokens = estimateTokens(message)
  return {
    recent_chat: {
      enabled: true,
      available: true,
      source: resumed ? 'codex_native_thread' : 'bounded_client_fallback',
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
    estimated_tokens: resumed ? currentMessageTokens : recentChatTokens + currentMessageTokens,
  }
}

export { estimateTokens }
