import { supabase } from '../../core/supabase'

export async function getMemories({ category, level, limit = 100 } = {}) {
  let query = supabase.from('memories').select('*').order('created_at', { ascending: false }).limit(limit)
  if (category) query = query.eq('category', category)
  if (level) query = query.eq('level', level)
  const { data, error } = await query
  if (error) throw error
  return data
}

export async function searchMemories(keyword, { limit = 50 } = {}) {
  const { data, error } = await supabase
    .from('memories')
    .select('*')
    .ilike('content', `%${keyword}%`)
    .order('created_at', { ascending: false })
    .limit(limit)
  if (error) throw error
  return data
}

export async function addMemory({ content, category = '日常点滴', level = '短期', importance = 1 }) {
  const { data, error } = await supabase.from('memories').insert({ content, category, level, importance }).select().single()
  if (error) throw error
  return data
}

export async function deleteMemory(id) {
  const { error } = await supabase.from('memories').delete().eq('id', id)
  if (error) throw error
}
