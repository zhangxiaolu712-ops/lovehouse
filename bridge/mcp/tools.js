import { MEMORY_ACTORS } from '../memory/index.js'
import { isLivingroomRest } from '../livingroom.js'
import { createMemorySpaceRouter } from './spacePolicy.js'

export const MCP_TOOL_ROUTES = Object.freeze({
  wake_up: 'memory-v2.starterPack',
  remember: 'memory-v2.remember',
  recall: 'memory-v2.recall',
  revise: 'memory-v2.revise',
  open_memory: 'memory-v2.open',
  read_livingroom: 'livingroom.read',
  say_livingroom: 'livingroom.write',
})

const UUID_PATTERN = '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$'
const uuidField = { type: 'string', pattern: UUID_PATTERN }
const spaceKeyField = { type: 'string', minLength: 1, maxLength: 100 }
const subjectKeyField = { type: 'string', minLength: 1, maxLength: 200 }

const closedObject = properties => ({
  type: 'object',
  properties,
  additionalProperties: false,
})

const sourceInput = {
  oneOf: [
    {
      ...closedObject({ source_id: uuidField }),
      required: ['source_id'],
    },
    {
      ...closedObject({
        source_kind: {
          type: 'string',
          pattern: '^[a-z][a-z0-9_]{0,79}$',
        },
        locator: { type: 'object' },
        provenance: { type: 'object' },
        quote_text: { type: 'string', minLength: 1, maxLength: 20000 },
      }),
      required: ['source_kind'],
    },
  ],
}

const optionalMemoryFields = {
  metadata: { type: 'object', maxProperties: 30 },
  event_time: { type: 'string', description: '真实事件时间；未知时不要填写。' },
  human_importance: { type: 'integer', minimum: 0, maximum: 5 },
  ai_importance: { type: 'integer', minimum: 0, maximum: 5 },
  supersedes_memory_id: uuidField,
  sources: { type: 'array', maxItems: 20, items: sourceInput },
}

function toMemoryV2Sources(sources) {
  if (sources === undefined) return undefined
  return sources.map(source => source.source_id
    ? { sourceId: source.source_id }
    : {
        sourceKind: source.source_kind,
        locator: source.locator,
        provenance: source.provenance,
        quoteText: source.quote_text,
      })
}

function toMemoryV2Input(args) {
  return {
    ...(args.content === undefined ? {} : { content: args.content }),
    ...(args.metadata === undefined ? {} : { metadata: args.metadata }),
    ...(args.event_time === undefined ? {} : { eventTime: args.event_time }),
    ...(args.human_importance === undefined ? {} : { humanImportance: args.human_importance }),
    ...(args.ai_importance === undefined ? {} : { aiImportance: args.ai_importance }),
    ...(args.supersedes_memory_id === undefined
      ? {}
      : { supersedesMemoryId: args.supersedes_memory_id }),
    ...(args.reason === undefined ? {} : { reason: args.reason }),
    ...(args.sources === undefined ? {} : { sources: toMemoryV2Sources(args.sources) }),
  }
}

export function createMcpToolDefinitions(actor) {
  const sender = actor === MEMORY_ACTORS.GPT ? 'GPT' : 'CC'
  return [
    {
      name: 'wake_up',
      description: '新窗口开始时调用。返回 Memory V2 的当前有效承诺、最近记忆/变化和少量随机盲盒；遵守固定 actor private 与 approved Shared 边界。',
      inputSchema: closedObject({
        soft_limit: { type: 'integer', minimum: 1, maximum: 15 },
        token_budget: { type: 'integer', minimum: 1, maximum: 4000 },
      }),
    },
    {
      name: 'remember',
      description: `记住一件事。最少只需 content；不传 space_key 时使用当前 ${actor} private space。Engineering 必须显式传 space_key 与稳定 subject_key。`,
      inputSchema: {
        ...closedObject({
          content: { type: 'string', minLength: 1, maxLength: 50000 },
          space_key: spaceKeyField,
          subject_key: subjectKeyField,
          ...optionalMemoryFields,
        }),
        required: ['content'],
      },
    },
    {
      name: 'recall',
      description: '召回相关 Memory。省略 space_key 时保持当前 channel private 语义；显式 space_key 由统一 policy 路由。',
      inputSchema: {
        ...closedObject({
          query: { type: 'string', minLength: 1, maxLength: 1000 },
          limit: { type: 'integer', minimum: 1, maximum: 10 },
          space_key: spaceKeyField,
        }),
        required: ['query'],
      },
    },
    {
      name: 'revise',
      description: '修订 Memory。普通空间使用 memory_id；Engineering 使用稳定 subject_key 并追加 revision。',
      inputSchema: {
        ...closedObject({
          memory_id: uuidField,
          subject_key: subjectKeyField,
          space_key: spaceKeyField,
          content: { type: 'string', minLength: 1, maxLength: 50000 },
          reason: { type: 'string', minLength: 1, maxLength: 1000 },
          ...optionalMemoryFields,
        }),
        required: ['content'],
      },
    },
    {
      name: 'open_memory',
      description: '深挖 Memory：普通空间传 memory_id，Engineering 传 subject_key；传 source_id 才显式展开对应原文。',
      inputSchema: {
        ...closedObject({
          memory_id: uuidField,
          subject_key: subjectKeyField,
          source_id: uuidField,
          space_key: spaceKeyField,
        }),
        anyOf: [
          { required: ['memory_id'] },
          { required: ['subject_key'] },
          { required: ['source_id'] },
        ],
      },
    },
    {
      name: 'read_livingroom',
      description: '读取小客厅最近消息，同时返回同一批消息的纯文本 context。',
      inputSchema: closedObject({
        limit: { type: 'integer', minimum: 1, maximum: 200 },
        since: { type: 'string', description: 'ISO 时间戳' },
      }),
    },
    {
      name: 'say_livingroom',
      description: `向小客厅说一句话。发送者由服务端固定为 ${sender}。`,
      inputSchema: {
        ...closedObject({ message: { type: 'string', minLength: 1, maxLength: 10000 } }),
        required: ['message'],
      },
    },
  ]
}

