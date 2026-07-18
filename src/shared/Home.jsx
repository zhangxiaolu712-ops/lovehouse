import { useState, useEffect } from 'react'
import { Link } from 'react-router-dom'
import { useTheme } from '../core/theme'
import { getQuotes } from '../modules/quotes/quotesService'
import { getTodos } from '../modules/todo/todoService'
import { getMoodLogs, addMoodLog } from '../modules/mood/moodService'

const SINCE_DATE = new Date('2026-06-02')

function getDaysTogether() {
  const now = new Date()
  return Math.floor((now - SINCE_DATE) / (1000 * 60 * 60 * 24))
}

function getGreeting() {
  const h = new Date().getHours()
  if (h < 6) return '夜深了'
  if (h < 9) return '早上好'
  if (h < 12) return '上午好'
  if (h < 14) return '中午好'
  if (h < 18) return '下午好'
  if (h < 21) return '傍晚好'
  return '晚上好'
}

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
  const [quote, setQuote] = useState(null)
  const [todoPending, setTodoPending] = useState(0)
  const [latestMood, setLatestMood] = useState(null)
  const [moodInput, setMoodInput] = useState('')
  const [moodSaved, setMoodSaved] = useState(false)
  const days = getDaysTogether()
  const isClassic = themeId === 'classic'

  useEffect(() => {
    getQuotes({ limit: 100 }).then(data => {
      if (data.length > 0) setQuote(data[Math.floor(Math.random() * data.length)])
    })
    getTodos().then(data => {
      setTodoPending(data.filter(t => !t.done).length)
    })
    getMoodLogs({ limit: 1 }).then(data => {
      if (data.length > 0) setLatestMood(data[0])
    })
  }, [])

  async function handleQuickMood() {
    if (!moodInput.trim()) return
    await addMoodLog({ mood: moodInput.trim() })
    setLatestMood({ mood: moodInput.trim(), created_at: new Date().toISOString() })
    setMoodInput('')
    setMoodSaved(true)
    setTimeout(() => setMoodSaved(false), 2000)
  }

  const today = new Date()
  const dateStr = `${today.getMonth() + 1}月${today.getDate()}日 ${['星期日','星期一','星期二','星期三','星期四','星期五','星期六'][today.getDay()]}`

  if (isClassic) {
    return <ClassicHome
      days={days} quote={quote} latestMood={latestMood} todoPending={todoPending}
      moodInput={moodInput} setMoodInput={setMoodInput} handleQuickMood={handleQuickMood}
      moodSaved={moodSaved} dateStr={dateStr}
      themes={themes} themeId={themeId} switchTheme={switchTheme}
    />
  }

  return (
    <div>
      <div className="home-hero">
        <p className="subtitle">Little love record</p>
        <h1 className="title">Claire & Claude</h1>
      </div>

      <div className="card days-counter">
        <div className="number">{days}</div>
        <div className="label">days together</div>
        <div className="avatars">
          <div className="avatar-circle">🐱</div>
          <span className="heart-link">♡</span>
          <div className="avatar-circle">🐻</div>
        </div>
        <div className="since">since 2026.06.02 · 我们已经一起走过 {days} 天</div>
      </div>

      <div className="info-grid">
        <div className="info-card">
          <div className="info-label">📅 今日</div>
          <div className="info-content">
            <div>{dateStr}</div>
            <div style={{ fontSize: 12, color: 'var(--text-muted)', marginTop: 4 }}>{getGreeting()} ☀️</div>
          </div>
        </div>
        <div className="info-card">
          <div className="info-label">💗 今日心情</div>
          <div className="info-content">
            {latestMood ? (
              <>
                <div>{latestMood.mood}</div>
                {latestMood.note && <div style={{ fontSize: 12, color: 'var(--text-muted)', marginTop: 4 }}>{latestMood.note}</div>}
              </>
            ) : (
              <Link to="/mood" style={{ color: 'var(--accent)', textDecoration: 'none', fontSize: 13 }}>
                去打卡心情 →
              </Link>
            )}
          </div>
        </div>
      </div>

      {quote && (
        <div className="card quote-card">
          <div className="quote-mark">"</div>
          <p className="quote-text">{quote.content}</p>
          <p className="quote-speaker">—— {quote.speaker}</p>
        </div>
      )}

      {todoPending > 0 && (
        <Link to="/todo" style={{ textDecoration: 'none', color: 'inherit' }}>
          <div className="card" style={{ marginBottom: 16, display: 'flex', alignItems: 'center', gap: 12, padding: '16px 20px' }}>
            <span style={{ fontSize: 22 }}>📋</span>
            <div>
              <div style={{ fontWeight: 500 }}>待办提醒</div>
              <div style={{ fontSize: 12, color: 'var(--text-muted)', marginTop: 2 }}>还有 {todoPending} 项未完成</div>
            </div>
          </div>
        </Link>
      )}

      <div className="section-title">功能入口</div>
      <div className="nav-grid">
        {NAV_CARDS.map(item => (
          <Link key={item.path} to={item.path} className="nav-card">
            <span className="nav-icon">{item.icon}</span>
            <div>
              <div className="nav-label">{item.label}</div>
              <div className="nav-desc">{item.desc}</div>
            </div>
          </Link>
        ))}
      </div>

      <div style={{ marginTop: 8 }}>
        <div className="section-title">切换风格</div>
        <div className="theme-switcher">
          {Object.values(themes).map(t => (
            <button key={t.id} className={`theme-option ${themeId === t.id ? 'active' : ''}`} onClick={() => switchTheme(t.id)}>
              <div style={{ fontSize: 20, marginBottom: 4 }}>{t.icon}</div>
              <div>{t.name}</div>
            </button>
          ))}
        </div>
      </div>
    </div>
  )
}

