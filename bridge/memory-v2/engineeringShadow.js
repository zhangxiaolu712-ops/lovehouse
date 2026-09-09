const FLOAT_TOLERANCE = 1e-12

function firstDifference(primary, shadow, path = '$') {
  if (typeof primary === 'number' && typeof shadow === 'number') {
    if (Number.isInteger(primary) && Number.isInteger(shadow)) {
      return primary === shadow ? null : { path, reason: 'integer_mismatch' }
    }
    const delta = Math.abs(primary - shadow)
    return delta <= FLOAT_TOLERANCE ? null : { path, reason: 'float_mismatch', delta }
  }
  if (Array.isArray(primary) || Array.isArray(shadow)) {
    if (!Array.isArray(primary) || !Array.isArray(shadow)) return { path, reason: 'type_mismatch' }
    if (primary.length !== shadow.length) return { path, reason: 'array_length_mismatch' }
    for (let index = 0; index < primary.length; index += 1) {
      const difference = firstDifference(primary[index], shadow[index], `${path}[${index}]`)
      if (difference) return difference
    }
    return null
  }
  if (primary && shadow && typeof primary === 'object' && typeof shadow === 'object') {
    const primaryKeys = Object.keys(primary).sort()
    const shadowKeys = Object.keys(shadow).sort()
    if (primaryKeys.join('\0') !== shadowKeys.join('\0')) return { path, reason: 'object_keys_mismatch' }
    for (const key of primaryKeys) {
      const difference = firstDifference(primary[key], shadow[key], `${path}.${key}`)
      if (difference) return difference
    }
    return null
  }
  return Object.is(primary, shadow) ? null : { path, reason: 'value_mismatch' }
}

export class EngineeringShadowMonitor {
  constructor({ logger = console } = {}) {
    this.logger = logger
    this.comparisonCount = 0
    this.mismatchCount = 0
    this.errorCount = 0
    this.lastMismatch = null
    this.pending = new Set()
  }

  observe(operation, primary, readShadow) {
    const work = Promise.resolve().then(readShadow).then(shadow => {
      this.comparisonCount += 1
      const difference = firstDifference(primary, shadow)
      if (!difference) return
      this.mismatchCount += 1
      this.lastMismatch = { operation, ...difference }
      this.logger.warn?.(`[engineering-shadow] mismatch operation=${operation} path=${difference.path} reason=${difference.reason} count=${this.mismatchCount}`)
    }).catch(error => {
      this.errorCount += 1
      this.logger.error?.(`[engineering-shadow] error operation=${operation} type=${error?.code || error?.name || 'Error'} count=${this.errorCount}`)
    }).finally(() => this.pending.delete(work))
    this.pending.add(work)
  }

  async drain() {
    await Promise.all([...this.pending])
  }

  snapshot() {
    return {
      enabled: true,
      comparison_count: this.comparisonCount,
      mismatch_count: this.mismatchCount,
      error_count: this.errorCount,
      last_mismatch: this.lastMismatch,
    }
  }
}

export class ShadowEngineeringRepository {
  constructor({ primary, shadow, monitor }) {
    this.primary = primary
    this.shadow = shadow
    this.monitor = monitor
  }

  async compare(operation, primaryRead, shadowRead) {
    const result = await primaryRead()
    this.monitor.observe(operation, result, shadowRead)
    return result
  }

  upsertEngineering(...args) { return this.primary.upsertEngineering(...args) }
  archiveEngineering(...args) { return this.primary.archiveEngineering(...args) }
  restoreEngineering(...args) { return this.primary.restoreEngineering(...args) }
  expandEngineeringSource(...args) { return this.primary.expandEngineeringSource(...args) }
  recallEngineering(...args) {
    return this.compare('recall', () => this.primary.recallEngineering(...args), () => this.shadow.recallEngineering(...args))
  }
  openEngineering(...args) {
    return this.compare('open', () => this.primary.openEngineering(...args), () => this.shadow.openEngineering(...args))
  }
}

export class ShadowProjectChecklistStore {
  constructor({ primary, shadow, monitor }) {
    this.primary = primary
    this.shadow = shadow
    this.monitor = monitor
  }

  async load(ownerId) {
    const result = await this.primary.load(ownerId)
    this.monitor.observe('checklist_load', result, () => this.shadow.loadChecklist(ownerId))
    return result
  }
  save(...args) { return this.primary.save(...args) }
  delete(...args) { return this.primary.delete(...args) }
  migrateLocalV1(...args) { return this.primary.migrateLocalV1(...args) }
}

export { firstDifference }
