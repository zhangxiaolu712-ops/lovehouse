export class ClientApiError extends Error {
  constructor(code, message, {
    stage = 'client',
    status = 500,
    retryable = false,
    cause,
  } = {}) {
    super(message, { cause })
    this.name = 'ClientApiError'
    this.code = code
    this.stage = stage
    this.status = status
    this.retryable = retryable
  }
}

export function normalizeClientApiError(error, fallback = {}) {
  if (error instanceof ClientApiError) return error
  return new ClientApiError(
    fallback.code || 'INTERNAL_ERROR',
    fallback.message || 'LoveHouse could not complete the request',
    {
      stage: fallback.stage || 'server',
      status: fallback.status || 500,
      retryable: fallback.retryable ?? false,
      cause: error,
    },
  )
}
