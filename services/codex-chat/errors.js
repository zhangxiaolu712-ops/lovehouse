export class ChatRuntimeError extends Error {
  constructor(code, message, {
    stage = 'runtime',
    status = 500,
    retryable = false,
    cause,
  } = {}) {
    super(message, { cause })
    this.name = 'ChatRuntimeError'
    this.code = code
    this.stage = stage
    this.status = status
    this.retryable = retryable
  }
}

export function publicRuntimeError(error, fallback = {}) {
  if (error instanceof ChatRuntimeError) {
    return {
      code: error.code,
      message: error.message,
      stage: error.stage,
      retryable: error.retryable,
    }
  }
  return {
    code: fallback.code || 'STREAM_INTERRUPTED',
    message: fallback.message || 'Runtime stream interrupted',
    stage: fallback.stage || 'runtime',
    retryable: fallback.retryable ?? true,
  }
}
