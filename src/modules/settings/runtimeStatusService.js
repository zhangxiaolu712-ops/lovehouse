export async function getRuntimeStatus({ fetchImpl = globalThis.fetch, getToken } = {}) {
  let token
  if (getToken) token = await getToken()
  else {
    const { supabase } = await import('../../core/supabase.js')
    const { data, error } = await supabase.auth.getSession()
    if (error || !data?.session?.access_token) throw new Error('登录状态已失效，请重新登录。')
    token = data.session.access_token
  }
  const response = await fetchImpl('/api/v1/runtime-status', {
    headers: { Accept: 'application/json', Authorization: `Bearer ${token}` },
  })
  const payload = await response.json().catch(() => null)
  if (!response.ok || payload?.ok !== true) {
    throw new Error(payload?.error?.message || `Runtime 状态读取失败（HTTP ${response.status}）`)
  }
  return payload.runtime
}
