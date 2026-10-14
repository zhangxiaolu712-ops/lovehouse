import crypto from 'node:crypto'

const UUID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-([0-9a-f])[0-9a-f]{3}-([89ab])[0-9a-f]{3}-[0-9a-f]{12}$/i

export function isUuid(value) {
  return typeof value === 'string' && UUID_RE.test(value)
}

export function isUuidV7(value) {
  const match = typeof value === 'string' ? UUID_RE.exec(value) : null
  return Boolean(match && match[1].toLowerCase() === '7')
}

export function newUuidV7(now = Date.now(), randomBytes = crypto.randomBytes(16)) {
  if (!Number.isSafeInteger(now) || now < 0 || now > 0xffffffffffff) {
    throw new RangeError('UUIDv7 timestamp is out of range')
  }
  if (!Buffer.isBuffer(randomBytes) || randomBytes.length !== 16) {
    throw new TypeError('UUIDv7 requires 16 random bytes')
  }
  const bytes = Buffer.from(randomBytes)
  for (let index = 0; index < 6; index += 1) {
    bytes[index] = Math.floor(now / (2 ** (40 - index * 8))) & 0xff
  }
  bytes[6] = (bytes[6] & 0x0f) | 0x70
  bytes[8] = (bytes[8] & 0x3f) | 0x80
  return [
    bytes.subarray(0, 4).toString('hex'),
    bytes.subarray(4, 6).toString('hex'),
    bytes.subarray(6, 8).toString('hex'),
    bytes.subarray(8, 10).toString('hex'),
    bytes.subarray(10, 16).toString('hex'),
  ].join('-')
}
