import test from 'node:test'
import assert from 'node:assert/strict'

import { resolveBridgePort } from './runtimeConfig.js'

test('resolveBridgePort defaults to 3000', () => {
  assert.equal(resolveBridgePort(undefined), 3000)
  assert.equal(resolveBridgePort(''), 3000)
})

test('resolveBridgePort accepts a valid explicit port', () => {
  assert.equal(resolveBridgePort('3101'), 3101)
  assert.equal(resolveBridgePort(3200), 3200)
})

test('resolveBridgePort rejects invalid ports', () => {
  assert.throws(() => resolveBridgePort('abc'), /PORT must be an integer/)
  assert.throws(() => resolveBridgePort('0'), /PORT must be an integer/)
  assert.throws(() => resolveBridgePort('65536'), /PORT must be an integer/)
  assert.throws(() => resolveBridgePort('3101x'), /PORT must be an integer/)
})
