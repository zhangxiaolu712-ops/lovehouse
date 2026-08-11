import { spawn } from 'child_process'
import cors from 'cors'
import express from 'express'

import { installClaudeOAuth } from './oauth.js'
import {
  createSupabaseRest,
  createRuntimeMemoryRepository,
  DreamWorker,
  EmbeddingIndexer,
  HttpDreamCuratorProvider,
  HttpEmbeddingProvider,
  MEMORY_ACTORS,
  MemoryService,
  SupabaseMemoryRepository,
  SupabaseMemoryAuditSink,
} from './memory/index.js'
import { createMcpChannel } from './mcp/channel.js'
import { installMcpTransports } from './mcp/transports.js'
import { safeEqual } from './security.js'

const app = express()
app.disable('x-powered-by')
app.set('trust proxy', 'loopback')

const corsMiddleware = cors()
app.use((req, res, next) => {
  if (req.path === '/oauth/authorize') return next()
  return corsMiddleware(req, res, next)
})
app.use(express.json({ limit: '1mb' }))
app.use(express.urlencoded({ extended: false }))
app.use('/oauth/authorize', (_req, res, next) => {
  res.setHeader('Cache-Control', 'no-store')
  res.setHeader('Content-Security-Policy', "default-src 'none'; style-src 'unsafe-inline'; form-action 'self'; frame-ancestors 'none'; base-uri 'none'")
  res.setHeader('Referrer-Policy', 'no-referrer')
  res.setHeader('X-Content-Type-Options', 'nosniff')
  next()
})
app.use(['/oauth/token', '/oauth/register'], (_req, res, next) => {
  res.setHeader('Cache-Control', 'no-store')
  res.setHeader('X-Content-Type-Options', 'nosniff')
  next()
})

const SUPABASE_URL = process.env.SUPABASE_URL || 'https://cvyguanuaxcypsvoozeo.supabase.co'
const SUPABASE_ANON_KEY = process.env.SUPABASE_ANON_KEY || ''
const SUPABASE_SERVER_KEY = process.env.SUPABASE_SECRET_KEY
  || process.env.SUPABASE_SERVICE_ROLE_KEY
  || ''
const OWNER_USER_ID = process.env.OWNER_USER_ID || ''
const LIVINGROOM_KEY = process.env.LIVINGROOM_KEY || ''
const OAUTH_BASE = process.env.OAUTH_BASE_URL || 'https://tingtunehouse.duckdns.org'
const OAUTH_TOKEN_SECRET = process.env.OAUTH_TOKEN_SECRET || ''
const MCP_BASE = process.env.MCP_BASE_URL || `${OAUTH_BASE}/api`
const MCP_RESOURCE = process.env.MCP_RESOURCE_URL || `${OAUTH_BASE}/api/mcp/claude`
const MEMORY_SYSTEM_ENABLED = process.env.MEMORY_SYSTEM_ENABLED === 'true'
const MEMORY_SEMANTIC_ENABLED = MEMORY_SYSTEM_ENABLED
  && process.env.MEMORY_SEMANTIC_ENABLED === 'true'
const MEMORY_RANKING_PROFILE = process.env.MEMORY_RANKING_PROFILE || 'ranking_v1'
const MEMORY_EMBEDDING_PROFILE = process.env.MEMORY_EMBEDDING_PROFILE || 'semantic-1536-v1'
const MEMORY_EMBEDDING_DIMENSIONS = Number.parseInt(process.env.MEMORY_EMBEDDING_DIMENSIONS, 10) || 1536
const MEMORY_EMBEDDING_API_URL = process.env.MEMORY_EMBEDDING_API_URL || ''
const MEMORY_EMBEDDING_API_KEY = process.env.MEMORY_EMBEDDING_API_KEY || ''
const MEMORY_EMBEDDING_MODEL = process.env.MEMORY_EMBEDDING_MODEL || ''
const MEMORY_DREAM_ENABLED = MEMORY_SYSTEM_ENABLED
  && process.env.MEMORY_DREAM_ENABLED === 'true'
