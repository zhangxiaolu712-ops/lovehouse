import { useCallback, useEffect, useMemo, useRef, useState } from 'react'
import { Link } from 'react-router'
import { supabase } from '../../core/supabase'
import LineIcon from '../../shared/LineIcon'

const POLL_MS = 5_000
const HOUSE_TIME_ZONE = 'Asia/Shanghai'
const TICKET_MARKER = /工单|任务卡|功能需求|升级需求|补充需求|完成报告/

const SENDER_META = {
  小婷: { label: 'T', className: 'is-ting' },
  CC: { label: 'K', className: 'is-cc' },
  GPT: { label: 'G', className: 'is-gpt' },
}

function getDateParts(timestamp) {
  const parts = new Intl.DateTimeFormat('zh-CN', {
    timeZone: HOUSE_TIME_ZONE,
    year: 'numeric',
    month: '2-digit',
    day: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
    hour12: false,
  }).formatToParts(new Date(timestamp))

  return Object.fromEntries(parts.map(part => [part.type, part.value]))
}

function fmtTime(timestamp) {
  if (!timestamp) return ''
  const date = getDateParts(timestamp)
  const today = getDateParts(new Date().toISOString())
  const time = `${date.hour}:${date.minute}`
  const sameDay = date.year === today.year && date.month === today.month && date.day === today.day
  return sameDay ? time : `${date.year}-${date.month}-${date.day} ${time}`
}

function ticketTitle(message) {
  const firstLine = message.split('\n').map(line => line.trim()).find(Boolean) || '未命名工单'
  const bracketTitle = firstLine.match(/^【([^】]+)】(.*)$/)

  if (bracketTitle) {
    const label = bracketTitle[1].replace(/^待办任务卡[｜|]?/, '').trim()
    const detail = bracketTitle[2].split(/[。；]/)[0].trim()
    return /补充工单/.test(label) && detail
      ? `${label}：${detail}`.slice(0, 38)
      : label
  }

  return firstLine
    .replace(/^📋\s*/u, '')
    .replace(/^【/, '')
    .replace(/】$/, '')
    .replace(/^\[/, '')
    .replace(/\]$/, '')
    .replace(/^待办任务卡[｜|]?/, '')
    .trim()
}

function isTicketMessage(message) {
  const firstLine = message.split('\n').map(line => line.trim()).find(Boolean) || ''
  return TICKET_MARKER.test(firstLine)
}

function ticketPriority(message) {
  const explicit = message.match(/\bP([0-3])\b/i)
  if (explicit) return `P${explicit[1]}`
  if (/不急|优先级不高|低优先级|后续升级/.test(message)) return 'P3'
  return '未分级'
}

function ticketStatus(message) {
  if (/已完成|完成报告|已经就绪|已落地/.test(message)) return 'completed'
  if (/阻断|待审批|待确认|冻结/.test(message)) return 'waiting'
  return 'planned'
}

function collectTickets(messages) {
  return messages
    .filter(item => isTicketMessage(item.message))
    .map(item => ({
      ...item,
      title: ticketTitle(item.message),
      priority: ticketPriority(item.message),
      status: ticketStatus(item.message),
    }))
    .reverse()
}

function Avatar({ sender, size = 'normal' }) {
  const meta = SENDER_META[sender] || SENDER_META.CC
  return (
    <span className={`livingroom-avatar ${meta.className} is-${size}`} aria-label={sender} title={sender}>
      {meta.label}
    </span>
  )
}

function TicketDrawer({ tickets, onClose, onOpenMessage }) {
  const completed = tickets.filter(ticket => ticket.status === 'completed').length

  return (
    <div className="livingroom-drawer-layer" role="presentation" onMouseDown={event => {
      if (event.target === event.currentTarget) onClose()
    }}>
      <aside className="livingroom-drawer" role="dialog" aria-modal="true" aria-labelledby="livingroom-ticket-title">
        <header className="livingroom-drawer-head">
          <div>
            <span className="livingroom-kicker">PROJECT DESK</span>
            <h2 id="livingroom-ticket-title">工单簿</h2>
          </div>
          <button type="button" className="livingroom-icon-button" onClick={onClose} aria-label="关闭工单簿">
            <LineIcon name="close" size={19} />
          </button>
        </header>

        <div className="livingroom-ticket-summary">
          <span>{tickets.length} 张记录</span>
          <span>{completed} 张已完成</span>
        </div>

        <div className="livingroom-ticket-list">
          {tickets.length === 0 ? (
            <div className="livingroom-ticket-empty">
              <LineIcon name="project" size={28} />
              <p>小客厅里还没有可识别的工单消息。</p>
            </div>
          ) : tickets.map(ticket => (
            <button
              type="button"
              key={ticket.id}
              className={`livingroom-ticket is-${ticket.status}`}
              onClick={() => onOpenMessage(ticket.id)}
            >
              <span className="livingroom-ticket-topline">
                <span className={`livingroom-ticket-priority is-${ticket.priority.toLowerCase()}`}>{ticket.priority}</span>
                <span className="livingroom-ticket-state">
                  {ticket.status === 'completed' ? '已完成' : ticket.status === 'waiting' ? '待确认' : '待排期'}
                </span>
              </span>
              <strong>{ticket.title}</strong>
              <span className="livingroom-ticket-meta">
                {ticket.status === 'completed' ? `完成于 ${fmtTime(ticket.created_at)}` : `${ticket.sender} · ${fmtTime(ticket.created_at)}`}
              </span>
            </button>
          ))}
        </div>

        <footer className="livingroom-drawer-note">
          工单来自小客厅原消息；这里负责索引和回看，不另外复制数据库。
        </footer>
      </aside>
    </div>
  )
}

