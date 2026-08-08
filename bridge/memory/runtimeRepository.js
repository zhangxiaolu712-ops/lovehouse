export class MemorySystemDisabledError extends Error {
  constructor() {
    super('Memory System schema is disabled; no legacy-table fallback is permitted')
    this.name = 'MemorySystemDisabledError'
    this.code = 'MEMORY_SYSTEM_DISABLED'
  }
}

export class DisabledMemoryRepository {
  async insert() { throw new MemorySystemDisabledError() }
  async getById() { throw new MemorySystemDisabledError() }
  async list() { throw new MemorySystemDisabledError() }
  async search() { throw new MemorySystemDisabledError() }
}

export function createRuntimeMemoryRepository({ enabled, canonicalRepository }) {
  if (enabled) {
    if (!canonicalRepository) throw new Error('Canonical MemoryRepository is required')
    return canonicalRepository
  }
  return new DisabledMemoryRepository()
}
