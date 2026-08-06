import { useState, useEffect, useRef } from 'react'
import { Link } from 'react-router'
import { supabase } from '../../core/supabase'
import LineIcon from '../../shared/LineIcon'

const POLL_MS = 5_000

function fmtTime(ts) {
  const d = new Date(ts)
  const mon = d.getMonth() + 1
  const day = d.getDate()
  const h = String(d.getHours()).padStart(2, '0')
  const m = String(d.getMinutes()).padStart(2, '0')
  const today = new Date()
  if (d.toDateString() === today.toDateString()) return `${h}:${m}`
  return `${mon}/${day} ${h}:${m}`
}

const SENDER_STYLE = {
  '小婷': { align: 'right', bg: 'var(--accent)', color: '#fff', label: 'T' },
  'CC':   { align: 'left',  bg: 'var(--bg-card)', color: 'var(--text-primary)', label: 'K' },
  'GPT':  { align: 'left',  bg: 'var(--bg-card)', color: 'var(--text-primary)', label: 'G' },
}

function Avatar({ sender }) {
  const s = SENDER_STYLE[sender] || SENDER_STYLE['CC']
  const colors = {
    '小婷': 'linear-gradient(135deg, #f5a0b0, #e87890)',
    'CC':   'linear-gradient(135deg, #a0c4f5, #6a9ed4)',
    'GPT':  'linear-gradient(135deg, #a0e8b0, #5a9e6f)',
  }
  return (
    <span style={{
      width: 32, height: 32, borderRadius: '50%',
      display: 'inline-flex', alignItems: 'center', justifyContent: 'center',
      background: colors[sender] || colors['CC'],
      color: '#fff', fontWeight: 700, fontSize: 13, flexShrink: 0,
    }}>{s.label}</span>
  )
}

export default function LivingroomPage() {
  const [messages, setMessages] = useState([])
  const [input, setInput] = useState('')
  const [sending, setSending] = useState(false)
  const listRef = useRef(null)
  const lastIdRef = useRef(0)
  const didInitRef = useRef(false)

  async function loadMessages() {
    const { data } = await supabase
      .from('livingroom')
      .select('*')
      .order('created_at', { ascending: true })
      .limit(200)

    if (data) {
      setMessages(data)
      if (data.length) lastIdRef.current = data[data.length - 1].id
    }
  }

  async function pollNew() {
    if (!lastIdRef.current) return
    const { data } = await supabase
      .from('livingroom')
      .select('*')
      .gt('id', lastIdRef.current)
      .order('created_at', { ascending: true })

    if (data?.length) {
      setMessages(prev => [...prev, ...data])
      lastIdRef.current = data[data.length - 1].id
    }
  }

  useEffect(() => {
    if (didInitRef.current) return
    didInitRef.current = true
    loadMessages()
  }, [])

  useEffect(() => {
    const id = setInterval(pollNew, POLL_MS)
    return () => clearInterval(id)
  }, [])

  useEffect(() => {
    const el = listRef.current
    if (el) el.scrollTop = el.scrollHeight
  }, [messages])

  async function send(e) {
    e.preventDefault()
    const text = input.trim()
    if (!text || sending) return

    setSending(true)
    setInput('')
    try {
      const { data, error } = await supabase
        .from('livingroom')
        .insert({ sender: '小婷', message: text })
        .select()

      if (data?.length) {
        setMessages(prev => [...prev, ...data])
        lastIdRef.current = data[data.length - 1].id
      }
      if (error) console.error('[livingroom]', error.message)
    } catch (err) {
      console.error('[livingroom]', err.message)
    } finally {
      setSending(false)
    }
  }

  return (
    <div style={{ display: 'flex', flexDirection: 'column', height: '100vh', position: 'fixed', inset: 0, background: 'var(--bg-primary)', zIndex: 100 }}>
      {/* Header */}
      <header style={{
        display: 'flex', alignItems: 'center', gap: 10,
        padding: '12px 16px', borderBottom: '1px solid var(--border)',
        background: 'var(--bg-card)',
      }}>
        <Link to="/" style={{ color: 'var(--text-primary)', display: 'flex' }}>
          <LineIcon name="back" size={20} />
        </Link>
        <div style={{ flex: 1 }}>
          <div style={{ fontWeight: 600, fontSize: 15 }}>Livingroom</div>
          <div style={{ fontSize: 11, color: 'var(--text-muted)' }}>T + K + G</div>
        </div>
        <div style={{ display: 'flex', gap: 4 }}>
          <Avatar sender="小婷" />
          <Avatar sender="CC" />
          <Avatar sender="GPT" />
        </div>
      </header>

      {/* Messages */}
      <div ref={listRef} style={{
        flex: 1, overflowY: 'auto', padding: '16px 12px',
        display: 'flex', flexDirection: 'column', gap: 12,
      }}>
        {messages.length === 0 && (
          <div style={{ textAlign: 'center', color: 'var(--text-muted)', fontSize: 13, marginTop: 40 }}>
            Welcome to the Livingroom~
          </div>
        )}
        {messages.map(msg => {
          const s = SENDER_STYLE[msg.sender] || SENDER_STYLE['CC']
          const isMe = msg.sender === '小婷'
          return (
            <div key={msg.id} style={{
              display: 'flex', gap: 8,
              flexDirection: isMe ? 'row-reverse' : 'row',
              alignItems: 'flex-start',
            }}>
              <Avatar sender={msg.sender} />
              <div style={{ maxWidth: '72%' }}>
                {!isMe && (
                  <div style={{ fontSize: 11, color: 'var(--text-muted)', marginBottom: 2, paddingLeft: 2 }}>
                    {msg.sender}
                  </div>
                )}
                <div style={{
                  padding: '10px 14px',
                  borderRadius: isMe ? '16px 4px 16px 16px' : '4px 16px 16px 16px',
                  background: s.bg, color: s.color,
                  fontSize: 14, lineHeight: 1.6,
                  border: isMe ? 'none' : '1px solid var(--border)',
                  wordBreak: 'break-word',
                  whiteSpace: 'pre-wrap',
                }}>
                  {msg.message}
                </div>
                <div style={{
                  fontSize: 10, color: 'var(--text-muted)', marginTop: 3,
                  textAlign: isMe ? 'right' : 'left', paddingLeft: 2, paddingRight: 2,
                }}>
                  {fmtTime(msg.created_at)}
                </div>
              </div>
            </div>
          )
        })}
      </div>

      {/* Input */}
      <form onSubmit={send} style={{
        display: 'flex', gap: 8, padding: '10px 12px',
        borderTop: '1px solid var(--border)', background: 'var(--bg-card)',
      }}>
        <input
          type="text"
          value={input}
          onChange={e => setInput(e.target.value)}
          placeholder="Say something..."
          style={{
            flex: 1, padding: '10px 14px', borderRadius: 20,
            border: '1px solid var(--border)', background: 'var(--bg-primary)',
            color: 'var(--text-primary)', fontSize: 14, outline: 'none',
          }}
        />
        <button type="submit" disabled={sending || !input.trim()} style={{
          width: 40, height: 40, borderRadius: '50%',
          background: input.trim() ? 'var(--accent)' : 'var(--border)',
          border: 'none', color: '#fff', display: 'flex',
          alignItems: 'center', justifyContent: 'center', cursor: 'pointer',
          transition: 'background 0.2s',
        }}>
          <LineIcon name="send" size={18} />
        </button>
      </form>
    </div>
  )
}
