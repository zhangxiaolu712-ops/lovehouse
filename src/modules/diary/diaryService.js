import { supabase } from '../../core/supabase'

export async function getDiaries({ limit = 50 } = {}) {
  const { data, error } = await supabase.from('diary').select('*').order('created_at', { ascending: false }).limit(limit)
  if (error) throw error
  return data
}

export async function addDiary({ title, content, mood }) {
  const { data, error } = await supabase.from('diary').insert({ title, content, mood }).select().single()
  if (error) throw error
  return data
}

export async function deleteDiary(id) {
  const { error } = await supabase.from('diary').delete().eq('id', id)
  if (error) throw error
}
