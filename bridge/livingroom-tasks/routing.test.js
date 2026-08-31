import test from 'node:test'
import assert from 'node:assert/strict'
import { createMentionRouter, routeLivingroomMessage } from './routing.js'

test('@Codex resolves through Agent -> Runtime -> Endpoint route', () => {
  assert.deepEqual(routeLivingroomMessage('@Codex 修复测试'), {
    agent: 'codex', runtime: 'vps-cli', endpoint: 'codex-vps-primary', prompt: '修复测试',
  })
  assert.equal(routeLivingroomMessage('普通群聊'), null)
})

test('router accepts future Agent / Runtime / Endpoint registrations without protocol changes', () => {
  const route = createMentionRouter([{
    mention: 'Gemini', agent: 'gemini', runtime: 'local-cli', endpoint: 'windows-primary',
  }])('@Gemini inspect locally')
  assert.deepEqual(route, {
    agent: 'gemini', runtime: 'local-cli', endpoint: 'windows-primary', prompt: 'inspect locally',
  })
})
