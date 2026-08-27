import { MediaRequestError } from './service.js'

function sendError(res, error) {
  if (error instanceof MediaRequestError) {
    return res.status(error.status).json({ error: error.code, message: error.message })
  }
  console.error('[media error]', error?.message || 'unknown error')
  return res.status(502).json({ error: 'MEDIA_SIGNING_FAILED', message: 'media URL signing failed' })
}

export function installMediaRoutes(app, { verifyOwner, mediaService }) {
  app.post('/v1/media/upload-url', verifyOwner, async (req, res) => {
    try {
      const result = await mediaService.createUploadUrl({
        ownerId: req.userId,
        filename: req.body?.filename,
        mimeType: req.body?.mime_type,
        size: req.body?.size,
      })
      res.setHeader('Cache-Control', 'no-store')
      return res.json(result)
    } catch (error) {
      return sendError(res, error)
    }
  })

  app.post('/v1/media/read-url', verifyOwner, async (req, res) => {
    try {
      const result = await mediaService.createReadUrl({
        ownerId: req.userId,
        objectKey: req.body?.object_key,
      })
      res.setHeader('Cache-Control', 'no-store')
      return res.json(result)
    } catch (error) {
      return sendError(res, error)
    }
  })
}
