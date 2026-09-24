import crypto from 'node:crypto'
import fs from 'node:fs/promises'
import path from 'node:path'

import { ClientApiError, normalizeClientApiError } from './errors.js'

const EXECUTION_ID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const TERMINAL = new Set(['completed', 'failed'])

function emptyState() {
  return { version: 1, records: {} }
}

function clone(value) {
  return value == null ? value : structuredClone(value)
}

function validateIdentity({ ownerUserId, executionId }) {
  if (typeof ownerUserId !== 'string' || !ownerUserId || ownerUserId.length > 128) {
    throw new ClientApiError('CHAT_EXECUTION_OWNER_INVALID', 'Chat execution owner is invalid', {
      stage: 'storage', status: 500,
    })
  }
  if (typeof executionId !== 'string' || !EXECUTION_ID_RE.test(executionId)) {
    throw new ClientApiError('INVALID_EXECUTION_ID', 'execution_id must be a UUID', {
      stage: 'validation', status: 400,
    })
  }
}

function validateState(state) {
  if (!state || state.version !== 1 || typeof state.records !== 'object' || Array.isArray(state.records)) {
    throw new Error('unsupported chat execution file shape')
  }
  return state
}

function keyOf(ownerUserId, executionId) {
  return crypto.createHash('sha256').update(`${ownerUserId}\0${executionId}`).digest('hex')
}

export function chatExecutionFingerprint(value) {
  return crypto.createHash('sha256').update(JSON.stringify(value)).digest('hex')
}

export class FileChatExecutionStore {
  #queue = Promise.resolve()
  #ready

  constructor({
    filePath,
    now = () => Date.now(),
    terminalTtlMs = 72 * 60 * 60 * 1000,
    cleanupIntervalMs = 60 * 60 * 1000,
  } = {}) {
    if (!path.isAbsolute(filePath || '')) throw new TypeError('Chat execution file path must be absolute')
    this.filePath = filePath
    this.now = now
    this.terminalTtlMs = terminalTtlMs
    this.#ready = this.#reconcileAfterRestart()
    if (cleanupIntervalMs > 0) {
      this.cleanupTimer = setInterval(() => this.cleanup().catch(() => {}), cleanupIntervalMs)
      this.cleanupTimer.unref?.()
    }
  }

  async #read() {
    try {
      return validateState(JSON.parse(await fs.readFile(this.filePath, 'utf8')))
    } catch (error) {
      if (error.code === 'ENOENT') return emptyState()
      throw new ClientApiError('CHAT_EXECUTION_READ_FAILED', 'Chat execution recovery state is unavailable', {
        stage: 'storage', status: 503, retryable: true, cause: error,
      })
    }
  }

  async #write(state) {
    const directory = path.dirname(this.filePath)
    const temporary = `${this.filePath}.${process.pid}.${Date.now()}.tmp`
    try {
      await fs.mkdir(directory, { recursive: true, mode: 0o700 })
      await fs.chmod(directory, 0o700)
      await fs.writeFile(temporary, `${JSON.stringify(state)}\n`, { mode: 0o600 })
      await fs.rename(temporary, this.filePath)
      await fs.chmod(this.filePath, 0o600)
    } catch (error) {
      await fs.rm(temporary, { force: true }).catch(() => {})
      throw new ClientApiError('CHAT_EXECUTION_WRITE_FAILED', 'Chat execution recovery state could not be saved', {
        stage: 'storage', status: 503, retryable: true, cause: error,
      })
    }
  }

  #cleanup(state, timestamp = this.now()) {
    const cutoff = timestamp - this.terminalTtlMs
    for (const [key, record] of Object.entries(state.records)) {
      if (TERMINAL.has(record.status) && Date.parse(record.updated_at) < cutoff) delete state.records[key]
    }
  }

  async #reconcileAfterRestart() {
    const operation = this.#queue.then(async () => {
      const state = await this.#read()
      const timestamp = this.now()
      let changed = false
      for (const record of Object.values(state.records)) {
        if (record.status !== 'running') continue
        record.status = 'failed'
        record.error = {
          code: 'EXECUTION_INTERRUPTED_BY_RUNTIME_RESTART',
          message: 'Chat execution was interrupted by a runtime restart',
        }
        record.updated_at = new Date(timestamp).toISOString()
        changed = true
      }
      const before = Object.keys(state.records).length
      this.#cleanup(state, timestamp)
      if (changed || Object.keys(state.records).length !== before) await this.#write(state)
    })
    this.#queue = operation.catch(() => {})
    return operation
  }

  async reserve(record) {
    validateIdentity(record)
    await this.#ready
    const operation = this.#queue.then(async () => {
      const state = await this.#read()
      this.#cleanup(state)
      const key = keyOf(record.ownerUserId, record.executionId)
      const current = state.records[key]
      if (current) {
        if (current.input_fingerprint !== record.inputFingerprint ||
          current.thread_id !== record.threadId || current.provider !== record.provider) {
          throw new ClientApiError('EXECUTION_ID_CONFLICT', 'execution_id is already bound to another chat request', {
            stage: 'validation', status: 409,
          })
        }
        return { created: false, record: clone(current) }
      }
      const timestamp = new Date(this.now()).toISOString()
      const value = {
        execution_id: record.executionId,
        thread_id: record.threadId,
        provider: record.provider,
        status: 'running',
        input_fingerprint: record.inputFingerprint,
        created_at: timestamp,
        updated_at: timestamp,
      }
      state.records[key] = value
      await this.#write(state)
      return { created: true, record: clone(value) }
    })
    this.#queue = operation.catch(() => {})
    return operation
  }

  async cleanup() {
    await this.#ready
    const operation = this.#queue.then(async () => {
      const state = await this.#read()
      const before = Object.keys(state.records).length
      this.#cleanup(state)
      if (Object.keys(state.records).length !== before) await this.#write(state)
    })
    this.#queue = operation.catch(() => {})
    return operation
  }

  async get(query) {
    validateIdentity(query)
    await this.#ready
    await this.#queue
    const state = await this.#read()
    return clone(state.records[keyOf(query.ownerUserId, query.executionId)] || null)
  }

  async complete(query, result) {
    return this.#finish(query, { status: 'completed', result: clone(result), error: undefined })
  }

  async fail(query, error) {
    const normalized = normalizeClientApiError(error, {
      code: 'CHAT_EXECUTION_FAILED',
      message: 'Chat execution failed',
      stage: 'provider',
    })
    return this.#finish(query, {
      status: 'failed',
      result: undefined,
      error: {
        code: normalized.code,
        message: normalized.message.slice(0, 500),
      },
    })
  }

  async #finish(query, terminal) {
    validateIdentity(query)
    await this.#ready
    const operation = this.#queue.then(async () => {
      const state = await this.#read()
      const record = state.records[keyOf(query.ownerUserId, query.executionId)]
      if (!record) throw new ClientApiError('CHAT_EXECUTION_NOT_FOUND', 'Chat execution was not found', {
        stage: 'storage', status: 404,
      })
      if (TERMINAL.has(record.status)) return clone(record)
      Object.assign(record, terminal, { updated_at: new Date(this.now()).toISOString() })
      if (terminal.result === undefined) delete record.result
      if (terminal.error === undefined) delete record.error
      await this.#write(state)
      return clone(record)
    })
    this.#queue = operation.catch(() => {})
    return operation
  }
}

