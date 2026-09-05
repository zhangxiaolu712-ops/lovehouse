export const TOOL_RISK_LEVELS = Object.freeze(['low', 'medium', 'high'])
export const TOOL_CAPABILITY_KINDS = Object.freeze(['read', 'write', 'execute', 'admin'])

export const BUILTIN_TOOL_CATALOG = Object.freeze([
  Object.freeze({
    id: 'builtin.memory.read', group: 'memory', mcpName: 'memory_read',
    displayName: '读取记忆', summary: '按关键词召回可访问的 Memory。',
    riskLevel: 'low', capabilityKind: 'read', requiresApproval: false,
    scope: Object.freeze(['owner', 'persona:codex', 'thread']),
  }),
  Object.freeze({
    id: 'builtin.memory.open', group: 'memory', mcpName: 'memory_open',
    displayName: '打开记忆', summary: '打开一条已授权 Memory 的完整内容。',
    riskLevel: 'low', capabilityKind: 'read', requiresApproval: false,
    scope: Object.freeze(['owner', 'persona:codex', 'thread']),
  }),
  Object.freeze({
    id: 'builtin.engineering.read_current', group: 'engineering', mcpName: 'engineering_read_current',
    displayName: '读取当前工程', summary: '检索 Engineering Workspace 当前修订。',
    riskLevel: 'low', capabilityKind: 'read', requiresApproval: false,
    scope: Object.freeze(['owner', 'persona:codex', 'thread']),
  }),
  Object.freeze({
    id: 'builtin.engineering.open', group: 'engineering', mcpName: 'engineering_open',
    displayName: '打开工程主题', summary: '打开指定 Engineering subject 的当前内容。',
    riskLevel: 'low', capabilityKind: 'read', requiresApproval: false,
    scope: Object.freeze(['owner', 'persona:codex', 'thread']),
  }),
  Object.freeze({
    id: 'builtin.livingroom.read', group: 'livingroom', mcpName: 'livingroom_read',
    displayName: '读取小客厅', summary: '读取小客厅最近消息。',
    riskLevel: 'low', capabilityKind: 'read', requiresApproval: false,
    scope: Object.freeze(['owner', 'persona:codex', 'thread']),
  }),
])

const BY_ID = new Map(BUILTIN_TOOL_CATALOG.map(tool => [tool.id, tool]))
const BY_MCP_NAME = new Map(BUILTIN_TOOL_CATALOG.map(tool => [tool.mcpName, tool]))

export function toolById(id) { return BY_ID.get(id) || null }
export function toolByMcpName(name) { return BY_MCP_NAME.get(name) || null }

export function normalizeToolPreferenceIds(value) {
  if (value === undefined || value === null) return []
  if (!Array.isArray(value) || value.length > BUILTIN_TOOL_CATALOG.length) {
    throw new TypeError('allowed_tool_ids must be a bounded array')
  }
  const unique = []
  for (const raw of value) {
    if (typeof raw !== 'string' || !BY_ID.has(raw)) throw new TypeError('allowed_tool_ids contains an unknown tool')
    if (!unique.includes(raw)) unique.push(raw)
  }
  return unique
}
