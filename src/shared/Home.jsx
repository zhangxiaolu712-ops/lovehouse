import { useState, useEffect, useRef } from 'react'
import { Link } from 'react-router'
import LineIcon from './LineIcon'
import { getQuotes } from '../modules/quotes/quotesService'

const SINCE = new Date('2026-06-02')

function pad(n) { return String(n).padStart(2, '0') }
function clock() { const d = new Date(); return `${pad(d.getHours())}:${pad(d.getMinutes())}` }

function AppIcon({ to, icon, label }) {
  return (
    <Link to={to} className="pd-app">
      <span className="pd-icon"><LineIcon name={icon} size={22} /></span>
      <span className="pd-label">{label}</span>
    </Link>
  )
}

export default function Home() {
  const [time, setTime] = useState(clock)
  const [page, setPage] = useState(0)
  const [quote, setQuote] = useState(null)
  const ref = useRef(null)

  function goToPage(nextPage) {
    const el = ref.current
    if (!el) return
    el.scrollTo({ left: nextPage * el.clientWidth, behavior: 'smooth' })
    setPage(nextPage)
  }

  useEffect(() => {
    const id = setInterval(() => setTime(clock()), 30_000)
    return () => clearInterval(id)
  }, [])

  useEffect(() => {
    getQuotes({ limit: 50 }).then(arr => {
      if (arr.length) setQuote(arr[Math.floor(Math.random() * arr.length)])
    }).catch(() => {})
  }, [])

  const now  = new Date()
  const days = Math.floor((now - SINCE) / 864e5)
  const date = `${now.getFullYear()}.${pad(now.getMonth() + 1)}.${pad(now.getDate())}`
  const wday = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'][now.getDay()]

  return (
    <div className="pd">
      {/* Hero card */}
      <header className="pd-hero">
        <div className="pd-pair">
          <i className="pd-av">T</i>
          <svg className="pd-pulse" viewBox="0 0 80 24" aria-hidden="true">
            <path d="M0 12h18l4-8 4 16 4-8 4 8 4-16 4 8h18"
              fill="none" stroke="currentColor" strokeWidth="1.2"
              strokeLinecap="round" strokeLinejoin="round" />
          </svg>
          <i className="pd-av">K</i>
        </div>
        <strong className="pd-hero-days">{days}</strong>
        <small className="pd-hero-since">DAYS TOGETHER</small>
      </header>

      {/* Swipeable pages */}
      <div className="pd-pages" ref={ref} aria-label="小屋桌面分页"
        onScroll={() => {
          const el = ref.current
          if (el) setPage(Math.round(el.scrollLeft / el.clientWidth))
        }}>

        {/* Page 1 — main apps + widgets */}
        <div className="pd-page">
          <div className="pd-mixed">
            <AppIcon to="/brain"       icon="memory" label="Brain" />
            <AppIcon to="/diary"       icon="book"   label="Diary" />

            {/* Clock widget 2×2 */}
            <div className="pd-wcard">
              <span className="pd-wtime">{time}</span>
              <span className="pd-wsub">{date}<br />{wday}</span>
            </div>

            <AppIcon to="/space/notes" icon="mail"   label="Notes" />
            <AppIcon to="/todo"        icon="check"  label="Todo" />

            {/* Quote widget 2×2 */}
            <div className="pd-wcard pd-wquote">
              <span className="pd-wqm">"</span>
              <p className="pd-wqt">{quote?.content || '你为你的玫瑰花费的时间，使你的玫瑰变得如此重要。'}</p>
              <small className="pd-wqf">— {quote?.speaker || '小王子'}</small>
            </div>

            <AppIcon to="/mood"          icon="mood"   label="Mood" />
            <AppIcon to="/memory/search" icon="search" label="Search" />
            <AppIcon to="/quotes"        icon="quote"  label="Quotes" />
            <AppIcon to="/stream"        icon="lock"   label="Private" />
          </div>
        </div>

        {/* Page 2 — more apps */}
        <div className="pd-page">
          <div className="pd-mixed">
            <AppIcon to="/space/theme" icon="theme"   label="Theme" />
            <AppIcon to="/changelog"   icon="history" label="Log" />

            {/* Days widget 2×2 */}
            <div className="pd-wcard pd-wdays">
              <span className="pd-wdays-num">{days}</span>
              <span className="pd-wsub">since 2026.06.02</span>
            </div>

            <AppIcon to="/livingroom"  icon="message" label="Room" />
            <AppIcon to="/autobiography" icon="edit" label="自传" />
            <AppIcon to="/space/clawd" icon="paw"     label="Clawd" />
            <AppIcon to="/profile"     icon="heart"   label="Us" />
            <AppIcon to="/stats"       icon="calendar" label="Stats" />
            <AppIcon to="/settings/status" icon="workbench" label="Config" />
            <AppIcon to="/codex-chat-v1" icon="workbench" label="Codex v1" />
            <AppIcon to="/claude-chat-v1" icon="message" label="Claude v1" />
            <AppIcon to="/engineering" icon="workbench" label="工程区" />
          </div>
        </div>
      </div>

      {/* Page dots */}
      <div className="pd-dots" aria-label="切换桌面分页">
        {[0, 1].map(index => (
          <button
            key={index}
            type="button"
            className={`pd-dot${page === index ? ' on' : ''}`}
            aria-label={`前往第 ${index + 1} 页`}
            aria-current={page === index ? 'page' : undefined}
            onClick={() => goToPage(index)}
          />
        ))}
      </div>
    </div>
  )
}
