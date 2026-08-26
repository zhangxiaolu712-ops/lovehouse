import { supabase } from '../../core/supabase'

async function accessToken() {
  const { data, error } = await supabase.auth.getSession()
  if (error) throw new Error('登录状态读取失败，请重新登录后再试。')
  const token = data?.session?.access_token
  if (!token) throw new Error('登录状态已失效，请重新登录后再试。')
  return token
}

export async function getGptMemoryTimeline({ limit = 60 } = {}) {
  const token = await accessToken()
  const response = await fetch(`/api/v1/memory/gpt/timeline?limit=${Math.min(Math.max(limit, 1), 100)}`, {
    headers: { Authorization: `Bearer ${token}` },
  })
  const payload = await response.json().catch(() => null)
  if (!response.ok) {
    throw new Error(payload?.error?.message || payload?.error || `Memory 请求失败（HTTP ${response.status}）`)
  }
  return Array.isArray(payload?.items) ? payload.items : []
}
