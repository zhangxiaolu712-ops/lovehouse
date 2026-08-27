import { supabase } from '../../core/supabase'

async function getAuthToken() {
  const { data } = await supabase.auth.getSession()
  return data?.session?.access_token || ''
}

export async function synthesizeVoice(text) {
  const token = await getAuthToken()
  if (!token) throw new Error('需要先登录')

  const res = await fetch('/api/voice/tts', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Authorization: `Bearer ${token}`,
    },
    body: JSON.stringify({ text }),
  })

  if (!res.ok) {
    let message = `语音生成失败 (${res.status})`
    try {
      const payload = await res.json()
      if (payload?.error) message = payload.error
    } catch {}
    throw new Error(message)
  }

  return res.blob()
}
