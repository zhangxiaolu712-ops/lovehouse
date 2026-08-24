import assert from 'node:assert/strict'
import test from 'node:test'
import { createElevenLabsVoiceClient } from './voice.js'

test('voice client fails closed when provider credentials are missing', async () => {
  const client = createElevenLabsVoiceClient({})
  assert.equal(client.configured, false)
  await assert.rejects(client.synthesize('你好'), error => {
    assert.equal(error.status, 503)
    return true
  })
})

test('voice client sends v3 Chinese TTS request without exposing the key', async () => {
  let request
  const client = createElevenLabsVoiceClient({
    apiKey: 'secret-key',
    voiceId: 'voice-123',
    modelId: 'eleven_v3',
    stability: 0.42,
    fetchImpl: async (url, options) => {
      request = { url, options }
      return new Response(new Uint8Array([1, 2, 3]), {
        status: 200,
        headers: { 'Content-Type': 'audio/mpeg' },
      })
    },
  })

  const audio = await client.synthesize('嗯……怎么了？')
  assert.deepEqual([...audio], [1, 2, 3])
  assert.equal(request.url, 'https://api.elevenlabs.io/v1/text-to-speech/voice-123')
  assert.equal(request.options.headers['xi-api-key'], 'secret-key')
  assert.deepEqual(JSON.parse(request.options.body), {
    text: '嗯……怎么了？',
    model_id: 'eleven_v3',
    language_code: 'zh',
    voice_settings: { stability: 0.42 },
  })
})
