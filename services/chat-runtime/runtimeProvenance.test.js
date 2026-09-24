import assert from 'node:assert/strict'
import test from 'node:test'

import {
  emitRuntimeProvenance,
  normalizeRuntimeTrace,
  runtimeFailureCategory,
} from './runtimeProvenance.js'

test('runtime provenance exposes only provider-neutral safe fields', () => {
  const lines = []
  const runtime = {
    persona_id: 'persona-a', persona_version: 7,
    instructions: 'PRIVATE PROMPT BODY', background: 'PRIVATE BACKGROUND BODY',
    connection_ids: ['connection-a'], execution_ticket: 'secret-ticket', reanchor_intent: true,
  }
  const trace = normalizeRuntimeTrace({ trace_id: 'trace-a', provider: 'claude' }, {
    requestId: 'request-a', threadId: 'thread-a', personaRuntime: runtime,
  })
  emitRuntimeProvenance('provider_invocation', {
    ...trace, argument_keys: ['query'], credential: 'secret-credential', token: 'secret-token',
  }, (...parts) => lines.push(parts.join(' ')))

  assert.match(lines[0], /"persona_id":"persona-a"/)
  assert.match(lines[0], /"persona_version":7/)
  assert.match(lines[0], /"instructions_present":true/)
  assert.match(lines[0], /"background_present":true/)
  assert.doesNotMatch(lines[0], /PRIVATE|secret-ticket|secret-credential|secret-token/)
})

test('runtime failure categories never invent a policy rejection', () => {
  assert.equal(runtimeFailureCategory({ code: 'PERSONA_BINDING_INVALIDATED' }), 'persona_binding_invalidated')
  assert.equal(runtimeFailureCategory({ stage: 'runtime' }), 'provider_cli_failure')
  assert.equal(runtimeFailureCategory(new Error('opaque failure')), 'unknown')
})

test('Claude and Codex normalize the same provider-neutral provenance contract', () => {
  const personaRuntime = {
    persona_id: 'persona-a', persona_version: 8, instructions: 'private', background: 'private',
    connection_ids: ['connection-a'], reanchor_intent: false,
  }
  const claude = normalizeRuntimeTrace({ trace_id: 'trace-a', provider: 'claude_cli' }, { personaRuntime })
  const codex = normalizeRuntimeTrace({ trace_id: 'trace-a', provider: 'codex_cli' }, { personaRuntime })
  assert.deepEqual(Object.keys(claude), Object.keys(codex))
  assert.equal(claude.persona_id, codex.persona_id)
  assert.equal(claude.persona_version, codex.persona_version)
  assert.equal(claude.connection_count, codex.connection_count)
})
