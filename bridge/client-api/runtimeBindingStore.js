import fs from 'node:fs/promises'
import path from 'node:path'

import { ClientApiError } from './errors.js'

const ID_RE = /^[A-Za-z0-9_-]{1,128}$/
const THREAD_ID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const SESSION_ID_RE = /^[A-Za-z0-9._:-]{8,256}$/

function assertKey({ ownerUserId, personaId, threadId, providerSessionId = null }) {
  if (typeof ownerUserId !== 'string' || !ID_RE.test(ownerUserId)) {
    throw new ClientApiError('RUNTIME_BINDING_OWNER_INVALID', 'Runtime binding owner is invalid', {
      stage: 'storage', status: 500,
    })
  }
  if (typeof personaId !== 'string' || !ID_RE.test(personaId)) {
    throw new ClientApiError('RUNTIME_BINDING_PERSONA_INVALID', 'Runtime binding persona is invalid', {
      stage: 'storage', status: 500,
    })
  }
  if (typeof threadId !== 'string' || !THREAD_ID_RE.test(threadId)) {
    throw new ClientApiError('RUNTIME_BINDING_THREAD_INVALID', 'Runtime binding thread is invalid', {
      stage: 'storage', status: 500,
    })
  }
  if (providerSessionId !== null && (
    typeof providerSessionId !== 'string' || !SESSION_ID_RE.test(providerSessionId)
  )) {
    throw new ClientApiError('RUNTIME_BINDING_SESSION_INVALID', 'Runtime binding session is invalid', {
      stage: 'storage', status: 500,
    })
  }
}

function emptyState() {
  return { version: 1, bindings: {} }
}

function validateState(state) {
  if (!state || state.version !== 1 || typeof state.bindings !== 'object' || Array.isArray(state.bindings)) {
    throw new Error('unsupported runtime binding file shape')
  }
  return state
}

export class InMemoryRuntimeBindingStore {
  constructor() {
    this.bindings = new Map()
  }

  async get(query) {
    assertKey(query)
    return this.bindings.get(`${query.ownerUserId}:${query.personaId}:${query.threadId}`) || null
  }

  async save(binding) {
    assertKey(binding)
    const value = {
      provider_session_id: binding.providerSessionId,
      updated_at: new Date().toISOString(),
    }
    this.bindings.set(`${binding.ownerUserId}:${binding.personaId}:${binding.threadId}`, value)
    return { ...value }
  }

  async delete(query) {
    assertKey(query)
    return this.bindings.delete(`${query.ownerUserId}:${query.personaId}:${query.threadId}`)
  }
}

export class FileRuntimeBindingStore {
  #queue = Promise.resolve()

  constructor({ filePath }) {
    if (!path.isAbsolute(filePath || '')) {
      throw new TypeError('Runtime binding file path must be absolute')
    }
    this.filePath = filePath
  }

  async #read() {
    try {
      return validateState(JSON.parse(await fs.readFile(this.filePath, 'utf8')))
    } catch (error) {
      if (error.code === 'ENOENT') return emptyState()
      throw new ClientApiError('RUNTIME_BINDING_READ_FAILED', 'Runtime binding state is unavailable', {
        stage: 'storage', status: 503, retryable: true, cause: error,
      })
    }
  }

  async #write(state) {
    const directory = path.dirname(this.filePath)
    const temporary = `${this.filePath}.${process.pid}.${Date.now()}.tmp`
    try {
      await fs.mkdir(directory, { recursive: true, mode: 0o700 })
      await fs.writeFile(temporary, `${JSON.stringify(state)}\n`, { mode: 0o600 })
      await fs.rename(temporary, this.filePath)
      await fs.chmod(this.filePath, 0o600)
    } catch (error) {
      await fs.rm(temporary, { force: true }).catch(() => {})
      throw new ClientApiError('RUNTIME_BINDING_WRITE_FAILED', 'Runtime binding state could not be saved', {
        stage: 'storage', status: 503, retryable: true, cause: error,
      })
    }
  }

  async get(query) {
    assertKey(query)
    await this.#queue
    const state = await this.#read()
    const value = state.bindings[query.ownerUserId]?.[query.personaId]?.[query.threadId]
    return value ? { ...value } : null
  }

  async save(binding) {
    assertKey(binding)
    const operation = this.#queue.then(async () => {
      const state = await this.#read()
      const value = {
        provider_session_id: binding.providerSessionId,
        updated_at: new Date().toISOString(),
      }
      state.bindings[binding.ownerUserId] ||= {}
      state.bindings[binding.ownerUserId][binding.personaId] ||= {}
      state.bindings[binding.ownerUserId][binding.personaId][binding.threadId] = value
      await this.#write(state)
      return { ...value }
    })
    this.#queue = operation.catch(() => {})
    return operation
  }

  async delete(query) {
    assertKey(query)
    const operation = this.#queue.then(async () => {
      const state = await this.#read()
      const personaBindings = state.bindings[query.ownerUserId]?.[query.personaId]
      if (!personaBindings?.[query.threadId]) return false
      delete personaBindings[query.threadId]
      if (!Object.keys(personaBindings).length) delete state.bindings[query.ownerUserId][query.personaId]
      if (!Object.keys(state.bindings[query.ownerUserId]).length) delete state.bindings[query.ownerUserId]
      await this.#write(state)
      return true
    })
    this.#queue = operation.catch(() => {})
    return operation
  }
}
