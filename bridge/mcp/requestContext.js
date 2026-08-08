import crypto from 'crypto'

function uuidFromDigest(buffer) {
  const bytes = Buffer.from(buffer.subarray(0, 16))
  bytes[6] = (bytes[6] & 0x0f) | 0x50
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  const hex = bytes.toString('hex')
  return `${hex.slice(0, 8)}-${hex.slice(8, 12)}-${hex.slice(12, 16)}-${hex.slice(16, 20)}-${hex.slice(20)}`
}

/**
 * Creates an idempotency key from authenticated transport state. Tool args,
 * request headers, query parameters and body authority fields are never used.
 */
export function createTrustedRequestId({ actor, transportIdentity, protocolRequestId, toolName }) {
  if (!['gpt', 'claude'].includes(actor)) throw new Error('A fixed MCP actor is required')
  if (!transportIdentity) throw new Error('Authenticated transport identity is required')
  if (protocolRequestId === undefined || protocolRequestId === null) {
    return crypto.randomUUID()
  }
  const digest = crypto.createHash('sha256')
    .update(JSON.stringify([
      'lovehouse-memory-runtime-v1',
      actor,
      String(transportIdentity),
      String(protocolRequestId),
      String(toolName || ''),
    ]))
    .digest()
  return uuidFromDigest(digest)
}
