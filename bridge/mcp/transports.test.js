import test from 'node:test'
import assert from 'node:assert/strict'
import http from 'node:http'

import express from 'express'

import { installMcpTransports } from './transports.js'

async function readUntil(reader, pattern, initial = '') {
  const decoder = new TextDecoder()
  let text = initial
  while (!text.includes(pattern)) {
    const { value, done } = await reader.read()
    if (done) break
    text += decoder.decode(value, { stream: true })
  }
  return text
}

test('real GPT SSE and Claude HTTP transports reach symmetric fixed actors', async t => {
  const calls = []
  const makeChannel = actor => ({
    actor,
    tools: [{ name: 'remember', inputSchema: { type: 'object' } }],
    async callTool(name, args, context) {
      calls.push({ actor, name, args, context })
      return JSON.stringify({ ok: true, actor })
    },
  })
  const app = express()
  app.use(express.json())
  const server = http.createServer(app)
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  const port = server.address().port
  const base = `http://127.0.0.1:${port}`
  installMcpTransports(app, {
    gptChannel: makeChannel('gpt'),
    claudeChannel: makeChannel('claude'),
    verifyGptRequest: () => true,
    verifyClaudeOAuth(req, _res, next) {
      req.oauth = { client_id: 'claude-client', jti: 'signed-token-id' }
      next()
    },
    checkRate: () => true,
    mcpBase: base,
  })

  const abort = new AbortController()
  t.after(async () => {
    abort.abort()
    await new Promise(resolve => server.close(resolve))
  })

  const sse = await fetch(`${base}/mcp/sse?key=test`, { signal: abort.signal })
  assert.equal(sse.status, 200)
  const reader = sse.body.getReader()
  const endpointEvent = await readUntil(reader, '\n\n')
  const endpoint = endpointEvent.match(/data: (.+)\n/)?.[1]
  assert.ok(endpoint)

  const forged = {
    jsonrpc: '2.0',
    id: 77,
    method: 'tools/call',
    params: {
      name: 'remember',
      arguments: { content: 'transport test', actor: 'owner', space_key: 'shared' },
    },
    actor: 'owner',
  }
  const gptPost = await fetch(endpoint, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', 'x-memory-actor': 'claude' },
    body: JSON.stringify(forged),
  })
  assert.equal(gptPost.status, 202)
  const messageEvent = await readUntil(reader, 'event: message')
  assert.match(messageEvent, /"id":77/)

  const gptReplay = await fetch(endpoint, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify(forged),
  })
  assert.equal(gptReplay.status, 202)
  await readUntil(reader, 'event: message')

  const claudePost = await fetch(`${base}/mcp/claude`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer test' },
    body: JSON.stringify(forged),
  })
  assert.equal(claudePost.status, 200)
  assert.equal((await claudePost.json()).id, 77)

  assert.deepEqual(calls.map(call => call.actor), ['gpt', 'gpt', 'claude'])
  assert.match(calls[0].context.requestId, /^[0-9a-f-]{36}$/)
  assert.equal(calls[0].context.requestId, calls[1].context.requestId)
  assert.match(calls[2].context.requestId, /^[0-9a-f-]{36}$/)
  assert.notEqual(calls[0].context.requestId, calls[2].context.requestId)
  assert.equal('actor' in calls[0].context, false)
  assert.equal('space_key' in calls[0].context, false)
})
