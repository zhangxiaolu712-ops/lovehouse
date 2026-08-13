import test from 'node:test'
import assert from 'node:assert/strict'

import {
  CLAUDE_ALLOWED_TOOLS,
  CLAUDE_MCP_TOOL_NAMES,
  assertClaudeToolPolicyMatchesBridge,
  buildClaudePolicyArgs,
  inspectClaudeMcpInit,
  resolveClaudeMcpUrl,
} from './claudePolicy.js'

test('the explicit Claude whitelist matches the reviewed Bridge routes', () => {
  assert.doesNotThrow(() => assertClaudeToolPolicyMatchesBridge())
  assert.equal(CLAUDE_MCP_TOOL_NAMES.length, 14)
  assert.equal(CLAUDE_ALLOWED_TOOLS.every(name => name.startsWith('mcp__lovehouse__')), true)
})

test('MCP URL resolution is deterministic and rejects credentials or insecure transport', () => {
  assert.equal(
    resolveClaudeMcpUrl({ OAUTH_BASE_URL: 'https://love.example/' }),
    'https://love.example/api/mcp/claude'
  )
  assert.equal(
    resolveClaudeMcpUrl({ MCP_RESOURCE_URL: 'https://love.example/custom/mcp' }),
    'https://love.example/custom/mcp'
  )
  assert.throws(() => resolveClaudeMcpUrl({ CLAUDE_MCP_URL: 'http://love.example/mcp' }), /must use https/)
  assert.throws(
    () => resolveClaudeMcpUrl({ CLAUDE_MCP_URL: 'https://token@love.example/mcp' }),
    /must not contain credentials/
  )
  assert.throws(
    () => buildClaudePolicyArgs({ mcpUrl: 'https://love.example/mcp?secret=value' }),
    /must not contain credentials/
  )
})

test('MCP initialization accepts only connected LoveHouse with the exact tool set', () => {
  assert.deepEqual(inspectClaudeMcpInit({ type: 'assistant' }), null)
  assert.deepEqual(inspectClaudeMcpInit({
    type: 'system',
    subtype: 'init',
    mcp_servers: [{ name: 'lovehouse', status: 'connected' }],
    tools: CLAUDE_ALLOWED_TOOLS,
  }), { ready: true })
  assert.deepEqual(inspectClaudeMcpInit({
    type: 'system',
    subtype: 'init',
    mcp_servers: [{ name: 'other', status: 'connected' }],
    tools: CLAUDE_ALLOWED_TOOLS,
  }), { ready: false, error: 'LoveHouse MCP was not reported by Claude' })
})
