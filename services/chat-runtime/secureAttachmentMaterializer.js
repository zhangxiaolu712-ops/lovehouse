import { randomUUID } from 'node:crypto'
import { mkdir, rm, writeFile } from 'node:fs/promises'
import { join } from 'node:path'

const DEFAULT_MAX_ATTACHMENT_BYTES = 25 * 1024 * 1024
const SUPPORTED_LIFECYCLES = new Set(['LOCAL', 'EPHEMERAL'])

export class AttachmentMaterializationError extends Error {
  constructor(code, message, options = {}) {
    super(message, { cause: options.cause })
    this.name = 'AttachmentMaterializationError'
    this.code = code
    this.stage = options.stage || 'attachment'
    this.status = options.status || 500
    this.retryable = options.retryable === true
  }
}

function safeAttachmentExtension(value) {
  const match = String(value || '').match(/\.[A-Za-z0-9]{1,10}$/)
  return match ? match[0].toLowerCase() : ''
}

function canonicalLifecycle(item) {
  if (typeof item?.lifecycle === 'string' && item.lifecycle.trim()) {
    return item.lifecycle.trim().toUpperCase()
  }
  return item?.type === 'location' ? 'LOCAL' : 'EPHEMERAL'
}

export class SecureAttachmentMaterializer {
  constructor({
    cwd = '/tmp',
    fetchImpl = globalThis.fetch,
    maxAttachmentBytes = DEFAULT_MAX_ATTACHMENT_BYTES,
    createError = (code, message, options) => new AttachmentMaterializationError(code, message, options),
  } = {}) {
    if (typeof fetchImpl !== 'function') throw new TypeError('Secure attachment materializer requires fetch')
    if (!Number.isSafeInteger(maxAttachmentBytes) || maxAttachmentBytes <= 0) {
      throw new TypeError('Secure attachment materializer requires a positive byte limit')
    }
    if (typeof createError !== 'function') throw new TypeError('Secure attachment materializer requires an error factory')
    this.cwd = cwd
    this.fetchImpl = fetchImpl
    this.maxAttachmentBytes = maxAttachmentBytes
    this.createError = createError
  }

  async materialize(attachments = [], signal) {
    if (!attachments.length) return { items: [], cleanup: async () => {} }
    const directory = join(this.cwd, `.lovehouse-media-${randomUUID()}`)
    await mkdir(directory, { recursive: false })
    const items = []
    try {
      for (const [index, item] of attachments.entries()) {
        const lifecycle = canonicalLifecycle(item)
        if (!SUPPORTED_LIFECYCLES.has(lifecycle)) {
          throw this.createError('ATTACHMENT_INVALID', 'Runtime attachment lifecycle is invalid', {
            stage: 'attachment', status: 400,
          })
        }
        if (item?.type === 'location') {
          items.push(item)
          continue
        }
        if (!['photo', 'file'].includes(item?.type) || typeof item.read_url !== 'string') {
          throw this.createError('ATTACHMENT_INVALID', 'Runtime attachment is invalid', {
            stage: 'attachment', status: 400,
          })
        }
        const url = new URL(item.read_url)
        if (url.protocol !== 'https:') {
          throw this.createError('ATTACHMENT_INVALID', 'Runtime attachment URL is not secure', {
            stage: 'attachment', status: 400,
          })
        }
        const response = await this.fetchImpl(url, { signal, redirect: 'error' })
        if (!response.ok) {
          throw this.createError('ATTACHMENT_UNAVAILABLE', 'Runtime could not retrieve an attachment', {
            stage: 'attachment', status: 502, retryable: true,
          })
        }
        const bytes = new Uint8Array(await response.arrayBuffer())
        const responseMime = String(response.headers.get('content-type') || '').split(';', 1)[0].trim().toLowerCase()
        if (responseMime && responseMime !== item.mime_type) {
          throw this.createError('ATTACHMENT_INVALID', 'Runtime attachment type verification failed', {
            stage: 'attachment', status: 400,
          })
        }
        if (bytes.byteLength !== item.size || bytes.byteLength > this.maxAttachmentBytes) {
          throw this.createError('ATTACHMENT_INVALID', 'Runtime attachment size verification failed', {
            stage: 'attachment', status: 400,
          })
        }
        const localPath = join(directory, `attachment-${String(index).padStart(2, '0')}${safeAttachmentExtension(item.name)}`)
        await writeFile(localPath, bytes, { flag: 'wx', mode: 0o600 })
        items.push({ ...item, read_url: undefined, local_path: localPath })
      }
      return {
        items,
        cleanup: () => rm(directory, { recursive: true, force: true }),
      }
    } catch (error) {
      await rm(directory, { recursive: true, force: true })
      throw error
    }
  }
}
