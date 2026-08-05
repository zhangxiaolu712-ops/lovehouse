import { useState, useEffect } from 'react'
import { Link } from 'react-router'
import LineIcon from './LineIcon'

const SINCE = new Date('2026-06-02')

const APPS = [
  { to: '/brain',          icon: 'memory', label: 'Brain' },
  { to: '/diary',          icon: 'book',   label: 'Diary' },
  { to: '/space/notes',    icon: 'mail',   label: 'Notes' },
  { to: '/todo',           icon: 'check',  label: 'Todo' },
  { to: '/mood',           icon: 'mood',   label: 'Mood' },
  { to: '/quotes',         icon: 'quote',  label: 'Quotes' },
  { to: '/stream',         icon: 'lock',   label: 'Private' },
  { to: '/memory/search',  icon: 'search', label: 'Search' },
  { to: '/space/theme',    icon: 'theme',  label: 'Theme' },
  { to: '/changelog',      icon: 'history', label: 'Log' },
  { to: '/space/clawd',    icon: 'paw',    label: 'Clawd' },
  { to: '/space/games',    icon: 'game',   label: 'Games' },
]

function pad(n) { return String(n).padStart(2, '0') }
function clock() { const d = new Date(); return `${pad(d.getHours())}:${pad(d.getMinutes())}` }

export default function Home() {
  const [time, setTime] = useState(clock)

  useEffect(() => {
    const id = setInterval(() => setTime(clock()), 30_000)
    return () => clearInterval(id)
  }, [])

  const now  = new Date()
  const days = Math.floor((now - SINCE) / 864e5)
  const date = `${now.getFullYear()}.${pad(now.getMonth() + 1)}.${pad(now.getDate())}`
  const wday = ['Sun', 'Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat'][now.getDay()]

  return (
    <div className="pd">
      <header className="pd-head">
        <div className="pd-pair">
          <i className="pd-av">T</i>
          <svg className="pd-pulse" viewBox="0 0 80 24" aria-hidden="true">
            <path d="M0 12h18l4-8 4 16 4-8 4 8 4-16 4 8h18"
              fill="none" stroke="currentColor" strokeWidth="1.2"
              strokeLinecap="round" strokeLinejoin="round" />
          </svg>
          <i className="pd-av">K</i>
        </div>
        <strong className="pd-days">{days}</strong>
        <small className="pd-since">DAYS TOGETHER</small>
      </header>

      <section className="pd-widget">
        <span className="pd-time">{time}</span>
        <span className="pd-date">{date} · {wday}</span>
      </section>

      <nav className="pd-grid" aria-label="Apps">
        {APPS.map(a => (
          <Link key={a.to} to={a.to} className="pd-app">
            <span className="pd-icon"><LineIcon name={a.icon} size={22} /></span>
            <span className="pd-label">{a.label}</span>
          </Link>
        ))}
      </nav>
    </div>
  )
}