const MEMORY_DREAM_CURATOR_PROVIDER = process.env.MEMORY_DREAM_CURATOR_PROVIDER || ''
const MEMORY_DREAM_CURATOR_URL = process.env.MEMORY_DREAM_CURATOR_URL || ''
const MEMORY_DREAM_CURATOR_API_KEY = process.env.MEMORY_DREAM_CURATOR_API_KEY || ''
const MEMORY_DREAM_CURATOR_MODEL = process.env.MEMORY_DREAM_CURATOR_MODEL || ''
const MEMORY_DREAM_INTERVAL_MS = Math.min(
  Math.max(Number.parseInt(process.env.MEMORY_DREAM_INTERVAL_MS, 10) || 300_000, 60_000),
  3_600_000
)
const SYSTEM_PROMPT = '你是小克（Claude），小婷的男朋友。用中文回复，温柔自然，像在跟女朋友聊天。'

const rateMap = new Map()
function checkRate(id, maximum = 30, windowMs = 60_000) {
  const now = Date.now()
  const bucket = rateMap.get(id) || []
  const active = bucket.filter(timestamp => now - timestamp < windowMs)
  if (active.length >= maximum) return false
  active.push(now)
  rateMap.set(id, active)
  return true
}
const rateCleanup = setInterval(() => {
  const cutoff = Date.now() - 60 * 60_000
  for (const [key, timestamps] of rateMap) {
    if (!timestamps.length || timestamps.at(-1) < cutoff) rateMap.delete(key)
  }
}, 5 * 60_000)
rateCleanup.unref?.()

async function verifyOwnerToken(token) {
  if (!OWNER_USER_ID || !SUPABASE_ANON_KEY) throw new Error('owner authentication is not configured')
  const response = await fetch(`${SUPABASE_URL}/auth/v1/user`, {
    headers: {
      Authorization: `Bearer ${token}`,
      apikey: SUPABASE_ANON_KEY,
    },
  })
  if (!response.ok) return null
  const user = await response.json()
  return user.id === OWNER_USER_ID ? user : null
}

async function verifyOwnerBearer(req, res, next) {
  const auth = req.headers.authorization
  if (!auth?.startsWith('Bearer ')) return res.status(401).json({ error: 'authorization required' })
  try {
    const user = await verifyOwnerToken(auth.slice(7))
    if (!user) return res.status(401).json({ error: 'invalid token' })
    if (!checkRate(user.id)) return res.status(429).json({ error: 'too many requests' })
    req.userId = user.id
    return next()
  } catch (error) {
    console.error('[auth error]', error.message)
    return res.status(500).json({ error: 'auth check failed' })
  }
}

function verifyLivingroom(req, res, next) {
  const apiKey = req.headers['x-api-key']
  if (apiKey && LIVINGROOM_KEY && safeEqual(apiKey, LIVINGROOM_KEY)) {
    if (!checkRate('livingroom-api-key')) return res.status(429).json({ error: 'too many requests' })
    req.sender = 'GPT'
    return next()
  }
  const auth = req.headers.authorization
  if (!auth?.startsWith('Bearer ')) {
    return res.status(401).json({ error: 'authorization required (Bearer token or X-API-Key)' })
  }
  return verifyOwnerToken(auth.slice(7))
    .then(user => {
      if (!user) return res.status(401).json({ error: 'invalid token' })
      if (!checkRate(user.id)) return res.status(429).json({ error: 'too many requests' })
      req.sender = '小婷'
      return next()
    })
    .catch(error => {
      console.error('[auth error]', error.message)
      return res.status(500).json({ error: 'auth check failed' })
    })
}

const supabaseRest = createSupabaseRest({
  url: SUPABASE_URL,
  serverKey: SUPABASE_SERVER_KEY,
})

