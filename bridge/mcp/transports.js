import crypto from 'crypto'

import { createTrustedRequestId } from './requestContext.js'

function mcpResult(id, result) {
  return { jsonrpc: '2.0', id, result }
}

export async function handleMcpMessage(message, {
  channel,
  serverName,
  transportIdentity,
}) {
  if (message.method === 'initialize') {
    return mcpResult(message.id, {
      protocolVersion: '2024-11-05',
      capabilities: { tools: {} },
      serverInfo: { name: serverName, version: '3.0.0-runtime' },
    })
  }
  if (message.method === 'notifications/initialized') return null
  if (message.method === 'tools/list') return mcpResult(message.id, { tools: channel.tools })
  if (message.method === 'tools/call') {
    const toolName = message.params?.name
    const requestId = createTrustedRequestId({
      actor: channel.actor,
      transportIdentity,
      protocolRequestId: message.id,
      toolName,
    })
    const text = await channel.callTool(
      toolName,
      message.params?.arguments || {},
      { requestId }
    )
    return mcpResult(message.id, { content: [{ type: 'text', text }] })
  }
  if (message.method === 'ping') return mcpResult(message.id, {})
  return {
    jsonrpc: '2.0',
    id: message.id,
    error: { code: -32601, message: `method not found: ${message.method}` },
  }
}

export function installMcpTransports(app, {
  gptChannel,
  claudeChannel,
  verifyGptRequest,
  verifyGptOAuth,
  verifyClaudeOAuth,
  checkRate,
  mcpBase,
}) {
  const gptSessions = new Map()

  app.get('/mcp/sse', (req, res) => {
    if (!verifyGptRequest(req)) return res.status(401).json({ error: 'unauthorized' })
    const clientId = `mcp_${crypto.randomBytes(24).toString('base64url')}`
    res.setHeader('Content-Type', 'text/event-stream')
    res.setHeader('Cache-Control', 'no-cache')
    res.setHeader('Connection', 'keep-alive')
    gptSessions.set(clientId, res)
    res.write(`event: endpoint\ndata: ${mcpBase}/mcp/message?clientId=${clientId}\n\n`)
    const keepAlive = setInterval(() => res.write(': ping\n\n'), 30_000)
    keepAlive.unref?.()
    res.on('close', () => {
      clearInterval(keepAlive)
      gptSessions.delete(clientId)
    })
  })

  app.post('/mcp/message', async (req, res) => {
    const clientId = String(req.query.clientId || '')
    const stream = gptSessions.get(clientId)
    if (!stream) {
      return res.status(400).json({
        jsonrpc: '2.0',
        error: { code: -32000, message: 'unknown or expired client session' },
      })
    }
    if (!checkRate(`gpt-mcp:${clientId}`)) return res.status(429).json({ error: 'too many requests' })
    try {
      const response = await handleMcpMessage(req.body, {
        channel: gptChannel,
        serverName: 'lovehouse-gpt-mcp',
        transportIdentity: `gpt-sse:${clientId}`,
      })
      if (response) stream.write(`event: message\ndata: ${JSON.stringify(response)}\n\n`)
      return res.status(202).end()
    } catch (error) {
      stream.write(`event: message\ndata: ${JSON.stringify({
        jsonrpc: '2.0',
        id: req.body?.id,
        error: { code: -32000, message: error.message },
      })}\n\n`)
      return res.status(202).end()
    }
  })

  function installAuthenticatedHttpTransport({ path, actorName, channel, verifyOAuth }) {
    app.post(path, verifyOAuth, async (req, res) => {
      if (!req.body?.jsonrpc) {
        return res.status(400).json({
          jsonrpc: '2.0',
          error: { code: -32700, message: 'invalid JSON-RPC' },
        })
      }
      try {
        const response = await handleMcpMessage(req.body, {
          channel,
          serverName: `lovehouse-${actorName}-mcp`,
          transportIdentity: `${actorName}-http:${req.oauth.client_id}:${req.oauth.jti}`,
        })
        return response ? res.json(response) : res.status(204).end()
      } catch (error) {
        return res.json({
          jsonrpc: '2.0',
          id: req.body.id,
          error: { code: -32000, message: error.message },
        })
      }
    })

    app.get(path, verifyOAuth, (_req, res) => {
      res.setHeader('Content-Type', 'text/event-stream')
      res.setHeader('Cache-Control', 'no-cache')
      res.setHeader('Connection', 'keep-alive')
      const keepAlive = setInterval(() => res.write(': ping\n\n'), 30_000)
      keepAlive.unref?.()
      res.on('close', () => clearInterval(keepAlive))
    })
    app.delete(path, verifyOAuth, (_req, res) => res.status(204).end())
  }

  installAuthenticatedHttpTransport({
    path: '/mcp/gpt',
    actorName: 'gpt',
    channel: gptChannel,
    verifyOAuth: verifyGptOAuth,
  })
  installAuthenticatedHttpTransport({
    path: '/mcp/claude',
    actorName: 'claude',
    channel: claudeChannel,
    verifyOAuth: verifyClaudeOAuth,
  })

  return { gptSessions }
}
