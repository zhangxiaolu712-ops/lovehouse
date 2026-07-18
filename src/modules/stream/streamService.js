import { supabase } from '../../core/supabase'

export async function getStreams({ limit = 50 } = {}) {
  const { data, error } = await supabase.from('stream').select('*').order('created_at', { ascending: false }).limit(limit)
  if (error) throw error
  return data
}

export async function addStream({ title, content }) {
  const { data, error } = await supabase.from('stream').insert({ title, content }).select().single()
  if (error) throw error
  return data
}

export async function deleteStream(id) {
  const { error } = await supabase.from('stream').delete().eq('id', id)
  if (error) throw error
}
