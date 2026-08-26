import { useEffect, useMemo, useState } from 'react'
import { Link } from 'react-router'
import LineIcon from '../../shared/LineIcon'
import { getGptMemoryTimeline } from './gptMemoryService'
import './gptMemory.css'

function formatDate(value) {
  if (!value) return '时间未标注'
  const date = new Date(value)
  if (Number.isNaN(date.valueOf())) return '时间未标注'
  return date.toLocaleDateString('zh-CN', { month: '2-digit', day: '2-digit' }).replace('/', '.')
}

function formatTime(value) {
  if (!value) return '—'
  const date = new Date(value)
  if (Number.isNaN(date.valueOf())) return '—'
  return date.toLocaleTimeString('zh-CN', { hour: '2-digit', minute: '2-digit', hour12: false })
}

function titleFor(item) {
  const metadata = item.metadata || {}
  return metadata.title || metadata.summary || item.content?.slice(0, 22) || '一条记忆'
}

export default function GptMemoryPage() {
  const [items, setItems] = useState([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')

  useEffect(() => {
    let alive = true
    getGptMemoryTimeline()
      .then(data => { if (alive) setItems(data) })
      .catch(err => { if (alive) setError(err.message || '记忆读取失败') })
      .finally(() => { if (alive) setLoading(false) })
    return () => { alive = false }
  }, [])

  const latestDate = useMemo(() => formatDate(items[0]?.event_time || items[0]?.created_at), [items])

  return (
    <main className="gpt-memory-page">
      <header className="gpt-memory-head">
        <Link to="/" className="gpt-memory-back" aria-label="返回首页"><LineIcon name="back" size={22} /></Link>
        <div>
          <p>GPT MEMORY · V2</p>
          <h1>Flow</h1>
          <small>近期时间流 · {latestDate}</small>
        </div>
        <span className="gpt-memory-mark" aria-hidden="true">G</span>
      </header>

      <section className="gpt-memory-intro">
        <div className="gpt-memory-rule"><span>03</span><i /><span>MEMORY FLOW</span></div>
        <h2>The memories<br /><em>move quietly.</em></h2>
        <p>先把 GPT 的 Memory V2 按时间摊开。现在只负责看清信息，不替原文做新的解释。</p>
      </section>

      {loading && <div className="gpt-memory-state">正在读取 GPT Memory V2…</div>}
      {error && <div className="gpt-memory-state is-error">{error}</div>}
      {!loading && !error && !items.length && <div className="gpt-memory-state">这一段时间流还空着。</div>}

      {!loading && !error && items.length > 0 && (
        <section className="gpt-memory-timeline">
          {items.map((item, index) => (
            <article className="gpt-memory-item" key={item.memory_id || `${item.created_at}-${index}`}>
              <span className={`gpt-memory-dot${index === 0 ? ' is-latest' : ''}`} />
              <div className="gpt-memory-copy">
                <small>{formatDate(item.event_time || item.created_at)} · {item.space_key || 'gpt'} · {item.status || 'active'}</small>
                <h3>{titleFor(item)}</h3>
                <p>{item.content}</p>
                <div className="gpt-memory-meta">
                  <span>{item.source_count || 0} source</span>
                  {item.revision_number && <span>revision {item.revision_number}</span>}
                  {item.metadata?.memory_type && <span>{item.metadata.memory_type}</span>}
                </div>
              </div>
              <time>{formatTime(item.event_time || item.created_at)}</time>
            </article>
          ))}
        </section>
      )}
    </main>
  )
}
