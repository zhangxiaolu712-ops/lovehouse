import assert from 'node:assert/strict'
import test from 'node:test'

import {
  normalizeClaudeToolInput,
  normalizeClaudeToolResult,
  normalizeCodexCommand,
  TOOL_DETAIL_LIMITS,
  validateToolDetailEnvelope,
} from './toolDetailEnvelope.js'

const NOW = () => new Date('2026-10-01T00:00:00.000Z')

function serialized(value) { return JSON.stringify(value) }

test('Claude input and result become one typed generic detail with nested secret redaction', () => {
  const input = normalizeClaudeToolInput({
    type: 'tool_use', id: 'call-1', input: {
      path: '/tmp/report.txt', authorization: 'Bearer top-secret',
      nested: { api_key: 'sk-hidden-value', keep: 'visible' },
    },
  }, NOW)
  const result = normalizeClaudeToolResult({
    type: 'tool_result', tool_use_id: 'call-1', content: 'done', is_error: false,
  }, input, NOW)
  assert.equal(result.call_id, 'call-1')
  assert.equal(result.detail_kind, 'generic_tool')
  assert.equal(result.created_at, '2026-10-01T00:00:00.000Z')
  assert.equal(result.is_error, false)
  assert.match(serialized(result), /report\.txt/)
  assert.match(serialized(result), /visible/)
  assert.match(serialized(result), /done/)
  assert.doesNotMatch(serialized(result), /top-secret|sk-hidden-value/)
  assert.match(serialized(result), /\[REDACTED\]/)
})

test('secondary value scan removes bearer, PEM, database credentials and signed query values', () => {
  const value = [
    'Bearer bearer-value',
    `-----BEGIN ${'PRIVATE'} KEY-----\nabc\n-----END ${'PRIVATE'} KEY-----`,
    'postgresql://user:password@db.example/app',
    'https://example.test/file?X-Amz-Signature=signed-value&safe=yes',
    'sb_secret_hidden and key-example-secret',
  ].join('\n')
  const detail = normalizeClaudeToolInput({ type: 'tool_use', id: 'call-2', input: { value } }, NOW)
  const text = serialized(detail)
  assert.doesNotMatch(text, /bearer-value|BEGIN PRIVATE|password@|signed-value|sb_secret_hidden|example-secret/)
  assert.match(text, /REDACTED/)
})

test('generic argument and result limits fail closed with truncation metadata', () => {
  const input = normalizeClaudeToolInput({
    type: 'tool_use', id: 'call-3', input: { content: 'a'.repeat(TOOL_DETAIL_LIMITS.genericArguments * 2) },
  }, NOW)
  const result = normalizeClaudeToolResult({
    type: 'tool_result', tool_use_id: 'call-3', content: 'b'.repeat(TOOL_DETAIL_LIMITS.genericResult * 2),
  }, input, NOW)
  assert.equal(input.truncated, true)
  assert.equal(result.truncated, true)
  assert.ok(input.original_length > TOOL_DETAIL_LIMITS.genericArguments)
  assert.ok(result.original_length > TOOL_DETAIL_LIMITS.genericResult)
  assert.ok(Buffer.byteLength(serialized(result)) < TOOL_DETAIL_LIMITS.envelope)
})

test('Codex command uses bounded output and does not invent stdout or stderr', () => {
  const detail = normalizeCodexCommand({
    type: 'command_execution', id: 'cmd-1', command: 'echo safe',
    aggregated_output: 'x'.repeat(TOOL_DETAIL_LIMITS.commandOutput + 100),
    exit_code: 0, status: 'completed', extra: 'drop-me',
  }, NOW)
  assert.equal(detail.command, 'echo safe')
  assert.equal(Buffer.byteLength(detail.output), TOOL_DETAIL_LIMITS.commandOutput)
  assert.equal(detail.exit_code, 0)
  assert.equal(detail.status, 'completed')
  assert.equal(detail.truncated, true)
  assert.equal('stdout' in detail, false)
  assert.equal('stderr' in detail, false)
  assert.equal('extra' in detail, false)
})

test('Bridge validator reconstructs allowlisted fields and drops unknown or malformed envelopes', () => {
  const detail = normalizeCodexCommand({
    type: 'command_execution', id: 'cmd-2', command: 'pwd', aggregated_output: '/tmp',
    exit_code: 0, status: 'completed',
  }, NOW)
  const validated = validateToolDetailEnvelope({ ...detail, raw_payload: 'must-drop' }, 'cmd-2')
  assert.equal(validated.raw_payload, undefined)
  assert.equal(validated.command, 'pwd')
  assert.equal(validateToolDetailEnvelope({ ...detail, call_id: '' }), null)
  assert.equal(validateToolDetailEnvelope({ ...detail, schema_version: 99 }), null)
  assert.equal(validateToolDetailEnvelope({ ...detail, detail_kind: 'future_kind' }), null)
  assert.equal(validateToolDetailEnvelope(detail, 'different-call'), null)
  const oversized = validateToolDetailEnvelope({
    ...detail, output: 'x'.repeat(TOOL_DETAIL_LIMITS.commandOutput + 1),
  }, 'cmd-2')
  assert.equal(Buffer.byteLength(oversized.output), TOOL_DETAIL_LIMITS.commandOutput)
  assert.equal(oversized.truncated, true)
})

test('missing call ids drop detail instead of creating a name-based identity', () => {
  assert.equal(normalizeClaudeToolInput({ type: 'tool_use', input: { safe: true } }, NOW), null)
  assert.equal(normalizeClaudeToolResult({ type: 'tool_result', content: 'done' }, null, NOW), null)
  assert.equal(normalizeCodexCommand({ type: 'command_execution', command: 'pwd' }, NOW), null)
})
