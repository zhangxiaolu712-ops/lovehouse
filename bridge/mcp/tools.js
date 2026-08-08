import { MEMORY_ACTORS } from '../memory/index.js'

export const MCP_TOOL_ROUTES = Object.freeze({
  read_livingroom_messages: 'livingroom.read',
  send_livingroom_message: 'livingroom.write',
  get_livingroom_context: 'livingroom.context',
  get_starter_pack: 'memory.starterPack',
  save_memory: 'memory.write',
  recall: 'memory.recall',
  load_memories: 'memory.list',
  search_memories: 'memory.recall',
  save_to_memories: 'memory.write',
})

const closedObject = properties => ({
  type: 'object',
  properties,
  additionalProperties: false,
})

export function createMcpToolDefinitions(actor) {
  const sender = actor === MEMORY_ACTORS.GPT ? 'GPT' : 'CC'
  return [
    {
      name: 'read_livingroom_messages',
      description: '读取小客厅最近的消息。',
      inputSchema: closedObject({
        limit: { type: 'integer', minimum: 1, maximum: 200 },
        since: { type: 'string', description: 'ISO 时间戳' },
      }),
    },
    {
      name: 'send_livingroom_message',
      description: `向小客厅发送消息。发送者由服务端固定为 ${sender}。`,
      inputSchema: {
        ...closedObject({ message: { type: 'string', minLength: 1, maxLength: 10000 } }),
        required: ['message'],
      },
    },
    {
      name: 'get_livingroom_context',
      description: '获取小客厅最近的纯文本上下文。',
      inputSchema: closedObject({
        limit: { type: 'integer', minimum: 1, maximum: 100 },
      }),
    },
    {
      name: 'get_starter_pack',
      description: '通过统一 Memory System 加载自己的私有记忆与已批准 Shared Memory。',
      inputSchema: closedObject({
        limit: { type: 'integer', minimum: 1, maximum: 50 },
      }),
    },
    {
      name: 'save_memory',
      description: '通过统一 Memory System 保存记忆。目标私有空间由服务端 actor 固定。',
      inputSchema: {
        ...closedObject({
          content: { type: 'string', minLength: 1, maxLength: 50000 },
          title: { type: 'string' },
          kind: { type: 'string', enum: ['记事', '记感受'] },
          tag: { type: 'string' },
          tags: { type: 'array', items: { type: 'string' } },
          feeling: { type: 'string' },
          mood: { type: 'string' },
          importance: { type: 'integer', minimum: 1, maximum: 5 },
          source_ref: { type: 'string' },
        }),
        required: ['content'],
      },
    },
    {
      name: 'recall',
      description: '检索自己的私有记忆与已批准 Shared Memory；不会检索另一方私有空间或 Legacy Pending。',
      inputSchema: {
        ...closedObject({
          query: { type: 'string', minLength: 1 },
          limit: { type: 'integer', minimum: 1, maximum: 50 },
        }),
        required: ['query'],
      },
    },
    {
      name: 'load_memories',
      description: '兼容旧工具名；实际调用统一 Memory System，不再直接读取 memories 表。',
      inputSchema: closedObject({
        level: { type: 'string' },
        category: { type: 'string' },
        limit: { type: 'integer', minimum: 1, maximum: 200 },
      }),
    },
    {
      name: 'search_memories',
      description: '兼容旧工具名；实际调用统一 Memory System 的 recall。',
      inputSchema: {
        ...closedObject({
          keyword: { type: 'string', minLength: 1 },
          category: { type: 'string' },
          limit: { type: 'integer', minimum: 1, maximum: 50 },
        }),
        required: ['keyword'],
      },
    },
    {
      name: 'save_to_memories',
      description: '兼容旧工具名；实际调用统一 Memory System，不再直接写入 memories 表。',
      inputSchema: {
        ...closedObject({
          content: { type: 'string', minLength: 1, maxLength: 50000 },
          category: { type: 'string' },
          level: { type: 'string' },
          importance: { type: 'integer', minimum: 1, maximum: 5 },
        }),
        required: ['content'],
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

export function createMcpToolHandler({ actor, memoryService, livingroomRest }) {
  if (!Object.values(MEMORY_ACTORS).includes(actor)) throw new Error('A fixed MCP actor is required')
  if (!memoryService) throw new Error('MemoryService is required')
  if (typeof livingroomRest !== 'function') throw new Error('Livingroom REST function is required')
  const sender = actor === MEMORY_ACTORS.GPT ? 'GPT' : 'CC'

  return async function callMcpTool(name, args = {}) {
    if (name === 'read_livingroom_messages') {
      const limit = parseLimit(args.limit, 50, 200)
      const since = parseSince(args.since)
      let path = `livingroom?order=created_at.desc&limit=${limit}`
      if (since) path += `&created_at=gt.${encodeURIComponent(since)}`
      const rows = await livingroomRest('GET', path)
      return JSON.stringify((rows || []).reverse())
    }

    if (name === 'send_livingroom_message') {
      if (typeof args.message !== 'string' || !args.message.trim()) {
        throw new TypeError('message is required')
      }
      if (args.message.length > 10_000) throw new TypeError('message is too long')
      const rows = await livingroomRest('POST', 'livingroom', {
        sender,
        message: args.message.trim(),
      })
      return JSON.stringify(rows?.[0] || { ok: true })
    }

    if (name === 'get_livingroom_context') {
      const limit = parseLimit(args.limit, 20, 100)
      const rows = await livingroomRest('GET', `livingroom?order=created_at.desc&limit=${limit}`)
      return (rows || []).reverse().map(row => `[${row.sender}] ${row.message}`).join('\n')
        || '(no messages yet)'
    }

    if (name === 'get_starter_pack') {
      return JSON.stringify(await memoryService.starterPack(actor, args))
    }

    if (name === 'save_memory') {
      const saved = await memoryService.write(actor, args)
      return JSON.stringify({ ok: true, id: saved?.id, preview: args.content.slice(0, 80) })
    }

    if (name === 'recall') {
      return JSON.stringify(await memoryService.recall(actor, args))
    }

    if (name === 'load_memories') {
      return JSON.stringify(await memoryService.list(actor, {
        limit: args.limit,
        level: args.level,
        category: args.category,
      }))
    }

    if (name === 'search_memories') {
      return JSON.stringify(await memoryService.recall(actor, {
        query: args.keyword,
        limit: args.limit,
        category: args.category,
      }))
    }

    if (name === 'save_to_memories') {
      const saved = await memoryService.write(actor, {
        content: args.content,
        category: args.category,
        level: args.level,
        importance: args.importance,
      })
      return JSON.stringify({ ok: true, id: saved?.id, preview: args.content.slice(0, 80) })
    }

    throw new Error(`unknown tool: ${name}`)
  }
}