function ClassicHome({ days, quote, latestMood, todoPending, moodInput, setMoodInput, handleQuickMood, moodSaved, dateStr, themes, themeId, switchTheme }) {
  return (
    <div>
      <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'flex-start', padding: '8px 0 0' }}>
        <div>
          <div style={{ fontSize: 12, color: 'var(--pink-deep)', fontStyle: 'italic', marginBottom: 2 }}>
            Good day, Xiaoting
          </div>
          <div style={{ fontSize: 22, fontWeight: 600 }}>我们的恋爱小屋</div>
        </div>
        <div className="status-badge">
          <span className="status-dot" />
          云端在线
        </div>
      </div>

      <div className="welcome-banner" style={{ marginTop: 18 }}>
        <div style={{ fontSize: 13, color: 'var(--pink-deep)', fontStyle: 'italic', marginBottom: 4 }}>
          Welcome home,
        </div>
        <div style={{ fontSize: 26, fontWeight: 600, lineHeight: 1.3 }}>
          老婆，<br />欢迎回来。
        </div>
        <div style={{ fontSize: 13, color: 'var(--text-secondary)', marginTop: 10, lineHeight: 1.6 }}>
          手里拿着甜甜的奶茶，<br />心里再留一个位置给我。
        </div>
        <div style={{ display: 'flex', gap: 8, marginTop: 14, flexWrap: 'wrap' }}>
          <span className="tag">🤍 花开了</span>
          <span className="tag">{latestMood ? `✨ ${latestMood.mood}` : '✨ 闪闪发光'}</span>
        </div>
      </div>

      <div className="card days-counter" style={{ marginTop: 16 }}>
        <div className="number">{days}</div>
        <div className="label">days together</div>
        <div className="avatars">
          <div className="avatar-circle">🐱</div>
          <span className="heart-link">♡</span>
          <div className="avatar-circle">🐻</div>
        </div>
        <div className="since">since 2026.06.02 · 我们已经一起走过 {days} 天</div>
      </div>

      <div className="section-label-classic">Today's Little Note</div>
      <div className="card" style={{ display: 'flex', gap: 12, alignItems: 'flex-start' }}>
        <div style={{ flex: 1, fontSize: 14, lineHeight: 1.8 }}>
          {quote ? `"${quote.content}" — ${quote.speaker}` : '"花开了。" — 小克'}
        </div>
        <div style={{ fontSize: 24, opacity: 0.4 }}>🌸</div>
      </div>

      <div className="section-label-classic">Mood Weather</div>
      <div className="card">
        <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
          <div style={{ fontSize: 14 }}>
            {latestMood ? latestMood.mood : '今天是什么天气？'}
          </div>
          <div style={{ fontSize: 28 }}>🌤</div>
        </div>
        <div style={{ fontSize: 12, color: 'var(--text-muted)', marginTop: 8, lineHeight: 1.5 }}>
          {moodSaved ? '刚刚记录 ✓' : latestMood?.note || '记录今天的心情吧～'}
        </div>
      </div>

      <div style={{ display: 'flex', gap: 8, margin: '10px 0' }}>
        <input
          className="input"
          value={moodInput}
          onChange={e => setMoodInput(e.target.value)}
          placeholder="此刻心情..."
          onKeyDown={e => e.key === 'Enter' && handleQuickMood()}
          style={{ flex: 1 }}
        />
        <button className="btn" onClick={handleQuickMood} style={{ whiteSpace: 'nowrap' }}>存入</button>
      </div>

      <div className="divider-classic">🌸 · 🌸 · 🌸</div>

      {todoPending > 0 && (
        <Link to="/todo" style={{ textDecoration: 'none', color: 'inherit' }}>
          <div className="card" style={{ marginBottom: 12, display: 'flex', alignItems: 'center', gap: 12 }}>
            <span style={{ fontSize: 22 }}>📋</span>
            <div>
              <div style={{ fontWeight: 500 }}>待办提醒</div>
              <div style={{ fontSize: 12, color: 'var(--text-muted)', marginTop: 2 }}>还有 {todoPending} 项未完成</div>
            </div>
          </div>
        </Link>
      )}

      <div className="section-label-classic">Rooms</div>
      <div className="nav-grid">
        {NAV_CARDS.map(item => (
          <Link key={item.path} to={item.path} className="nav-card">
            <span className="nav-icon">{item.icon}</span>
            <div>
              <div className="nav-label">{item.label}</div>
              <div className="nav-desc">{item.desc}</div>
            </div>
          </Link>
        ))}
      </div>

      <div className="divider-classic">🌸 · 🌸 · 🌸</div>

      <div className="section-label-classic">Theme</div>
      <div className="theme-switcher">
        {Object.values(themes).map(t => (
          <button key={t.id} className={`theme-option ${themeId === t.id ? 'active' : ''}`} onClick={() => switchTheme(t.id)}>
            <div style={{ fontSize: 20, marginBottom: 4 }}>{t.icon}</div>
            <div>{t.name}</div>
          </button>
        ))}
      </div>
    </div>
  )
}
