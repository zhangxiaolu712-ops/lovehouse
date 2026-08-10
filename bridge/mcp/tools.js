import { MEMORY_ACTORS } from '../memory/index.js'

export const MCP_TOOL_ROUTES = Object.freeze({
  read_livingroom_messages: 'livingroom.read',
  send_livingroom_message: 'livingroom.write',
  get_livingroom_context: 'livingroom.context',
  get_starter_pack: 'memory.starterPack',
  open_memory_box: 'memory.memoryBox',
  save_memory: 'memory.write',
  recall: 'memory.recall',
  load_memories: 'memory.list',
  search_memories: 'memory.recall',
  save_to_memories: 'memory.write',
  get_memory: 'memory.get',
  revise_memory: 'memory.revise',
  propose_shared_candidate: 'memory.proposeShared',
})

const closedObject = properties => ({
  type: 'object',
  properties,
  additionalProperties: false,
})

const memoryFields = {
  content: { type: 'string', minLength: 1, maxLength: 50000 },
  title: { type: 'string', maxLength: 500 },
  memory_type: {
    type: 'string',
    enum: ['fact', 'feeling', 'diary', 'article', 'small_moment', 'memo', 'self_inquiry', 'quote', 'summary', 'reflection'],
  },
  kind: { type: 'string', enum: ['记事', '记感受'] },
  tag: { type: 'string', maxLength: 80 },
  tags: { type: 'array', maxItems: 30, items: { type: 'string', maxLength: 80 } },
  feeling: { type: 'string', maxLength: 1000 },
  mood: { type: 'string', maxLength: 80 },
  importance: { type: 'integer', minimum: 1, maximum: 5 },
  retention: { type: 'string', enum: ['fixed', 'long', 'short', 'temporary'] },
  source_ref: { type: 'string', maxLength: 500 },
}

