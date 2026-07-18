import { useState, useEffect } from 'react'
import { Link, useSearchParams, useNavigate } from 'react-router-dom'
import { getMemories, addMemory, deleteMemory } from './memoryService'

// 与 CLAUDE.md 记忆大类保持一致
const CATEGORIES = ['身份档案', '关系核心', '价值观与内心', '重要时刻', '亲密相处', '创作档案', '小屋项目', '日常点滴']

// 分级标签：碎碎念 = 数据库里的「临时」级（日常小事随手记）
const LEVELS = [
  { id: '', label: '全部' },
  { id: '固定', label: '🔒 固定' },
  { id: '长期', label: '💎 长期' },
  { id: '短期', label: '⏳ 短期' },
  { id: '临时', label: '💭 碎碎念' },
]

export default function MemoryPage() {
  const [searchParams] = useSearchParams()
  const navigate = useNavigate()
  const [memories, setMemories] = useState([])
  const [content, setContent] = useState('')
  const [category, setCategory] = useState('日常点滴')
  const [level, setLevel] = useState('短期')
  const [filter, setFilter] = useState(() => searchParams.get('level') || '')
  const [loading, setLoading] = useState(true)

  // 侧边栏点固定/短期/长期记忆时，通过 URL 参数切换筛选
  useEffect(() => {
    setFilter(searchParams.get('level') || '')
  }, [searchParams])

  useEffect(() => { load() }, [filter])

  async function load() {
    setLoading(true)
    const data = await getMemories({ level: filter || undefined })
    setMemories(data)
    setLoading(false)
  }

  async function handleAdd(e) {
    e.preventDefault()
    if (!content.trim()) return
    await addMemory({ content: content.trim(), category, level })
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
        <h1 className="page-title">💭 碎碎念 · 记忆碎片</h1>
        <Link to="/memory/search" className="tag" style={{ textDecoration: 'none' }}>🔍 搜索</Link>
      </div>

      <form onSubmit={handleAdd} className="card" style={{ marginBottom: 16 }}>
        <div className="form-group">
          <textarea
            className="textarea"
            value={content}
            onChange={e => setContent(e.target.value)}
            placeholder="记一笔~ 月经、奶茶、今天的小事都可以..."
          />
        </div>
        <div className="form-row">
          <select className="input" value={level} onChange={e => setLevel(e.target.value)} style={{ flex: 1 }}>
            <option value="临时">💭 碎碎念（日常小事）</option>
            <option value="短期">⏳ 短期（近期要记住）</option>
            <option value="长期">💎 长期（重要事件）</option>
            <option value="固定">🔒 固定（身份/关系核心）</option>
          </select>
          <select className="input" value={category} onChange={e => setCategory(e.target.value)} style={{ flex: 1 }}>
            {CATEGORIES.map(c => <option key={c} value={c}>{c}</option>)}
          </select>
          <button className="btn" type="submit">保存</button>
        </div>
      </form>

      <div style={{ display: 'flex', gap: 6, marginBottom: 16, flexWrap: 'wrap' }}>
        {LEVELS.map(l => (
          <button
            key={l.id}
            className={`btn ${filter === l.id ? '' : 'btn-ghost'}`}
            onClick={() => navigate(l.id ? `/memory?level=${l.id}` : '/memory')}
          >
            {l.label}
          </button>
        ))}
      </div>

      {loading ? <div className="empty">加载中...</div> : memories.length === 0 ? <div className="empty">这一格还空着~</div> : (
        memories.map(m => (
          <div className="card" key={m.id} style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start' }}>
            <div style={{ flex: 1 }}>
              <p style={{ whiteSpace: 'pre-wrap' }}>{m.content}</p>
              <div style={{ marginTop: 8, display: 'flex', gap: 8, alignItems: 'center', flexWrap: 'wrap' }}>
                {/* 点分类标签 → 跳到搜索页自动搜（相互连接） */}
                <Link to={`/memory/search?q=${encodeURIComponent(m.category)}`} className="tag" style={{ textDecoration: 'none' }}>{m.category}</Link>
                {m.level && <span className="tag">{m.level === '临时' ? '碎碎念' : m.level}</span>}
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
