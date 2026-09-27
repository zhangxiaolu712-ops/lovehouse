function replyCompletedEndpoint(identityEndpoint) {
  const url = new URL(identityEndpoint)
  url.pathname = '/internal/events/reply-completed'
  url.search = ''
  url.hash = ''
  return url.toString()
}

function requiredId(value, field) {
  if (typeof value !== 'string' || !value || value.length > 256) {
    throw new TypeError(`${field} is required`)
  }
  return value
}

export function createReplyCompletedEventProducer({
  identityEndpoint,
  internalKey,
  endpoint = replyCompletedEndpoint(identityEndpoint),
  fetchImpl = globalThis.fetch,
  timeoutMs = 1_500,
} = {}) {
  if (!identityEndpoint || !internalKey || typeof fetchImpl !== 'function') {
    throw new TypeError('reply-completed producer configuration is incomplete')
  }
  if (!Number.isFinite(timeoutMs) || timeoutMs <= 0) {
    throw new TypeError('reply-completed producer timeout must be positive')
  }
  return {
    async produce({ appAccountId, executionId, threadId }) {
      const controller = new AbortController()
      let rejectTimeout
      const timedOut = new Promise((_resolve, reject) => { rejectTimeout = reject })
      const timeout = setTimeout(() => {
        controller.abort()
        rejectTimeout(Object.assign(new Error('reply-completed delivery timed out'), {
          code: 'REPLY_COMPLETED_DELIVERY_TIMEOUT',
        }))
      }, timeoutMs)
      timeout.unref?.()
      try {
        const response = await Promise.race([
          fetchImpl(endpoint, {
            method: 'POST',
            headers: {
              'Content-Type': 'application/json',
              'X-LoveHouse-Internal-Key': internalKey,
            },
            body: JSON.stringify({
              app_account_id: requiredId(appAccountId, 'appAccountId'),
              execution_id: requiredId(executionId, 'executionId'),
              thread_id: requiredId(threadId, 'threadId'),
            }),
            signal: controller.signal,
          }),
          timedOut,
        ])
        if (!response.ok) {
          throw Object.assign(new Error(`reply-completed delivery failed: HTTP ${response.status}`), {
            code: 'REPLY_COMPLETED_DELIVERY_FAILED',
            status: response.status,
          })
        }
        const payload = await response.json()
        if (payload?.event?.kind !== 'reply_completed' || payload.event.origin_id !== executionId ||
          payload.event.app_account_id !== appAccountId || payload.event.thread_id !== threadId) {
          throw Object.assign(new Error('reply-completed delivery returned an invalid event'), {
            code: 'REPLY_COMPLETED_INVALID_RESPONSE',
          })
        }
        return payload.event
      } catch (error) {
        if (controller.signal.aborted) {
          throw Object.assign(new Error('reply-completed delivery timed out'), {
            code: 'REPLY_COMPLETED_DELIVERY_TIMEOUT',
          })
        }
        throw error
      } finally {
        clearTimeout(timeout)
      }
    },
  }
}
