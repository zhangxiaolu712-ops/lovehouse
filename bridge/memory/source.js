const MAX_PAGE_SIZE = 20
const MAX_RANGE_SPAN = 49

function positiveId(value, label) {
  const parsed = Number.parseInt(value, 10)
  if (!Number.isSafeInteger(parsed) || parsed < 1) {
    throw new TypeError(`${label} must be a positive integer`)
  }
  return parsed
}

function pageLimit(value) {
  const parsed = Number.parseInt(value, 10)
  if (!Number.isFinite(parsed) || parsed < 1) return MAX_PAGE_SIZE
  return Math.min(parsed, MAX_PAGE_SIZE)
}

function sourceBase(source) {
  if (!source || typeof source !== 'object') {
    throw new TypeError('A trusted source descriptor is required')
  }
  return {
    source_id: positiveId(source.source_id, 'source_id'),
    source_channel: source.source_channel,
    source_kind: source.source_kind,
    locator: source.locator || {},
    created_by_actor: source.created_by_actor,
    created_at: source.created_at,
  }
}

function resolverError(code, message) {
  const error = new Error(message)
  error.code = code
  return error
}

function evidenceMissing() {
  return resolverError(
    'MEMORY_SOURCE_EVIDENCE_NOT_FOUND',
    'The referenced source evidence is unavailable'
  )
}

export class SourceResolver {
  constructor({ routes = [] } = {}) {
    this.routes = []
    for (const route of routes) this.register(route)
  }

  register({ sourceKinds, sourceChannels = null, resolver }) {
    if (!Array.isArray(sourceKinds) || sourceKinds.length === 0
      || !sourceKinds.every(kind => typeof kind === 'string' && kind)) {
      throw new TypeError('Source resolver routes require one or more source kinds')
    }
    if (sourceChannels !== null && (
      !Array.isArray(sourceChannels) || sourceChannels.length === 0
      || !sourceChannels.every(channel => typeof channel === 'string' && channel)
    )) {
      throw new TypeError('Source resolver channels must be a non-empty string array')
    }
    if (typeof resolver?.resolve !== 'function') {
      throw new TypeError('Source resolver routes require a resolve function')
    }
    this.routes.push({
      sourceKinds: new Set(sourceKinds),
      sourceChannels: sourceChannels === null ? null : new Set(sourceChannels),
      resolver,
    })
    return this
  }

  async resolve(source, options = {}) {
    sourceBase(source)
    const exact = this.routes.find(route => (
      route.sourceChannels !== null
      && route.sourceKinds.has(source.source_kind)
      && route.sourceChannels.has(source.source_channel)
    ))
    const channelNeutral = this.routes.find(route => (
      route.sourceChannels === null && route.sourceKinds.has(source.source_kind)
    ))
    const route = exact || channelNeutral
    if (!route) {
      throw resolverError(
        'MEMORY_SOURCE_RESOLVER_NOT_CONFIGURED',
        `No evidence resolver is configured for ${source.source_channel}:${source.source_kind}`
      )
    }
    return route.resolver.resolve(source, options)
  }
}

export class ManualQuoteEvidenceResolver {
  async resolve(source) {
    const base = sourceBase(source)
    if (typeof source.quote_text !== 'string' || !source.quote_text.trim()) {
      throw evidenceMissing()
    }
    return {
      ...base,
      evidence: {
        type: 'manual_quote',
        quote_text: source.quote_text,
      },
    }
  }
}

export class ManualSummaryEvidenceResolver {
  async resolve(source) {
    const base = sourceBase(source)
    return {
      ...base,
      evidence: {
        type: 'manual_summary',
        available: false,
        provenance: {
          source_channel: base.source_channel,
          locator: base.locator,
          created_by_actor: base.created_by_actor,
          created_at: base.created_at,
        },
      },
    }
  }
}

