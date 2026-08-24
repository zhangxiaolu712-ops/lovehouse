import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router'

import Markdown from '../../shared/Markdown'
import { streamCodexV1 } from './codexChatV1Service'
import {
  boundCodexV1History,
  getCodexV1Identity,
  loadCodexV1History,
  saveCodexV1History,
} from './codexChatV1State'
import './codexChatV1.css'

const EMPTY_REASONING = { available: false, status: 'unavailable', summary: null }
const EMPTY_QUOTA = { status: 'unknown', remaining: null, unit: null, reset_at: null }

function upsertTool(current, event, value) {
  const next = current.filter(item => item.call_id !== value.call_id)
  return [...next, { ...value, event }].slice(-12)
}

function ContextLine({ name, value }) {
  return (
    <li>
      <span>{name}</span>
      <strong>{value?.enabled ? (value.available === false ? '不可用' : '启用') : '关闭'}</strong>
      <small>{value?.estimated_tokens == null ? '—' : `~${value.estimated_tokens} tokens`}</small>
    </li>
  )
}

export default function CodexChatV1Page() {
  const identity = useRef(getCodexV1Identity())
  const [messages, setMessages] = useState(() => loadCodexV1History())
  const [input, setInput] = useState('')
  const [stream, setStream] = useState('')
  const [loading, setLoading] = useState(false)
  const [runtime, setRuntime] = useState({ status: 'idle', runtime_type: 'codex_cli', adapter_id: 'codex-cli-v1' })
  const [reasoning, setReasoning] = useState(EMPTY_REASONING)
  const [tools, setTools] = useState([])
  const [usage, setUsage] = useState(null)
  const [quota, setQuota] = useState(EMPTY_QUOTA)
  const [context, setContext] = useState(null)
  const [error, setError] = useState(null)

  useEffect(() => { saveCodexV1History(messages) }, [messages])

  async function send(event) {
    event?.preventDefault()
    const message = input.trim()
    if (!message || loading) return
    setMessages(current => boundCodexV1History([...current, { role: 'user', content: message }]))
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
      const result = await streamCodexV1({
        threadId: identity.current.threadId,
        windowId: identity.current.windowId,
        message,
      }, {
        onText: (_delta, full) => setStream(full),
        onError: setError,
        onEvent: (name, data) => {
          if (name === 'message_start') {
            setRuntime(current => ({
              ...current,
              status: 'streaming',
              runtime_type: data.runtime || current.runtime_type,
              adapter_id: data.adapter_id || current.adapter_id,
            }))
          } else if (name === 'runtime_status') setRuntime(data)
          else if (name === 'reasoning_status') setReasoning(data)
          else if (['tool_call', 'tool_result', 'tool_error'].includes(name)) {
            setTools(current => upsertTool(current, name, data))
          } else if (name === 'usage') setUsage(data)
          else if (name === 'quota') setQuota(data)
          else if (name === 'context_breakdown') setContext(data)
          else if (name === 'message_end') {
            setRuntime(current => ({ ...current, status: data.ok ? 'ready' : 'error' }))
          }
        },
      })
      if (result.ok && result.text) {
        setMessages(current => boundCodexV1History([
          ...current,
          { role: 'assistant', content: result.text },
        ]))
      }
    } catch (requestError) {
      setError(requestError.detail || {
        code: 'STREAM_INTERRUPTED', message: requestError.message, stage: 'transport', retryable: true,
      })
      setRuntime(current => ({ ...current, status: 'error' }))
    } finally {
      setLoading(false)
      setStream('')
    }
  }

  return (
    <main className="codex-v1-page">
      <header className="codex-v1-header">
        <div>
          <Link to="/">← 返回</Link>
          <p>UNIFIED CHAT MAINLINE · EXPERIMENT V1</p>
          <h1>Codex CLI Chat</h1>
        </div>
        <span className={`codex-v1-pill is-${runtime.status}`}>{runtime.status}</span>
      </header>

      <section className="codex-v1-facts" aria-label="Chat runtime facts">
        <div><span>Persona</span><strong>Codex</strong></div>
        <div><span>Runtime</span><strong>{runtime.runtime_type || 'codex_cli'}</strong></div>
        <div><span>Adapter</span><strong>{runtime.adapter_id || 'codex-cli-v1'}</strong></div>
        <div className="is-wide"><span>LoveHouse Thread</span><code>{identity.current.threadId}</code></div>
        <div><span>Scene</span><strong>work · text</strong></div>
      </section>

      <div className="codex-v1-grid">
        <section className="codex-v1-chat-panel">
          <div className="codex-v1-messages" aria-live="polite">
            {!messages.length && !loading && <p className="codex-v1-empty">发一条消息，观察统一主干事件。</p>}
            {messages.map((message, index) => (
              <article key={`${message.role}-${index}`} className={`is-${message.role}`}>
                <small>{message.role === 'user' ? '小婷' : 'Codex'}</small>
                <div>{message.role === 'assistant'
                  ? <Markdown text={message.content} />
                  : message.content}</div>
              </article>
            ))}
            {loading && (
              <article className="is-assistant is-streaming">
                <small>Codex</small>
                <div>{stream ? <Markdown text={stream} /> : '等待 runtime…'}</div>
              </article>
            )}
          </div>
          {error && (
            <div className="codex-v1-error" role="alert">
              <strong>{error.code}</strong>
              <span>stage: {error.stage}</span>
              <p>{error.message}</p>
            </div>
          )}
          <form onSubmit={send} className="codex-v1-compose">
            <textarea
              value={input}
              onChange={event => setInput(event.target.value)}
              placeholder="发送 text 消息…"
              maxLength="16000"
              disabled={loading}
              rows="2"
            />
            <button disabled={loading || !input.trim()} type="submit">发送</button>
          </form>
        </section>

        <aside className="codex-v1-observability">
          <section>
            <h2>Reasoning</h2>
            <p><strong>{reasoning.available ? 'available' : 'unavailable'}</strong> · {reasoning.status}</p>
            <small>{reasoning.summary || '没有真实 reasoning summary，本轮不生成旁白。'}</small>
          </section>
          <section>
            <h2>Tools</h2>
            {!tools.length && <small>本轮没有真实工具事件。</small>}
            <ul>{tools.map(tool => (
              <li key={tool.call_id}>
                <strong>{tool.name}</strong><span>{tool.status}</span>
                {tool.summary && <small>{tool.summary}</small>}
              </li>
            ))}</ul>
          </section>
          <section>
            <h2>Tokens</h2>
            <dl>
              <dt>estimate input</dt><dd>{usage?.estimated_input_tokens ?? '—'}</dd>
              <dt>actual input</dt><dd>{usage?.actual_input_tokens ?? '—'}</dd>
              <dt>actual output</dt><dd>{usage?.actual_output_tokens ?? '—'}</dd>
              <dt>total</dt><dd>{usage?.total_tokens ?? '—'}</dd>
              <dt>source</dt><dd>{usage?.usage_source ?? '—'}</dd>
            </dl>
          </section>
          <section>
            <h2>Quota</h2>
            <p><strong>{quota.status}</strong></p>
            <small>{quota.remaining == null ? 'CLI 没有可靠额度数据，不猜。' : `${quota.remaining} ${quota.unit || ''}`}</small>
          </section>
          <section>
            <h2>Context</h2>
            {!context ? <small>尚无 context breakdown。</small> : (
              <ul>
                <ContextLine name="recent_chat" value={context.recent_chat} />
                <ContextLine name="current_message" value={context.current_message} />
                <ContextLine name="memory" value={context.memory} />
                <ContextLine name="worldbook" value={context.worldbook} />
                <ContextLine name="persona" value={context.persona} />
              </ul>
            )}
          </section>
        </aside>
      </div>
    </main>
  )
}
