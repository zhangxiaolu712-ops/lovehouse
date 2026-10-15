const MCP_SOURCE_CALL_ID = /^mcp-live:[0-9a-f]{64}$/

function fromMetadata(value) {
  const relation = value?._meta?.lovehouse_relation
  if (relation?.version !== 1 || typeof relation.source_call_id !== 'string' ||
    !MCP_SOURCE_CALL_ID.test(relation.source_call_id)) return null
  return Object.freeze({ source_call_id: relation.source_call_id })
}

export function toolRelationProvenance(value, callId = null) {
  if (!value || typeof value !== 'object') return null
  const direct = fromMetadata(value)
  if (direct) return direct
  const toolUseResult = value.tool_use_result
  if (!toolUseResult || typeof toolUseResult !== 'object') return null
  if (Array.isArray(toolUseResult)) {
    const matching = toolUseResult.find(item => item?.tool_use_id === callId)
    return fromMetadata(matching)
  }
  if (callId && toolUseResult.tool_use_id && toolUseResult.tool_use_id !== callId) return null
  return fromMetadata(toolUseResult)
}