const revisionMemoryFields = Object.fromEntries(
  Object.entries(memoryFields).filter(([name]) => name !== 'source_ref')
)

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
      description: '新对话开始时先调用。返回一眼可读完的 House Rules、自己的私有记忆与已批准 Shared Memory；无需预先了解 LoveHouse 历史，也不会返回另一 AI 的私有记忆或 Legacy Pending。',
      inputSchema: closedObject({
        limit: { type: 'integer', minimum: 1, maximum: 20 },
      }),
    },
    {
      name: 'open_memory_box',
      description: 'AI 在新对话读完 get_starter_pack 后可调用：随机带回少量有权读取的旧记忆，用来重新认识过去的自己、共享经历和旧判断。这不是相关性搜索；拿到后可以接受旧理解、质疑它、用 revise_memory 修订自己的私有记忆，或暂时不处理。',
      inputSchema: closedObject({
        limit: { type: 'integer', minimum: 1, maximum: 4, default: 3 },
      }),
    },
    {
      name: 'save_memory',
      description: `主动保存一条自己的私有记忆。memory_type=diary 表示你以第一人称记录自己的经历、判断、感受和变化，不是对小婷的观察报告，也不替她写日记。日记作者由服务端固定为 ${actor}；归属、actor、审计和幂等同样由服务端处理。`,
      inputSchema: {
        ...closedObject(memoryFields),
        required: ['content'],
      },
    },
    {
      name: 'recall',
      description: '检索自己的私有记忆与已批准 Shared；不会检索另一方私有空间或 Legacy Pending。',
      inputSchema: {
        ...closedObject({
          query: { type: 'string', minLength: 1, maxLength: 500 },
          limit: { type: 'integer', minimum: 1, maximum: 10 },
          cursor: { type: 'integer', minimum: 1 },
          tags: memoryFields.tags,
        }),
        required: ['query'],
      },
    },
    {
      name: 'load_memories',
      description: '兼容旧工具名；实际调用统一 Memory System，不直接读取旧 memories 表。',
      inputSchema: closedObject({
        level: { type: 'string' },
        category: { type: 'string' },
        limit: { type: 'integer', minimum: 1, maximum: 50 },
        cursor: { type: 'integer', minimum: 1 },
      }),
    },
    {
      name: 'search_memories',
      description: '兼容旧工具名；实际调用统一 Memory System 的 recall。',
      inputSchema: {
        ...closedObject({
          keyword: { type: 'string', minLength: 1, maxLength: 500 },
          category: { type: 'string' },
          limit: { type: 'integer', minimum: 1, maximum: 10 },
          cursor: { type: 'integer', minimum: 1 },
        }),
        required: ['keyword'],
      },
    },
    {
      name: 'save_to_memories',
      description: '兼容旧工具名；实际保存到统一 Memory System 的固定私有空间。',
      inputSchema: {
        ...closedObject({
          content: memoryFields.content,
          category: { type: 'string' },
          level: { type: 'string' },
          importance: memoryFields.importance,
        }),
        required: ['content'],
      },
    },
    {
      name: 'get_memory',
      description: '按编号读取一条自己有权访问的记忆。',
      inputSchema: {
        ...closedObject({ memory_id: { type: 'integer', minimum: 1 } }),
        required: ['memory_id'],
      },
    },
    {
      name: 'revise_memory',
      description: '修订自己的私有记忆；旧版本、来源链和审计由服务端自动保存。',
      inputSchema: {
        ...closedObject({
          memory_id: { type: 'integer', minimum: 1 },
          reason: { type: 'string', minLength: 1, maxLength: 1000 },
          ...revisionMemoryFields,
        }),
        required: ['memory_id', 'reason'],
      },
    },
    {
      name: 'propose_shared_candidate',
      description: '推荐把自己私有记忆的当前确定版本作为 Shared 候选；只有 Owner 能批准。',
      inputSchema: {
        ...closedObject({
          memory_id: { type: 'integer', minimum: 1 },
          reason: { type: 'string', minLength: 1, maxLength: 1000 },
        }),
        required: ['memory_id', 'reason'],
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

  return async function callMcpTool(name, args = {}, trustedContext = {}) {
    if (name === 'read_livingroom_messages') {
      const limit = parseLimit(args.limit, 50, 200)
      const since = parseSince(args.since)
      let path = `livingroom?order=created_at.desc&limit=${limit}`
      if (since) path += `&created_at=gt.${encodeURIComponent(since)}`
      const rows = await livingroomRest('GET', path)
      return JSON.stringify((Array.isArray(rows) ? rows : []).reverse())
    }

    if (name === 'send_livingroom_message') {
      if (typeof args.message !== 'string' || !args.message.trim()) throw new TypeError('message is required')
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
      return (Array.isArray(rows) ? rows : []).reverse().map(row => `[${row.sender}] ${row.message}`).join('\n')
        || '(no messages yet)'
    }

    if (name === 'get_starter_pack') {
      return JSON.stringify(await memoryService.starterPack(actor, args, trustedContext))
    }
    if (name === 'open_memory_box') {
      return JSON.stringify(await memoryService.memoryBox(actor, args, trustedContext))
    }
    if (name === 'save_memory') {
      const saved = await memoryService.write(actor, args, trustedContext)
      return JSON.stringify({ ok: true, id: saved?.id, preview: args.content.slice(0, 80) })
    }
    if (name === 'recall') {
      return JSON.stringify(await memoryService.recall(actor, args, trustedContext))
    }
    if (name === 'load_memories') {
      return JSON.stringify(await memoryService.list(actor, {
        limit: args.limit,
        cursor: args.cursor,
        level: args.level,
        category: args.category,
      }, trustedContext))
    }
    if (name === 'search_memories') {
      return JSON.stringify(await memoryService.recall(actor, {
        query: args.keyword,
        limit: args.limit,
        cursor: args.cursor,
        category: args.category,
      }, trustedContext))
    }
    if (name === 'save_to_memories') {
      const saved = await memoryService.write(actor, {
        content: args.content,
        category: args.category,
        level: args.level,
        importance: args.importance,
      }, trustedContext)
      return JSON.stringify({ ok: true, id: saved?.id, preview: args.content.slice(0, 80) })
    }
    if (name === 'get_memory') {
      return JSON.stringify(await memoryService.get(actor, args.memory_id, trustedContext))
    }
    if (name === 'revise_memory') {
      return JSON.stringify(await memoryService.revise(actor, args, trustedContext))
    }
    if (name === 'propose_shared_candidate') {
      const candidate = await memoryService.proposeShared(actor, args, trustedContext)
      return JSON.stringify({ ok: true, candidate_id: candidate?.id, status: candidate?.shared_status })
    }
    throw new Error(`unknown tool: ${name}`)
  }
}
