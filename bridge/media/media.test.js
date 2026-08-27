import assert from 'node:assert/strict'
import { once } from 'node:events'
import http from 'node:http'
import test from 'node:test'

import { GetObjectCommand, PutObjectCommand } from '@aws-sdk/client-s3'
import express from 'express'

import { resolveR2MediaConfig } from './config.js'
import { installMediaRoutes } from './routes.js'
import { createR2MediaService } from './service.js'

const OWNER_ID = 'owner-123'
const CONFIG = {
  available: true,
  accountId: 'account-id',
  accessKeyId: 'DO-NOT-LEAK-ACCESS',
  secretAccessKey: 'DO-NOT-LEAK-SECRET',
  bucket: 'lovehouse-media',
  endpoint: 'https://account-id.r2.cloudflarestorage.com',
  maxBytes: 25 * 1024 * 1024,
  urlTtlSeconds: 600,
}

function createFakeService(overrides = {}) {
  const calls = []
  const service = createR2MediaService({
    config: CONFIG,
    client: { fake: true },
    now: () => new Date('2026-08-28T01:02:03.000Z'),
    uuid: () => '11111111-2222-4333-8444-555555555555',
    sign: async (client, command, options) => {
      calls.push({ client, command, options })
      return command instanceof PutObjectCommand
        ? 'https://signed.example/upload'
        : 'https://signed.example/read'
    },
    ...overrides,
  })
  return { service, calls }
}

async function startHarness(t, { service, authorize = true }) {
  const app = express()
  app.use(express.json())
  const verifyOwner = (req, res, next) => {
    if (!authorize || req.headers.authorization !== 'Bearer owner-token') {
      return res.status(401).json({ error: 'authorization required' })
    }
    req.userId = OWNER_ID
    return next()
  }
  installMediaRoutes(app, { verifyOwner, mediaService: service })
  const server = http.createServer(app)
  server.listen(0, '127.0.0.1')
  await once(server, 'listening')
  t.after(() => server.close())
  return `http://127.0.0.1:${server.address().port}`
}

async function post(base, path, body, authenticated = true) {
  return fetch(`${base}${path}`, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...(authenticated ? { Authorization: 'Bearer owner-token' } : {}),
    },
    body: JSON.stringify(body),
  })
}

test('missing R2 configuration stays unavailable without constructing a client', async () => {
  const config = resolveR2MediaConfig({})
  assert.equal(config.available, false)
  assert.deepEqual(config.missing, [
    'R2_ACCOUNT_ID', 'R2_ACCESS_KEY_ID', 'R2_SECRET_ACCESS_KEY', 'R2_BUCKET',
  ])
  const service = createR2MediaService({ config })
  assert.equal(service.available, false)
  await assert.rejects(
    service.createUploadUrl({ ownerId: OWNER_ID, filename: 'photo.jpg', mimeType: 'image/jpeg', size: 10 }),
    error => error.code === 'MEDIA_UNAVAILABLE' && error.status === 503,
  )
})

test('signs valid image, audio and document uploads with exact PUT parameters', async () => {
  for (const [filename, mimeType] of [
    ['夏天 photo.jpg', 'image/jpeg'],
    ['voice note.mp3', 'audio/mpeg'],
    ['notes.pdf', 'application/pdf'],
  ]) {
    const { service, calls } = createFakeService()
    const result = await service.createUploadUrl({ ownerId: OWNER_ID, filename, mimeType, size: 1234 })
    assert.match(result.object_key, /^media\/owner-123\/2026\/08\/11111111-2222-4333-8444-555555555555-/)
    assert.equal(result.upload_url, 'https://signed.example/upload')
    assert.equal(result.expires_at, '2026-08-28T01:12:03.000Z')
    assert.deepEqual(result.required_headers, {
      'Content-Type': mimeType,
      'Content-Length': '1234',
    })
    assert.equal(calls.length, 1)
    assert.equal(calls[0].command instanceof PutObjectCommand, true)
    assert.deepEqual(calls[0].command.input, {
      Bucket: 'lovehouse-media',
      Key: result.object_key,
      ContentType: mimeType,
      ContentLength: 1234,
    })
    assert.deepEqual(calls[0].options, { expiresIn: 600 })
  }
})

