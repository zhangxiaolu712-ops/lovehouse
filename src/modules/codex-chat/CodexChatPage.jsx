import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router'
import LineIcon from '../../shared/LineIcon'
import Markdown from '../../shared/Markdown'
import { streamCodexChat } from './codexChatService'
import {
  boundCodexRecentHistory,
  getCodexWindowId,
  loadCodexRecentHistory,
  saveCodexRecentHistory,
} from './codexChatState'

function errorText(error) {
  return error?.message || 'Codex 暂时无法回复，请稍后重试。'
}

export default function CodexChatPage() {
  const [messages, setMessages] = useState(() => loadCodexRecentHistory())
  const [input, setInput] = useState('')
  const [stream, setStream] = useState('')
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(null)
  const [retryMessage, setRetryMessage] = useState('')
  const [sessionState, setSessionState] = useState(null)
  const windowIdRef = useRef(getCodexWindowId())
  const listRef = useRef(null)

  useEffect(() => { saveCodexRecentHistory(messages) }, [messages])

  useEffect(() => {
    const list = listRef.current
    if (list) list.scrollTop = list.scrollHeight
  }, [messages, stream, error])

  async function sendMessage(rawMessage, { appendUser = true } = {}) {
    const message = rawMessage.trim()
    if (!message || loading) return

    const historySource = appendUser
      ? messages
      : (messages.at(-1)?.role === 'user' ? messages.slice(0, -1) : messages)
    const nextMessages = appendUser
      ? boundCodexRecentHistory([...messages, { role: 'user', content: message }])
      : messages

    if (appendUser) setMessages(nextMessages)
    setInput('')
    setLoading(true)
    setStream('')
    setError(null)
    setRetryMessage('')

    try {
      const result = await streamCodexChat({
        windowId: windowIdRef.current,
        message,
        recentHistory: boundCodexRecentHistory(historySource),
      }, {
        onSession: session => setSessionState(session),
        onText: (_delta, fullText) => setStream(fullText),
      })

      if (!result.ok) {
        setError(result.error)
        setRetryMessage(message)
        return
      }
      if (result.text) {
        setMessages(current => boundCodexRecentHistory([
          ...current,
          { role: 'assistant', content: result.text },
        ]))
      }
    } catch (requestError) {
      setError(requestError.detail || { message: requestError.message, retryable: true })
      setRetryMessage(message)
    } finally {
      setLoading(false)
      setStream('')
    }
  }

  function submit(event) {
    event.preventDefault()
    sendMessage(input)
  }

  return (
    <section className="codex-chat-page">
      <header className="codex-chat-head">
        <Link to="/" className="codex-chat-icon-button" aria-label="返回首页">
          <LineIcon name="back" size={20} />
        </Link>
        <div className="codex-chat-heading">
          <span className="codex-chat-kicker">INDEPENDENT CHAT</span>
          <h1>Codex</h1>
        </div>
        <span className={`codex-chat-status${sessionState ? ' is-connected' : ''}`}>
          {sessionState ? (sessionState.resumed ? '已继续' : '已连接') : '独立窗口'}
        </span>
      </header>

      <div className="codex-chat-messages" ref={listRef} aria-live="polite">
        {!messages.length && !loading && (
          <div className="codex-chat-empty">
            <span className="codex-chat-mark">C</span>
            <h2>和 Codex 聊聊</h2>
            <p>这里是独立于小克的聊天窗口。完整原文以后由服务端档案保存。</p>
          </div>
        )}

        {messages.map((message, index) => (
          <article className={`codex-chat-message is-${message.role}`} key={`${message.role}-${index}`}>
            <span className="codex-chat-speaker">{message.role === 'user' ? '小婷' : 'Codex'}</span>
            <div className="codex-chat-bubble">
              {message.role === 'assistant'
                ? <Markdown text={message.content} />
                : <span>{message.content}</span>}
            </div>
          </article>
        ))}

        {loading && (
          <article className="codex-chat-message is-assistant is-streaming">
            <span className="codex-chat-speaker">Codex</span>
            <div className="codex-chat-bubble">
              {stream ? <Markdown text={stream} /> : <span className="codex-chat-typing">正在思考</span>}
            </div>
          </article>
        )}
      </div>

      {error && (
        <div className="codex-chat-error" role="alert">
          <div>
            <strong>这次没有发送成功</strong>
            <span>{errorText(error)}</span>
          </div>
          {retryMessage && (
            <button type="button" onClick={() => sendMessage(retryMessage, { appendUser: false })} disabled={loading}>
              重试
            </button>
          )}
        </div>
      )}

      <form className="codex-chat-compose" onSubmit={submit}>
        <textarea
          value={input}
          onChange={event => setInput(event.target.value)}
          onKeyDown={event => {
            if (event.key === 'Enter' && !event.shiftKey) {
              event.preventDefault()
              submit(event)
            }
          }}
          placeholder="发消息给 Codex…"
          rows="1"
          maxLength="16000"
          disabled={loading}
          aria-label="消息"
        />
        <button type="submit" disabled={loading || !input.trim()} aria-label="发送消息">
          <LineIcon name="send" size={19} />
        </button>
      </form>
    </section>
  )
}
