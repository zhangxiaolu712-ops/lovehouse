import test from 'node:test'
import assert from 'node:assert/strict'

import {
  createRuntimeMemoryRepository,
  DisabledMemoryRepository,
  MemorySystemDisabledError,
} from './runtimeRepository.js'

test('disabled runtime repository fails closed for every operation without touching canonical storage', async () => {
  let canonicalCalls = 0
  const canonicalRepository = new Proxy({}, {
    get() {
      return async () => { canonicalCalls += 1 }
    },
  })
  const repository = createRuntimeMemoryRepository({ enabled: false, canonicalRepository })

  assert.equal(repository instanceof DisabledMemoryRepository, true)
  for (const operation of [
    () => repository.remember({}),
    () => repository.getById(1),
    () => repository.list({}),
    () => repository.search({}),
    () => repository.memoryBox({}),
    () => repository.revise(1, {}, 'reason'),
    () => repository.proposeShared(1, 'reason'),
  ]) {
    await assert.rejects(
      operation(),
      error => error instanceof MemorySystemDisabledError
        && error.code === 'MEMORY_SYSTEM_DISABLED'
    )
  }
  assert.equal(canonicalCalls, 0)
})

test('enabled runtime repository uses only the canonical repository', () => {
  const canonicalRepository = { name: 'memory_entries only' }
  assert.equal(
    createRuntimeMemoryRepository({ enabled: true, canonicalRepository }),
    canonicalRepository
  )
})
