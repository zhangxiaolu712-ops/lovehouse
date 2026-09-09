import pg from 'pg'

import { boundedLimit, fixedEngineeringActor } from './repository.js'

const CALLS = Object.freeze({
  upsert: 'select public.memory_v2_engineering_upsert($1::uuid, $2::text, $3::text, $4::text, $5::jsonb) as result',
  recall: 'select public.memory_v2_engineering_recall($1::uuid, $2::text, $3::text, $4::integer, $5::boolean) as result',
  open: 'select public.memory_v2_engineering_open($1::uuid, $2::text, $3::text) as result',
  expandSource: 'select public.memory_v2_engineering_expand_source($1::uuid, $2::text, $3::uuid) as result',
  archive: 'select public.memory_v2_engineering_archive($1::uuid, $2::text, $3::text) as result',
  restore: 'select public.memory_v2_engineering_restore($1::uuid, $2::text, $3::text) as result',
  checklistLoad: 'select public.engineering_project_checklist_load($1::uuid) as result',
  checklistSave: 'select public.engineering_project_checklist_save($1::uuid, $2::jsonb) as result',
  checklistDelete: 'select public.engineering_project_checklist_delete($1::uuid, $2::text) as result',
  checklistMigrate: 'select public.engineering_project_checklist_migrate_local_v1($1::uuid, $2::jsonb) as result',
})

export class PostgresEngineeringRepository {
  constructor({ connectionString, ownerId, pool = null, ssl = { rejectUnauthorized: false } }) {
    if (!ownerId) throw new TypeError('A fixed owner id is required')
    if (!pool && !connectionString) throw new TypeError('ENGINEERING_DATABASE_URL is required')
    this.ownerId = ownerId
    this.pool = pool || new pg.Pool({ connectionString, ssl, max: 5, idleTimeoutMillis: 30_000 })
  }

  async call(sql, values) {
    const result = await this.pool.query(sql, values)
    return result.rows[0]?.result ?? null
  }

  fixedOwner(ownerId) {
    if (ownerId !== this.ownerId) throw new TypeError('Checklist owner does not match the configured owner')
    return this.ownerId
  }

  upsertEngineering(actor, subjectKey, content, options = {}) {
    return this.call(CALLS.upsert, [this.ownerId, fixedEngineeringActor(actor), subjectKey, content, options])
  }

  recallEngineering(actor, { query = '', limit = 30, includeArchived = false } = {}) {
    return this.call(CALLS.recall, [
      this.ownerId, fixedEngineeringActor(actor), String(query),
      boundedLimit(limit, 30, 50), includeArchived === true,
    ])
  }

  openEngineering(actor, subjectKey) {
    return this.call(CALLS.open, [this.ownerId, fixedEngineeringActor(actor), subjectKey])
  }

  expandEngineeringSource(actor, sourceId) {
    return this.call(CALLS.expandSource, [this.ownerId, fixedEngineeringActor(actor), sourceId])
  }

  archiveEngineering(actor, subjectKey) {
    return this.call(CALLS.archive, [this.ownerId, fixedEngineeringActor(actor), subjectKey])
  }

  restoreEngineering(actor, subjectKey) {
    return this.call(CALLS.restore, [this.ownerId, fixedEngineeringActor(actor), subjectKey])
  }

  loadChecklist(ownerId) {
    return this.call(CALLS.checklistLoad, [this.fixedOwner(ownerId)])
  }

  saveChecklist(ownerId, item) {
    return this.call(CALLS.checklistSave, [this.fixedOwner(ownerId), item])
  }

  deleteChecklist(ownerId, itemKey) {
    return this.call(CALLS.checklistDelete, [this.fixedOwner(ownerId), itemKey])
  }

  migrateChecklistLocalV1(ownerId, items) {
    return this.call(CALLS.checklistMigrate, [this.fixedOwner(ownerId), items])
  }
}
