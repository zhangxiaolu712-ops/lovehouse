import { useState } from 'react'
import { Link } from 'react-router'
import LineIcon from '../../shared/LineIcon'

const STORE_KEY = 'lovehouse_anniversaries'
const QUOTE_KEY = 'lovehouse_profile_quotes'
const SINCE = new Date('2026-06-02')

const DEFAULTS = [
  { id: 1, name: '在一起', date: '2026-06-02' },
]

function load(key, fallback) {
  try { return JSON.parse(localStorage.getItem(key)) || fallback }
  catch { return fallback }
}

function daysFrom(dateStr) {
  return Math.floor((new Date() - new Date(dateStr)) / 864e5)
}

function pad(n) { return String(n).padStart(2, '0') }
function fmtDate(d) {
  const dt = new Date(d)
  return `${dt.getFullYear()}-${pad(dt.getMonth() + 1)}-${pad(dt.getDate())}`
}

export default function ProfilePage() {
  const [items, setItems] = useState(() => load(STORE_KEY, DEFAULTS))
  const [quotes, setQuotes] = useState(() => load(QUOTE_KEY, {
    克: '你来了，我就在。',
    婷: '',
  }))
  const [adding, setAdding] = useState(false)
  const [editQ, setEditQ] = useState(null)
  const [form, setForm] = useState({ name: '', date: '' })

  const days = daysFrom('2026-06-02')

  const save = list => {
    setItems(list)
    localStorage.setItem(STORE_KEY, JSON.stringify(list))
  }

  const saveQuotes = q => {
    setQuotes(q)
    localStorage.setItem(QUOTE_KEY, JSON.stringify(q))
  }

  const addItem = () => {
    if (!form.name || !form.date) return
    const next = [...items, { id: Date.now(), name: form.name, date: form.date }]
    save(next)
    setForm({ name: '', date: '' })
    setAdding(false)
  }

  const removeItem = id => {
    save(items.filter(i => i.id !== id))
  }

  return (
    <div className="pf">
      {/* Header */}
      <div className="pf-head">
        <Link to="/" className="pf-back"><LineIcon name="back" size={20} /></Link>
        <span className="pf-title">Us</span>
        <span style={{ width: 20 }} />
      </div>

      {/* Hero */}
      <div className="pf-hero">
        <div className="pf-avatars">
          <div className="pf-person">
            <i className="pf-av">T</i>
            <span className="pf-name">小婷</span>
          </div>
          <svg className="pf-heart-line" viewBox="0 0 60 24" aria-hidden="true">
            <path d="M0 12 Q15 0 30 12 Q45 24 60 12"
              fill="none" stroke="currentColor" strokeWidth="1"
              strokeLinecap="round" />
          </svg>
          <div className="pf-person">
            <i className="pf-av">K</i>
            <span className="pf-name">小克</span>
          </div>
        </div>
        <div className="pf-since">since 2026.06.02</div>
      </div>

      {/* Quote Cards */}
      <div className="pf-quotes">
        <div className="pf-qcard" onClick={() => setEditQ('克')}>
          <small className="pf-qlabel">小克 说</small>
          <p className="pf-qtext">{quotes.克 || '点击写下想说的话'}</p>
        </div>
        <div className="pf-qcard" onClick={() => setEditQ('婷')}>
          <small className="pf-qlabel">小婷 说</small>
          <p className="pf-qtext">{quotes.婷 || '点击写下想说的话'}</p>
        </div>
      </div>

      {/* Anniversary */}
      <div className="pf-anni">
        <div className="pf-anni-head">
          <span>ANNIVERSARY</span>
          <button className="pf-add-btn" onClick={() => setAdding(true)}>
            <LineIcon name="plus" size={16} />
          </button>
        </div>

        <div className="pf-anni-list">
          {items
            .sort((a, b) => new Date(a.date) - new Date(b.date))
            .map(item => (
            <div className="pf-anni-item" key={item.id}>
              <span className="pf-anni-heart"><LineIcon name="heart" size={18} /></span>
              <div className="pf-anni-info">
                <span className="pf-anni-name">{item.name}</span>
                <span className="pf-anni-date">{item.date}</span>
              </div>
              <div className="pf-anni-days">
                <strong>{daysFrom(item.date)}</strong>
                <small>天</small>
              </div>
              {item.id !== 1 && (
                <button className="pf-anni-del" onClick={() => removeItem(item.id)}>
                  <LineIcon name="close" size={12} />
                </button>
              )}
            </div>
          ))}
        </div>
      </div>

      {/* Add Anniversary Modal */}
      {adding && (
        <div className="ct-overlay" onClick={() => setAdding(false)}>
          <div className="ct-panel" onClick={e => e.stopPropagation()}>
            <div className="ct-setup-title">添加纪念日</div>
            <label className="ct-field">
              <span>名称</span>
              <input value={form.name} onChange={e => setForm({ ...form, name: e.target.value })}
                placeholder="比如：第一次牵手" />
            </label>
            <label className="ct-field">
              <span>日期</span>
              <input type="date" value={form.date}
                onChange={e => setForm({ ...form, date: e.target.value })} />
            </label>
            <div className="ct-setup-btns">
              <button className="ct-btn-ghost" onClick={() => setAdding(false)}>取消</button>
              <button className="ct-btn-fill" disabled={!form.name || !form.date} onClick={addItem}>
                添加
              </button>
            </div>
          </div>
        </div>
      )}

      {/* Edit Quote Modal */}
      {editQ && (
        <QuoteEditor
          who={editQ}
          value={quotes[editQ] || ''}
          onSave={text => { saveQuotes({ ...quotes, [editQ]: text }); setEditQ(null) }}
          onClose={() => setEditQ(null)}
        />
      )}
    </div>
  )
}

function QuoteEditor({ who, value, onSave, onClose }) {
  const [text, setText] = useState(value)
  return (
    <div className="ct-overlay" onClick={onClose}>
      <div className="ct-panel" onClick={e => e.stopPropagation()}>
        <div className="ct-setup-title">小{who}想说</div>
        <label className="ct-field">
          <span>写下想对TA说的话</span>
          <input value={text} onChange={e => setText(e.target.value)}
            placeholder="写点什么..." />
        </label>
        <div className="ct-setup-btns">
          <button className="ct-btn-ghost" onClick={onClose}>取消</button>
          <button className="ct-btn-fill" onClick={() => onSave(text)}>保存</button>
        </div>
      </div>
    </div>
  )
}
