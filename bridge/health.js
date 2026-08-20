function repositoryState(repository) {
  const type = repository?.constructor?.name || null
  if (!type) return { type: null, status: 'unavailable' }
  return {
    type,
    status: type === 'DisabledMemoryRepository' ? 'disabled' : 'selected',
  }
}

export function createHealthSnapshot({
  bridgeStartedAt,
  claudeProcess,
  memorySystemEnabled,
  memorySemanticEnabled,
  memoryEmbeddingProviderConfigured,
  memoryDreamEnabled,
  memoryDreamCuratorConfigured,
  memoryDreamCuratorProvider,
  memoryRankingProfile,
  memoryWritesEnabled,
  memoryRepository,
}) {
  return {
    status: 'ok',
    bridge_started_at: bridgeStartedAt,
    deployment_release: {
      status: 'unavailable',
      commit: null,
    },
    claude_process: claudeProcess,
    memory_system_enabled: memorySystemEnabled,
    memory_writes_enabled: memoryWritesEnabled,
    memory_semantic_enabled: memorySemanticEnabled,
    memory_dream_enabled: memoryDreamEnabled,
    memory_repository: repositoryState(memoryRepository),
    memory_embedding_provider_configured: memoryEmbeddingProviderConfigured,
    memory_dream_curator_configured: memoryDreamCuratorConfigured,
    memory_dream_curator_provider: memoryDreamCuratorProvider || null,
    memory_ranking_profile: memoryRankingProfile,
  }
}
