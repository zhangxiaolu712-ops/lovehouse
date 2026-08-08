import { MEMORY_ACTORS, MEMORY_SPACES, SHARED_STATES } from './model.js'

const RESERVED_INPUT_KEYS = new Set([
  'actor',
  'createdbyactor',
  'namespace',
  'owner',
  'ownermodel',
  'space',
  'spacekey',
  'sharedstatus',
  'approvalstatus',
])

function normalizedKey(key) {
  return String(key).replaceAll('_', '').replaceAll('-', '').toLowerCase()
}

function findReservedKey(value, seen = new Set()) {
  if (!value || typeof value !== 'object' || seen.has(value)) return null
  seen.add(value)

  for (const [key, nested] of Object.entries(value)) {
    if (RESERVED_INPUT_KEYS.has(normalizedKey(key))) return key
    const found = findReservedKey(nested, seen)
    if (found) return found
  }
  return null
}

export class MemoryAccessError extends Error {
  constructor(message, code = 'MEMORY_ACCESS_DENIED') {
    super(message)
    this.name = 'MemoryAccessError'
    this.code = code
  }
}
export class MemoryAccessPolicy {
  assertActor(actor) {
    if (!Object.values(MEMORY_ACTORS).includes(actor)) {
      throw new MemoryAccessError('Unknown memory actor', 'INVALID_MEMORY_ACTOR')
    }
  }

  assertNoSpaceOverride(input) {
    const key = findReservedKey(input)
    if (key) {
      throw new MemoryAccessError(
        `Memory space is assigned by the server; '${key}' is not accepted`,
        'SPACE_OVERRIDE_REJECTED'
      )
    }
  }

  privateSpaceFor(actor) {
    this.assertActor(actor)
    return actor === MEMORY_ACTORS.GPT
      ? MEMORY_SPACES.GPT
      : MEMORY_SPACES.CLAUDE
  }

  readScopeFor(actor) {
    return Object.freeze({
      privateSpace: this.privateSpaceFor(actor),
      sharedSpace: MEMORY_SPACES.SHARED,
      requiredSharedState: SHARED_STATES.APPROVED,
    })
  }

  canRead(actor, memory) {
    const scope = this.readScopeFor(actor)
    if (memory?.space_key === scope.privateSpace) return true
    return memory?.space_key === scope.sharedSpace
      && memory?.shared_status === scope.requiredSharedState
  }

  assertCanRead(actor, memory) {
    if (!this.canRead(actor, memory)) {
      throw new MemoryAccessError('This memory is outside the actor read scope')
    }
  }

  canMutate(actor, memory) {
    return memory?.space_key === this.privateSpaceFor(actor)
  }

  assertCanMutate(actor, memory) {
    if (!this.canMutate(actor, memory)) {
      throw new MemoryAccessError('MCP actors may only modify their own private memory')
    }
  }
}
