const DEFAULT_LIMIT = 200
const MAX_LIMIT = 200

function clampLimit(value) {
  const parsed = Number.parseInt(value, 10)
  if (!Number.isFinite(parsed)) return DEFAULT_LIMIT
  return Math.min(Math.max(parsed, 1), MAX_LIMIT)
}

export function buildLivingroomReadUrl({ limit = DEFAULT_LIMIT, since } = {}) {
  const query = new URLSearchParams({ limit: String(clampLimit(limit)) })
  if (since) query.set('since', since)
  return `/api/livingroom?${query}`
}

export async function readLivingroom({ accessToken, limit, since, fetchImpl = globalThis.fetch }) {
  if (!accessToken) throw new Error('Owner session is required')
  if (typeof fetchImpl !== 'function') throw new Error('Fetch is unavailable')

  const response = await fetchImpl(buildLivingroomReadUrl({ limit, since }), {
    method: 'GET',
    headers: {
      Accept: 'application/json',
      Authorization: `Bearer ${accessToken}`,
    },
  })

  if (!response.ok) throw new Error(`LivingRoom read failed (${response.status})`)
  const rows = await response.json()
  if (!Array.isArray(rows)) throw new Error('LivingRoom response is invalid')

  return rows.map(row => ({
    id: row.id,
    sender: row.sender,
    message: row.message,
    created_at: row.created_at,
    message_id: row.message_id || null,
    thread_id: row.thread_id || null,
  }))
}
