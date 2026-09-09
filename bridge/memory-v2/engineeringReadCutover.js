export class ReadCutoverEngineeringRepository {
  constructor({ legacy, railway }) {
    if (!legacy || !railway) throw new TypeError('Engineering read cutover requires legacy and Railway repositories')
    this.legacy = legacy
    this.railway = railway
  }

  recallEngineering(...args) { return this.railway.recallEngineering(...args) }
  openEngineering(...args) { return this.railway.openEngineering(...args) }
  expandEngineeringSource(...args) { return this.railway.expandEngineeringSource(...args) }

  upsertEngineering(...args) { return this.legacy.upsertEngineering(...args) }
  archiveEngineering(...args) { return this.legacy.archiveEngineering(...args) }
  restoreEngineering(...args) { return this.legacy.restoreEngineering(...args) }
}

export class ReadCutoverProjectChecklistStore {
  constructor({ legacy, railway }) {
    if (!legacy || !railway) throw new TypeError('Checklist read cutover requires legacy and Railway repositories')
    this.legacy = legacy
    this.railway = railway
  }

  load(ownerId) { return this.railway.loadChecklist(ownerId) }
  save(...args) { return this.legacy.save(...args) }
  delete(...args) { return this.legacy.delete(...args) }
  migrateLocalV1(...args) { return this.legacy.migrateLocalV1(...args) }
}
