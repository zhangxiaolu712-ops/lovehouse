import { randomUUID } from 'node:crypto'

import { GetObjectCommand, HeadObjectCommand, PutObjectCommand, S3Client } from '@aws-sdk/client-s3'
import { getSignedUrl } from '@aws-sdk/s3-request-presigner'

const ALLOWED_MIME_TYPES = new Set([
  'image/jpeg', 'image/png', 'image/gif', 'image/webp', 'image/avif', 'image/heic', 'image/heif',
  'audio/mpeg', 'audio/mp4', 'audio/wav', 'audio/x-wav', 'audio/ogg', 'audio/webm',
  'audio/aac', 'audio/flac',
  'application/pdf', 'text/plain', 'text/csv', 'application/rtf',
  'application/msword',
  'application/vnd.openxmlformats-officedocument.wordprocessingml.document',
  'application/vnd.ms-excel',
  'application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',
  'application/vnd.ms-powerpoint',
  'application/vnd.openxmlformats-officedocument.presentationml.presentation',
  'application/vnd.oasis.opendocument.text',
  'application/vnd.oasis.opendocument.spreadsheet',
])

export class MediaRequestError extends Error {
  constructor(message, code = 'MEDIA_INVALID_REQUEST', status = 400) {
    super(message)
    this.code = code
    this.status = status
  }
}

export function normalizeFilename(value) {
  if (typeof value !== 'string' || !value.trim()) {
    throw new MediaRequestError('filename is required')
  }
  const normalized = value.trim().normalize('NFKC')
  if (normalized.includes('/') || normalized.includes('\\') || normalized === '.' || normalized === '..') {
    throw new MediaRequestError('filename must not contain a path')
  }
  const safe = normalized
    .replace(/[\u0000-\u001f\u007f]/g, '')
    .replace(/[^\p{L}\p{N}._()-]+/gu, '-')
    .replace(/^\.+/, '')
    .replace(/-+/g, '-')
    .slice(0, 120)
  if (!safe || safe === '.' || safe === '..') {
    throw new MediaRequestError('filename is invalid')
  }
  return safe
}

function validateMimeType(value) {
  if (typeof value !== 'string' || !value.trim()) {
    throw new MediaRequestError('mime_type is required')
  }
  const mimeType = value.trim().toLowerCase()
  if (!ALLOWED_MIME_TYPES.has(mimeType)) {
    throw new MediaRequestError('mime_type is not allowed', 'MEDIA_MIME_NOT_ALLOWED')
  }
  return mimeType
}

function validateSize(value, maxBytes) {
  if (!Number.isSafeInteger(value) || value < 0) {
    throw new MediaRequestError('size must be a non-negative integer')
  }
  if (value > maxBytes) {
    throw new MediaRequestError('file exceeds the configured size limit', 'MEDIA_TOO_LARGE', 413)
  }
  return value
}

function ownerPrefix(ownerId) {
  if (typeof ownerId !== 'string' || !/^[A-Za-z0-9_-]+$/.test(ownerId)) {
    throw new MediaRequestError('owner identity is invalid', 'MEDIA_OWNER_INVALID', 403)
  }
  return `media/${ownerId}/`
}

