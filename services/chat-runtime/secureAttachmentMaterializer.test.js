import assert from 'node:assert/strict'
import fs from 'node:fs/promises'
import os from 'node:os'
import path from 'node:path'
import test from 'node:test'

import {
  AttachmentMaterializationError,
  SecureAttachmentMaterializer,
} from './secureAttachmentMaterializer.js'

async function temporaryRoot(t) {
  const directory = await fs.mkdtemp(path.join(os.tmpdir(), 'lovehouse-shared-media-test-'))
  t.after(() => fs.rm(directory, { recursive: true, force: true }))
  return directory
}

function remoteAttachment(type, suffix, mimeType, bytes) {
  return {
    type,
    name: `owner-${suffix}.${type === 'photo' ? suffix : 'pdf'}`,
    mime_type: mimeType,
    size: bytes.length,
    read_url: `https://signed.example/${suffix}`,
  }
}

test('shared materializer preserves canonical order for multiple photos, files and location', async t => {
  const directory = await temporaryRoot(t)
  const bodies = new Map([
    ['https://signed.example/jpg', { mime: 'image/jpeg', bytes: [1, 2, 3] }],
    ['https://signed.example/png', { mime: 'image/png', bytes: [4, 5] }],
    ['https://signed.example/document', { mime: 'application/pdf', bytes: [6, 7, 8, 9] }],
  ])
  const materializer = new SecureAttachmentMaterializer({
    cwd: directory,
    fetchImpl: async url => {
      const body = bodies.get(url.toString())
      return new Response(new Uint8Array(body.bytes), { status: 200, headers: { 'Content-Type': body.mime } })
    },
  })
  const location = {
    type: 'location', latitude: 31.2, longitude: 121.5, accuracy: 8,
    captured_at: '2026-09-08T00:00:00Z',
  }
  const result = await materializer.materialize([
    remoteAttachment('photo', 'jpg', 'image/jpeg', [1, 2, 3]),
    remoteAttachment('photo', 'png', 'image/png', [4, 5]),
    remoteAttachment('file', 'document', 'application/pdf', [6, 7, 8, 9]),
    location,
  ])

  assert.deepEqual(result.items.map(item => item.type), ['photo', 'photo', 'file', 'location'])
  assert.match(result.items[0].local_path, /attachment-00\.jpg$/)
  assert.match(result.items[1].local_path, /attachment-01\.png$/)
  assert.match(result.items[2].local_path, /attachment-02\.pdf$/)
  assert.equal(result.items[0].read_url, undefined)
  assert.equal(result.items[3], location)
  for (const item of result.items.slice(0, 3)) assert.equal((await fs.stat(item.local_path)).isFile(), true)
  assert.equal((await fs.readdir(directory)).length, 1)

  await result.cleanup()
  assert.deepEqual(await fs.readdir(directory), [])
})

test('MIME and byte-size mismatches fail closed and remove every temporary file', async t => {
  for (const scenario of [
    { name: 'mime', responseMime: 'image/png', responseBytes: [1, 2, 3], declaredMime: 'image/jpeg', declaredSize: 3 },
    { name: 'size', responseMime: 'image/jpeg', responseBytes: [1, 2], declaredMime: 'image/jpeg', declaredSize: 3 },
    { name: 'limit', responseMime: 'image/jpeg', responseBytes: [1, 2, 3], declaredMime: 'image/jpeg', declaredSize: 3, maxBytes: 2 },
  ]) {
    await t.test(scenario.name, async t => {
      const directory = await temporaryRoot(t)
      const materializer = new SecureAttachmentMaterializer({
        cwd: directory,
        fetchImpl: async () => new Response(new Uint8Array(scenario.responseBytes), {
          status: 200, headers: { 'Content-Type': scenario.responseMime },
        }),
        ...(scenario.maxBytes ? { maxAttachmentBytes: scenario.maxBytes } : {}),
      })
      await assert.rejects(
        materializer.materialize([{
          type: 'photo', name: 'photo.jpg', mime_type: scenario.declaredMime,
          size: scenario.declaredSize, read_url: 'https://signed.example/photo',
        }]),
        error => error instanceof AttachmentMaterializationError && error.code === 'ATTACHMENT_INVALID',
      )
      assert.deepEqual(await fs.readdir(directory), [])
    })
  }
})

test('download failure, cancellation and timeout all clean the materialization directory', async t => {
  await t.test('download failure', async t => {
    const directory = await temporaryRoot(t)
    const materializer = new SecureAttachmentMaterializer({
      cwd: directory,
      fetchImpl: async () => new Response('unavailable', { status: 503 }),
    })
    await assert.rejects(
      materializer.materialize([remoteAttachment('file', 'document', 'application/pdf', [1])]),
      error => error.code === 'ATTACHMENT_UNAVAILABLE',
    )
    assert.deepEqual(await fs.readdir(directory), [])
  })

  for (const scenario of ['cancel', 'timeout']) {
    await t.test(scenario, async t => {
      const directory = await temporaryRoot(t)
      const controller = scenario === 'cancel' ? new AbortController() : null
      const signal = controller?.signal || AbortSignal.timeout(10)
      const materializer = new SecureAttachmentMaterializer({
        cwd: directory,
        fetchImpl: async (_url, options) => new Promise((resolve, reject) => {
          if (options.signal.aborted) {
            reject(options.signal.reason)
            return
          }
          options.signal.addEventListener('abort', () => reject(options.signal.reason), { once: true })
        }),
      })
      const pending = materializer.materialize([
        remoteAttachment('photo', 'jpg', 'image/jpeg', [1]),
      ], signal)
      if (controller) controller.abort()
      await assert.rejects(pending, error => error?.name === 'AbortError' || error?.name === 'TimeoutError')
      assert.deepEqual(await fs.readdir(directory), [])
    })
  }
})

test('unsupported lifecycle fails before download without provider or persona branching', async t => {
  const directory = await temporaryRoot(t)
  let downloads = 0
  const materializer = new SecureAttachmentMaterializer({
    cwd: directory,
    fetchImpl: async () => { downloads += 1; return new Response() },
  })
  await assert.rejects(
    materializer.materialize([{
      ...remoteAttachment('file', 'document', 'application/pdf', [1]),
      lifecycle: 'DURABLE',
    }]),
    error => error.code === 'ATTACHMENT_INVALID',
  )
  assert.equal(downloads, 0)
  assert.deepEqual(await fs.readdir(directory), [])
})