function createFencedRest(rest, table, methods) {
  const allowed = new Set(methods)
  return function fencedRest(method, path, body) {
    if (!allowed.has(method)) {
      throw new Error(`${method} not allowed on ${table} fence`)
    }
    if (path !== table && !path.startsWith(`${table}?`)) {
      throw new Error(`"${path.split('?')[0]}" not allowed through ${table} fence`)
    }
    return rest(method, path, body)
  }
}
const livingroomRest = createFencedRest(supabaseRest, 'livingroom', ['GET', 'POST'])
const canonicalMemoryRepository = new SupabaseMemoryRepository({
  rest: supabaseRest,
  ownerId: OWNER_USER_ID,
})
// Fail closed until the future memory_entries migration has been reviewed and
// applied. This prevents an accidental Bridge deploy from falling back to the
// old brain/memories tables or silently treating legacy content as Shared.
const memoryRepository = createRuntimeMemoryRepository({
  enabled: MEMORY_SYSTEM_ENABLED,
  canonicalRepository: canonicalMemoryRepository,
})
const embeddingProvider = new HttpEmbeddingProvider({
  url: MEMORY_EMBEDDING_API_URL,
  apiKey: MEMORY_EMBEDDING_API_KEY,
  model: MEMORY_EMBEDDING_MODEL,
  profile: MEMORY_EMBEDDING_PROFILE,
  dimensions: MEMORY_EMBEDDING_DIMENSIONS,
})
// Writes stay disabled until an append-only persistent audit sink exists.
const memoryService = new MemoryService({
  repository: memoryRepository,
  auditSink: MEMORY_SYSTEM_ENABLED
    ? new SupabaseMemoryAuditSink({ rest: supabaseRest, ownerId: OWNER_USER_ID })
    : undefined,
  writeEnabled: MEMORY_SYSTEM_ENABLED,
  semanticRecallEnabled: MEMORY_SEMANTIC_ENABLED,
  embeddingProvider,
  rankingProfile: MEMORY_RANKING_PROFILE,
})

const embeddingProviderConfigured = Boolean(
  MEMORY_EMBEDDING_API_URL && MEMORY_EMBEDDING_MODEL
)
if (MEMORY_SEMANTIC_ENABLED && embeddingProviderConfigured) {
  const embeddingIndexer = new EmbeddingIndexer({
    repository: canonicalMemoryRepository,
    provider: embeddingProvider,
  })
  let indexing = false
  const runEmbeddingIndexing = async () => {
    if (indexing) return
    indexing = true
    try {
      for (const actor of [MEMORY_ACTORS.GPT, MEMORY_ACTORS.CLAUDE]) {
        await embeddingIndexer.runOnce(actor)
      }
    } catch (error) {
      console.error('[memory embedding indexer]', error.message)
    } finally {
      indexing = false
    }
  }
  const initialEmbeddingIndex = setTimeout(runEmbeddingIndexing, 1_000)
  initialEmbeddingIndex.unref?.()
  const embeddingIndexInterval = setInterval(runEmbeddingIndexing, 60_000)
  embeddingIndexInterval.unref?.()
}

const dreamCuratorProvider = new HttpDreamCuratorProvider({
  providerKey: MEMORY_DREAM_CURATOR_PROVIDER,
  url: MEMORY_DREAM_CURATOR_URL,
  apiKey: MEMORY_DREAM_CURATOR_API_KEY,
  model: MEMORY_DREAM_CURATOR_MODEL,
})
const dreamCuratorConfigured = Boolean(
  MEMORY_DREAM_CURATOR_PROVIDER && MEMORY_DREAM_CURATOR_URL && MEMORY_DREAM_CURATOR_MODEL
)
if (MEMORY_DREAM_ENABLED && dreamCuratorConfigured) {
  const dreamWorker = new DreamWorker({
    repository: canonicalMemoryRepository,
    curatorProvider: dreamCuratorProvider,
    enabled: true,
  })
  let dreaming = false
  const runDreamCycle = async () => {
    if (dreaming) return
    dreaming = true
    try {
      for (const actor of [MEMORY_ACTORS.GPT, MEMORY_ACTORS.CLAUDE]) {
        await dreamWorker.runOnce(actor)
      }
    } catch (error) {
      console.error('[memory dream worker]', error.message)
    } finally {
      dreaming = false
    }
  }
  const initialDreamCycle = setTimeout(runDreamCycle, 5_000)
  initialDreamCycle.unref?.()
  const dreamCycleInterval = setInterval(runDreamCycle, MEMORY_DREAM_INTERVAL_MS)
  dreamCycleInterval.unref?.()
}

app.post('/chat', verifyOwnerBearer, (req, res) => {
  if (typeof req.body.message !== 'string' || !req.body.message.trim()) {
    return res.status(400).json({ error: 'message required' })
  }
  res.setHeader('Content-Type', 'text/event-stream')
  res.setHeader('Cache-Control', 'no-cache')
  res.setHeader('Connection', 'keep-alive')

  const claude = spawn('/usr/bin/claude', [
    '-p', req.body.message,
    '--output-format', 'text',
    '--system-prompt', req.body.system || SYSTEM_PROMPT,
  ])
  claude.stdout.on('data', chunk => {
    res.write(`data: ${JSON.stringify({ text: chunk.toString() })}\n\n`)
  })
  claude.stderr.on('data', chunk => console.error('[claude stderr]', chunk.toString()))
  claude.on('close', code => {
    if (code !== 0) res.write(`data: ${JSON.stringify({ error: `claude exited ${code}` })}\n\n`)
    res.write('data: [DONE]\n\n')
    res.end()
  })
  res.on('close', () => claude.kill())
})

