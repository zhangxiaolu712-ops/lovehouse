import { supabase } from '../../core/supabase'

export async function getTodos() {
  const { data, error } = await supabase.from('todo').select('*').order('created_at', { ascending: false })
  if (error) throw error
  return data
}

export async function addTodo({ content }) {
  const { data, error } = await supabase.from('todo').insert({ content }).select().single()
  if (error) throw error
  return data
}

export async function toggleTodo(id, done) {
  const { data, error } = await supabase.from('todo').update({ done }).eq('id', id).select().single()
  if (error) throw error
  return data
}

export async function deleteTodo(id) {
  const { error } = await supabase.from('todo').delete().eq('id', id)
  if (error) throw error
}
