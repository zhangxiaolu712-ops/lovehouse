import assert from 'node:assert/strict'
import { mkdtemp, readFile, rm, stat } from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'

import { FileRuntimeBindingStore } from './runtimeBindingStore.js'

const OWNER_ID = 'owner-user'
const THREAD_ID = '11111111-1111-4111-8111-111111111111'
const SESSION_ID = '22222222-2222-4222-8222-222222222222'

test('file runtime binding survives store recreation and supports explicit reset', async t => {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'lovehouse-runtime-binding-'))
  t.after(() => rm(directory, { recursive: true, force: true }))
  const filePath = path.join(directory, 'runtime-bindings.json')
  const key = { ownerUserId: OWNER_ID, personaId: 'claude', threadId: THREAD_ID }

  const first = new FileRuntimeBindingStore({ filePath })
  await first.save({ ...key, providerSessionId: SESSION_ID })

  const afterRestart = new FileRuntimeBindingStore({ filePath })
  assert.equal((await afterRestart.get(key)).provider_session_id, SESSION_ID)
  const payload = JSON.parse(await readFile(filePath, 'utf8'))
  assert.equal(payload.version, 1)
  assert.equal(payload.bindings[OWNER_ID].claude[THREAD_ID].provider_session_id, SESSION_ID)
  if (process.platform !== 'win32') assert.equal((await stat(filePath)).mode & 0o777, 0o600)

  assert.equal(await afterRestart.delete(key), true)
  assert.equal(await new FileRuntimeBindingStore({ filePath }).get(key), null)
})

test('file runtime binding rejects provider session ids as LoveHouse thread ids', async t => {
  const directory = await mkdtemp(path.join(os.tmpdir(), 'lovehouse-runtime-binding-'))
  t.after(() => rm(directory, { recursive: true, force: true }))
  const store = new FileRuntimeBindingStore({ filePath: path.join(directory, 'state.json') })
  await assert.rejects(
    store.save({
      ownerUserId: OWNER_ID,
      personaId: 'claude',
      threadId: 'provider-session-is-not-a-thread',
      providerSessionId: SESSION_ID,
    }),
    error => error.code === 'RUNTIME_BINDING_THREAD_INVALID',
  )
})
