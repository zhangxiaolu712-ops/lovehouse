import assert from 'node:assert/strict'
import { once } from 'node:events'
import http from 'node:http'
import test from 'node:test'

import express from 'express'

import { createChatRuntimeServer } from '../../services/codex-chat/app.js'
import { unknownQuota } from '../../services/codex-chat/runtimeContract.js'
import { createClientOwnerAuth, installClientApi } from './clientApi.js'
import { createPersonaRegistry } from './personas.js'
import { createClaudeCliAdapter, createProviderRouter } from './providerAdapters.js'

const THREAD_ID = '11111111-1111-4111-8111-111111111111'
const SESSION_ID = '22222222-2222-4222-8222-222222222222'

function runtime(observed) {
  return {
    getCapabilities: () => ({
      runtime_type: 'claude_cli', adapter_id: 'claude-cli-v1', enabled: true,
      capabilities: { streaming_text: true, reasoning_summary: 'conditional' },
    }),
    getQuota: () => unknownQuota('claude_cli_unavailable'),
    getUsage: () => null,
    startOrResume: () => ({}),
    sendMessage() {},
    resetRuntime: async () => ({ reset: true }),
    async streamEvents(input) {
      observed.push(input.sessionId)
      const sessionId = input.sessionId || SESSION_ID
      input.onRuntimeBinding(sessionId)
      input.onEvent('reasoning_status', {
        available: false, status: 'unavailable', summary: null, source: 'claude_cli',
      })
      input.onText('ok')
      return { text: 'ok', sessionId, usage: null }
    },
  }
}

async function listen(server) {
  server.listen(0, '127.0.0.1')
  await once(server, 'listening')
  return `http://127.0.0.1:${server.address().port}`
}

function close(server) {
  return new Promise(resolve => server.close(resolve))
}

async function startBridge(sidecarBase) {
  const app = express()
  app.use(express.json())
  const providerRouter = createProviderRouter({
    personaRegistry: createPersonaRegistry(),
    adapters: { claude: createClaudeCliAdapter({ baseUrl: `${sidecarBase}/api/claude` }) },
  })
  installClientApi(app, {
    verifyOwner: createClientOwnerAuth({
      verifyOwnerToken: async token => token === 'owner-jwt' ? { id: 'owner' } : null,
    }),
    providerRouter,
    startedAt: '2026-08-24T08:00:00.000Z',
  })
  const server = http.createServer(app)
  const base = await listen(server)
  return { server, base }
}

async function turn(bridgeBase) {
  const response = await fetch(`${bridgeBase}/v1/chat`, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json', Authorization: 'Bearer owner-jwt' },
    body: JSON.stringify({
      persona_id: 'claude', thread_id: THREAD_ID, window_id: 'client-window-1',
      scene: 'casual', message: { type: 'text', text: 'hello' },
    }),
  })
  assert.equal(response.status, 200)
  const text = await response.text()
  assert.match(text, /event: message_end/)
  assert.equal(text.includes('session_id'), false)
  assert.match(text, new RegExp(THREAD_ID))
}

test('Claude Thread A resumes after Bridge restart while its sidecar stays independent', async t => {
  const observed = []
  const sidecar = createChatRuntimeServer({
    authenticate: async () => ({ userId: 'owner' }),
    runtime: runtime(observed),
    routePrefix: '/api/claude',
    serviceName: 'lovehouse-claude-chat',
  })
  const sidecarBase = await listen(sidecar)
  t.after(() => close(sidecar))

  const bridge1 = await startBridge(sidecarBase)
  await turn(bridge1.base)
  await turn(bridge1.base)
  await close(bridge1.server)

  const bridge2 = await startBridge(sidecarBase)
  t.after(() => close(bridge2.server))
  await turn(bridge2.base)

  assert.deepEqual(observed, [null, SESSION_ID, SESSION_ID])
  assert.notEqual(THREAD_ID, SESSION_ID)
})
