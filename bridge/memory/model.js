export const MEMORY_ACTORS = Object.freeze({
  GPT: 'gpt',
  CLAUDE: 'claude',
})

export const MEMORY_SPACES = Object.freeze({
  GPT: 'gpt',
  CLAUDE: 'claude',
  SHARED: 'shared',
  LEGACY_PENDING: 'legacy_pending',
})

export const SHARED_STATES = Object.freeze({
  PENDING: 'pending',
  APPROVED: 'approved',
  REJECTED: 'rejected',
  REVOKED: 'revoked',
})

// Stable keys for the structural abilities inherited from the old tables.
// Display labels stay outside the storage contract so the UI can change safely.
export const MEMORY_TYPES = Object.freeze([
  'fact',
  'feeling',
  'diary',
  'article',
  'small_moment',
  'memo',
  'self_inquiry',
  'quote',
  'summary',
  'reflection',
])

export const LEGACY_MEMORY_TYPE_MAP = Object.freeze({
  '记事': 'fact',
  '记感受': 'feeling',
  '日记': 'diary',
  '长文': 'article',
  '小事记': 'small_moment',
  '备忘录': 'memo',
  '问心': 'self_inquiry',
  '语录': 'quote',
  '总结': 'summary',
  '观点': 'reflection',
})

export function memoryTypeFromInput(input = {}) {
  const requested = input.memory_type || input.memoryType
  if (MEMORY_TYPES.includes(requested)) return requested

  const mapped = LEGACY_MEMORY_TYPE_MAP[input.tag]
    || LEGACY_MEMORY_TYPE_MAP[input.kind]
  return mapped || 'fact'
}
