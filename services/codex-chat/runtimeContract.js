export const RUNTIME_TYPES = Object.freeze([
  'codex_cli',
  'codex_api',
  'claude_cli',
  'claude_api',
  'openai_api',
])

export const RUNTIME_ADAPTER_METHODS = Object.freeze([
  'startOrResume',
  'sendMessage',
  'streamEvents',
  'getUsage',
  'getQuota',
  'getCapabilities',
  'resetRuntime',
])

export function assertRuntimeAdapter(adapter) {
  if (!adapter || typeof adapter !== 'object') throw new TypeError('Runtime adapter is required')
  for (const method of RUNTIME_ADAPTER_METHODS) {
    if (typeof adapter[method] !== 'function') {
      throw new TypeError(`Runtime adapter must implement ${method}()`)
    }
  }
  const capabilities = adapter.getCapabilities()
  if (!RUNTIME_TYPES.includes(capabilities?.runtime_type)) {
    throw new TypeError('Runtime adapter runtime_type is unsupported')
  }
  if (typeof capabilities.adapter_id !== 'string' || !capabilities.adapter_id) {
    throw new TypeError('Runtime adapter adapter_id is required')
  }
  return adapter
}

export function unknownQuota(source = 'runtime_unavailable') {
  return {
    status: 'unknown',
    remaining: null,
    unit: null,
    reset_at: null,
    source,
  }
}
