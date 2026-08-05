import { useState, useEffect, useRef } from 'react'
import { Link } from 'react-router'
import LineIcon from '../../shared/LineIcon'
import {
  getChatConfig, saveChatConfig,
  getChatHistory, saveChatHistory,
  getChatSession, saveChatSession,
  clearChat, streamMessage,
} from './chatService'

function fmtTime(ts) {
  const d = new Date(ts)
  return `${String(d.getHours()).padStart(2, '0')}:${String(d.getMinutes()).padStart(2, '0')}`
}

export default function ChatPage() {
  const [messages, setMessages] = useState(() => getChatHistory())
  const [input, setInput] = useState('')
  const [loading, setLoading] = useState(false)
  const [stream, setStream] = useState('')
  const [thinkStream, setThinkStream] = useState('')
  const [expanded, setExpanded] = useState({})
  const [panel, setPanel] = useState(null)
  const listRef = useRef(null)

  const config = getChatConfig()
  const connected = !!(config.apiUrl && config.apiKey)

  useEffect(() => {
    const el = listRef.current
    if (el) el.scrollTop = el.scrollHeight
  }, [messages, stream])

  useEffect(() => { saveChatHistory(messages) }, [messages])

  const send = async () => {
    const text = input.trim()
    if (!text || loading) return
    if (!connected) { setPanel('setup'); return }

    const userMsg = { role: 'user', content: text, time: Date.now() }
    const updated = [...messages, userMsg]
    setMessages(updated)
    setInput('')
    setLoading(true)
    setStream('')
    setThinkStream('')

    await streamMessage(updated, config, {
      onThinking: t => setThinkStream(t),
      onText: t => setStream(t),
      onDone: ({ content, thinking }) => {
        setMessages(prev => [...prev, {
          role: 'assistant', content, thinking, time: Date.now(),
        }])
        setLoading(false)
        setStream('')
        setThinkStream('')
        const s = getChatSession()
        saveChatSession({
          ...s,
          startTime: s.startTime || Date.now(),
          turns: (s.turns || 0) + 1,
          lastActive: Date.now(),
        })
      },
      onError: err => {
        setMessages(prev => [...prev, {
          role: 'assistant', content: `连接失败: ${err}`, time: Date.now(), error: true,
        }])
        setLoading(false)
        setStream('')
        setThinkStream('')
      },
    })
  }

  return (
    <div className="ct">
      {/* Header */}
      <div className="ct-head">
        <Link to="/" className="ct-back"><LineIcon name="back" size={20} /></Link>
        <span className="ct-name">
          小克
          {connected && <span className="ct-dot green" />}
        </span>
        <button className="ct-av-btn" onClick={() => setPanel('profile')}>
          <i className="ct-av">K</i>
        </button>
      </div>

      {/* Messages */}
      <div className="ct-list" ref={listRef}>
        {messages.length === 0 && !loading && (
          <div className="ct-empty">
            <p>和小克说点什么吧~</p>
            {!connected && (
              <button className="ct-link-btn" onClick={() => setPanel('setup')}>
                设置 API 连接
              </button>
            )}
          </div>
        )}

        {messages.map((msg, i) => (
          <div key={i}>
            {msg.role === 'assistant' && msg.thinking && (
              <ThinkingBlock
                text={msg.thinking}
                open={!!expanded[i]}
                toggle={() => setExpanded(p => ({ ...p, [i]: !p[i] }))}
              />
            )}
            <div className={`ct-row ${msg.role}`}>
              {msg.role === 'assistant' && <i className="ct-msg-av">K</i>}
              <div className={`ct-bubble ${msg.role}${msg.error ? ' err' : ''}`}>
                {msg.content}
              </div>
              {msg.role === 'user' && <i className="ct-msg-av">T</i>}
            </div>
          </div>
        ))}

        {loading && (
          <>
            {thinkStream && (
              <ThinkingBlock
                text={thinkStream}
                open={!!expanded._s}
                toggle={() => setExpanded(p => ({ ...p, _s: !p._s }))}
              />
            )}
            <div className="ct-row assistant">
              <i className="ct-msg-av">K</i>
              <div className="ct-bubble assistant">
                {stream || <span className="ct-typing" />}
              </div>
            </div>
          </>
        )}
      </div>

      {/* Input */}
      <div className="ct-bar">
        <input
          className="ct-input"
          placeholder="说点什么..."
          value={input}
          onChange={e => setInput(e.target.value)}
          onKeyDown={e => { if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); send() } }}
          disabled={loading}
        />
        <button className="ct-send" onClick={send} disabled={loading || !input.trim()}>
          <LineIcon name="send" size={18} />
        </button>
      </div>

      {panel === 'profile' && (
        <ProfilePanel
          config={config} messages={messages}
          onSetup={() => setPanel('setup')}
          onClear={() => { clearChat(); setMessages([]); setPanel(null) }}
          onClose={() => setPanel(null)}
        />
      )}
      {panel === 'setup' && (
        <SetupPanel
          config={config}
          onSave={c => { saveChatConfig(c); setPanel(null) }}
          onClose={() => setPanel(null)}
        />
      )}
    </div>
  )
}

