import { pathToFileURL } from 'node:url'
import { createSupabaseRest } from '../memory/repository.js'
import { createOllamaEmbeddingFromEnv } from './ollamaEmbedding.js'
import { SupabaseMemoryV2Repository } from './repository.js'

const ACTORS = ['gpt', 'claude']
const DEFAULT_BATCH_SIZE = 25
const MAX_BATCH_SIZE = 50

function boundedPositiveInteger(value, fallback, maximum = Number.MAX_SAFE_INTEGER) {
  const parsed = Number.parseInt(value, 10)
  return Number.isFinite(parsed) && parsed > 0 ? Math.min(parsed, maximum) : fallback
}
function fixedActors(value = ACTORS) {
  const actors = Array.isArray(value) ? value : [value]
  if (!actors.length || actors.some(actor => !ACTORS.includes(actor))) {
    throw new TypeError('Memory V2 backfill actors must be gpt and/or claude')
  }
  return [...new Set(actors)]
}
function encodeIn(values) {
  return values.map(value => `"${String(value).replaceAll('"', '\\"')}"`).join(',')
}
async function fetchRevisionContents(rest, revisionIds) {
  if (!revisionIds.length) return new Map()
  const rows = await rest('GET', `memory_v2_revisions?select=id,content&id=in.(${encodeIn(revisionIds)})`)
  if (!Array.isArray(rows)) throw new Error('Memory V2 revision page response is invalid')
  return new Map(rows.map(row => [row.id, row.content]))
}
async function fetchTargetPage({ rest, ownerId, spaceKey, actor, offset, batchSize }) {
  const filters = [
    'memory_v2_entries?select=id,current_revision_id,space_key',
    `owner_id=eq.${encodeURIComponent(ownerId)}`,
    `space_key=eq.${spaceKey}`,
    'status=eq.active',
    'order=id.asc',
    `offset=${offset}`,
    `limit=${batchSize}`,
  ]
  if (spaceKey === 'shared') filters.push('shared_status=eq.approved')
  const entries = await rest('GET', filters.join('&'))
  if (!Array.isArray(entries)) throw new Error('Memory V2 current target page response is invalid')
  const contents = await fetchRevisionContents(rest, entries.map(row => row.current_revision_id))
  return entries.flatMap(entry => {
    const content = contents.get(entry.current_revision_id)
    return entry.current_revision_id && String(content || '').trim() ? [{
      revisionId: entry.current_revision_id, memoryId: entry.id, actor,
      spaceKey: entry.space_key, content,
    }] : []
  })
}
export async function *collectCurrentTargetBatches({ rest, ownerId, actors = ACTORS, batchSize = DEFAULT_BATCH_SIZE }) {
  if (typeof rest !== 'function') throw new TypeError('Supabase REST is required')
  if (!ownerId) throw new TypeError('Memory V2 owner id is required')
  const selectedActors = fixedActors(actors)
  const size = boundedPositiveInteger(batchSize, DEFAULT_BATCH_SIZE, MAX_BATCH_SIZE)
  const spaces = selectedActors.map(actor => ({ spaceKey: actor, actor }))
  spaces.push({ spaceKey: 'shared', actor: selectedActors[0] })
  for (const space of spaces) {
    for (let offset = 0; ; offset += size) {
      const page = await fetchTargetPage({ rest, ownerId, ...space, offset, batchSize: size })
      if (page.length) yield page
      if (page.length < size) break
    }
  }
}

async function existingRevisionIds(rest, model, revisionIds) {
  if (!revisionIds.length) return new Set()
  const rows = await rest('GET', `memory_v2_embeddings?select=revision_id&model=eq.${encodeURIComponent(model)}&revision_id=in.(${encodeIn(revisionIds)})`)
  if (!Array.isArray(rows)) throw new Error('Memory V2 embedding page response is invalid')
  return new Set(rows.map(row => row.revision_id))
}

export async function runEmbeddingBackfill({ repository, rest, embedding, ownerId = repository?.ownerId,
  actors = ACTORS, batchSize = DEFAULT_BATCH_SIZE, maxBatches = Number.MAX_SAFE_INTEGER, dryRun = false }) {
  if (!repository || typeof repository.storeEmbedding !== 'function') throw new TypeError('Memory V2 repository is required')
  if (typeof rest !== 'function') throw new TypeError('Supabase REST is required')
  if (!embedding || typeof embedding.embed !== 'function' || !embedding.model) throw new TypeError('Memory V2 embedding adapter is required')
  const report = {
    model: embedding.model, actors: fixedActors(actors),
    batch_size: boundedPositiveInteger(batchSize, DEFAULT_BATCH_SIZE, MAX_BATCH_SIZE),
    dry_run: dryRun === true, batches: 0, total: 0, success: 0, skipped: 0,
    pending: 0, failed: 0, stopped: false, failed_revision_id: null,
    failed_revision_ids: [], errors: {},
  }
  const batchLimit = boundedPositiveInteger(maxBatches, Number.MAX_SAFE_INTEGER)
  for await (const targets of collectCurrentTargetBatches({ rest, ownerId, actors: report.actors, batchSize: report.batch_size })) {
    if (report.batches >= batchLimit) break
    report.batches += 1
    report.total += targets.length
    const existing = await existingRevisionIds(rest, embedding.model, targets.map(target => target.revisionId))
    for (const target of targets) {
      if (existing.has(target.revisionId)) { report.skipped += 1; continue }
      if (report.dry_run) { report.pending += 1; continue }
      try {
        const generated = await embedding.embed(target.content)
        await repository.storeEmbedding(target.actor, target.revisionId, generated)
        report.success += 1
      } catch (error) {
        report.failed += 1
        const code = error?.code || error?.message || 'MEMORY_V2_BACKFILL_FAILED'
        report.failed_revision_id ||= target.revisionId
        report.failed_revision_ids.push(target.revisionId)
        report.errors[code] = (report.errors[code] || 0) + 1
      }
    }
  }
  report.embedding_status = typeof embedding.getStatus === 'function' ? embedding.getStatus() : null
  return report
}

function parseCliArgs(argv) {
  const options = {}
  for (const argument of argv) {
    if (argument === '--dry-run') options.dryRun = true
    else if (argument.startsWith('--batch-size=')) options.batchSize = argument.split('=', 2)[1]
    else if (argument.startsWith('--max-batches=')) options.maxBatches = argument.split('=', 2)[1]
    else if (argument.startsWith('--actor=')) options.actors = [argument.split('=', 2)[1]]
    else throw new Error(`Unknown Memory V2 backfill argument: ${argument}`)
  }
  return options
}
export async function runFromEnvironment(env = process.env, options = {}) {
  const serverKey = env.SUPABASE_SECRET_KEY || env.SUPABASE_SERVICE_ROLE_KEY
  const rest = createSupabaseRest({ url: env.SUPABASE_URL, serverKey })
  const repository = new SupabaseMemoryV2Repository({ rest, ownerId: env.OWNER_USER_ID })
  const embedding = createOllamaEmbeddingFromEnv({ env })
  if (!embedding) throw new Error('Memory V2 embedding environment is not configured')
  return runEmbeddingBackfill({ repository, rest, embedding, ...options })
}
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  runFromEnvironment(process.env, parseCliArgs(process.argv.slice(2))).then(report => {
    process.stdout.write(`${JSON.stringify(report)}\n`)
    if (report.failed) process.exitCode = 1
  }).catch(error => {
    process.stderr.write(`${JSON.stringify({ error: error?.code || error?.message || 'MEMORY_V2_BACKFILL_FAILED' })}\n`)
    process.exitCode = 1
  })
}
