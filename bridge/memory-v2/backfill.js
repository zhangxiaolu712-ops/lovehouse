import { pathToFileURL } from 'node:url'

import { createSupabaseRest } from '../memory/repository.js'
import { createOllamaEmbeddingFromEnv } from './ollamaEmbedding.js'
import { SupabaseMemoryV2Repository } from './repository.js'

const ACTORS = ['gpt', 'claude']
const TARGET_LIMIT = 50

export async function collectCurrentTargets(repository) {
  const targets = new Map()
  for (const actor of ACTORS) {
    const visible = await repository.recallLexical(actor, { query: '', limit: TARGET_LIMIT })
    if (!Array.isArray(visible)) throw new Error('Memory V2 current target response is invalid')
    if (visible.length >= TARGET_LIMIT) {
      const error = new Error('Memory V2 current target limit was reached; refusing partial backfill')
      error.code = 'MEMORY_V2_BACKFILL_TARGET_LIMIT'
      throw error
    }
    for (const item of visible) {
      if (!item?.revision_id || !item?.content) continue
      if (!targets.has(item.revision_id)) {
        targets.set(item.revision_id, {
          revisionId: item.revision_id,
          memoryId: item.memory_id,
          actor: item.space_key === 'shared' ? actor : item.space_key,
          spaceKey: item.space_key,
          content: item.content,
        })
      }
    }
  }
  return [...targets.values()]
}

export async function runEmbeddingBackfill({ repository, rest, embedding }) {
  if (!repository || typeof repository.storeEmbedding !== 'function') {
    throw new TypeError('Memory V2 repository is required')
  }
  if (typeof rest !== 'function') throw new TypeError('Supabase REST is required')
  if (!embedding || typeof embedding.embed !== 'function') {
    throw new TypeError('Memory V2 embedding adapter is required')
  }

  const targets = await collectCurrentTargets(repository)
  const existingRows = await rest(
    'GET',
    `memory_v2_embeddings?select=revision_id,model&model=eq.${encodeURIComponent(embedding.model)}`
  )
  const existing = new Set(
    (Array.isArray(existingRows) ? existingRows : []).map(row => row.revision_id)
  )
  const report = {
    model: embedding.model,
    total: targets.length,
    success: 0,
    skipped: 0,
    failed: 0,
    stopped: false,
    failed_revision_id: null,
  }

  for (const target of targets) {
    if (existing.has(target.revisionId)) {
      report.skipped += 1
      continue
    }
    try {
      const generated = await embedding.embed(target.content)
      await repository.storeEmbedding(target.actor, target.revisionId, generated)
      report.success += 1
    } catch (error) {
      report.failed += 1
      report.stopped = true
      report.failed_revision_id = target.revisionId
      report.error = error?.code || error?.message || 'MEMORY_V2_BACKFILL_FAILED'
      break
    }
  }
  report.embedding_status = typeof embedding.getStatus === 'function'
    ? embedding.getStatus()
    : null
  return report
}

export async function runFromEnvironment(env = process.env) {
  const serverKey = env.SUPABASE_SECRET_KEY || env.SUPABASE_SERVICE_ROLE_KEY
  const rest = createSupabaseRest({ url: env.SUPABASE_URL, serverKey })
  const repository = new SupabaseMemoryV2Repository({ rest, ownerId: env.OWNER_USER_ID })
  const embedding = createOllamaEmbeddingFromEnv({ env })
  if (!embedding) throw new Error('Memory V2 embedding environment is not configured')
  return runEmbeddingBackfill({ repository, rest, embedding })
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  runFromEnvironment().then(report => {
    process.stdout.write(`${JSON.stringify(report)}\n`)
    if (report.failed) process.exitCode = 1
  }).catch(error => {
    process.stderr.write(`${JSON.stringify({
      error: error?.code || error?.message || 'MEMORY_V2_BACKFILL_FAILED',
    })}\n`)
    process.exitCode = 1
  })
}
