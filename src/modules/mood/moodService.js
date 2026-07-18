import { supabase } from '../../core/supabase'

export async function getMoodLogs({ limit = 50 } = {}) {
  const { data, error } = await supabase.from('mood_log').select('*').order('created_at', { ascending: false }).limit(limit)
  if (error) throw error
  return data
}

export async function addMoodLog({ mood, note }) {
  const { data, error } = await supabase.from('mood_log').insert({ mood, note }).select().single()
  if (error) throw error
  return data
}

export async function deleteMoodLog(id) {
  const { error } = await supabase.from('mood_log').delete().eq('id', id)
  if (error) throw error
}
