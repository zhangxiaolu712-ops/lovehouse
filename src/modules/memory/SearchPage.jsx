import { useState } from 'react'
import { supabase } from '../../core/supabase'

// 各数据源的检索配置：表名、内容字段、展示信息
const SOURCES = [
  { table: 'memories', fields: ['content'], icon: '💭', label: '记忆' },
  { table: 'diary', fields: ['title', 'content'], icon: '📖', label: '日记' },
  { table: 'quotes', fields: ['content'], icon: '💬', label: '语录' },
  { table: 'notes', fields: ['content'], icon: '💌', label: '纸条' },
  { table: 'stream', fields: ['title', 'content'], icon: '🔒', label: '私密' },
]

export default function SearchPage() {
  const [keyword, setKeyword] = useState('')
  const [results, setResults] = useState(null)
  const [loading, setLoading] = useState(false)

  async function handleSearch(e) {
    e?.preventDefault()
    const kw = keyword.trim()
    if (!kw) return
    setLoading(true)
    const all = await Promise.all(
      SOURCES.map(async src => {
        try {
          const orFilter = src.fields.map(f => `${f}.ilike.%${kw}%`).join(',')
          const { data } = await supabase
            .from(src.table)
            .select('*')
            .or(orFilter)
            .order('created_at', { ascending: false })
            .limit(20)
          return (data || []).map(row => ({ ...row, _src: src }))
        } catch {
          return []
        }
      }),
    )
    setResults(all.flat().sort((a, b) => new Date(b.created_at) - new Date(a.created_at)))
    setLoading(false)
  }

  return (
    <div>
      <div className="page-header">
        <h1 className="page-title">🔍 搜索整理</h1>
        <span className="tag">全屋检索</span>
      </div>

      <form onSubmit={handleSearch} className="card" style={{ marginBottom: 16 }}>
        <div className="form-row">
          <input
            className="input"
            value={keyword}
            onChange={e => setKeyword(e.target.value)}
            placeholder="输入关键词，搜遍记忆/日记/语录/纸条/私密记录..."
            style={{ flex: 1 }}
            autoFocus
          />
          <button className="btn" type="submit">搜索</button>
        </div>
      </form>

      {loading ? (
        <div className="empty">翻箱倒柜中...</div>
      ) : results === null ? (
        <div className="empty">想找什么？输入关键词就开搜~</div>
      ) : results.length === 0 ? (
        <div className="empty">没找到「{keyword}」相关的内容</div>
      ) : (
        <>
          <div style={{ fontSize: 13, color: 'var(--text-muted)', marginBottom: 12 }}>找到 {results.length} 条</div>
          {results.map(r => (
            <div className="card" key={`${r._src.table}-${r.id}`}>
              {r.title && <h3 style={{ marginBottom: 6 }}>{r.title}</h3>}
              <p style={{ lineHeight: 1.7, whiteSpace: 'pre-wrap' }}>{r.content}</p>
              <div style={{ marginTop: 8, display: 'flex', gap: 8, alignItems: 'center' }}>
                <span className="tag">{r._src.icon} {r._src.label}</span>
                {r.category && <span className="tag">{r.category}</span>}
                <span style={{ fontSize: 12, color: 'var(--text-secondary)' }}>{new Date(r.created_at).toLocaleDateString('zh-CN')}</span>
              </div>
            </div>
          ))}
        </>
      )}
    </div>
  )
}