test('rejects oversized, negative, non-integer and disallowed MIME uploads', async () => {
  const { service } = createFakeService()
  await assert.rejects(
    service.createUploadUrl({ ownerId: OWNER_ID, filename: 'large.pdf', mimeType: 'application/pdf', size: CONFIG.maxBytes + 1 }),
    error => error.code === 'MEDIA_TOO_LARGE' && error.status === 413,
  )
  for (const size of [-1, 1.5]) {
    await assert.rejects(
      service.createUploadUrl({ ownerId: OWNER_ID, filename: 'bad.pdf', mimeType: 'application/pdf', size }),
      /non-negative integer/,
    )
  }
  for (const mimeType of ['', 'video/mp4', 'application/octet-stream']) {
    await assert.rejects(
      service.createUploadUrl({ ownerId: OWNER_ID, filename: 'bad.bin', mimeType, size: 1 }),
      error => error.code === (mimeType ? 'MEDIA_MIME_NOT_ALLOWED' : 'MEDIA_INVALID_REQUEST'),
    )
  }
})

test('rejects path traversal filenames and normalizes safe filenames', async () => {
  const { service } = createFakeService()
  for (const filename of ['../secret.jpg', '..\\secret.jpg', 'folder/photo.jpg', '..']) {
    await assert.rejects(
      service.createUploadUrl({ ownerId: OWNER_ID, filename, mimeType: 'image/jpeg', size: 1 }),
      /filename/,
    )
  }
  const result = await service.createUploadUrl({
    ownerId: OWNER_ID,
    filename: '  résumé 2026 #1.pdf  ',
    mimeType: 'application/pdf',
    size: 1,
  })
  assert.match(result.object_key, /résumé-2026-1\.pdf$/)
})

test('signs owner-scoped reads with exact GET parameters and rejects other namespaces', async () => {
  const { service, calls } = createFakeService()
  const objectKey = 'media/owner-123/2026/08/id-photo.jpg'
  const result = await service.createReadUrl({ ownerId: OWNER_ID, objectKey })
  assert.deepEqual(result, {
    read_url: 'https://signed.example/read',
    expires_at: '2026-08-28T01:12:03.000Z',
  })
  assert.equal(calls[0].command instanceof GetObjectCommand, true)
  assert.deepEqual(calls[0].command.input, { Bucket: 'lovehouse-media', Key: objectKey })
  assert.deepEqual(calls[0].options, { expiresIn: 600 })

  for (const invalidKey of [
    'media/someone-else/2026/08/id-photo.jpg',
    'media/owner-123/../someone-else/file.jpg',
    '/media/owner-123/file.jpg',
  ]) {
    await assert.rejects(service.createReadUrl({ ownerId: OWNER_ID, objectKey: invalidKey }))
  }
})

test('media routes reject non-Owner requests', async t => {
  const { service } = createFakeService()
  const base = await startHarness(t, { service })
  for (const path of ['/v1/media/upload-url', '/v1/media/read-url']) {
    const response = await post(base, path, {}, false)
    assert.equal(response.status, 401)
  }
})

test('API responses never expose R2 access credentials', async t => {
  const { service } = createFakeService()
  const base = await startHarness(t, { service })
  const uploadResponse = await post(base, '/v1/media/upload-url', {
    filename: 'photo.jpg', mime_type: 'image/jpeg', size: 1234,
  })
  assert.equal(uploadResponse.status, 200)
  assert.equal(uploadResponse.headers.get('cache-control'), 'no-store')
  const uploadBody = await uploadResponse.text()
  assert.equal(uploadBody.includes(CONFIG.accessKeyId), false)
  assert.equal(uploadBody.includes(CONFIG.secretAccessKey), false)

  const objectKey = JSON.parse(uploadBody).object_key
  const readResponse = await post(base, '/v1/media/read-url', { object_key: objectKey })
  assert.equal(readResponse.status, 200)
  const readBody = await readResponse.text()
  assert.equal(readBody.includes(CONFIG.accessKeyId), false)
  assert.equal(readBody.includes(CONFIG.secretAccessKey), false)
})
