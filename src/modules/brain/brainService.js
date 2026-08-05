import { supabase } from '../../core/supabase'

const TABLE = 'brain'

export async function getBrainEntries({ kind, tag, status, mood, date, isSpecial, limit = 60 } = {}) {
  let query = supabase.from(TABLE).select('*').order('created_at', { ascending: false }).limit(limit)
  if (kind) query = query.eq('kind', kind)
  if (tag) query = query.eq('tag', tag)
  if (status && status !== 'all') query = query.neq('status', 'archived')
  else if (!status) query = query.neq('status', 'archived')
  if (mood) query = query.eq('mood', mood)
  if (date) query = query.eq('memory_date', date)
  if (isSpecial) query = query.eq('is_special', true)
  const { data, error } = await query
  if (error) throw error
  return data || []
}

export async function getBrainStats() {
  const { count } = await supabase.from(TABLE).select('*', { count: 'exact', head: true }).neq('status', 'archived')
  const { data: dates } = await supabase.from(TABLE).select('memory_date').neq('status', 'archived')
  const days = new Set(dates?.map(d => d.memory_date) || []).size
  return { total: count || 0, days }
}

export async function addBrainEntry({ content, title, kind = '记事', tag = '日记', speaker, feeling, mood, author = '小克', memoryDate, isSpecial, specialLabel, refId }) {
  const { data, error } = await supabase.from(TABLE).insert({
    content,
    title: title || null,
    kind,
    tag,
    speaker: speaker || null,
    feeling: feeling || null,
    mood: mood || null,
    author,
    memory_date: memoryDate || new Date().toISOString().slice(0, 10),
    is_special: isSpecial || false,
    special_label: specialLabel || null,
    ref_id: refId || null,
  }).select().single()
  if (error) throw error
  return data
}

export async function deleteBrainEntry(id) {
  const { error } = await supabase.from(TABLE).delete().eq('id', id)
  if (error) throw error
}

export async function updateBrainEntry(id, updates) {
  const { data, error } = await supabase.from(TABLE)
    .update({ ...updates, updated_at: new Date().toISOString() })
    .eq('id', id).select().single()
  if (error) throw error
  return data
}

export async function awakenEntry(id) {
  const { data: current } = await supabase.from(TABLE).select('awaken_count').eq('id', id).single()
  return updateBrainEntry(id, {
    status: 'awakened',
    awaken_count: (current?.awaken_count || 0) + 1,
    last_awakened_at: new Date().toISOString(),
  })
}

export async function fadeEntry(id) {
  return updateBrainEntry(id, { status: 'faded' })
}

export async function getRandomEntry() {
  const { count } = await supabase.from(TABLE).select('*', { count: 'exact', head: true }).neq('status', 'archived')
  if (!count) return null
  const offset = Math.floor(Math.random() * count)
  const { data } = await supabase.from(TABLE).select('*').neq('status', 'archived').range(offset, offset).limit(1)
  return data?.[0] || null
}

export async function searchBrain(keyword, { limit = 50 } = {}) {
  const queries = [
    supabase.from(TABLE).select('*').ilike('content', `%${keyword}%`).order('created_at', { ascending: false }).limit(limit),
    supabase.from(TABLE).select('*').ilike('title', `%${keyword}%`).order('created_at', { ascending: false }).limit(limit),
  ]
  const [byContent, byTitle] = await Promise.all(queries)
  const merged = new Map()
  for (const item of [...(byContent.data || []), ...(byTitle.data || [])]) {
    merged.set(item.id, item)
  }
  return [...merged.values()].sort((a, b) => new Date(b.created_at) - new Date(a.created_at)).slice(0, limit)
}

export async function getDatesWithEntries(yearMonth) {
  let query = supabase.from(TABLE).select('memory_date').neq('status', 'archived')
  if (yearMonth) {
    const start = `${yearMonth}-01`
    const end = `${yearMonth}-31`
    query = query.gte('memory_date', start).lte('memory_date', end)
  }
  const { data } = await query
  return [...new Set(data?.map(d => d.memory_date) || [])]
}

export async function getMemoryTides() {
  const { data, error } = await supabase.from(TABLE)
    .select('*')
    .neq('status', 'archived')
    .order('created_at', { ascending: false })
  if (error) throw error
  const entries = data || []
  const now = new Date()

  const monthCounts = []
  for (let i = 5; i >= 0; i--) {
    const d = new Date(now.getFullYear(), now.getMonth() - i, 1)
    const ym = `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}`
    const count = entries.filter(e => e.memory_date?.startsWith(ym)).length
    monthCounts.push({ label: `${d.getMonth() + 1}月`, count })
  }

  const sevenDaysAgo = new Date(now - 7 * 86400000).toISOString()

  return {
    monthCounts,
    total: entries.length,
    categories: [
      {
        id: 'new', label: 'Recent', desc: '7 days', color: '#c4d0c0',
        items: entries.filter(e => e.created_at >= sevenDaysAgo),
      },
      {
        id: 'referenced', label: 'Referenced', desc: 'most cited', color: '#c0c8d4',
        items: entries.filter(e => (e.awaken_count || 0) > 0)
          .sort((a, b) => (b.awaken_count || 0) - (a.awaken_count || 0)),
      },
      {
        id: 'awakened', label: 'Awakened', desc: 'recalled', color: '#d8d0b8',
        items: entries.filter(e => e.status === 'awakened')
          .sort((a, b) => new Date(b.last_awakened_at) - new Date(a.last_awakened_at)),
      },
      {
        id: 'fading', label: 'Fading', desc: 'forgotten', color: '#d4c0c8',
        items: entries.filter(e => e.status === 'faded'),
      },
    ],
  }
}

export async function toggleSpecial(id, isSpecial, label) {
  return updateBrainEntry(id, { is_special: isSpecial, special_label: label || null })
}

export async function getRandomFeeling() {
  const { count } = await supabase.from(TABLE).select('*', { count: 'exact', head: true })
    .eq('kind', '记感受').is('ref_id', null).neq('status', 'archived')
  if (!count) return null
  const offset = Math.floor(Math.random() * count)
  const { data } = await supabase.from(TABLE).select('*')
    .eq('kind', '记感受').is('ref_id', null).neq('status', 'archived')
    .range(offset, offset).limit(1)
  return data?.[0] || null
}

export async function getFeelingReplies(refId) {
  const { data, error } = await supabase.from(TABLE).select('*')
    .eq('ref_id', refId).order('created_at', { ascending: true })
  if (error) throw error
  if (!data || data.length === 0) return { earliest: null, latest: null, count: 0 }
  return {
    earliest: data[0],
    latest: data.length > 1 ? data[data.length - 1] : null,
    count: data.length,
  }
}

export async function getFeelingCount() {
  const { count } = await supabase.from(TABLE).select('*', { count: 'exact', head: true })
    .eq('kind', '记感受').is('ref_id', null).neq('status', 'archived')
  return count || 0
}

export async function addFeelingReply(refId, { content, tag, feeling }) {
  return addBrainEntry({
    content,
    kind: '记感受',
    tag,
    feeling,
    refId: refId,
  })
}
