import { useState, useEffect } from 'react'
import { getDiaries, addDiary, deleteDiary } from './diaryService'

const MOODS = ['😊 开心', '😌 平静', '😢 难过', '😤 生气', '🥱 疲惫', '🥰 甜蜜', '🤔 思考']

export default function DiaryPage() {
  const [diaries, setDiaries] = useState([])
  const [title, setTitle] = useState('')
  const [content, setContent] = useState('')
  const [mood, setMood] = useState('')
  const [showForm, setShowForm] = useState(false)
  const [loading, setLoading] = useState(true)

  useEffect(() => { load() }, [])

  async function load() {
    setLoading(true)
    const data = await getDiaries()
    setDiaries(data)
    setLoading(false)
  }

  async function handleAdd(e) {
    e.preventDefault()
    if (!content.trim()) return
    await addDiary({ title: title.trim() || null, content: content.trim(), mood: mood || null })
    setTitle('')
    setContent('')
    setMood('')
    setShowForm(false)
    load()
  }

  async function handleDelete(id) {
    await deleteDiary(id)
    load()
  }

  return (
    <div>
      <div className="page-header">
        <h1 className="page-title">日记本</h1>
        <button className="btn" onClick={() => setShowForm(!showForm)}>{showForm ? '收起' : '写日记'}</button>
      </div>

      {showForm && (
        <form onSubmit={handleAdd} className="card" style={{ marginBottom: 16 }}>
          <div className="form-group">
            <input className="input" value={title} onChange={e => setTitle(e.target.value)} placeholder="标题（可选）" />
          </div>
          <div className="form-group">
            <textarea className="textarea" value={content} onChange={e => setContent(e.target.value)} placeholder="写点什么..." style={{ minHeight: 120 }} />
          </div>
          <div className="form-row">
            <select className="input" value={mood} onChange={e => setMood(e.target.value)} style={{ flex: 1 }}>
              <option value="">选择心情...</option>
              {MOODS.map(m => <option key={m} value={m}>{m}</option>)}
            </select>
            <button className="btn" type="submit">保存</button>
          </div>
        </form>
      )}

      {loading ? <div className="empty">加载中...</div> : diaries.length === 0 ? <div className="empty">还没有日记</div> : (
        diaries.map(d => (
          <div className="card" key={d.id}>
            <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
              <div>
                {d.title && <h3 style={{ marginBottom: 8 }}>{d.title}</h3>}
                <p style={{ lineHeight: 1.8, whiteSpace: 'pre-wrap' }}>{d.content}</p>
                <div style={{ marginTop: 10, display: 'flex', gap: 8, alignItems: 'center' }}>
                  {d.mood && <span className="tag">{d.mood}</span>}
                  <span style={{ fontSize: 12, color: 'var(--text-secondary)' }}>{new Date(d.created_at).toLocaleDateString('zh-CN')}</span>
                </div>
              </div>
              <button className="delete-btn" onClick={() => handleDelete(d.id)}>×</button>
            </div>
          </div>
        ))
      )}
    </div>
  )
}
