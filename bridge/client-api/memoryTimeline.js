const ACTORS = new Set(['gpt', 'claude'])

function fixedActor(value) {
  if (!ACTORS.has(value)) {
    const error = new TypeError('Unknown memory actor')
    error.status = 404
    throw error
  }
  return value
}

function boundedLimit(value) {
  const parsed = Number.parseInt(value, 10)
  if (!Number.isFinite(parsed) || parsed < 1) return 60
  return Math.min(parsed, 100)
}

function requestInput(body = {}) {
  return {
    content: body.content,
    metadata: body.metadata,
    eventTime: body.event_time,
    humanImportance: body.human_importance,
    aiImportance: body.ai_importance,
    reason: body.reason,
    sources: body.sources,
  }
}

function sendMemoryError(res, error) {
  return res.status(error?.status || (error instanceof TypeError ? 400 : 500)).json({
    ok: false,
    error: { message: error?.message || 'Memory V2 request failed' },
  })
}

export function installMemoryTimeline(app, { memoryV2Repository, memoryV2Service }) {
  if (!app || typeof app.get !== 'function') throw new TypeError('Memory timeline requires an Express app')
  if (!memoryV2Repository || typeof memoryV2Repository.timeline !== 'function') {
    throw new TypeError('Memory timeline requires Memory V2 repository')
  }
  if (!memoryV2Service || typeof memoryV2Service.forActor !== 'function') {
    throw new TypeError('Memory timeline requires Memory V2 service')
  }

  app.get('/v1/memory/:actor/timeline', async (req, res) => {
    try {
      const actor = fixedActor(req.params.actor)
      const items = await memoryV2Repository.timeline(actor, {
        limit: boundedLimit(req.query.limit),
        query: req.query.query || '',
      })
      res.setHeader('Cache-Control', 'no-store')
      return res.json({ ok: true, actor, items })
    } catch (error) { return sendMemoryError(res, error) }
  })

  app.post('/v1/memory/:actor', async (req, res) => {
    try {
      const actor = fixedActor(req.params.actor)
      const result = await memoryV2Service.forActor(actor).remember(requestInput(req.body))
      return res.status(201).json({ ok: true, actor, ...result })
    } catch (error) { return sendMemoryError(res, error) }
  })

  app.post('/v1/memory/:actor/:memoryId/revise', async (req, res) => {
    try {
      const actor = fixedActor(req.params.actor)
      const result = await memoryV2Service.forActor(actor).revise(req.params.memoryId, requestInput(req.body))
      return res.json({ ok: true, actor, ...result })
    } catch (error) { return sendMemoryError(res, error) }
  })

  app.get('/v1/memory/:actor/:memoryId/history', async (req, res) => {
    try {
      const actor = fixedActor(req.params.actor)
      const history = await memoryV2Service.forActor(actor).history(req.params.memoryId)
      res.setHeader('Cache-Control', 'no-store')
      return res.json({ ok: true, actor, history })
    } catch (error) { return sendMemoryError(res, error) }
  })

  app.get('/v1/memory/:actor/sources/:sourceId', async (req, res) => {
    try {
      const actor = fixedActor(req.params.actor)
      const source = await memoryV2Service.forActor(actor).expandSource(req.params.sourceId)
      res.setHeader('Cache-Control', 'no-store')
      return res.json({ ok: true, actor, source })
    } catch (error) { return sendMemoryError(res, error) }
  })

  app.post('/v1/memory/:actor/:memoryId/archive', async (req, res) => {
    try {
      const actor = fixedActor(req.params.actor)
      const result = await memoryV2Repository.archive(actor, req.params.memoryId)
      return res.json({ ok: true, actor, ...result })
    } catch (error) { return sendMemoryError(res, error) }
  })
}
