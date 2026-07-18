import { useState, useEffect } from 'react'
import { getNotes, addNote, deleteNote } from './notesService'

const COLORS = [
  { id: 'pink', label: '🌸 粉' },
  { id: 'blue', label: '💙 蓝' },
  { id: 'yellow', label: '⭐ 黄' },
  { id: 'green', label: '🌿 绿' },
]

const AUTHORS = ['小婷', '小克']

export default function NotesPage() {
  const [notes, setNotes] = useState([])
  const [content, setContent] = useState('')
  const [author, setAuthor] = useState('小婷')
  const [color, setColor] = useState('pink')
  const [loading, setLoading] = useState(true)

  useEffect(() => { load() }, [])

  async function load() {
    setLoading(true)
    const data = await getNotes()
    setNotes(data)
    setLoading(false)
  }

  async function handleAdd(e) {
    e.preventDefault()
    if (!content.trim()) return
    await addNote({ content: content.trim(), author, color })
    setContent('')
    load()
  }

  async function handleDelete(id) {
    await deleteNote(id)
    load()
  }

  return (
    <div>
      <div className="page-header">
        <h1 className="page-title">💌 小纸条留言板</h1>
        <span className="tag">{notes.length} 张</span>
      </div>

      <form onSubmit={handleAdd} className="card" style={{ marginBottom: 20 }}>
        <div className="form-group">
          <textarea
            className="textarea"
            value={content}
            onChange={e => setContent(e.target.value)}
            placeholder="写张小纸条贴上去..."
          />
        </div>
        <div className="form-row" style={{ alignItems: 'center', flexWrap: 'wrap' }}>
          <div className="note-author-toggle">
            {AUTHORS.map(a => (
              <button
                key={a}
                type="button"
                className={`note-toggle-btn ${author === a ? 'active' : ''}`}
                onClick={() => setAuthor(a)}
              >
                {a}
              </button>
            ))}
          </div>
          <div className="note-color-picker">
            {COLORS.map(c => (
              <button
                key={c.id}
                type="button"
                title={c.label}
                className={`note-color-dot note-color-${c.id} ${color === c.id ? 'active' : ''}`}
                onClick={() => setColor(c.id)}
              />
            ))}
          </div>
          <button className="btn" type="submit" style={{ marginLeft: 'auto' }}>贴上去</button>
        </div>
      </form>

      {loading ? (
        <div className="empty">加载中...</div>
      ) : notes.length === 0 ? (
        <div className="empty">留言板还空着，贴第一张小纸条吧~</div>
      ) : (
        <div className="notes-wall">
          {notes.map(n => (
            <div key={n.id} className={`note-paper note-color-${n.color}`}>
              <div className="note-pin">📌</div>
              <p className="note-content">{n.content}</p>
              <div className="note-meta">
                <span>{n.author === '小克' ? '🐻' : '🐱'} {n.author}</span>
                <span>{new Date(n.created_at).toLocaleDateString('zh-CN', { month: 'numeric', day: 'numeric' })}</span>
              </div>
              <button className="delete-btn note-delete" onClick={() => handleDelete(n.id)}>×</button>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