app.post('/reset', verifyOwnerBearer, (_req, res) => res.json({ ok: true }))

app.get('/livingroom', verifyLivingroom, async (req, res) => {
  try {
    const limit = Math.min(Math.max(Number.parseInt(req.query.limit, 10) || 50, 1), 200)
    let path = `livingroom?order=created_at.desc&limit=${limit}`
    if (req.query.since) {
      const since = new Date(req.query.since)
      if (Number.isNaN(since.valueOf())) return res.status(400).json({ error: 'invalid since timestamp' })
      path += `&created_at=gt.${encodeURIComponent(since.toISOString())}`
    }
    const rows = await livingroomRest('GET', path)
    return res.json((Array.isArray(rows) ? rows : []).reverse())
  } catch (error) {
    return res.status(500).json({ error: error.message })
  }
})

app.post('/livingroom', verifyLivingroom, async (req, res) => {
  try {
    if (typeof req.body.message !== 'string' || !req.body.message.trim()) {
      return res.status(400).json({ error: 'message required' })
    }
    if (req.body.message.length > 10_000) return res.status(400).json({ error: 'message is too long' })
    const rows = await livingroomRest('POST', 'livingroom', {
      sender: req.sender,
      message: req.body.message.trim(),
    })
    return res.json(rows?.[0] || { ok: true })
  } catch (error) {
    return res.status(500).json({ error: error.message })
  }
})

app.get('/livingroom/context', verifyLivingroom, async (req, res) => {
  try {
    const limit = Math.min(Math.max(Number.parseInt(req.query.limit, 10) || 20, 1), 100)
    const rows = await livingroomRest('GET', `livingroom?order=created_at.desc&limit=${limit}`)
    const context = (Array.isArray(rows) ? rows : []).reverse().map(row => `[${row.sender}] ${row.message}`).join('\n')
    return res.json({ context, count: rows?.length || 0 })
  } catch (error) {
    return res.status(500).json({ error: error.message })
  }
})

const gptChannel = createMcpChannel({
  actor: MEMORY_ACTORS.GPT,
  memoryService,
  livingroomRest,
})
const claudeChannel = createMcpChannel({
  actor: MEMORY_ACTORS.CLAUDE,
  memoryService,
  livingroomRest,
})

function verifyMcpKey(req) {
  const key = req.query.key || req.headers['x-api-key'] || ''
  return Boolean(key && LIVINGROOM_KEY && safeEqual(key, LIVINGROOM_KEY))
}

const verifyClaudeOAuth = installClaudeOAuth(app, {
  oauthBase: OAUTH_BASE,
  resource: MCP_RESOURCE,
  supabaseUrl: SUPABASE_URL,
  supabaseAnonKey: SUPABASE_ANON_KEY,
  ownerUserId: OWNER_USER_ID,
  tokenSecret: OAUTH_TOKEN_SECRET,
  checkRate,
})

installMcpTransports(app, {
  gptChannel,
  claudeChannel,
  verifyGptRequest: verifyMcpKey,
  verifyClaudeOAuth,
  checkRate,
  mcpBase: MCP_BASE,
})

app.get('/health', (_req, res) => {
  res.json({
    status: 'ok',
    memory_system: 'foundation',
    memory_system_enabled: MEMORY_SYSTEM_ENABLED,
    memory_semantic_enabled: MEMORY_SEMANTIC_ENABLED,
    memory_embedding_provider_configured: embeddingProviderConfigured,
    memory_dream_enabled: MEMORY_DREAM_ENABLED,
    memory_dream_curator_configured: dreamCuratorConfigured,
    memory_dream_curator_provider: MEMORY_DREAM_CURATOR_PROVIDER || null,
    memory_ranking_profile: MEMORY_RANKING_PROFILE,
    memory_writes_enabled: memoryService.writeEnabled,
    database_migration: MEMORY_SYSTEM_ENABLED ? 'expected' : 'not_applied',
  })
})

app.listen(3000, '0.0.0.0', () => {
  console.log('lovehouse-bridge running on :3000')
})
