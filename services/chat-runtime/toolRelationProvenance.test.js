import assert from 'node:assert/strict'
import test from 'node:test'

import { toolRelationProvenance } from './toolRelationProvenance.js'

const SOURCE = `mcp-live:${'a'.repeat(64)}`

test('tool relation provenance accepts only versioned authoritative MCP metadata', () => {
  assert.deepEqual(toolRelationProvenance({
    _meta: { lovehouse_relation: { version: 1, source_call_id: SOURCE } },
  }), { source_call_id: SOURCE })
  assert.equal(toolRelationProvenance({
    _meta: { lovehouse_relation: { version: 2, source_call_id: SOURCE } },
  }), null)
  assert.equal(toolRelationProvenance({
    structured_content: { lovehouse_relation: { version: 1, source_call_id: SOURCE } },
  }), null)
})

test('Claude tool_use_result provenance is bound to the exact tool use id', () => {
  const event = { tool_use_result: [
    { tool_use_id: 'other', _meta: { lovehouse_relation: { version: 1, source_call_id: SOURCE } } },
    { tool_use_id: 'target', _meta: { lovehouse_relation: { version: 1, source_call_id: SOURCE } } },
  ] }
  assert.deepEqual(toolRelationProvenance(event, 'target'), { source_call_id: SOURCE })
  assert.equal(toolRelationProvenance(event, 'missing'), null)
})
