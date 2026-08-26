const ACTORS = new Set(['gpt', 'claude'])

function boundedLimit(value) {
  const parsed = Number.parseInt(value, 10)
  if (!Number.isFinite(parsed) || parsed < 1) return 60
  return Math.min(parsed, 100)
}

export function installMemoryTimeline(app, { verifyOwner, memoryV2Repository }) {
  if (!app || typeof app.get !== 'function') throw new TypeError('Memory timeline requires an Express app')
  if (typeof verifyOwner !== 'function') throw new TypeError('Memory timeline requires Owner auth')
  if (!memoryV2Repository || typeof memoryV2Repository.rest !== 'function') throw new TypeError('Memory timeline requires Memory V2 repository')

  app.get('/v1/memory/:actor/timeline', verifyOwner, async (req, res) => {
    const actor = req.params.actor
    if (!ACTORS.has(actor)) return res.status(404).json({ error: { message: 'Unknown memory actor' } })
    const limit = boundedLimit(req.query.limit)
    try {
      const rows = await memoryV2Repository.rest('GET', [
        'memory_v2_entries?select=id,space_key,status,shared_status,current_revision_id,created_at,updated_at,memory_v2_revisions!memory_v2_entries_current_revision_fk(id,revision_number,content,event_time,metadata,created_at)',
        `&owner_id=eq.${encodeURIComponent(req.userId)}`,
        `&space_key=eq.${actor}`,
        '&status=eq.active&superseded_by_id=is.null',
        '&order=created_at.desc',
        `&limit=${limit}`,
      ].join(''))
      const items = (Array.isArray(rows) ? rows : []).map(row => {
        const revision = Array.isArray(row.memory_v2_revisions) ? row.memory_v2_revisions[0] : row.memory_v2_revisions
        return {
          memory_id: row.id,
          revision_id: revision?.id || row.current_revision_id,
          revision_number: revision?.revision_number || null,
          content: revision?.content || '',
          event_time: revision?.event_time || null,
          metadata: revision?.metadata || {},
          created_at: row.created_at,
          revision_created_at: revision?.created_at || null,
          space_key: row.space_key,
          status: row.status,
          source_count: 0,
        }
      })
      res.setHeader('Cache-Control', 'no-store')
      return res.json({ ok: true, actor, items })
    } catch (error) {
      return res.status(500).json({ error: { message: error.message } })
    }
  })
}
