import { getOwnerAccessToken } from '../engineering/engineeringService.js'

const ENDPOINT = '/api/v1/memory'

async function request(path, options = {}, dependencies = {}) {
  const token = await (dependencies.getAccessToken || getOwnerAccessToken)()
  const response = await (dependencies.fetchImpl || globalThis.fetch)(`${dependencies.endpoint || ENDPOINT}${path}`, {
    ...options,
    headers: { Accept: 'application/json', Authorization: `Bearer ${token}`, ...(options.body ? { 'Content-Type': 'application/json' } : {}), ...options.headers },
  })
  const payload = await response.json().catch(() => null)
  if (!response.ok || payload?.ok === false) throw new Error(payload?.error?.message || `Memory V2 请求失败（HTTP ${response.status}）`)
  return payload
}

export function listMemoryTimeline(actor, { limit = 100, query = '' } = {}, dependencies) {
  const params = new URLSearchParams({ limit: String(limit) }); if (query) params.set('query', query)
  return request(`/${actor}/timeline?${params}`, {}, dependencies).then(payload => payload.items || [])
}
export function createMemory(actor, input, dependencies) { return request(`/${actor}`, { method: 'POST', body: JSON.stringify(input) }, dependencies) }
export function reviseMemory(actor, memoryId, input, dependencies) { return request(`/${actor}/${encodeURIComponent(memoryId)}/revise`, { method: 'POST', body: JSON.stringify(input) }, dependencies) }
export function archiveMemory(actor, memoryId, dependencies) { return request(`/${actor}/${encodeURIComponent(memoryId)}/archive`, { method: 'POST', body: '{}' }, dependencies) }
export function getMemoryHistory(actor, memoryId, dependencies) { return request(`/${actor}/${encodeURIComponent(memoryId)}/history`, {}, dependencies).then(payload => payload.history) }
export function expandMemorySource(actor, sourceId, dependencies) { return request(`/${actor}/sources/${encodeURIComponent(sourceId)}`, {}, dependencies).then(payload => payload.source) }
