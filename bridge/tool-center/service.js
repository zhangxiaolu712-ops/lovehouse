import { BUILTIN_TOOL_CATALOG, normalizeToolPreferenceIds, toolById, toolByMcpName } from './catalog.js'

const GROUP_LABELS = Object.freeze({ memory: 'Memory', engineering: 'Engineering', livingroom: 'LivingRoom' })
const THREAD_ID = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i

function boundedString(value, name, maximum) {
  if (typeof value !== 'string' || !value.trim() || value.length > maximum) {
    throw new TypeError(`${name} is required`)
  }
  return value.trim()
}

function boundedLimit(value, fallback, maximum) {
  const parsed = Number.parseInt(value, 10)
  return Number.isFinite(parsed) && parsed > 0 ? Math.min(parsed, maximum) : fallback
}

export class ToolCenterService {
  constructor({ memoryV2Service = null, engineeringMemoryService = null, livingroomRest = null } = {}) {
    this.memory = memoryV2Service
    this.engineering = engineeringMemoryService?.forActor?.('codex') || null
    this.livingroomRest = typeof livingroomRest === 'function' ? livingroomRest : null
  }

  availability(tool) {
    if (tool.group === 'memory') {
      return this.memory?.forActor
        ? { status: 'no_permission', detail: 'Codex Memory space 尚未配置；不会借用 GPT 或 Claude 私有记忆。' }
        : { status: 'unconfigured', detail: 'Memory 服务尚未配置。' }
    }
    if (tool.group === 'engineering') {
      return this.engineering
        ? { status: 'available', detail: 'Engineering 只读能力可用。' }
        : { status: 'unconfigured', detail: 'Engineering 服务尚未配置。' }
    }
    return this.livingroomRest
      ? { status: 'available', detail: 'LivingRoom 只读能力可用。' }
      : { status: 'unconfigured', detail: 'LivingRoom 服务尚未配置。' }
  }

  capabilities() {
    return BUILTIN_TOOL_CATALOG.map(tool => ({
      tool_id: tool.id,
      group: tool.group,
      group_label: GROUP_LABELS[tool.group],
      display_name: tool.displayName,
      summary: tool.summary,
      risk_level: tool.riskLevel,
      capability_kind: tool.capabilityKind,
      requires_approval: tool.requiresApproval,
      scope: tool.scope,
      ...this.availability(tool),
    }))
  }

  validateRequest({ personaId, threadId, requestedIds }) {
    if (personaId !== 'codex') throw new TypeError('built-in Tool Center v1 is scoped to persona codex')
    if (typeof threadId !== 'string' || !THREAD_ID.test(threadId)) throw new TypeError('valid thread scope is required')
    return normalizeToolPreferenceIds(requestedIds).filter(id => this.availability(toolById(id)).status === 'available')
  }

  async test(toolId) {
    const tool = toolById(toolId)
    if (!tool) throw new TypeError('unknown tool_id')
    const availability = this.availability(tool)
    if (availability.status !== 'available') return { ok: false, tool_id: tool.id, ...availability }
    if (tool.group === 'engineering') {
      const result = await this.engineering.recallEngineering({ query: '', limit: 1 })
      return { ok: true, tool_id: tool.id, status: 'available', result_summary: `${result.items?.length || 0} 个当前主题可读取` }
    }
    if (tool.group === 'livingroom') {
      const rows = await this.livingroomRest('GET', 'livingroom?order=created_at.desc&limit=1')
      return { ok: true, tool_id: tool.id, status: 'available', result_summary: `${Array.isArray(rows) ? rows.length : 0} 条消息可读取` }
    }
    return { ok: false, tool_id: tool.id, ...availability }
  }

  channel(requestedIds) {
    const allowed = new Set(normalizeToolPreferenceIds(requestedIds))
    const tools = BUILTIN_TOOL_CATALOG
      .filter(tool => allowed.has(tool.id) && this.availability(tool).status === 'available')
      .map(tool => ({ name: tool.mcpName, description: tool.summary, inputSchema: inputSchema(tool.id) }))
    return Object.freeze({
      actor: 'codex',
      tools,
      callTool: async (name, args = {}) => {
        const tool = toolByMcpName(name)
        if (!tool || !allowed.has(tool.id) || this.availability(tool).status !== 'available') {
          throw new Error('tool is not allowed for this request scope')
        }
        return JSON.stringify(await this.execute(tool.id, args))
      },
    })
  }

  async execute(toolId, args) {
    if (toolId === 'builtin.engineering.read_current') {
      return this.engineering.recallEngineering({ query: String(args.query || ''), limit: boundedLimit(args.limit, 10, 30) })
    }
    if (toolId === 'builtin.engineering.open') {
      return this.engineering.openEngineeringFact(boundedString(args.subject_key, 'subject_key', 200))
    }
    if (toolId === 'builtin.livingroom.read') {
      const limit = boundedLimit(args.limit, 20, 100)
      const rows = await this.livingroomRest('GET', `livingroom?order=created_at.desc&limit=${limit}`)
      const messages = rows.slice().reverse()
      return { messages, context: messages.map(row => `[${row.sender}] ${row.message}`).join('\n') }
    }
    throw new Error('tool is unavailable')
  }
}

function inputSchema(toolId) {
  if (toolId === 'builtin.engineering.open') {
    return { type: 'object', properties: { subject_key: { type: 'string', minLength: 1, maxLength: 200 } }, required: ['subject_key'], additionalProperties: false }
  }
  if (toolId === 'builtin.engineering.read_current') {
    return { type: 'object', properties: { query: { type: 'string', maxLength: 1000 }, limit: { type: 'integer', minimum: 1, maximum: 30 } }, additionalProperties: false }
  }
  if (toolId === 'builtin.livingroom.read') {
    return { type: 'object', properties: { limit: { type: 'integer', minimum: 1, maximum: 100 } }, additionalProperties: false }
  }
  return { type: 'object', properties: {}, additionalProperties: false }
}
