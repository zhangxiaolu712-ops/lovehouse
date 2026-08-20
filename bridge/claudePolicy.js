import { MCP_TOOL_ROUTES } from './mcp/tools.js'

export const CLAUDE_MCP_SERVER_NAME = 'lovehouse'

// Keep this as an explicit review gate. If the Bridge exposes a new MCP tool,
// the equality test below must fail until this allowlist is deliberately
// updated.
export const CLAUDE_MCP_TOOL_NAMES = Object.freeze([
  'wake_up',
  'remember',
  'recall',
  'revise',
  'open_memory',
  'read_livingroom',
  'say_livingroom',
])

export const CLAUDE_ALLOWED_TOOLS = Object.freeze(
  CLAUDE_MCP_TOOL_NAMES.map(name => `mcp__${CLAUDE_MCP_SERVER_NAME}__${name}`)
)

const DEFAULT_OAUTH_BASE_URL = 'https://tingtunehouse.duckdns.org'

const CHILD_ENV_ALLOWLIST = Object.freeze([
  'HOME',
  'PATH',
  'LANG',
  'LC_ALL',
  'LC_CTYPE',
  'TERM',
  'TMPDIR',
  'USER',
  'LOGNAME',
  'SHELL',
  'XDG_CONFIG_HOME',
  'XDG_CACHE_HOME',
  'XDG_STATE_HOME',
  'XDG_DATA_HOME',
  'CLAUDE_CONFIG_DIR',
])

function normalizeHttpsUrl(value, label) {
  let url
  try {
    url = new URL(value)
  } catch {
    throw new Error(`${label} must be a valid URL`)
  }
  if (url.protocol !== 'https:') throw new Error(`${label} must use https`)
  if (url.username || url.password || url.search || url.hash) {
    throw new Error(`${label} must not contain credentials, query parameters or fragments`)
  }
  return url.toString()
}

export function resolveClaudeMcpUrl(sourceEnv = process.env) {
  if (sourceEnv.CLAUDE_MCP_URL) {
    return normalizeHttpsUrl(sourceEnv.CLAUDE_MCP_URL, 'CLAUDE_MCP_URL')
  }
  if (sourceEnv.MCP_RESOURCE_URL) {
    return normalizeHttpsUrl(sourceEnv.MCP_RESOURCE_URL, 'MCP_RESOURCE_URL')
  }
  const oauthBase = normalizeHttpsUrl(
    sourceEnv.OAUTH_BASE_URL || DEFAULT_OAUTH_BASE_URL,
    'OAUTH_BASE_URL'
  )
  return new URL('/api/mcp/claude', oauthBase).toString()
}

export function buildClaudeChildEnv(sourceEnv = process.env) {
  return Object.fromEntries(
    CHILD_ENV_ALLOWLIST
      .filter(name => typeof sourceEnv[name] === 'string' && sourceEnv[name])
      .map(name => [name, sourceEnv[name]])
  )
}

export function buildClaudePolicyArgs({ mcpUrl }) {
  const config = {
    mcpServers: {
      [CLAUDE_MCP_SERVER_NAME]: {
        type: 'http',
        url: normalizeHttpsUrl(mcpUrl, 'Claude MCP URL'),
      },
    },
  }
  return [
    '--tools', '',
    '--allowedTools', ...CLAUDE_ALLOWED_TOOLS,
    '--permission-mode', 'dontAsk',
    '--disable-slash-commands',
    '--setting-sources', '',
    '--settings', '{}',
    '--strict-mcp-config',
    '--mcp-config', JSON.stringify(config),
  ]
}

export function inspectClaudeMcpInit(event) {
  if (event?.type !== 'system' || event?.subtype !== 'init') return null

  const servers = Array.isArray(event.mcp_servers) ? event.mcp_servers : []
  const server = servers.find(item => item?.name === CLAUDE_MCP_SERVER_NAME)
  if (!server) return { ready: false, error: 'LoveHouse MCP was not reported by Claude' }
  if (server.status !== 'connected') {
    const status = typeof server.status === 'string' ? server.status : 'unknown'
    return { ready: false, error: `LoveHouse MCP failed to initialize (${status})` }
  }

  const reportedTools = Array.isArray(event.tools) ? [...event.tools].sort() : []
  const expectedTools = [...CLAUDE_ALLOWED_TOOLS].sort()
  if (reportedTools.length !== expectedTools.length
    || reportedTools.some((name, index) => name !== expectedTools[index])) {
    return { ready: false, error: 'LoveHouse MCP tool allowlist did not match Claude initialization' }
  }
  return { ready: true }
}

export function assertClaudeToolPolicyMatchesBridge() {
  const exposed = Object.keys(MCP_TOOL_ROUTES).sort()
  const allowed = [...CLAUDE_MCP_TOOL_NAMES].sort()
  if (exposed.length !== allowed.length || exposed.some((name, index) => name !== allowed[index])) {
    throw new Error('Claude MCP tool allowlist is out of sync with Bridge tool routes')
  }
}
