const SUPPORTED_OPERATIONS = Object.freeze(['remember', 'recall', 'revise', 'open'])

function invalidSpace(spaceKey) {
  const error = new TypeError(`Unsupported memory space: ${spaceKey}`)
  error.code = 'MCP_MEMORY_SPACE_UNSUPPORTED'
  return error
}

function forbiddenSpace(actor, spaceKey, operation) {
  const error = new TypeError(`${actor} cannot ${operation} in ${spaceKey} memory space`)
  error.code = 'MCP_MEMORY_SPACE_FORBIDDEN'
  return error
}

function requireSubjectKey(subjectKey) {
  if (typeof subjectKey !== 'string' || !subjectKey.trim()) {
    throw new TypeError('subject_key is required for Engineering Memory')
  }
  return subjectKey.trim()
}

function rejectSubjectKey(subjectKey) {
  if (subjectKey !== undefined) {
    throw new TypeError('subject_key is only valid for Engineering Memory')
  }
}

function rejectMemoryId(memoryId) {
  if (memoryId !== undefined) {
    throw new TypeError('memory_id is not valid for Engineering Memory')
  }
}

function filterSpace(result, spaceKey, limit) {
  return {
    ...result,
    items: Array.isArray(result?.items)
      ? result.items.filter(item => item?.space_key === spaceKey).slice(0, limit)
      : [],
  }
}

function requestedLimit(input) {
  const parsed = Number.parseInt(input?.limit, 10)
  return Number.isFinite(parsed) && parsed > 0 ? Math.min(parsed, 10) : 5
}

function privatePolicy(spaceKey) {
  return Object.freeze({
    key: spaceKey,
    authorize(actor, operation) {
      if (actor !== spaceKey) throw forbiddenSpace(actor, spaceKey, operation)
    },
    remember({ memory, input, subjectKey }) {
      rejectSubjectKey(subjectKey)
      return memory.remember(input)
    },
    async recall({ memory, input, explicit }) {
      if (!explicit) return memory.recall(input)
      const result = await memory.recall({ ...input, limit: 10 })
      return filterSpace(result, spaceKey, requestedLimit(input))
    },
    revise({ memory, memoryId, subjectKey, input }) {
      rejectSubjectKey(subjectKey)
      if (!memoryId) throw new TypeError('memory_id is required for private Memory')
      return memory.revise(memoryId, input)
    },
    open({ memory, memoryId, sourceId, subjectKey }) {
      rejectSubjectKey(subjectKey)
      if (memoryId) return memory.history(memoryId)
      return memory.expandSource(sourceId)
    },
  })
}

const SPACE_POLICY_REGISTRY = Object.freeze({
  gpt: privatePolicy('gpt'),
  claude: privatePolicy('claude'),
  shared: Object.freeze({
    key: 'shared',
    authorize(_actor, operation) {
      if (!['recall', 'open'].includes(operation)) {
        throw forbiddenSpace(_actor, 'shared', operation)
      }
    },
    async recall({ memory, input }) {
      const result = await memory.recall({ ...input, limit: 10 })
      return filterSpace(result, 'shared', requestedLimit(input))
    },
    open({ memory, memoryId, sourceId, subjectKey }) {
      rejectSubjectKey(subjectKey)
      if (memoryId) return memory.history(memoryId)
      return memory.expandSource(sourceId)
    },
  }),
  engineering: Object.freeze({
    key: 'engineering',
    authorize() {},
    remember({ engineering, input, subjectKey }) {
      return engineering.upsertEngineeringFact({
        ...input,
        subjectKey: requireSubjectKey(subjectKey),
      })
    },
    recall({ engineering, input }) {
      return engineering.recallEngineering(input)
    },
    revise({ engineering, memoryId, subjectKey, input }) {
      rejectMemoryId(memoryId)
      return engineering.upsertEngineeringFact({
        ...input,
        subjectKey: requireSubjectKey(subjectKey),
      })
    },
    open({ engineering, memoryId, sourceId, subjectKey }) {
      rejectMemoryId(memoryId)
      if (sourceId) return engineering.expandEngineeringSource(sourceId)
      return engineering.openEngineeringFact(requireSubjectKey(subjectKey))
    },
  }),
})

export function createMemorySpaceRouter({ actor, memory, engineering }) {
  if (!actor || !memory || !engineering) {
    throw new TypeError('Memory space router requires fixed actor facades')
  }

  function resolve(spaceKey, operation) {
    if (!SUPPORTED_OPERATIONS.includes(operation)) throw new TypeError(`Unknown space operation: ${operation}`)
    const explicit = spaceKey !== undefined
    if (explicit && (typeof spaceKey !== 'string' || !spaceKey.trim())) {
      throw new TypeError('space_key must be a non-empty string')
    }
    const resolvedKey = explicit ? spaceKey.trim() : actor
    const policy = SPACE_POLICY_REGISTRY[resolvedKey]
    if (!policy) throw invalidSpace(resolvedKey)
    policy.authorize(actor, operation)
    return { policy, explicit }
  }

  return Object.freeze({
    call(operation, spaceKey, context) {
      const { policy, explicit } = resolve(spaceKey, operation)
      return policy[operation]({ actor, memory, engineering, explicit, ...context })
    },
  })
}

export const memorySpacePolicyRegistry = SPACE_POLICY_REGISTRY
