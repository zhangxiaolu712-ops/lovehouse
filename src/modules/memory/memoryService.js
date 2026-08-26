import { archiveMemory, createMemory, listMemoryTimeline } from '../memory-v2/memoryV2Service'

function metaValue(meta, key) { return meta?.[key] ?? meta?.[`legacy_${key}`] }
function project(item) { const meta = item.metadata || {}; return { id: item.memory_id, content: item.content, category: metaValue(meta, 'category') || metaValue(meta, 'tag') || '日常点滴', level: metaValue(meta, 'level') || '长期', created_at: item.event_time || item.created_at, revision_number: item.revision_number, source_count: item.source_count } }

export async function getMemories({ category, level, limit = 100 } = {}) {
  const items = (await listMemoryTimeline('claude', { limit })).map(project)
  return items.filter(item => (!category || item.category === category) && (!level || item.level === level))
}

export async function searchMemories(keyword, { limit = 50 } = {}) {
  return listMemoryTimeline('claude', { query: keyword, limit }).then(rows => rows.map(project))
}

export async function addMemory({ content, category = '日常点滴', level = '短期', importance = 1 }) {
  return createMemory('claude', { content, metadata: { category, level, legacy_importance_retired: importance }, reason: 'owner_frontend_create' })
}

export async function deleteMemory(id) {
  return archiveMemory('claude', id)
}
