import test from 'node:test'
import assert from 'node:assert/strict'
import { CodexRuntimeEndpoint } from './codexRuntimeEndpoint.js'

test('runtime events become bounded workflow milestone contracts', async () => {
  const milestones = []
  const endpoint = new CodexRuntimeEndpoint({
    runtime: {
      async streamEvents(input) {
        input.onRuntimeBinding('session-1')
        input.onEvent('reasoning_status', { summary: 'Inspecting the existing implementation' })
        input.onEvent('tool_call', { name: 'shell' })
        input.onEvent('tool_error', { summary: 'Targeted test failed' })
        input.onText('done')
        return { text: 'done', sessionId: 'session-1' }
      },
    },
  })
  const result = await endpoint.run({
    threadId: 'thread-1', message: 'work', onMilestone: event => milestones.push(event),
  })
  assert.equal(result.text, 'done')
  assert.deepEqual(milestones, [
    { stage: 'reasoning', status: 'running', summary: 'Inspecting the existing implementation' },
    { stage: 'tool', status: 'running', summary: '正在使用 shell' },
    { stage: 'tool', status: 'failed', summary: 'Targeted test failed' },
  ])
})
