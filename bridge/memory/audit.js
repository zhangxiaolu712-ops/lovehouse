/**
 * Phase-one audit boundary. It deliberately records metadata only, never memory
 * content. A later migration will replace this sink with an append-only table.
 */
export class NullMemoryAuditSink {
  persistent = false

  async record(_event) {}
}

export class InMemoryAuditSink {
  persistent = false

  constructor() {
    this.events = []
  }

  async record(event) {
    this.events.push(structuredClone(event))
  }
}

/**
 * Persistent metadata-only audit sink for the canonical memory_audit_log table.
 * It is intentionally not enabled by server.js until the reviewed migration is
 * applied and production activation is approved as a separate step.
 */
export class SupabaseMemoryAuditSink {
  persistent = true

  constructor({ rest, ownerId }) {
    if (typeof rest !== 'function') throw new Error('A Supabase REST function is required')
    this.rest = rest
    this.ownerId = ownerId
  }

  async record(event) {
    if (!this.ownerId) {
      const error = new Error('OWNER_USER_ID is required for persistent memory audit')
      error.code = 'MEMORY_OWNER_NOT_CONFIGURED'
      throw error
    }

    const resultSpaces = Array.isArray(event.result_spaces)
      ? event.result_spaces.filter(Boolean)
      : []
    if (!['gpt', 'claude'].includes(event.actor)) {
      const error = new Error('A fixed memory actor is required for persistent audit')
      error.code = 'INVALID_MEMORY_ACTOR'
      throw error
    }
    if (!event.request_id) {
      const error = new Error('A server-generated request id is required for persistent audit')
      error.code = 'MEMORY_REQUEST_ID_REQUIRED'
      throw error
    }

    return this.rest('POST', `rpc/memory_runtime_audit_${event.actor}`, {
      p_owner_id: this.ownerId,
      p_request_id: event.request_id,
      p_action: event.action,
      p_memory_id: event.memory_id || null,
      p_space_key: event.target_space || (resultSpaces.length === 1 ? resultSpaces[0] : null),
      p_result: event.result || (event.allowed ? 'allowed' : 'denied'),
      p_reason_code: event.reason_code || null,
      p_result_count: Number.isInteger(event.result_count) ? event.result_count : null,
      p_result_spaces: resultSpaces,
    })
  }
}
