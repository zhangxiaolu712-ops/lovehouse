import fs from 'node:fs'
import path from 'node:path'

const DEFAULT_TTL_MS = 72 * 60 * 60 * 1000

export class TransientThreadStore {
  constructor({ ttlMs = DEFAULT_TTL_MS, now = () => Date.now() } = {}) {
    this.ttlMs = ttlMs
    this.now = now
    this.records = new Map()
  }

  append(threadId, event) {
    const current = this.read(threadId)
    this.records.set(threadId, {
      expiresAt: this.now() + this.ttlMs,
      events: [...current, { ...event, at: new Date(this.now()).toISOString() }].slice(-200),
    })
  }

  read(threadId) {
    const record = this.records.get(threadId)
    if (!record) return []
    if (record.expiresAt <= this.now()) {
      this.records.delete(threadId)
      return []
    }
    return record.events
  }
}

export class FileTransientThreadStore extends TransientThreadStore {
  constructor({ filePath, cleanupIntervalMs, ...options } = {}) {
    super(options)
    if (!path.isAbsolute(filePath || '')) throw new TypeError('transient thread file path must be absolute')
    this.filePath = filePath
    this.lockPath = `${filePath}.lock`
    this.ensureDirectory()
    this.loadFromDisk()
    const interval = cleanupIntervalMs ?? Math.min(this.ttlMs, 60 * 60 * 1000)
    this.cleanupTimer = interval > 0 ? setInterval(() => this.cleanupExpired(), interval) : null
    this.cleanupTimer?.unref?.()
  }

  ensureDirectory() {
    fs.mkdirSync(path.dirname(this.filePath), { recursive: true, mode: 0o700 })
    fs.chmodSync(path.dirname(this.filePath), 0o700)
  }

  loadFromDisk({ includeExpired = false } = {}) {
    this.records.clear()
    try {
      const parsed = JSON.parse(fs.readFileSync(this.filePath, 'utf8'))
      for (const [threadId, record] of Object.entries(parsed.threads || {})) {
        if (Number.isFinite(record.expiresAt) && (includeExpired || record.expiresAt > this.now()) && Array.isArray(record.events)) {
          this.records.set(threadId, record)
        }
      }
      fs.chmodSync(this.filePath, 0o600)
    } catch (error) {
      if (error.code !== 'ENOENT') throw error
    }
  }

  append(threadId, event) {
    return this.withLock(() => {
      this.loadFromDisk()
      const current = this.records.get(threadId)?.events || []
      this.records.set(threadId, {
        expiresAt: this.now() + this.ttlMs,
        events: [...current, { ...event, at: new Date(this.now()).toISOString() }].slice(-200),
      })
      this.persistAtomic()
    })
  }

  read(threadId) {
    this.loadFromDisk()
    return super.read(threadId)
  }

  cleanupExpired() {
    return this.withLock(() => {
      this.loadFromDisk({ includeExpired: true })
      const before = this.records.size
      for (const [threadId, record] of this.records) {
        if (record.expiresAt <= this.now()) this.records.delete(threadId)
      }
      if (this.records.size !== before) this.persistAtomic()
      return before - this.records.size
    })
  }

  persistAtomic() {
    const temporary = `${this.filePath}.${process.pid}.${Math.random().toString(16).slice(2)}.tmp`
    fs.writeFileSync(temporary, JSON.stringify({ version: 1, threads: Object.fromEntries(this.records) }), { mode: 0o600 })
    fs.renameSync(temporary, this.filePath)
    fs.chmodSync(this.filePath, 0o600)
  }

  withLock(action) {
    let descriptor
    for (let attempt = 0; attempt < 100; attempt += 1) {
      try {
        descriptor = fs.openSync(this.lockPath, 'wx', 0o600)
        break
      } catch (error) {
        if (error.code !== 'EEXIST') throw error
        try {
          if (this.now() - fs.statSync(this.lockPath).mtimeMs > 30_000) fs.unlinkSync(this.lockPath)
        } catch (statError) {
          if (statError.code !== 'ENOENT') throw statError
        }
        Atomics.wait(new Int32Array(new SharedArrayBuffer(4)), 0, 0, 10)
      }
    }
    if (descriptor === undefined) throw new Error('transient thread store lock timeout')
    try {
      return action()
    } finally {
      fs.closeSync(descriptor)
      try { fs.unlinkSync(this.lockPath) } catch (error) { if (error.code !== 'ENOENT') throw error }
    }
  }

  close() {
    if (this.cleanupTimer) clearInterval(this.cleanupTimer)
    this.cleanupTimer = null
  }
}
