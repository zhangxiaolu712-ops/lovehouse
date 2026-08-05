import { useState, useMemo } from 'react'
import { Link } from 'react-router'
import LineIcon from '../../shared/LineIcon'

function getChatHistory() {
  try { return JSON.parse(localStorage.getItem('lovehouse_chat_history')) || [] }
  catch { return [] }
}

function pad(n) { return String(n).padStart(2, '0') }
function fmtDate(d) { return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}` }
function getWeekDay(d) { return (d.getDay() + 6) % 7 }

function buildHeatmap(messages) {
  const counts = {}
  let total = 0
  let earliest = null
  let latest = null

  for (const msg of messages) {
    if (!msg.time) continue
    const d = new Date(msg.time)
    const key = fmtDate(d)
    counts[key] = (counts[key] || 0) + 1
    total++
    if (!earliest || d < earliest) earliest = d
    if (!latest || d > latest) latest = d
  }

  return { counts, total, earliest, latest }
}

function buildWeeks(year, month) {
  const first = new Date(year, month, 1)
  const last = new Date(year, month + 1, 0)
  const weeks = []
  let week = new Array(getWeekDay(first)).fill(null)

  for (let d = 1; d <= last.getDate(); d++) {
    const date = new Date(year, month, d)
    week.push(fmtDate(date))
    if (week.length === 7) {
      weeks.push(week)
      week = []
    }
  }
  if (week.length) {
    while (week.length < 7) week.push(null)
    weeks.push(week)
  }
  return weeks
}

function getLevel(count, max) {
  if (!count) return 0
  if (max <= 0) return 1
  const ratio = count / max
  if (ratio <= 0.25) return 1
  if (ratio <= 0.5) return 2
  if (ratio <= 0.75) return 3
  return 4
}

const MONTHS = ['一月','二月','三月','四月','五月','六月','七月','八月','九月','十月','十一月','十二月']
const WDAYS = ['一','二','三','四','五','六','日']

export default function StatsPage() {
  const messages = useMemo(() => getChatHistory(), [])
  const { counts, total, earliest } = useMemo(() => buildHeatmap(messages), [messages])

  const now = new Date()
  const [year, setYear] = useState(now.getFullYear())
  const [month, setMonth] = useState(now.getMonth())
  const [selected, setSelected] = useState(null)

  const weeks = useMemo(() => buildWeeks(year, month), [year, month])
  const maxCount = useMemo(() => {
    let mx = 0
    for (const w of weeks) for (const d of w) if (d && counts[d] > mx) mx = counts[d]
    return mx
  }, [weeks, counts])

  const userMsgs = messages.filter(m => m.role === 'user').length
  const aiMsgs = messages.filter(m => m.role === 'assistant').length
  const days = Object.keys(counts).length
  const streak = useMemo(() => {
    let s = 0
    const d = new Date()
    while (true) {
      const key = fmtDate(d)
      if (counts[key]) { s++; d.setDate(d.getDate() - 1) }
      else break
    }
    return s
  }, [counts])

  const prevMonth = () => {
    if (month === 0) { setMonth(11); setYear(y => y - 1) }
    else setMonth(m => m - 1)
  }
  const nextMonth = () => {
    if (month === 11) { setMonth(0); setYear(y => y + 1) }
    else setMonth(m => m + 1)
  }

  return (
    <div className="st">
      <div className="st-head">
        <Link to="/" className="st-back"><LineIcon name="back" size={20} /></Link>
        <span className="st-title">Stats</span>
        <span style={{ width: 20 }} />
      </div>

      {/* Summary cards */}
      <div className="st-cards">
        <div className="st-card">
          <span className="st-card-num">{total}</span>
          <span className="st-card-label">总消息</span>
        </div>
        <div className="st-card">
          <span className="st-card-num">{days}</span>
          <span className="st-card-label">活跃天</span>
        </div>
        <div className="st-card">
          <span className="st-card-num">{streak}</span>
          <span className="st-card-label">连续天</span>
        </div>
        <div className="st-card">
          <span className="st-card-num">{days ? Math.round(total / days) : 0}</span>
          <span className="st-card-label">日均</span>
        </div>
      </div>

      {/* Who said more */}
      <div className="st-ratio">
        <div className="st-ratio-bar">
          <div className="st-ratio-t" style={{ flex: userMsgs || 1 }} />
          <div className="st-ratio-k" style={{ flex: aiMsgs || 1 }} />
        </div>
        <div className="st-ratio-labels">
          <span>小婷 {userMsgs}</span>
          <span>小克 {aiMsgs}</span>
        </div>
      </div>

      {/* Month nav */}
      <div className="st-month-nav">
        <button className="st-nav-btn" onClick={prevMonth}>
          <LineIcon name="back" size={16} />
        </button>
        <span className="st-month-title">{year}年 {MONTHS[month]}</span>
        <button className="st-nav-btn" onClick={nextMonth}>
          <svg className="line-icon" width="16" height="16" viewBox="0 0 24 24"
            fill="none" stroke="currentColor" strokeWidth="1.55"
            strokeLinecap="round" strokeLinejoin="round">
            <path d="M9 5l7 7-7 7" />
          </svg>
        </button>
      </div>

      {/* Heatmap */}
      <div className="st-heatmap">
        <div className="st-wdays">
          {WDAYS.map(w => <span key={w} className="st-wday">{w}</span>)}
        </div>
        <div className="st-grid">
          {weeks.map((week, wi) => (
            <div key={wi} className="st-week">
              {week.map((day, di) => (
                <div
                  key={di}
                  className={`st-cell lv${day ? getLevel(counts[day], maxCount) : ''}`}
                  data-empty={!day || undefined}
                  onClick={() => day && counts[day] && setSelected(day === selected ? null : day)}
                >
                  {day && <span className="st-day-num">{parseInt(day.split('-')[2])}</span>}
                </div>
              ))}
            </div>
          ))}
        </div>

        {/* Legend */}
        <div className="st-legend">
          <span className="st-legend-label">少</span>
          <span className="st-cell-sm lv0" />
          <span className="st-cell-sm lv1" />
          <span className="st-cell-sm lv2" />
          <span className="st-cell-sm lv3" />
          <span className="st-cell-sm lv4" />
          <span className="st-legend-label">多</span>
        </div>
      </div>

      {/* Selected day detail */}
      {selected && (
        <div className="st-detail">
          <span className="st-detail-date">{selected}</span>
          <span className="st-detail-count">{counts[selected]} 条消息</span>
        </div>
      )}

      {earliest && (
        <div className="st-since">
          第一条消息: {fmtDate(earliest)}
        </div>
      )}
    </div>
  )
}
