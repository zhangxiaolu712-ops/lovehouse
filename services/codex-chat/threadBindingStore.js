import fs from 'node:fs/promises'
import path from 'node:path'

import { ChatRuntimeError } from './errors.js'

const THREAD_ID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i
const LEGACY_WINDOW_ID_RE = /^[A-Za-z0-9_-]{8,128}$/
const RUNTIME_ID_RE = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i

function validate({ ownerUserId, threadId, runtimeSessionId = null }) {
  if (typeof ownerUserId !== 'string' || !ownerUserId) {
    throw new ChatRuntimeError('AUTH_FAILED', 'Binding owner is invalid', { stage: 'storage' })
  }
  if (typeof threadId !== 'string' || (!THREAD_ID_RE.test(threadId) && !LEGACY_WINDOW_ID_RE.test(threadId))) {
    throw new ChatRuntimeError('SESSION_RECOVERY_FAILED', 'LoveHouse thread_id is invalid', {
      stage: 'session', status: 400,
    })
  }
  if (runtimeSessionId !== null && !RUNTIME_ID_RE.test(runtimeSessionId)) {
    throw new ChatRuntimeError('SESSION_RECOVERY_FAILED', 'Runtime session id is invalid', {
      stage: 'storage', status: 500,
    })
  }
}

function key({ ownerUserId, threadId }) {
  return `${ownerUserId}:${threadId}`
}

function normalizeCumulativeUsage(value) {
  if (!value || typeof value !== 'object') return null
  const token = item => (Number.isFinite(item) && item >= 0 ? item : null)
  const normalized = {
    input_tokens: token(value.input_tokens),
    output_tokens: token(value.output_tokens),
    cached_input_tokens: token(value.cached_input_tokens),
    reasoning_output_tokens: token(value.reasoning_output_tokens),
  }
  return Object.values(normalized).some(item => item !== null) ? normalized : null
}

function nextCumulativeUsage(binding, existing) {
  if (binding.cumulativeUsage !== undefined) {
    return normalizeCumulativeUsage(binding.cumulativeUsage)
  }
  return existing?.runtime_session_id === binding.runtimeSessionId
    ? existing.cumulative_usage || null
    : null
}

export class InMemoryThreadBindingStore {
  constructor() { this.bindings = new Map() }

  async get(query) {
    validate(query)
    return this.bindings.get(key(query)) || null
  }

  async save(binding) {
    validate(binding)
    const existing = this.bindings.get(key(binding)) || null
    const value = {
      runtime_session_id: binding.runtimeSessionId,
      runtime_type: 'codex_cli',
      updated_at: new Date().toISOString(),
      cumulative_usage: nextCumulativeUsage(binding, existing),
    }
    this.bindings.set(key(binding), value)
    return { ...value }
  }

  async delete(query) {
    validate(query)
    return this.bindings.delete(key(query))
  }
}

export class FileThreadBindingStore {
  #queue = Promise.resolve()

  constructor({ filePath }) {
    if (!path.isAbsolute(filePath || '')) throw new TypeError('Thread binding path must be absolute')
    this.filePath = filePath
  }

  async #read() {
    try {
      const state = JSON.parse(await fs.readFile(this.filePath, 'utf8'))
      if (state?.version !== 1 || typeof state.bindings !== 'object' || Array.isArray(state.bindings)) {
        throw new Error('unsupported binding state')
      }
      return state
    } catch (error) {
      if (error.code === 'ENOENT') return { version: 1, bindings: {} }
      throw new ChatRuntimeError('SESSION_RECOVERY_FAILED', 'Runtime binding state is unavailable', {
        stage: 'storage', status: 503, retryable: true, cause: error,
      })
    }
  }

  async #write(state) {
    const directory = path.dirname(this.filePath)
    const temporary = `${this.filePath}.${process.pid}.${Date.now()}.tmp`
    try {
      await fs.mkdir(directory, { recursive: true, mode: 0o700 })
      await fs.writeFile(temporary, `${JSON.stringify(state, null, 2)}\n`, { mode: 0o600 })
      await fs.rename(temporary, this.filePath)
      await fs.chmod(this.filePath, 0o600)
    } catch (cause) {
      await fs.rm(temporary, { force: true }).catch(() => {})
      throw new ChatRuntimeError('SESSION_RECOVERY_FAILED', 'Runtime binding could not be saved', {
        stage: 'storage', status: 503, retryable: true, cause,
      })
    }
  }

  async get(query) {
    validate(query)
    await this.#queue
    const state = await this.#read()
    const value = state.bindings[query.ownerUserId]?.[query.threadId]
    const runtimeSessionId = value?.runtime_session_id || value?.codexThreadId
    return runtimeSessionId ? {
      runtime_session_id: runtimeSessionId,
      runtime_type: value.runtime_type || 'codex_cli',
      updated_at: value.updated_at || value.updatedAt || null,
      cumulative_usage: normalizeCumulativeUsage(value.lastUsage || value.cumulative_usage),
    } : null
  }

  async save(binding) {
    validate(binding)
    const operation = this.#queue.then(async () => {
      const state = await this.#read()
      const existingValue = state.bindings[binding.ownerUserId]?.[binding.threadId]
      const existing = existingValue ? {
        runtime_session_id: existingValue.runtime_session_id || existingValue.codexThreadId,
        cumulative_usage: normalizeCumulativeUsage(
          existingValue.lastUsage || existingValue.cumulative_usage,
        ),
      } : null
      const cumulativeUsage = nextCumulativeUsage(binding, existing)
      const value = {
        // Preserve the production v1 file shape so this experiment can be
        // rolled back without migrating or invalidating existing bindings.
        codexThreadId: binding.runtimeSessionId,
        runtime_type: 'codex_cli',
        updatedAt: new Date().toISOString(),
        ...(cumulativeUsage ? { lastUsage: cumulativeUsage } : {}),
      }
      state.bindings[binding.ownerUserId] ||= {}
      state.bindings[binding.ownerUserId][binding.threadId] = value
      await this.#write(state)
      return {
        runtime_session_id: value.codexThreadId,
        runtime_type: value.runtime_type,
        updated_at: value.updatedAt,
        cumulative_usage: cumulativeUsage,
      }
    })
    this.#queue = operation.catch(() => {})
    return operation
  }

  async delete(query) {
    validate(query)
    const operation = this.#queue.then(async () => {
      const state = await this.#read()
      const owner = state.bindings[query.ownerUserId]
      if (!owner?.[query.threadId]) return false
      delete owner[query.threadId]
      if (!Object.keys(owner).length) delete state.bindings[query.ownerUserId]
      await this.#write(state)
      return true
    })
    this.#queue = operation.catch(() => {})
    return operation
  }
}
