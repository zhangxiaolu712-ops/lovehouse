import { supabase } from '../../core/supabase'

export async function getQuotes({ limit = 50 } = {}) {
  const { data, error } = await supabase.from('quotes').select('*').order('created_at', { ascending: false }).limit(limit)
  if (error) throw error
  return data
}

export async function addQuote({ content, speaker = '小克' }) {
  const { data, error } = await supabase.from('quotes').insert({ content, speaker }).select().single()
  if (error) throw error
  return data
}

export async function deleteQuote(id) {
  const { error } = await supabase.from('quotes').delete().eq('id', id)
  if (error) throw error
}
