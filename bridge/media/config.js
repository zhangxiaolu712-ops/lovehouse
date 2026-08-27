const DEFAULT_MAX_BYTES = 25 * 1024 * 1024
const DEFAULT_URL_TTL_SECONDS = 10 * 60

function positiveInteger(value, fallback) {
  if (value === undefined || value === null || value === '') return fallback
  const parsed = Number(value)
  return Number.isSafeInteger(parsed) && parsed > 0 ? parsed : fallback
}

export function resolveR2MediaConfig(env = process.env) {
  const accountId = env.R2_ACCOUNT_ID?.trim() || ''
  const accessKeyId = env.R2_ACCESS_KEY_ID?.trim() || ''
  const secretAccessKey = env.R2_SECRET_ACCESS_KEY?.trim() || ''
  const bucket = env.R2_BUCKET?.trim() || ''
  const missing = [
    ['R2_ACCOUNT_ID', accountId],
    ['R2_ACCESS_KEY_ID', accessKeyId],
    ['R2_SECRET_ACCESS_KEY', secretAccessKey],
    ['R2_BUCKET', bucket],
  ].filter(([, value]) => !value).map(([name]) => name)

  return {
    available: missing.length === 0,
    missing,
    accountId,
    accessKeyId,
    secretAccessKey,
    bucket,
    endpoint: accountId ? `https://${accountId}.r2.cloudflarestorage.com` : '',
    maxBytes: positiveInteger(env.R2_MEDIA_MAX_BYTES, DEFAULT_MAX_BYTES),
    urlTtlSeconds: Math.min(
      positiveInteger(env.R2_MEDIA_URL_TTL_SECONDS, DEFAULT_URL_TTL_SECONDS),
      60 * 60,
    ),
  }
}

export { DEFAULT_MAX_BYTES, DEFAULT_URL_TTL_SECONDS }
