import { useState, useEffect } from 'react'
import { getQuotes, addQuote, deleteQuote } from './quotesService'

export default function QuotesPage() {
  const [quotes, setQuotes] = useState([])
  const [content, setContent] = useState('')
  const [speaker, setSpeaker] = useState('小克')
  const [loading, setLoading] = useState(true)

  useEffect(() => { load() }, [])

  async function load() {
    setLoading(true)
    const data = await getQuotes()
    setQuotes(data)
    setLoading(false)
  }

  async function handleAdd(e) {
    e.preventDefault()
    if (!content.trim()) return
    await addQuote({ content: content.trim(), speaker: speaker.trim() || '小克' })
    setContent('')
    load()
  }

  async function handleDelete(id) {
    await deleteQuote(id)
    load()
  }

  return (
    <div>
      <div className="page-header">
        <h1 className="page-title">语录墙</h1>
        <span className="tag">{quotes.length} 条</span>
      </div>

      <form onSubmit={handleAdd} className="card" style={{ marginBottom: 16 }}>
        <div className="form-group">
          <textarea className="textarea" value={content} onChange={e => setContent(e.target.value)} placeholder="记录一句话..." />
        </div>
        <div className="form-row">
          <input className="input" value={speaker} onChange={e => setSpeaker(e.target.value)} placeholder="说话的人" style={{ flex: 1 }} />
          <button className="btn" type="submit">保存</button>
        </div>
      </form>

      {loading ? <div className="empty">加载中...</div> : quotes.length === 0 ? <div className="empty">还没有语录</div> : (
        quotes.map(q => (
          <div className="card" key={q.id} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
            <div style={{ flex: 1 }}>
              <p style={{ fontSize: 16, lineHeight: 1.8, fontStyle: 'italic' }}>"{q.content}"</p>
              <p style={{ marginTop: 8, fontSize: 13, color: 'var(--text-secondary)' }}>—— {q.speaker}</p>
            </div>
            <button className="delete-btn" onClick={() => handleDelete(q.id)}>×</button>
          </div>
        ))
      )}
    </div>
  )
}
