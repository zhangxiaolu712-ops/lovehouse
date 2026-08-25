const ENDPOINT = '/api/v1/engineering-memory'

function apiError(payload, status) {
  const detail = payload?.error || {}
  const error = new Error(detail.message || `工程记忆请求失败（HTTP ${status}）`)
  error.code = detail.code || `HTTP_${status}`
  error.retryable = detail.retryable === true || status >= 500
  return error
}

export async function getOwnerAccessToken() {
  const { supabase } = await import('../../core/supabase')
  const { data, error } = await supabase.auth.getSession()
  if (error || !data?.session?.access_token) throw new Error('登录状态已失效，请重新登录。')
  return data.session.access_token
}

async function request(path = '', options = {}, dependencies = {}) {
  const fetchImpl = dependencies.fetchImpl || globalThis.fetch
  const getToken = dependencies.getAccessToken || getOwnerAccessToken
  const token = await getToken()
  const response = await fetchImpl(`${dependencies.endpoint || ENDPOINT}${path}`, {
    ...options,
    headers: {
      Accept: 'application/json',
      Authorization: `Bearer ${token}`,
      ...(options.body ? { 'Content-Type': 'application/json' } : {}),
      ...options.headers,
    },
  })
  let payload
  try { payload = await response.json() } catch { payload = null }
  if (!response.ok || payload?.ok === false) throw apiError(payload, response.status)
  return payload
}

export function listEngineeringFacts({ query = '', includeArchived = false, limit = 50 } = {}, dependencies) {
  const params = new URLSearchParams({ query, limit: String(limit) })
  if (includeArchived) params.set('include_archived', 'true')
  return request(`?${params}`, {}, dependencies).then(payload => payload.items || [])
}

export function openEngineeringFact(subjectKey, dependencies) {
  return request(`/${encodeURIComponent(subjectKey)}`, {}, dependencies).then(payload => payload.fact)
}

export function saveEngineeringFact(input, dependencies) {
  return request('', { method: 'POST', body: JSON.stringify(input) }, dependencies)
}

export function expandEngineeringSource(sourceId, dependencies) {
  return request(`/sources/${encodeURIComponent(sourceId)}`, {}, dependencies).then(payload => payload.source)
}

export function archiveEngineeringFact(subjectKey, dependencies) {
  return request(`/${encodeURIComponent(subjectKey)}/archive`, { method: 'POST', body: '{}' }, dependencies)
}

export function restoreEngineeringFact(subjectKey, dependencies) {
  return request(`/${encodeURIComponent(subjectKey)}/restore`, { method: 'POST', body: '{}' }, dependencies)
}
