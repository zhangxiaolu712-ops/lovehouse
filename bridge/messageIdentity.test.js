import assert from 'node:assert/strict'
import test from 'node:test'

import { isUuidV7, newUuidV7 } from './messageIdentity.js'

test('UUIDv7 generation is offline, timestamp ordered, and RFC variant compliant', () => {
  const id = newUuidV7(1_760_313_600_123, Buffer.alloc(16, 0x2a))
  assert.equal(id, '0199dadd-e87b-7a2a-aa2a-2a2a2a2a2a2a')
  assert.equal(isUuidV7(id), true)
  assert.equal(isUuidV7('11111111-1111-4111-8111-111111111111'), false)
})
