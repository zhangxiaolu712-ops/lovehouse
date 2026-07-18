import { supabase } from '../../core/supabase'

export async function getNotes({ limit = 100 } = {}) {
  const { data, error } = await supabase.from('notes').select('*').order('created_at', { ascending: false }).limit(limit)
  if (error) throw error
  return data
}

export async function addNote({ content, author = '小婷', color = 'pink' }) {
  const { data, error } = await supabase.from('notes').insert({ content, author, color }).select().single()
  if (error) throw error
  return data
}

export async function deleteNote(id) {
  const { error } = await supabase.from('notes').delete().eq('id', id)
  if (error) throw error
}