export function createR2MediaService({
  config,
  client,
  sign = getSignedUrl,
  now = () => new Date(),
  uuid = randomUUID,
} = {}) {
  const s3Client = client || (config.available ? new S3Client({
    region: 'auto',
    endpoint: config.endpoint,
    credentials: {
      accessKeyId: config.accessKeyId,
      secretAccessKey: config.secretAccessKey,
    },
  }) : null)

  function ensureAvailable() {
    if (!config.available || !s3Client) {
      throw new MediaRequestError('media storage is unavailable', 'MEDIA_UNAVAILABLE', 503)
    }
  }

  async function createUploadUrl({ ownerId, filename, mimeType, size }) {
    ensureAvailable()
    const safeName = normalizeFilename(filename)
    const safeMimeType = validateMimeType(mimeType)
    const safeSize = validateSize(size, config.maxBytes)
    const timestamp = now()
    const year = String(timestamp.getUTCFullYear()).padStart(4, '0')
    const month = String(timestamp.getUTCMonth() + 1).padStart(2, '0')
    const mediaAssetId = uuid()
    const objectKey = `${ownerPrefix(ownerId)}${year}/${month}/${mediaAssetId}-${safeName}`
    const command = new PutObjectCommand({
      Bucket: config.bucket,
      Key: objectKey,
      ContentType: safeMimeType,
      ContentLength: safeSize,
    })
    const uploadUrl = await sign(s3Client, command, { expiresIn: config.urlTtlSeconds })
    return {
      media_asset_id: mediaAssetId,
      storage_ref: objectKey,
      object_key: objectKey,
      name: safeName,
      mime_type: safeMimeType,
      size: safeSize,
      upload_url: uploadUrl,
      expires_at: new Date(timestamp.getTime() + config.urlTtlSeconds * 1000).toISOString(),
      required_headers: {
        'Content-Type': safeMimeType,
        'Content-Length': String(safeSize),
      },
    }
  }

  async function resolveRuntimeAsset({ ownerId, mediaAssetId, storageRef }) {
    ensureAvailable()
    if (typeof mediaAssetId !== 'string' || !/^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(mediaAssetId)) {
      throw new MediaRequestError('media_asset_id is invalid')
    }
    if (typeof storageRef !== 'string' || !storageRef.startsWith(ownerPrefix(ownerId))) {
      throw new MediaRequestError('storage_ref is outside the owner media namespace', 'MEDIA_OBJECT_FORBIDDEN', 403)
    }
    if (storageRef.includes('\\') || storageRef.split('/').some(part => !part || part === '.' || part === '..')) {
      throw new MediaRequestError('storage_ref is invalid')
    }
    const filename = storageRef.split('/').at(-1) || ''
    if (!filename.startsWith(`${mediaAssetId}-`)) {
      throw new MediaRequestError('media_asset_id does not match storage_ref', 'MEDIA_ASSET_MISMATCH', 403)
    }
    const head = await s3Client.send(new HeadObjectCommand({ Bucket: config.bucket, Key: storageRef }))
    const mimeType = validateMimeType(head.ContentType)
    const size = validateSize(head.ContentLength, config.maxBytes)
    const signed = await createReadUrl({ ownerId, objectKey: storageRef })
    return {
      media_asset_id: mediaAssetId,
      storage_ref: storageRef,
      name: filename.slice(mediaAssetId.length + 1),
      mime_type: mimeType,
      size,
      read_url: signed.read_url,
      expires_at: signed.expires_at,
    }
  }

  async function createReadUrl({ ownerId, objectKey }) {
    ensureAvailable()
    if (typeof objectKey !== 'string' || !objectKey.startsWith(ownerPrefix(ownerId))) {
      throw new MediaRequestError('object_key is outside the owner media namespace', 'MEDIA_OBJECT_FORBIDDEN', 403)
    }
    if (objectKey.includes('\\') || objectKey.split('/').some(part => !part || part === '.' || part === '..')) {
      throw new MediaRequestError('object_key is invalid')
    }
    const timestamp = now()
    const command = new GetObjectCommand({ Bucket: config.bucket, Key: objectKey })
    const readUrl = await sign(s3Client, command, { expiresIn: config.urlTtlSeconds })
    return {
      read_url: readUrl,
      expires_at: new Date(timestamp.getTime() + config.urlTtlSeconds * 1000).toISOString(),
    }
  }

  return {
    available: Boolean(config.available),
    maxBytes: config.maxBytes,
    urlTtlSeconds: config.urlTtlSeconds,
    createUploadUrl,
    createReadUrl,
    resolveRuntimeAsset,
  }
}

export { ALLOWED_MIME_TYPES }
