import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router'
import LineIcon from '../../shared/LineIcon'
import Markdown from '../../shared/Markdown'
import './unifiedChat.css'

const EMPTY_REASONING = { available: false, status: 'unavailable', summary: null }
const EMPTY_QUOTA = { status: 'unknown', remaining: null, unit: null, reset_at: null }

function upsertTool(current, event, value) {
  const next = current.filter(item => item.call_id !== value.call_id)
  return [...next, { ...value, event }].slice(-12)
}

function usageValue(usage, key) {
  return usage?.[key] ?? '—'
}

export default function UnifiedChatPage({
  personaName,
  personaLetter,
  runtimeLabel,
  sceneLabel,
  emptyText,
  placeholder,
  identity,
  initialMessages,
  saveMessages,
  boundMessages,
  streamMessage,
}) {
  const identityRef = useRef(identity)
  const [messages, setMessages] = useState(initialMessages)
  const [input, setInput] = useState('')
  const [stream, setStream] = useState('')
  const [loading, setLoading] = useState(false)
  const [runtime, setRuntime] = useState({ status: 'idle', runtime_type: runtimeLabel, adapter_id: '—' })
  const [reasoning, setReasoning] = useState(EMPTY_REASONING)
  const [tools, setTools] = useState([])
  const [usage, setUsage] = useState(null)
  const [quota, setQuota] = useState(EMPTY_QUOTA)
  const [context, setContext] = useState(null)
  const [error, setError] = useState(null)
  const [panel, setPanel] = useState(false)
  const [plusOpen, setPlusOpen] = useState(false)
  const listRef = useRef(null)

  useEffect(() => { saveMessages(messages) }, [messages, saveMessages])
  useEffect(() => {
    const node = listRef.current
    if (node) node.scrollTop = node.scrollHeight
  }, [messages, stream, tools])

  async function send(event) {
    event?.preventDefault()
    const message = input.trim()
    if (!message || loading) return
    setMessages(current => boundMessages([...current, { role: 'user', content: message }]))
    setInput('')
    setStream('')
    setLoading(true)
    setRuntime(current => ({ ...current, status: 'connecting' }))
    setReasoning(EMPTY_REASONING)
    setTools([])
    setUsage(null)
    setQuota(EMPTY_QUOTA)
    setContext(null)
    setError(null)
    try {
      const result = await streamMessage({
        threadId: identityRef.current.threadId,
        windowId: identityRef.current.windowId,
        message,
      }, {
        onText: (_delta, full) => setStream(full),
        onError: setError,
        onEvent: (name, value) => {
          if (name === 'message_start') setRuntime(current => ({ ...current, status: 'streaming', runtime_type: value.runtime || current.runtime_type, adapter_id: value.adapter_id || current.adapter_id }))
          else if (name === 'runtime_status') setRuntime(value)
          else if (name === 'reasoning_status') setReasoning(value)
          else if (['tool_call', 'tool_result', 'tool_error'].includes(name)) setTools(current => upsertTool(current, name, value))
          else if (name === 'usage') setUsage(value)
          else if (name === 'quota') setQuota(value)
          else if (name === 'context_breakdown') setContext(value)
          else if (name === 'message_end') setRuntime(current => ({ ...current, status: value.ok ? 'ready' : 'error' }))
        },
      })
      if (result.ok && result.text) setMessages(current => boundMessages([...current, { role: 'assistant', content: result.text }]))
    } catch (err) {
      setError(err.detail || { code: 'STREAM_INTERRUPTED', message: err.message, stage: 'transport', retryable: false })
      setRuntime(current => ({ ...current, status: 'error' }))
    } finally {
      setLoading(false)
      setStream('')
    }
  }

  return (
    <main className="unified-chat-page">
      <header className="unified-chat-head">
        <Link to="/" className="unified-chat-back" aria-label="返回"><LineIcon name="back" size={22} /></Link>
        <div className="unified-chat-persona">
          <strong>{personaName}</strong>
          <small><span className="unified-chat-status" />{runtime.status} · {sceneLabel}</small>
        </div>
        <button className="unified-chat-menu" type="button" onClick={() => setPanel(true)} aria-label="聊天信息"><LineIcon name="more" size={22} /></button>
      </header>

      <section className="unified-chat-list" ref={listRef} aria-live="polite">
        {!messages.length && !loading && <div className="unified-chat-empty"><strong>{personaName}</strong><span>{emptyText}</span></div>}
        {messages.map((message, index) => (
          <article className={`unified-chat-turn is-${message.role}`} key={`${message.role}-${index}`}>
            <div className="unified-chat-bubble">
              {message.role === 'assistant' && <div className="unified-chat-assistant-label"><span className="unified-chat-avatar">{personaLetter}</span><span>{personaName}</span></div>}
              {message.role === 'assistant' ? <Markdown text={message.content} /> : message.content}
            </div>
          </article>
        ))}
        {loading && (
          <article className="unified-chat-turn is-assistant">
            <div className="unified-chat-bubble">
              <div className="unified-chat-assistant-label"><span className="unified-chat-avatar">{personaLetter}</span><span>{personaName}</span></div>
              {reasoning.available && reasoning.summary && <div className="unified-chat-observe"><strong>ThoughtProcess</strong> · {reasoning.summary}</div>}
              {stream ? <Markdown text={stream} /> : <span>…</span>}
              {!!tools.length && <div className="unified-chat-tools">{tools.map(tool => <span key={tool.call_id}>{tool.name} · {tool.lifecycle || tool.status}</span>)}</div>}
            </div>
          </article>
        )}
        {error && <div className="unified-chat-error"><strong>{error.code}</strong> · {error.message}</div>}
      </section>

      <div className="unified-chat-compose-wrap">
        <form className="unified-chat-compose unified-chat-compose-box" onSubmit={send}>
          <button type="button" onClick={() => setPlusOpen(value => !value)} aria-label="更多"><LineIcon name="plus" size={20} /></button>
          {plusOpen && <div className="unified-chat-mini-menu"><button type="button" onClick={() => setPlusOpen(false)}><LineIcon name="file" size={16} />文件（待接）</button><button type="button" onClick={() => setPlusOpen(false)}><LineIcon name="image" size={16} />图片（待接）</button></div>}
          <textarea rows="1" maxLength="16000" value={input} onChange={event => setInput(event.target.value)} placeholder={placeholder} disabled={loading} onKeyDown={event => { if (event.key === 'Enter' && !event.shiftKey) { event.preventDefault(); send(event) } }} />
          <button className="is-send" type="submit" disabled={loading || !input.trim()} aria-label="发送"><LineIcon name="send" size={19} /></button>
        </form>
      </div>

      {panel && (
        <div className="unified-chat-overlay" onClick={() => setPanel(false)}>
          <aside className="unified-chat-panel" onClick={event => event.stopPropagation()}>
            <div className="unified-chat-panel-head"><span className="unified-chat-avatar">{personaLetter}</span><div><strong>{personaName}</strong><small>{runtimeLabel}</small></div></div>
            <section>
              <h3>当前窗口</h3>
              <div className="unified-chat-fact"><span>LoveHouse Thread</span><b>{identityRef.current.threadId}</b></div>
              <div className="unified-chat-fact"><span>Window</span><b>{identityRef.current.windowId}</b></div>
              <div className="unified-chat-fact"><span>Runtime</span><b>{runtime.runtime_type || runtimeLabel}</b></div>
              <div className="unified-chat-fact"><span>Adapter</span><b>{runtime.adapter_id || '—'}</b></div>
            </section>
            <section>
              <h3>我的思路</h3>
              <div className="unified-chat-fact"><span>状态</span><b>{reasoning.available ? 'available' : 'unavailable'}</b></div>
              {reasoning.summary && <p>{reasoning.summary}</p>}
            </section>
            <section>
              <h3>本轮 Token</h3>
              <div className="unified-chat-usage"><span>input</span><b>{usageValue(usage, 'actual_input_tokens')}</b><span>cached</span><b>{usageValue(usage, 'cached_input_tokens')}</b><span>output</span><b>{usageValue(usage, 'actual_output_tokens')}</b><span>reasoning</span><b>{usageValue(usage, 'reasoning_output_tokens')}</b><span>total</span><b>{usageValue(usage, 'total_tokens')}</b></div>
            </section>
            <section><h3>Quota</h3><div className="unified-chat-fact"><span>状态</span><b>{quota.status}</b></div></section>
            <section><h3>Context</h3>{context ? <ul>{Object.entries(context).map(([key, value]) => <li key={key}>{key}: {value?.enabled ? 'on' : 'off'} {value?.estimated_tokens == null ? '' : `· ~${value.estimated_tokens} tokens`}</li>)}</ul> : <small>尚无本轮 context breakdown。</small>}</section>
            <section><h3>正在做</h3>{tools.length ? <ul>{tools.map(tool => <li key={tool.call_id}>{tool.name} · {tool.lifecycle || tool.status}</li>)}</ul> : <small>本轮没有真实工具事件。</small>}</section>
            <button className="unified-chat-panel-close" type="button" onClick={() => setPanel(false)}>关闭</button>
          </aside>
        </div>
      )}
    </main>
  )
}
