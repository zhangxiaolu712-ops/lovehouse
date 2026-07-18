import { useState, useEffect } from 'react'
import { getTodos, addTodo, toggleTodo, deleteTodo } from './todoService'

export default function TodoPage() {
  const [todos, setTodos] = useState([])
  const [content, setContent] = useState('')
  const [loading, setLoading] = useState(true)

  useEffect(() => { load() }, [])

  async function load() {
    setLoading(true)
    const data = await getTodos()
    setTodos(data)
    setLoading(false)
  }

  async function handleAdd(e) {
    e.preventDefault()
    if (!content.trim()) return
    await addTodo({ content: content.trim() })
    setContent('')
    load()
  }

  async function handleToggle(id, done) {
    await toggleTodo(id, !done)
    load()
  }

  async function handleDelete(id) {
    await deleteTodo(id)
    load()
  }

  const undone = todos.filter(t => !t.done)
  const done = todos.filter(t => t.done)

  return (
    <div>
      <div className="page-header">
        <h1 className="page-title">待办事项</h1>
        <span className="tag">{undone.length} 项待完成</span>
      </div>

      <form onSubmit={handleAdd} className="card" style={{ marginBottom: 16 }}>
        <div className="form-row">
          <input className="input" value={content} onChange={e => setContent(e.target.value)} placeholder="添加新的待办..." style={{ flex: 1 }} />
          <button className="btn" type="submit">添加</button>
        </div>
      </form>

      {loading ? <div className="empty">加载中...</div> : todos.length === 0 ? <div className="empty">没有待办事项</div> : (
        <div className="card">
          {undone.map(t => (
            <div className="todo-item" key={t.id}>
              <input type="checkbox" className="todo-checkbox" checked={false} onChange={() => handleToggle(t.id, t.done)} />
              <span className="todo-text">{t.content}</span>
              <button className="delete-btn" onClick={() => handleDelete(t.id)}>×</button>
            </div>
          ))}
          {done.length > 0 && (
            <>
              <p style={{ fontSize: 13, color: 'var(--text-secondary)', padding: '12px 0 4px', borderTop: undone.length ? '1px solid var(--border)' : 'none' }}>已完成</p>
              {done.map(t => (
                <div className="todo-item" key={t.id}>
                  <input type="checkbox" className="todo-checkbox" checked onChange={() => handleToggle(t.id, t.done)} />
                  <span className="todo-text done">{t.content}</span>
                  <button className="delete-btn" onClick={() => handleDelete(t.id)}>×</button>
                </div>
              ))}
            </>
          )}
        </div>
      )}
    </div>
  )
}
