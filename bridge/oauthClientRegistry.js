import crypto from 'crypto'
import { chmod, mkdir, open, readFile, rename, rm } from 'fs/promises'
import os from 'os'
import path from 'path'

import { validateRedirectUris } from './security.js'

const STORE_VERSION = 1
const DEFAULT_MAX_CLIENTS = 10_000
const LOCK_WAIT_MS = 5_000
const SUPPORTED_GRANTS = new Set(['authorization_code', 'refresh_token'])
const SUPPORTED_APPLICATION_TYPES = new Set(['native', 'web'])
const SUPPORTED_AUTH_METHODS = new Set(['none', 'client_secret_post'])

function emptyState() {
  return { version: STORE_VERSION, clients: {} }
}

function clone(value) {
  return structuredClone(value)
}

function nullableTimestamp(value) {
  return value === null || Number.isFinite(value)
}

function validateClient(record) {
  if (!record
    || typeof record.client_id !== 'string'
    || !/^lh_[a-f0-9]{32}$/.test(record.client_id)
    || typeof record.client_name !== 'string'
    || !record.client_name
    || record.client_name.length > 120
    || !validateRedirectUris(record.redirect_uris)
    || !Array.isArray(record.grant_types)
    || record.grant_types.length < 1
    || record.grant_types.length > 2
    || new Set(record.grant_types).size !== record.grant_types.length
    || !record.grant_types.includes('authorization_code')
    || record.grant_types.some(value => !SUPPORTED_GRANTS.has(value))
    || !Array.isArray(record.response_types)
    || record.response_types.length !== 1
    || record.response_types[0] !== 'code'
    || !SUPPORTED_APPLICATION_TYPES.has(record.application_type)
    || !SUPPORTED_AUTH_METHODS.has(record.token_endpoint_auth_method)
    || (record.application_type === 'native' && record.token_endpoint_auth_method !== 'none')
    || !Number.isFinite(record.created_at)
    || !nullableTimestamp(record.expires_at)
    || !nullableTimestamp(record.revoked_at)
    || !Number.isFinite(record.client_secret_expires_at)) {
    throw new Error('OAuth client registry record is invalid')
  }
  const hasSecretDigest = typeof record.client_secret_digest === 'string'
    && record.client_secret_digest.length > 0
  if ((record.token_endpoint_auth_method === 'client_secret_post') !== hasSecretDigest) {
    throw new Error('OAuth client registry record is invalid')
  }
}

function validateState(value) {
  if (!value || value.version !== STORE_VERSION || !value.clients || Array.isArray(value.clients)) {
    throw new Error('OAuth client registry is invalid')
  }
  for (const [clientId, record] of Object.entries(value.clients)) {
    validateClient(record)
    if (record.client_id !== clientId) throw new Error('OAuth client registry is invalid')
  }
  return value
}

export class OAuthClientRegistry {
  constructor({ filePath = null, now = () => Date.now(), maxClients = DEFAULT_MAX_CLIENTS } = {}) {
    this.filePath = filePath
    this.now = now
    this.maxClients = maxClients
    this.memoryState = emptyState()
    this.operation = Promise.resolve()
  }

  async register(record) {
    validateClient(record)
    return this.#exclusive(async () => {
      const state = await this.#load()
      if (state.clients[record.client_id]) throw new Error('OAuth client id collision')
      if (Object.keys(state.clients).length >= this.maxClients) {
        throw new Error('OAuth client registry capacity exceeded')
      }
      state.clients[record.client_id] = clone(record)
      await this.#save(state)
      return clone(record)
    })
  }

  async get(clientId) {
    if (typeof clientId !== 'string' || !clientId) return null
    return this.#exclusive(async () => {
      const state = await this.#load()
      const record = state.clients[clientId]
      return record ? clone(record) : null
    })
  }

  async revoke(clientId) {
    if (typeof clientId !== 'string' || !clientId) return false
    return this.#exclusive(async () => {
      const state = await this.#load()
      const record = state.clients[clientId]
      if (!record) return false
      if (record.revoked_at === null) record.revoked_at = this.now()
      await this.#save(state)
      return true
    })
  }

  async #exclusive(work) {
    const guardedWork = this.filePath ? () => this.#withFileLock(work) : work
    const next = this.operation.then(guardedWork, guardedWork)
    this.operation = next.catch(() => {})
    return next
  }

  async #withFileLock(work) {
    const directory = path.dirname(this.filePath)
    await mkdir(directory, { recursive: true, mode: 0o700 })
    await chmod(directory, 0o700)
    const lockPath = `${this.filePath}.lock`
    const deadline = Date.now() + LOCK_WAIT_MS

    while (true) {
      try {
        await mkdir(lockPath, { mode: 0o700 })
        await chmod(lockPath, 0o700)
        break
      } catch (error) {
        if (error?.code !== 'EEXIST') throw error
        if (Date.now() >= deadline) throw new Error('OAuth client registry is busy')
        await new Promise(resolve => setTimeout(resolve, 10 + crypto.randomInt(20)))
      }
    }

    try {
      return await work()
    } finally {
      await rm(lockPath, { recursive: true, force: true })
    }
  }

  async #load() {
    if (!this.filePath) return clone(this.memoryState)
    try {
      return validateState(JSON.parse(await readFile(this.filePath, 'utf8')))
    } catch (error) {
      if (error?.code === 'ENOENT') return emptyState()
      throw error
    }
  }

  async #save(state) {
    validateState(state)
    if (!this.filePath) {
      this.memoryState = clone(state)
      return
    }
    const directory = path.dirname(this.filePath)
    await mkdir(directory, { recursive: true, mode: 0o700 })
    await chmod(directory, 0o700)
    const temporary = `${this.filePath}.${process.pid}.${crypto.randomBytes(8).toString('hex')}.tmp`
    try {
      const temporaryHandle = await open(temporary, 'wx', 0o600)
      try {
        await temporaryHandle.writeFile(`${JSON.stringify(state)}\n`, 'utf8')
        await temporaryHandle.sync()
      } finally {
        await temporaryHandle.close()
      }
      await rename(temporary, this.filePath)
      await chmod(this.filePath, 0o600)
      if (process.platform !== 'win32') {
        const directoryHandle = await open(directory, 'r')
        try {
          await directoryHandle.sync()
        } finally {
          await directoryHandle.close()
        }
      }
    } catch (error) {
      await rm(temporary, { force: true }).catch(() => {})
      throw error
    }
  }
}

export function createMemoryOAuthClientRegistry(options = {}) {
  return new OAuthClientRegistry(options)
}

export function createFileOAuthClientRegistry({ filePath, ...options } = {}) {
  if (filePath && !path.isAbsolute(filePath)) {
    throw new Error('OAUTH_CLIENT_REGISTRY_PATH must be an absolute path')
  }
  return new OAuthClientRegistry({
    ...options,
    filePath: filePath || path.join(os.homedir(), '.config', 'lovehouse-bridge', 'oauth-clients.json'),
  })
}
