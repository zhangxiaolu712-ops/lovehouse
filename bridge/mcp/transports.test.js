import test from 'node:test'
import assert from 'node:assert/strict'
import http from 'node:http'

import express from 'express'

import { handleMcpMessage, installMcpTransports } from './transports.js'

const TOOL_NAMES = [
  'wake_up',
  'remember',
  'recall',
  'revise',
  'open_memory',
  'read_livingroom',
  'say_livingroom',
]

function assertCalledAt(value) {
  assert.equal(typeof value, 'string')
  assert.match(value, /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}\.\d{3}\+08:00$/)
  assert.equal(Number.isNaN(Date.parse(value)), false)
}

test('all seven GPT and Claude tool results preserve fields and add UTC+8 called_at', async () => {
  for (const actor of ['gpt', 'claude']) {
    const channel = {
      actor,
      tools: TOOL_NAMES.map(name => ({ name })),
      async callTool(name) {
        return JSON.stringify({ ok: true, actor, tool: name, nested: { preserved: true } })
      },
    }

    for (const [index, name] of TOOL_NAMES.entries()) {
      const response = await handleMcpMessage({
        jsonrpc: '2.0',
        id: `${actor}-${index}`,
        method: 'tools/call',
        params: { name, arguments: {} },
      }, {
        channel,
        serverName: `lovehouse-${actor}-mcp`,
        transportIdentity: `${actor}-test`,
      })
      const payload = JSON.parse(response.result.content[0].text)

      assert.equal(payload.ok, true)
      assert.equal(payload.actor, actor)
      assert.equal(payload.tool, name)
      assert.deepEqual(payload.nested, { preserved: true })
      assertCalledAt(payload.called_at)
    }
  }
})

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

test('legacy GPT SSE plus OAuth GPT and Claude HTTP transports reach fixed actors', async t => {
  const calls = []
  const makeChannel = actor => ({
    actor,
    tools: [{ name: 'remember', inputSchema: { type: 'object' } }],
    async callTool(name, args, context) {
      calls.push({ actor, name, args, context })
      if (name === 'explode') throw new Error('tool exploded')
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
    verifyGptOAuth(req, _res, next) {
      req.oauth = { client_id: 'gpt-client', jti: 'gpt-signed-token-id' }
      next()
    },
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

  const gptOAuthPost = await fetch(`${base}/mcp/gpt`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer test' },
    body: JSON.stringify(forged),
  })
  assert.equal(gptOAuthPost.status, 200)
  const gptOAuthResponse = await gptOAuthPost.json()
  assert.equal(gptOAuthResponse.id, 77)
  const gptToolPayload = JSON.parse(gptOAuthResponse.result.content[0].text)
  assert.deepEqual(
    { ok: gptToolPayload.ok, actor: gptToolPayload.actor },
    { ok: true, actor: 'gpt' }
  )
  assertCalledAt(gptToolPayload.called_at)

  const failedToolPost = await fetch(`${base}/mcp/claude`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer test' },
    body: JSON.stringify({
      jsonrpc: '2.0',
      id: 78,
      method: 'tools/call',
      params: { name: 'explode', arguments: {} },
    }),
  })
  const failedToolResponse = await failedToolPost.json()
  assert.equal(failedToolResponse.id, 78)
  assert.equal(failedToolResponse.error.message, 'tool exploded')
  assertCalledAt(failedToolResponse.error.data.called_at)

  assert.deepEqual(calls.map(call => call.actor), ['gpt', 'gpt', 'claude', 'gpt', 'claude'])
  assert.match(calls[0].context.requestId, /^[0-9a-f-]{36}$/)
  assert.equal(calls[0].context.requestId, calls[1].context.requestId)
  assert.match(calls[2].context.requestId, /^[0-9a-f-]{36}$/)
  assert.notEqual(calls[0].context.requestId, calls[2].context.requestId)
  assert.equal('actor' in calls[0].context, false)
  assert.equal('space_key' in calls[0].context, false)
  assert.match(calls[3].context.requestId, /^[0-9a-f-]{36}$/)
  assert.notEqual(calls[2].context.requestId, calls[3].context.requestId)
})