// Stable data-access contract for persistent chat evidence. Implementations
// may use PostgreSQL, an API, WeChat export storage, or another repository;
// SourceResolver and MemoryService do not know which storage backs it.
export class UnavailableChatMessageRepository {
  async getMessage() {
    throw resolverError(
      'MEMORY_CHAT_SOURCE_NOT_CONFIGURED',
      'Persistent chat message source storage is not configured'
    )
  }

  async listMessages() {
    throw resolverError(
      'MEMORY_CHAT_SOURCE_NOT_CONFIGURED',
      'Persistent chat message source storage is not configured'
    )
  }
}

export class ChatMessageEvidenceResolver {
  constructor({ messageRepository = new UnavailableChatMessageRepository() } = {}) {
    if (typeof messageRepository?.getMessage !== 'function'
      || typeof messageRepository?.listMessages !== 'function') {
      throw new TypeError('A chat message repository is required')
    }
    this.messageRepository = messageRepository
  }

  async resolve(source, { cursorMessageId = null, limit = MAX_PAGE_SIZE } = {}) {
    const base = sourceBase(source)
    if (source.source_kind === 'lovehouse_message') {
      const messageId = positiveId(base.locator.message_id, 'locator.message_id')
      const message = await this.messageRepository.getMessage({
        sourceChannel: base.source_channel,
        messageId,
      })
      if (!message) throw evidenceMissing()
      return {
        ...base,
        evidence: {
          type: 'chat_messages',
          messages: [message],
          has_more: false,
          next_cursor: null,
        },
      }
    }

    if (!['lovehouse_message_range', 'lovehouse_range'].includes(source.source_kind)) {
      throw evidenceMissing()
    }
    const start = positiveId(base.locator.start_message_id, 'locator.start_message_id')
    const end = positiveId(base.locator.end_message_id, 'locator.end_message_id')
    if (end < start || end - start > MAX_RANGE_SPAN) {
      throw resolverError('MEMORY_SOURCE_RANGE_INVALID', 'Chat source range exceeds the hard limit')
    }
    const cursor = cursorMessageId === null || cursorMessageId === undefined
      ? null
      : positiveId(cursorMessageId, 'cursor_message_id')
    if (cursor !== null && (cursor < start || cursor >= end)) {
      throw resolverError('MEMORY_SOURCE_CURSOR_INVALID', 'Source cursor is outside the stored message range')
    }

    const safeLimit = pageLimit(limit)
    const rows = await this.messageRepository.listMessages({
      sourceChannel: base.source_channel,
      startMessageId: start,
      endMessageId: end,
      afterMessageId: cursor,
      limit: safeLimit + 1,
    })
    if (!Array.isArray(rows)) {
      throw resolverError('MEMORY_SOURCE_REPOSITORY_INVALID', 'Chat source repository returned an invalid result')
    }
    if (rows.length === 0) throw evidenceMissing()
    const hasMore = rows.length > safeLimit
    const messages = rows.slice(0, safeLimit)
    return {
      ...base,
      evidence: {
        type: 'chat_messages',
        messages,
        has_more: hasMore,
        next_cursor: hasMore ? positiveId(messages.at(-1)?.id, 'message.id') : null,
      },
    }
  }
}

export function createCanonicalSourceResolver({ messageRepository } = {}) {
  return new SourceResolver({
    routes: [
      {
        sourceKinds: ['manual_quote'],
        resolver: new ManualQuoteEvidenceResolver(),
      },
      {
        sourceKinds: ['manual_summary'],
        resolver: new ManualSummaryEvidenceResolver(),
      },
      {
        // These existing canonical kinds mean persistent LoveHouse chat
        // messages, never livingroom rows. The default repository is
        // intentionally unavailable until a real chat message store exists.
        sourceKinds: ['lovehouse_message', 'lovehouse_message_range', 'lovehouse_range'],
        sourceChannels: ['lovehouse'],
        resolver: new ChatMessageEvidenceResolver({ messageRepository }),
      },
    ],
  })
}
