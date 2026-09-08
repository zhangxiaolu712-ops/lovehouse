import assert from 'node:assert/strict'
import test from 'node:test'

import { normalizeToolPreferenceIds } from './catalog.js'
import { ToolCenterService } from './service.js'
import { handleMcpMessage } from '../mcp/transports.js'

function service() {
  const calls = []
  const engineeringMemoryService = {
    forActor(actor) {
      assert.equal(actor, 'codex')
      return {
        async recallEngineering(input) { calls.push(['engineering-read', input]); return { items: [{ subject_key: 'current' }] } },
        async openEngineeringFact(subjectKey) { calls.push(['engineering-open', subjectKey]); return { subject_key: subjectKey } },
      }
    },
  }
  const livingroomRest = async (method, path) => {
    calls.push(['livingroom', method, path])
    return [{ sender: 'GPT', message: 'hello' }]
  }
  return { value: new ToolCenterService({ memoryV2Service: { forActor() {} }, engineeringMemoryService, livingroomRest }), calls }
}

const THREAD_ID = '7c814f9a-7588-4e35-b4b6-a216f172c012'

test('catalog accepts only stable built-in ids and deduplicates preferences', () => {
  assert.deepEqual(normalizeToolPreferenceIds([
    'builtin.engineering.read_current', 'builtin.engineering.read_current', 'builtin.livingroom.read',
  ]), ['builtin.engineering.read_current', 'builtin.livingroom.read'])
  assert.throws(() => normalizeToolPreferenceIds(['builtin.livingroom.send']), /unknown tool/)
})

test('capabilities are honest and do not borrow GPT or Claude private Memory for Codex', () => {
  const { value } = service()
  const statuses = Object.fromEntries(value.capabilities().map(tool => [tool.tool_id, tool.status]))
  assert.equal(statuses['builtin.memory.read'], 'no_permission')
  assert.equal(statuses['builtin.engineering.read_current'], 'available')
  assert.equal(statuses['builtin.livingroom.read'], 'available')
  assert.equal(statuses['builtin.livingroom.send'], undefined)
})

test('server scope validation rejects other personas and filters unavailable tools', () => {
  const { value } = service()
  assert.throws(() => value.validateRequest({ personaId: 'claude', threadId: THREAD_ID, requestedIds: [] }), /persona codex/)
  assert.deepEqual(value.validateRequest({
    personaId: 'codex', threadId: THREAD_ID,
    requestedIds: ['builtin.memory.read', 'builtin.engineering.read_current'],
  }), ['builtin.engineering.read_current'])
})

test('test and MCP channel execute only reviewed read operations', async () => {
  const { value, calls } = service()
  assert.equal((await value.test('builtin.engineering.read_current')).ok, true)
  const channel = value.channel(['builtin.engineering.open', 'builtin.livingroom.read'])
  assert.deepEqual(channel.tools.map(tool => tool.name), ['engineering_open', 'livingroom_read'])
  assert.deepEqual(channel.tools.map(tool => tool.annotations), [
    { title: '打开工程主题', readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false },
    { title: '读取小客厅', readOnlyHint: true, destructiveHint: false, idempotentHint: true, openWorldHint: false },
  ])
  assert.deepEqual(JSON.parse(await channel.callTool('engineering_open', { subject_key: 'architecture.current' })), {
    subject_key: 'architecture.current',
  })
  await assert.rejects(channel.callTool('memory_read', { query: 'private' }), /not allowed/)
  assert.equal(calls.some(call => call[0] === 'engineering-open'), true)
})

test('Codex MCP tools call reaches the real read service with a trusted request id', async () => {
  const { value, calls } = service()
  const response = await handleMcpMessage({
    jsonrpc: '2.0', id: 7, method: 'tools/call',
    params: { name: 'engineering_read_current', arguments: { query: '主线', limit: 1 } },
  }, {
    channel: value.channel(['builtin.engineering.read_current']),
    serverName: 'lovehouse-codex-tools',
    transportIdentity: `owner:test:${THREAD_ID}`,
  })

  assert.equal(response.result.content[0].type, 'text')
  assert.equal(JSON.parse(response.result.content[0].text).items[0].subject_key, 'current')
  assert.equal(calls.some(call => call[0] === 'engineering-read'), true)
})