export default function LivingroomPage() {
  const [messages, setMessages] = useState([])
  const [input, setInput] = useState('')
  const [sending, setSending] = useState(false)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState('')
  const [ticketsOpen, setTicketsOpen] = useState(false)
  const listRef = useRef(null)
  const lastIdRef = useRef(0)
  const didInitRef = useRef(false)

  const tickets = useMemo(() => collectTickets(messages), [messages])

  const loadMessages = useCallback(async () => {
    setLoading(true)
    const { data, error: loadError } = await supabase
      .from('livingroom')
      .select('*')
      .order('created_at', { ascending: true })
      .limit(200)

    if (loadError) {
      setError('小客厅暂时没有连上，请稍后再试。')
    } else {
      setError('')
      setMessages(data || [])
      if (data?.length) lastIdRef.current = data[data.length - 1].id
    }
    setLoading(false)
  }, [])

  const pollNew = useCallback(async () => {
    if (!lastIdRef.current) return
    const { data } = await supabase
      .from('livingroom')
      .select('*')
      .gt('id', lastIdRef.current)
      .order('created_at', { ascending: true })

    if (data?.length) {
      setMessages(previous => [...previous, ...data])
      lastIdRef.current = data[data.length - 1].id
    }
  }, [])

  useEffect(() => {
    if (didInitRef.current) return
    didInitRef.current = true
    loadMessages()
  }, [loadMessages])

  useEffect(() => {
    const interval = setInterval(pollNew, POLL_MS)
    return () => clearInterval(interval)
  }, [pollNew])

  useEffect(() => {
    const list = listRef.current
    if (list) list.scrollTop = list.scrollHeight
  }, [messages])

  useEffect(() => {
    if (!ticketsOpen) return undefined
    function closeOnEscape(event) {
      if (event.key === 'Escape') setTicketsOpen(false)
    }
    window.addEventListener('keydown', closeOnEscape)
    return () => window.removeEventListener('keydown', closeOnEscape)
  }, [ticketsOpen])

  async function send(event) {
    event.preventDefault()
    const text = input.trim()
    if (!text || sending) return

    setSending(true)
    setInput('')
    setError('')

    try {
      const { data, error: sendError } = await supabase
        .from('livingroom')
        .insert({ sender: '小婷', message: text })
        .select()

      if (sendError) throw sendError
      if (data?.length) {
        setMessages(previous => [...previous, ...data])
        lastIdRef.current = data[data.length - 1].id
      }
    } catch {
      setInput(text)
      setError('消息没有送出去，内容已经替你保留。')
    } finally {
      setSending(false)
    }
  }

  function openTicketMessage(id) {
    setTicketsOpen(false)
    requestAnimationFrame(() => {
      document.querySelector(`[data-livingroom-message="${id}"]`)?.scrollIntoView({ behavior: 'smooth', block: 'center' })
    })
  }

  return (
    <div className="livingroom-page">
      <header className="livingroom-head">
        <Link to="/" className="livingroom-icon-button" aria-label="返回小屋">
          <LineIcon name="back" size={19} />
        </Link>

        <div className="livingroom-heading">
          <span className="livingroom-kicker">T · K · G</span>
          <h1>Livingroom</h1>
        </div>

        <div className="livingroom-head-tools">
          <div className="livingroom-people" aria-label="小客厅成员">
            <Avatar sender="小婷" size="small" />
            <Avatar sender="CC" size="small" />
            <Avatar sender="GPT" size="small" />
          </div>
          <button
            type="button"
            className="livingroom-icon-button"
            onClick={() => setTicketsOpen(true)}
            aria-label={`打开工单簿，共 ${tickets.length} 张记录`}
          >
            <LineIcon name="more" size={20} />
          </button>
        </div>
      </header>

      <div className="livingroom-messages" ref={listRef} aria-live="polite">
        {loading && <div className="livingroom-state">正在把小客厅的灯打开…</div>}
        {!loading && messages.length === 0 && !error && (
          <div className="livingroom-state">Welcome to the Livingroom.</div>
        )}

        {messages.map(message => {
          const isMe = message.sender === '小婷'
          return (
            <article
              key={message.id}
              data-livingroom-message={message.id}
              className={`livingroom-message${isMe ? ' is-me' : ''}`}
            >
              <Avatar sender={message.sender} />
              <div className="livingroom-message-body">
                {!isMe && <span className="livingroom-sender">{message.sender}</span>}
                <div className="livingroom-bubble">{message.message}</div>
                <time dateTime={message.created_at}>{fmtTime(message.created_at)}</time>
              </div>
            </article>
          )
        })}
      </div>

      <form className="livingroom-compose" onSubmit={send}>
        {error && <div className="livingroom-error" role="status">{error}</div>}
        <div className="livingroom-compose-row">
          <input
            type="text"
            value={input}
            onChange={event => setInput(event.target.value)}
            placeholder="在小客厅说点什么…"
            aria-label="小客厅消息"
          />
          <button type="submit" className="livingroom-send" disabled={sending || !input.trim()} aria-label="发送消息">
            <LineIcon name="send" size={18} />
          </button>
        </div>
      </form>

      {ticketsOpen && (
        <TicketDrawer tickets={tickets} onClose={() => setTicketsOpen(false)} onOpenMessage={openTicketMessage} />
      )}
    </div>
  )
}
