import crypto from 'crypto'
import { chmod, mkdir, open, readFile, rename, rm } from 'fs/promises'
import os from 'os'
import path from 'path'

const STORE_VERSION = 1
const DEFAULT_MAX_RECORDS = 10_000
const TERMINAL_RETENTION_MS = 30 * 24 * 60 * 60_000
const LOCK_WAIT_MS = 5_000

function emptyState() {
  return { version: STORE_VERSION, records: {} }
}

function clone(value) {
  return structuredClone(value)
}

function validateState(value) {
  if (!value || value.version !== STORE_VERSION || !value.records || Array.isArray(value.records)) {
    throw new Error('OAuth refresh token store is invalid')
  }
  for (const [digest, record] of Object.entries(value.records)) {
    if (!digest || !record || record.token_digest !== digest || !record.family_id) {
      throw new Error('OAuth refresh token store is invalid')
    }
  }
  return value
}

function validateRecord(record) {
  const requiredStrings = [
    'token_digest',
    'family_id',
    'client_id',
    'client_auth_method',
    'owner_user_id',
    'resource',
    'scope',
  ]
  if (!record || requiredStrings.some(key => typeof record[key] !== 'string' || !record[key])) {
    throw new Error('OAuth refresh token record is invalid')
  }
  if (!Number.isInteger(record.generation) || record.generation < 0) {
    throw new Error('OAuth refresh token record is invalid')
  }
  if (!Number.isFinite(record.created_at) || !Number.isFinite(record.expires_at)) {
    throw new Error('OAuth refresh token record is invalid')
  }
}

export function createRefreshToken() {
  return `lh_rt_${crypto.randomBytes(32).toString('base64url')}`
}

export function digestRefreshToken(token, secret) {
  if (typeof token !== 'string' || !token.startsWith('lh_rt_') || token.length > 256) return null
  return crypto.createHmac('sha256', secret)
    .update('lovehouse-refresh-token\0')
    .update(token)
    .digest('base64url')
}

export function digestClientSecret(secretValue, signingSecret) {
  if (typeof secretValue !== 'string' || !secretValue || secretValue.length > 512) return null
  return crypto.createHmac('sha256', signingSecret)
    .update('lovehouse-client-secret\0')
    .update(secretValue)
    .digest('base64url')
}

export class RefreshTokenStore {
  constructor({ filePath = null, now = () => Date.now(), maxRecords = DEFAULT_MAX_RECORDS } = {}) {
    this.filePath = filePath
    this.now = now
    this.maxRecords = maxRecords
    this.memoryState = emptyState()
    this.operation = Promise.resolve()
  }

  async issue(record) {
    validateRecord(record)
    return this.#exclusive(async () => {
      const state = await this.#load()
      this.#prune(state)
      if (state.records[record.token_digest]) throw new Error('OAuth refresh token digest collision')
      if (Object.keys(state.records).length >= this.maxRecords) {
        throw new Error('OAuth refresh token store capacity exceeded')
      }
      state.records[record.token_digest] = { ...record, status: 'active' }
      await this.#save(state)
    })
  }

  async rotate(tokenDigest, createReplacement, bindingMatches) {
    if (typeof createReplacement !== 'function' || typeof bindingMatches !== 'function') {
      throw new Error('OAuth refresh token rotation callbacks are required')
    }
    return this.#exclusive(async () => {
      const state = await this.#load()
      const current = state.records[tokenDigest]
      if (!current) return { status: 'invalid' }
      if (!bindingMatches(current)) return { status: 'invalid_binding' }

      const now = this.now()
      if (current.expires_at <= now) {
        current.status = 'expired'
        current.status_changed_at = now
        await this.#save(state)
        return { status: 'expired' }
      }
      if (current.status === 'rotated') {
        this.#revokeFamily(state, current.family_id, now)
        await this.#save(state)
        return { status: 'replayed' }
      }
      if (current.status !== 'active') return { status: current.status || 'invalid' }
      const replacement = createReplacement(clone(current))
      validateRecord(replacement)
      if (replacement.family_id !== current.family_id || replacement.generation !== current.generation + 1) {
        throw new Error('OAuth refresh token rotation is invalid')
      }
      if (state.records[replacement.token_digest]) throw new Error('OAuth refresh token digest collision')

      this.#prune(state)
      if (Object.keys(state.records).length >= this.maxRecords) {
        throw new Error('OAuth refresh token store capacity exceeded')
      }
      current.status = 'rotated'
      current.status_changed_at = now
      current.replaced_by = replacement.token_digest
      state.records[replacement.token_digest] = { ...replacement, status: 'active' }
      await this.#save(state)
      return { status: 'rotated', record: clone(replacement) }
    })
  }

  async revoke(tokenDigest) {
    return this.#exclusive(async () => {
      const state = await this.#load()
      const current = state.records[tokenDigest]
      if (!current) return false
      this.#revokeFamily(state, current.family_id, this.now())
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
        if (Date.now() >= deadline) throw new Error('OAuth refresh token store is busy')
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
  }

  #prune(state) {
    const cutoff = this.now() - TERMINAL_RETENTION_MS
    for (const [digest, record] of Object.entries(state.records)) {
      const terminal = ['expired', 'revoked'].includes(record.status)
      if (terminal && (record.status_changed_at || record.expires_at) < cutoff) delete state.records[digest]
    }
  }

  #revokeFamily(state, familyId, now) {
    for (const record of Object.values(state.records)) {
      if (record.family_id !== familyId) continue
      record.status = 'revoked'
      record.status_changed_at = now
      delete record.replaced_by
    }
  }
}

export function createMemoryRefreshTokenStore(options = {}) {
  return new RefreshTokenStore(options)
}

export function createFileRefreshTokenStore({ filePath, ...options } = {}) {
  if (filePath && !path.isAbsolute(filePath)) {
    throw new Error('OAUTH_REFRESH_STORE_PATH must be an absolute path')
  }
  return new RefreshTokenStore({
    ...options,
    filePath: filePath || path.join(os.homedir(), '.config', 'lovehouse-bridge', 'oauth-refresh-tokens.json'),
  })
}
