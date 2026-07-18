import { useState, useEffect } from 'react'
import { Link } from 'react-router-dom'
import { useTheme } from '../core/theme'
import { getMemories } from '../modules/memory/memoryService'
import { getDiaries } from '../modules/diary/diaryService'
import { getTodos } from '../modules/todo/todoService'
import { getQuotes } from '../modules/quotes/quotesService'

const NAV_CARDS = [
  { path: '/diary', icon: '📖', label: '日记本', desc: '记录每一天' },
  { path: '/memory', icon: '💎', label: '记忆碎片', desc: '珍贵的回忆' },
  { path: '/quotes', icon: '💬', label: '语录墙', desc: '那些话语' },
  { path: '/todo', icon: '✅', label: '待办事项', desc: '要做的事' },
  { path: '/mood', icon: '🌈', label: '心情日志', desc: '此刻的感受' },
  { path: '/stream', icon: '🌊', label: '动态流', desc: '生活点滴' },
]

export default function Home() {
  const { theme, themes, switchTheme, themeId } = useTheme()
  const [stats, setStats] = useState({ memories: 0, diaries: 0, todos: 0, quote: null })

  useEffect(() => {
    Promise.all([
      getMemories({ limit: 1 }),
      getDiaries({ limit: 1 }),
      getTodos(),
      getQuotes({ limit: 100 }),
    ]).then(([mem, dia, todos, quotes]) => {
      const randomQuote = quotes.length > 0 ? quotes[Math.floor(Math.random() * quotes.length)] : null
      setStats({
        memories: mem.length > 0,
        diaries: dia.length > 0,
        todos: todos.filter(t => !t.done).length,
        quote: randomQuote,
      })
    })
  }, [])

  return (
    <div>
      <div style={{ textAlign: 'center', padding: '20px 0 30px' }}>
        <h1 style={{ fontSize: 28, marginBottom: 6 }}>{theme.icon} LoveHouse</h1>
        <p style={{ color: 'var(--text-secondary)' }}>你的数字小屋</p>
      </div>

      {stats.quote && (
        <div className="card" style={{ textAlign: 'center', marginBottom: 20 }}>
          <p style={{ fontStyle: 'italic', lineHeight: 1.8 }}>"{stats.quote.content}"</p>
          <p style={{ marginTop: 6, fontSize: 13, color: 'var(--text-secondary)' }}>—— {stats.quote.speaker}</p>
        </div>
      )}

      {stats.todos > 0 && (
        <Link to="/todo" style={{ textDecoration: 'none' }}>
          <div className="card" style={{ marginBottom: 20, display: 'flex', alignItems: 'center', gap: 12 }}>
            <span style={{ fontSize: 24 }}>📋</span>
            <span>你还有 <strong>{stats.todos}</strong> 项待办未完成</span>
          </div>
        </Link>
      )}

      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(2, 1fr)', gap: 12, marginBottom: 24 }}>
        {NAV_CARDS.map(item => (
          <Link key={item.path} to={item.path} style={{ textDecoration: 'none', color: 'inherit' }}>
            <div className="card" style={{ textAlign: 'center', padding: 20 }}>
              <div style={{ fontSize: 32, marginBottom: 8 }}>{item.icon}</div>
              <div style={{ fontWeight: 600, marginBottom: 4 }}>{item.label}</div>
              <div style={{ fontSize: 12, color: 'var(--text-secondary)' }}>{item.desc}</div>
            </div>
          </Link>
        ))}
      </div>

      <div className="card">
        <p style={{ marginBottom: 10, fontWeight: 600 }}>切换主题</p>
        <div className="theme-switcher">
          {Object.values(themes).map(t => (
            <button key={t.id} className={`theme-option ${themeId === t.id ? 'active' : ''}`} onClick={() => switchTheme(t.id)}>
              {t.icon} {t.name}
            </button>
          ))}
        </div>
      </div>
    </div>
  )
}
