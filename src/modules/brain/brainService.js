import { archiveMemory, createMemory, listMemoryTimeline, reviseMemory } from '../memory-v2/memoryV2Service'

function metaValue(metadata, key) { return metadata?.[key] ?? metadata?.[`legacy_${key}`] }
function project(item) {
  const metadata = item.metadata || {}; const eventTime = item.event_time || item.created_at
  return { id: item.memory_id, content: item.content, created_at: item.created_at,
    memory_date: metaValue(metadata, 'memory_date') || eventTime?.slice(0, 10), title: metaValue(metadata, 'title') || '',
    kind: metaValue(metadata, 'kind') || '记事', tag: metaValue(metadata, 'tag') || '日记', speaker: metaValue(metadata, 'speaker') || '',
    feeling: metaValue(metadata, 'feeling') || '', mood: metaValue(metadata, 'mood') || '', author: metaValue(metadata, 'author') || '小克',
    is_special: Boolean(metaValue(metadata, 'is_special')), special_label: metaValue(metadata, 'special_label') || '', ref_id: metaValue(metadata, 'ref_id') || null,
    status: item.status, revision_number: item.revision_number, source_count: item.source_count, metadata }
}

export async function getBrainEntries({ kind, tag, mood, date, isSpecial, limit = 100 } = {}) {
  const items = (await listMemoryTimeline('claude', { limit })).map(project)
  return items.filter(item => (!kind || item.kind === kind) && (!tag || item.tag === tag) && (!mood || item.mood === mood) && (!date || item.memory_date === date) && (!isSpecial || item.is_special))
}
export async function getBrainStats() { const items = await getBrainEntries(); return { total: items.length, days: new Set(items.map(item => item.memory_date).filter(Boolean)).size } }
export function addBrainEntry({ content, title, kind = '记事', tag = '日记', speaker, feeling, mood, author = '小克', memoryDate, isSpecial, specialLabel, refId }) {
  const date = memoryDate || new Date().toISOString().slice(0, 10)
  return createMemory('claude', { content, event_time: `${date}T12:00:00+08:00`, metadata: { title: title || null, kind, tag, speaker: speaker || null, feeling: feeling || null, mood: mood || null, author, memory_date: date, is_special: Boolean(isSpecial), special_label: specialLabel || null, ref_id: refId || null }, reason: 'owner_frontend_create' })
}
export function deleteBrainEntry(id) { return archiveMemory('claude', id) }
export function updateBrainEntry(id, currentEntry, metadataPatch) { return reviseMemory('claude', id, { content: currentEntry.content, metadata: { ...currentEntry.metadata, ...metadataPatch }, reason: 'owner_frontend_metadata_edit' }).then(() => ({ ...currentEntry, ...metadataPatch })) }
export async function getRandomEntry() { const items = await getBrainEntries(); return items[Math.floor(Math.random() * items.length)] || null }
export async function searchBrain(keyword, { limit = 50 } = {}) { return (await listMemoryTimeline('claude', { query: keyword, limit })).map(project) }
export async function getDatesWithEntries(yearMonth) { return [...new Set((await getBrainEntries()).map(e => e.memory_date).filter(d => d && (!yearMonth || d.startsWith(yearMonth))))] }
export function toggleSpecial(id, isSpecial, label, currentEntry) { return updateBrainEntry(id, currentEntry, { is_special: isSpecial, special_label: label || null }) }
export async function getMemoryTides() {
  const entries = await getBrainEntries(); const now = new Date(); const monthCounts = []
  for (let i = 5; i >= 0; i--) { const d = new Date(now.getFullYear(), now.getMonth() - i, 1); const ym = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`; monthCounts.push({ label: `${d.getMonth() + 1}月`, count: entries.filter(e => e.memory_date?.startsWith(ym)).length }) }
  const recent = new Date(now - 7 * 86400000).toISOString()
  return { monthCounts, total: entries.length, categories: [
    { id: 'new', label: 'Recent', desc: '7 days', color: '#c4d0c0', items: entries.filter(e => e.created_at >= recent) },
    { id: 'special', label: 'Special', desc: 'marked', color: '#c0c8d4', items: entries.filter(e => e.is_special) },
    { id: 'journal', label: 'Journal', desc: 'entries', color: '#d8d0b8', items: entries.filter(e => e.kind === '记事') },
    { id: 'feelings', label: 'Feelings', desc: 'reflections', color: '#d4c0c8', items: entries.filter(e => e.kind === '记感受') },
  ] }
}
