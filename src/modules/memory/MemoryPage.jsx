import { useState, useEffect } from 'react'
import { getMemories, addMemory, deleteMemory } from './memoryService'

const CATEGORIES = ['日常', '重要', '感悟', '梦境', '灵感']

export default function MemoryPage() {
  const [memories, setMemories] = useState([])
  const [content, setContent] = useState('')
  const [category, setCategory] = useState('日常')
  const [filter, setFilter] = useState('')
  const [loading, setLoading] = useState(true)

  useEffect(() => { load() }, [filter])

  async function load() {
    setLoading(true)
    const data = await getMemories({ category: filter || undefined })
    setMemories(data)
    setLoading(false)
  }

  async function handleAdd(e) {
    e.preventDefault()
    if (!content.trim()) return
    await addMemory({ content: content.trim(), category })
    setContent('')
    load()
  }

  async function handleDelete(id) {
    await deleteMemory(id)
    load()
  }

  return (
    <div>
      <div className="page-header">
        <h1 className="page-title">记忆碎片</h1>
        <span className="tag">{memories.length} 条</span>
      </div>

      <form onSubmit={handleAdd} className="card" style={{ marginBottom: 16 }}>
        <div className="form-group">
          <textarea className="textarea" value={content} onChange={e => setContent(e.target.value)} placeholder="记录一段记忆..." />
        </div>
        <div className="form-row">
          <select className="input" value={category} onChange={e => setCategory(e.target.value)} style={{ flex: 1 }}>
            {CATEGORIES.map(c => <option key={c} value={c}>{c}</option>)}
          </select>
          <button className="btn" type="submit">保存</button>
        </div>
      </form>

      <div style={{ display: 'flex', gap: 6, marginBottom: 16, flexWrap: 'wrap' }}>
        <button className={`btn ${!filter ? '' : 'btn-ghost'}`} onClick={() => setFilter('')}>全部</button>
        {CATEGORIES.map(c => (
          <button key={c} className={`btn ${filter === c ? '' : 'btn-ghost'}`} onClick={() => setFilter(c)}>{c}</button>
        ))}
      </div>

      {loading ? <div className="empty">加载中...</div> : memories.length === 0 ? <div className="empty">还没有记忆</div> : (
        memories.map(m => (
          <div className="card" key={m.id} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
            <div style={{ flex: 1 }}>
              <p>{m.content}</p>
              <div style={{ marginTop: 8, display: 'flex', gap: 8, alignItems: 'center' }}>
                <span className="tag">{m.category}</span>
                <span style={{ fontSize: 12, color: 'var(--text-secondary)' }}>{new Date(m.created_at).toLocaleDateString('zh-CN')}</span>
              </div>
            </div>
            <button className="delete-btn" onClick={() => handleDelete(m.id)}>×</button>
          </div>
        ))
      )}
    </div>
  )
}
