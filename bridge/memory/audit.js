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

  constructor({ rest, ownerId, table = 'memory_audit_log' }) {
    if (typeof rest !== 'function') throw new Error('A Supabase REST function is required')
    this.rest = rest
    this.ownerId = ownerId
    this.table = table
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
    const row = {
      owner_id: this.ownerId,
      actor: event.actor,
      action: event.action,
      memory_id: event.memory_id || null,
      space_key: event.target_space || (resultSpaces.length === 1 ? resultSpaces[0] : null),
      result: event.allowed ? 'allowed' : 'denied',
      reason_code: event.reason_code || null,
      result_count: Number.isInteger(event.result_count) ? event.result_count : null,
      result_spaces: resultSpaces,
      metadata: {},
      occurred_at: event.occurred_at,
    }
    const rows = await this.rest('POST', this.table, row)
    return Array.isArray(rows) ? rows[0] : rows
  }
}
