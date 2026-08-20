import { MEMORY_ACTORS } from '../memory/index.js'
import { isLivingroomRest } from '../livingroom.js'

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
  human_importance: { type: 'number', minimum: 0, maximum: 5 },
  ai_importance: { type: 'number', minimum: 0, maximum: 5 },
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
      description: `记住一件事。最少只需 content；owner、actor 与 private space 由服务端固定为 ${actor}。`,
      inputSchema: {
        ...closedObject({
          content: { type: 'string', minLength: 1, maxLength: 50000 },
          ...optionalMemoryFields,
        }),
        required: ['content'],
      },
    },
    {
      name: 'recall',
      description: '召回相关 Memory V2。语义检索可用时使用 semantic，不可用时由 Memory V2 自动 lexical fallback；MCP 不实现第二套搜索。',
      inputSchema: {
        ...closedObject({
          query: { type: 'string', minLength: 1, maxLength: 1000 },
          limit: { type: 'integer', minimum: 1, maximum: 10 },
        }),
        required: ['query'],
      },
    },
    {
      name: 'revise',
      description: '修订一条自己的私有 Memory V2；旧 revision 与 currentness 由 Memory V2 保存。',
      inputSchema: {
        ...closedObject({
          memory_id: uuidField,
          content: { type: 'string', minLength: 1, maxLength: 50000 },
          reason: { type: 'string', minLength: 1, maxLength: 1000 },
          ...optionalMemoryFields,
        }),
        required: ['memory_id', 'content'],
      },
    },
    {
      name: 'open_memory',
      description: '深挖 Memory V2：传 memory_id 查看完整 revision history 与 source descriptors；传 source_id 才显式展开对应原文。',
      inputSchema: {
        oneOf: [
          {
            ...closedObject({ memory_id: uuidField }),
            required: ['memory_id'],
          },
          {
            ...closedObject({ source_id: uuidField }),
            required: ['source_id'],
          },
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

export function createMcpToolHandler({ actor, memoryV2Service, livingroomRest }) {
  if (!Object.values(MEMORY_ACTORS).includes(actor)) throw new Error('A fixed MCP actor is required')
  if (!memoryV2Service || typeof memoryV2Service.forActor !== 'function') {
    throw new Error('MemoryV2Service is required')
  }
  if (!isLivingroomRest(livingroomRest)) {
    throw new Error('A fenced livingroom REST function is required')
  }
  const memory = memoryV2Service.forActor(actor)
  const sender = actor === MEMORY_ACTORS.GPT ? 'GPT' : 'CC'

  return async function callMcpTool(name, args = {}) {
    if (name === 'wake_up') {
      return JSON.stringify(await memory.starterPack({
        softLimit: args.soft_limit,
        tokenBudget: args.token_budget,
      }))
    }
    if (name === 'remember') {
      return JSON.stringify(await memory.remember(toMemoryV2Input(args)))
    }
    if (name === 'recall') {
      return JSON.stringify(await memory.recall(args))
    }
    if (name === 'revise') {
      return JSON.stringify(await memory.revise(args.memory_id, toMemoryV2Input(args)))
    }
    if (name === 'open_memory') {
      const hasMemoryId = typeof args.memory_id === 'string' && args.memory_id.length > 0
      const hasSourceId = typeof args.source_id === 'string' && args.source_id.length > 0
      if (hasMemoryId === hasSourceId) {
        throw new TypeError('open_memory requires exactly one of memory_id or source_id')
      }
      if (hasMemoryId) {
        return JSON.stringify({
          mode: 'history',
          memory_id: args.memory_id,
          revisions: await memory.history(args.memory_id),
        })
      }
      return JSON.stringify({
        mode: 'source',
        source_id: args.source_id,
        source: await memory.expandSource(args.source_id),
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