export class ChatExecutionCoordinator {
  constructor({ store, log = console } = {}) {
    if (!store) throw new TypeError('Chat execution coordinator requires a store')
    this.store = store
    this.log = log
    this.active = new Map()
  }

  #key(ownerUserId, executionId) {
    return `${ownerUserId}\0${executionId}`
  }

  async observeOrStart({ ownerUserId, executionId, threadId, provider, inputFingerprint, execute, onEvent }) {
    const reserved = await this.store.reserve({ ownerUserId, executionId, threadId, provider, inputFingerprint })
    const key = this.#key(ownerUserId, executionId)
    let active = this.active.get(key)
    if (!reserved.created && !active) {
      onEvent?.({ type: 'terminal', record: reserved.record })
      return { record: reserved.record, finished: Promise.resolve(reserved.record), unsubscribe() {} }
    }
    if (reserved.created) {
      active = { listeners: new Set(), promise: null }
      this.active.set(key, active)
    }
    if (onEvent) active.listeners.add(onEvent)
    const unsubscribe = () => active.listeners.delete(onEvent)
    if (reserved.created) {
      active.promise = this.#run({ key, active, ownerUserId, executionId, provider, threadId, execute })
    }
    return { record: reserved.record, finished: active.promise, unsubscribe }
  }

  async #run({ key, active, ownerUserId, executionId, provider, threadId, execute }) {
    const emit = event => {
      for (const listener of active.listeners) {
        try { listener(event) } catch {}
      }
    }
    this.log.info?.('[chat-execution]', JSON.stringify({ execution_id: executionId, thread_id: threadId, provider, status: 'running' }))
    try {
      const result = await execute(event => emit({ type: 'stream', ...event }))
      const record = await this.store.complete({ ownerUserId, executionId }, result)
      emit({ type: 'terminal', record })
      this.log.info?.('[chat-execution]', JSON.stringify({ execution_id: executionId, thread_id: threadId, provider, status: 'completed' }))
      return record
    } catch (error) {
      const record = await this.store.fail({ ownerUserId, executionId }, error)
      emit({ type: 'terminal', record })
      this.log.warn?.('[chat-execution]', JSON.stringify({
        execution_id: executionId, thread_id: threadId, provider, status: 'failed',
        error_code: record.error?.code || 'CHAT_EXECUTION_FAILED',
      }))
      return record
    } finally {
      this.active.delete(key)
      active.listeners.clear()
    }
  }
}
