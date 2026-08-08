/**
 * Phase-one audit boundary. It deliberately records metadata only, never memory
 * content. A later migration will replace this sink with an append-only table.
 */
export class NullMemoryAuditSink {
  async record(_event) {}
}

export class InMemoryAuditSink {
  constructor() {
    this.events = []
  }

  async record(event) {
    this.events.push(structuredClone(event))
  }
}