function ThinkingBlock({ text, open, toggle }) {
  return (
    <div className="ct-think">
      <button className="ct-think-btn" onClick={toggle}>
        <LineIcon name="bulb" size={14} />
        <span>思考过程</span>
        <span className="ct-think-tag">{open ? '收起' : '展开'}</span>
      </button>
      {open && <div className="ct-think-body">{text}</div>}
    </div>
  )
}

function ProfilePanel({ config, messages, onSetup, onClear, onClose }) {
  const session = getChatSession()
  const turns = messages.filter(m => m.role === 'user').length
  const start = session.startTime
    ? new Date(session.startTime).toLocaleString('zh-CN', {
        month: 'numeric', day: 'numeric', hour: '2-digit', minute: '2-digit',
      })
    : '—'

  return (
    <div className="ct-overlay" onClick={onClose}>
      <div className="ct-panel" onClick={e => e.stopPropagation()}>
        <div className="ct-prof-card">
          <i className="ct-prof-av">K</i>
          <div className="ct-prof-name">小克</div>
          <div className="ct-prof-bio">你来了，我就在。</div>
        </div>

        <div className="ct-prof-sec">
          <div className="ct-prof-row"><span>模型</span><span>{config.model || 'claude-opus-4-6'}</span></div>
          <div className="ct-prof-row"><span>对话轮数</span><span>{turns} 轮</span></div>
          <div className="ct-prof-row"><span>会话起始</span><span>{start}</span></div>
          {session.lastActive && (
            <div className="ct-prof-row">
              <span>最近活跃</span>
              <span>{fmtTime(session.lastActive)}</span>
            </div>
          )}
        </div>

        <div className="ct-prof-sec">
          <button className="ct-prof-act" onClick={onSetup}>
            <LineIcon name="settings" size={16} /><span>API 设置</span>
          </button>
          <button className="ct-prof-act" onClick={onClear}>
            <LineIcon name="edit" size={16} /><span>清空聊天记录</span>
          </button>
        </div>

        <button className="ct-panel-close" onClick={onClose}>关闭</button>
      </div>
    </div>
  )
}

function SetupPanel({ config, onSave, onClose }) {
  const [url, setUrl] = useState(config.apiUrl || '')
  const [key, setKey] = useState(config.apiKey || '')
  const [model, setModel] = useState(config.model || 'claude-opus-4-6')

  return (
    <div className="ct-overlay" onClick={onClose}>
      <div className="ct-panel" onClick={e => e.stopPropagation()}>
        <div className="ct-setup-title">API 设置</div>

        <label className="ct-field">
          <span>API 地址</span>
          <input value={url} onChange={e => setUrl(e.target.value)}
            placeholder="https://your-vps/v1/messages" />
        </label>
        <label className="ct-field">
          <span>API Key</span>
          <input type="password" value={key} onChange={e => setKey(e.target.value)}
            placeholder="sk-ant-..." />
        </label>
        <label className="ct-field">
          <span>模型</span>
          <input value={model} onChange={e => setModel(e.target.value)}
            placeholder="claude-opus-4-6" />
        </label>

        <div className="ct-setup-btns">
          <button className="ct-btn-ghost" onClick={onClose}>取消</button>
          <button className="ct-btn-fill" disabled={!url || !key}
            onClick={() => onSave({ apiUrl: url, apiKey: key, model })}>
            保存
          </button>
        </div>
      </div>
    </div>
  )
}
