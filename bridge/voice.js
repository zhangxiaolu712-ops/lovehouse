const DEFAULT_MODEL = 'eleven_v3'

export function createElevenLabsVoiceClient({
  apiKey,
  voiceId,
  modelId = DEFAULT_MODEL,
  stability = 0.45,
  fetchImpl = fetch,
} = {}) {
  const configured = Boolean(apiKey && voiceId)

  async function synthesize(text) {
    if (!configured) {
      const error = new Error('voice provider is not configured')
      error.status = 503
      throw error
    }
    if (typeof text !== 'string' || !text.trim()) {
      const error = new Error('text required')
      error.status = 400
      throw error
    }

    const response = await fetchImpl(`https://api.elevenlabs.io/v1/text-to-speech/${encodeURIComponent(voiceId)}`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Accept: 'audio/mpeg',
        'xi-api-key': apiKey,
      },
      body: JSON.stringify({
        text: text.trim(),
        model_id: modelId,
        language_code: 'zh',
        voice_settings: { stability },
      }),
    })

    if (!response.ok) {
      const detail = await response.text().catch(() => '')
      const error = new Error(`voice provider failed (${response.status})${detail ? `: ${detail.slice(0, 240)}` : ''}`)
      error.status = response.status >= 400 && response.status < 500 ? 400 : 502
      throw error
    }

    return Buffer.from(await response.arrayBuffer())
  }

  return { configured, synthesize, modelId, voiceId }
}