function parseLimit(value, fallback, maximum) {
  const parsed = Number.parseInt(value, 10)
  if (!Number.isFinite(parsed) || parsed < 1) return fallback
  return Math.min(parsed, maximum)
}

function parseSince(value) {
  if (value === undefined || value === null || value === '') return null
  if (typeof value !== 'string' || Number.isNaN(Date.parse(value))) {
    throw new TypeError('since must be a valid ISO timestamp')
  }
  return new Date(value).toISOString()
}

export function createMcpToolHandler({ actor, memoryV2Service, engineeringMemoryService, livingroomRest }) {
  if (!Object.values(MEMORY_ACTORS).includes(actor)) throw new Error('A fixed MCP actor is required')
  if (!memoryV2Service || typeof memoryV2Service.forActor !== 'function') {
    throw new Error('MemoryV2Service is required')
  }
  if (!engineeringMemoryService || typeof engineeringMemoryService.forActor !== 'function') {
    throw new Error('EngineeringMemoryService is required')
  }
  if (!isLivingroomRest(livingroomRest)) {
    throw new Error('A fenced livingroom REST function is required')
  }
  const memory = memoryV2Service.forActor(actor)
  const engineering = engineeringMemoryService.forActor(actor)
  const spaces = createMemorySpaceRouter({ actor, memory, engineering })
  const sender = actor === MEMORY_ACTORS.GPT ? 'GPT' : 'CC'

  return async function callMcpTool(name, args = {}) {
    if (name === 'wake_up') {
      return JSON.stringify(await memory.starterPack({
        softLimit: args.soft_limit,
        tokenBudget: args.token_budget,
      }))
    }
    if (name === 'remember') {
      return JSON.stringify(await spaces.call('remember', args.space_key, {
        subjectKey: args.subject_key,
        input: toMemoryV2Input(args),
      }))
    }
    if (name === 'recall') {
      return JSON.stringify(await spaces.call('recall', args.space_key, {
        input: { query: args.query, limit: args.limit },
      }))
    }
    if (name === 'revise') {
      if (args.memory_id !== undefined && args.subject_key !== undefined) {
        throw new TypeError('revise accepts memory_id or subject_key, not both')
      }
      return JSON.stringify(await spaces.call('revise', args.space_key, {
        memoryId: args.memory_id,
        subjectKey: args.subject_key,
        input: toMemoryV2Input(args),
      }))
    }
    if (name === 'open_memory') {
      const hasMemoryId = typeof args.memory_id === 'string' && args.memory_id.length > 0
      const hasSubjectKey = typeof args.subject_key === 'string' && args.subject_key.length > 0
      const hasSourceId = typeof args.source_id === 'string' && args.source_id.length > 0
      if ([hasMemoryId, hasSubjectKey, hasSourceId].filter(Boolean).length !== 1) {
        throw new TypeError('open_memory requires exactly one of memory_id, subject_key or source_id')
      }
      const opened = await spaces.call('open', args.space_key, {
        memoryId: args.memory_id,
        subjectKey: args.subject_key,
        sourceId: args.source_id,
      })
      if (hasMemoryId || hasSubjectKey) {
        return JSON.stringify({
          mode: 'history',
          ...(hasMemoryId ? { memory_id: args.memory_id } : { subject_key: args.subject_key }),
          ...(hasSubjectKey ? opened : { revisions: opened }),
        })
      }
      return JSON.stringify({
        mode: 'source',
        source_id: args.source_id,
        source: opened,
      })
    }
    if (name === 'read_livingroom') {
      const limit = parseLimit(args.limit, 50, 200)
      const since = parseSince(args.since)
      let path = `livingroom?order=created_at.desc&limit=${limit}`
      if (since) path += `&created_at=gt.${encodeURIComponent(since)}`
      const messages = (await livingroomRest('GET', path)).slice().reverse()
      return JSON.stringify({
        messages,
        context: messages.map(row => `[${row.sender}] ${row.message}`).join('\n'),
      })
    }
    if (name === 'say_livingroom') {
      if (typeof args.message !== 'string' || !args.message.trim()) throw new TypeError('message is required')
      if (args.message.length > 10_000) throw new TypeError('message is too long')
      const rows = await livingroomRest('POST', 'livingroom', {
        sender,
        message: args.message.trim(),
      })
      return JSON.stringify(rows[0])
    }
    throw new Error(`unknown tool: ${name}`)
  }
}
