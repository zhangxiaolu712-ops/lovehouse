import { useState, useEffect } from 'react'
import { getStreams, addStream, deleteStream } from './streamService'

export default function StreamPage() {
  const [streams, setStreams] = useState([])
  const [title, setTitle] = useState('')
  const [content, setContent] = useState('')
  const [loading, setLoading] = useState(true)
  const [revealed, setRevealed] = useState(() => new Set())

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

  function toggleReveal(id) {
    setRevealed(prev => {
      const next = new Set(prev)
      next.has(id) ? next.delete(id) : next.add(id)
      return next
    })
  }

  return (
    <div>
      <div className="page-header">
        <h1 className="page-title">🔒 私密记录</h1>
        <span className="tag">点开才能看清</span>
      </div>

      <form onSubmit={handleAdd} className="card" style={{ marginBottom: 16 }}>
        <div className="form-group">
          <input className="input" value={title} onChange={e => setTitle(e.target.value)} placeholder="标题（可选）" />
        </div>
        <div className="form-group">
          <textarea className="textarea" value={content} onChange={e => setContent(e.target.value)} placeholder="只有我们看的记录..." />
        </div>
        <button className="btn" type="submit">收进来</button>
      </form>

      {loading ? <div className="empty">加载中...</div> : streams.length === 0 ? <div className="empty">还没有私密记录</div> : (
        streams.map(s => {
          const isOpen = revealed.has(s.id)
          return (
            <div
              className={`card private-card ${isOpen ? 'open' : ''}`}
              key={s.id}
              onClick={() => toggleReveal(s.id)}
              title={isOpen ? '点击模糊' : '点击查看'}
            >
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
                <div style={{ flex: 1 }} className="private-content">
                  {s.title && <h3 style={{ marginBottom: 6 }}>{s.title}</h3>}
                  <p style={{ lineHeight: 1.7, whiteSpace: 'pre-wrap' }}>{s.content}</p>
                  <p style={{ marginTop: 8, fontSize: 12, color: 'var(--text-secondary)' }}>{new Date(s.created_at).toLocaleString('zh-CN')}</p>
                </div>
                {isOpen && (
                  <button className="delete-btn" onClick={e => { e.stopPropagation(); handleDelete(s.id) }}>×</button>
                )}
              </div>
              {!isOpen && <div className="private-hint">🔒 点击查看</div>}
            </div>
          )
        })
      )}
    </div>
  )
}
