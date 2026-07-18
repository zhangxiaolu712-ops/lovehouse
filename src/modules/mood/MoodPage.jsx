import { useState, useEffect } from 'react'
import { getMoodLogs, addMoodLog, deleteMoodLog } from './moodService'

const MOOD_OPTIONS = [
  { emoji: '😊', label: '开心' },
  { emoji: '😌', label: '平静' },
  { emoji: '🥰', label: '甜蜜' },
  { emoji: '😢', label: '难过' },
  { emoji: '😤', label: '生气' },
  { emoji: '🥱', label: '疲惫' },
  { emoji: '😰', label: '焦虑' },
  { emoji: '🤔', label: '思考' },
]

export default function MoodPage() {
  const [logs, setLogs] = useState([])
  const [mood, setMood] = useState('')
  const [note, setNote] = useState('')
  const [loading, setLoading] = useState(true)

  useEffect(() => { load() }, [])

  async function load() {
    setLoading(true)
    const data = await getMoodLogs()
    setLogs(data)
    setLoading(false)
  }

  async function handleAdd() {
    if (!mood) return
    await addMoodLog({ mood, note: note.trim() || null })
    setMood('')
    setNote('')
    load()
  }

  async function handleDelete(id) {
    await deleteMoodLog(id)
    load()
  }

  return (
    <div>
      <div className="page-header">
        <h1 className="page-title">心情日志</h1>
      </div>

      <div className="card" style={{ marginBottom: 16 }}>
        <p style={{ marginBottom: 12, color: 'var(--text-secondary)' }}>现在的心情是？</p>
        <div style={{ display: 'flex', gap: 8, flexWrap: 'wrap', marginBottom: 12 }}>
          {MOOD_OPTIONS.map(m => (
            <button
              key={m.emoji}
              onClick={() => setMood(m.emoji + ' ' + m.label)}
              style={{
                padding: '8px 14px',
                borderRadius: 'var(--radius)',
                border: mood === m.emoji + ' ' + m.label ? '2px solid var(--accent)' : '1px solid var(--border)',
                background: mood === m.emoji + ' ' + m.label ? 'var(--accent-light)' : 'var(--bg-secondary)',
                cursor: 'pointer',
                fontSize: 14,
                color: 'var(--text-primary)',
              }}
            >
              {m.emoji} {m.label}
            </button>
          ))}
        </div>
        <div className="form-group">
          <input className="input" value={note} onChange={e => setNote(e.target.value)} placeholder="备注（可选）" />
        </div>
        <button className="btn" onClick={handleAdd} disabled={!mood}>记录</button>
      </div>

      {loading ? <div className="empty">加载中...</div> : logs.length === 0 ? <div className="empty">还没有心情记录</div> : (
        logs.map(l => (
          <div className="card" key={l.id} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <div>
              <span style={{ fontSize: 18 }}>{l.mood}</span>
              {l.note && <p style={{ marginTop: 4, color: 'var(--text-secondary)', fontSize: 13 }}>{l.note}</p>}
              <p style={{ marginTop: 4, fontSize: 12, color: 'var(--text-secondary)' }}>{new Date(l.created_at).toLocaleString('zh-CN')}</p>
            </div>
            <button className="delete-btn" onClick={() => handleDelete(l.id)}>×</button>
          </div>
        ))
      )}
    </div>
  )
}
