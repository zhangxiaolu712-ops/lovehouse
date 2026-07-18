import { useState, useEffect } from 'react'
import { getStreams, addStream, deleteStream } from './streamService'

export default function StreamPage() {
  const [streams, setStreams] = useState([])
  const [title, setTitle] = useState('')
  const [content, setContent] = useState('')
  const [loading, setLoading] = useState(true)

  useEffect(() => { load() }, [])

  async function load() {
    setLoading(true)
    const data = await getStreams()
    setStreams(data)
    setLoading(false)
  }

  async function handleAdd(e) {
    e.preventDefault()
    if (!content.trim()) return
    await addStream({ title: title.trim() || null, content: content.trim() })
    setTitle('')
    setContent('')
    load()
  }

  async function handleDelete(id) {
    await deleteStream(id)
    load()
  }

  return (
    <div>
      <div className="page-header">
        <h1 className="page-title">动态流</h1>
      </div>

      <form onSubmit={handleAdd} className="card" style={{ marginBottom: 16 }}>
        <div className="form-group">
          <input className="input" value={title} onChange={e => setTitle(e.target.value)} placeholder="标题（可选）" />
        </div>
        <div className="form-group">
          <textarea className="textarea" value={content} onChange={e => setContent(e.target.value)} placeholder="发一条动态..." />
        </div>
        <button className="btn" type="submit">发布</button>
      </form>

      {loading ? <div className="empty">加载中...</div> : streams.length === 0 ? <div className="empty">还没有动态</div> : (
        streams.map(s => (
          <div className="card" key={s.id} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
            <div style={{ flex: 1 }}>
              {s.title && <h3 style={{ marginBottom: 6 }}>{s.title}</h3>}
              <p style={{ lineHeight: 1.7, whiteSpace: 'pre-wrap' }}>{s.content}</p>
              <p style={{ marginTop: 8, fontSize: 12, color: 'var(--text-secondary)' }}>{new Date(s.created_at).toLocaleString('zh-CN')}</p>
            </div>
            <button className="delete-btn" onClick={() => handleDelete(s.id)}>×</button>
          </div>
        ))
      )}
    </div>
  )
}
