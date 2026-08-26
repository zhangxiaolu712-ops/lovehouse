function sendError(res, error) {
  return res.status(error instanceof TypeError ? 400 : 500).json({
    ok: false, error: { message: error?.message || 'Project checklist request failed' },
  })
}

function unwrap(payload) {
  return Array.isArray(payload) && payload.length === 1 ? payload[0] : payload
}

export class ProjectChecklistStore {
  constructor({ rest }) {
    if (typeof rest !== 'function') throw new TypeError('Project checklist requires Supabase REST')
    this.rest = rest
  }
  load(ownerId) {
    return this.rest('POST', 'rpc/engineering_project_checklist_load', { p_owner_id: ownerId }).then(unwrap)
  }
  save(ownerId, item) {
    return this.rest('POST', 'rpc/engineering_project_checklist_save', { p_owner_id: ownerId, p_item: item }).then(unwrap)
  }
  delete(ownerId, itemKey) {
    return this.rest('POST', 'rpc/engineering_project_checklist_delete', { p_owner_id: ownerId, p_item_key: itemKey }).then(unwrap)
  }
  migrateLocalV1(ownerId, items) {
    return this.rest('POST', 'rpc/engineering_project_checklist_migrate_local_v1', { p_owner_id: ownerId, p_items: items }).then(unwrap)
  }
}

export function installProjectChecklistApi(app, { store }) {
  if (!store) throw new TypeError('Project checklist store is required')
  app.get('/v1/engineering/project-checklist', async (req, res) => {
    try { return res.json({ ok: true, ...(await store.load(req.userId)) }) } catch (error) { return sendError(res, error) }
  })
  app.put('/v1/engineering/project-checklist/items/:itemKey', async (req, res) => {
    try {
      const item = { ...req.body, id: req.params.itemKey }
      return res.json({ ok: true, item: await store.save(req.userId, item) })
    } catch (error) { return sendError(res, error) }
  })
  app.delete('/v1/engineering/project-checklist/items/:itemKey', async (req, res) => {
    try { return res.json({ ok: true, deleted: await store.delete(req.userId, req.params.itemKey) }) } catch (error) { return sendError(res, error) }
  })
  app.post('/v1/engineering/project-checklist/migrate-local-v1', async (req, res) => {
    try {
      const items = Array.isArray(req.body?.items) ? req.body.items : []
      return res.json({ ok: true, ...(await store.migrateLocalV1(req.userId, items)) })
    } catch (error) { return sendError(res, error) }
  })
}
