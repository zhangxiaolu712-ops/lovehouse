import { useEffect, useRef, useState } from 'react'
import { Link } from 'react-router'
import LineIcon from '../../shared/LineIcon'
import Markdown from '../../shared/Markdown'
import { synthesizeVoice } from '../voice/voiceService'
import {
  clearCodexChat,
  getCodexHistory,
  getCodexSession,
  saveCodexHistory,
  streamCodexMessage,
} from './codexService'

export default function CodexChatPage() {
  const [messages, setMessages] = useState(() => getCodexHistory())
  const [input, setInput] = useState('')
  const [loading, setLoading] = useState(false)
  const [stream, setStream] = useState('')
  const [voiceState, setVoiceState] = useState({ index: null, error: '' })
  const listRef = useRef(null)
  const audioRef = useRef(null)
  const audioUrlRef = useRef(null)

  useEffect(() => { saveCodexHistory(messages) }, [messages])
  useEffect(() => {
    const el = listRef.current
    if (el) el.scrollTop = el.scrollHeight
  }, [messages, stream])
  useEffect(() => () => {
    audioRef.current?.pause()
    if (audioUrlRef.current) URL.revokeObjectURL(audioUrlRef.current)
  }, [])

  async function speak(text, index) {
    if (!text || voiceState.index !== null) return
    setVoiceState({ index, error: '' })
    try {
      const blob = await synthesizeVoice(text)
      if (audioUrlRef.current) URL.revokeObjectURL(audioUrlRef.current)
      const url = URL.createObjectURL(blob)
      audioUrlRef.current = url
      const audio = new Audio(url)
      audioRef.current = audio
      audio.onended = () => setVoiceState({ index: null, error: '' })
      audio.onerror = () => setVoiceState({ index: null, error: '音频播放失败' })
      await audio.play()
    } catch (error) {
      setVoiceState({ index: null, error: error.message })
    }
  }

  async function send() {
    const text = input.trim()
    if (!text || loading) return
    const userMessage = { role: 'user', content: text, time: Date.now() }
    const updated = [...messages, userMessage]
    setMessages(updated)
    setInput('')
    setLoading(true)
    setStream('')
    try {
      await streamCodexMessage(updated, {
        onText: setStream,
        onDone: ({ content }) => {
          setMessages(previous => [...previous, { role: 'assistant', content, time: Date.now() }])
          setStream('')
          setLoading(false)
        },
      })
    } catch (error) {
      setMessages(previous => [...previous, {
        role: 'assistant',
        content: `连接失败: ${error.message}`,
        time: Date.now(),
        error: true,
      }])
      setStream('')
      setLoading(false)
    }
  }

  const session = getCodexSession()

  return (
    <div className="ct">
      <div className="ct-head">
        <Link to="/" className="ct-back"><LineIcon name="back" size={20} /></Link>
        <span className="ct-name">Codex <span className="ct-dot green" /></span>
        <button
          className="ct-av-btn"
          title="清空本地 Codex 窗口"
          onClick={() => {
            if (!confirm('清空这个 Codex 窗口的本地聊天记录？')) return
            clearCodexChat()
            setMessages([])
          }}
        >
          <i className="ct-av">C</i>
        </button>
      </div>

      <div className="ct-list" ref={listRef}>
        {messages.length === 0 && !loading && (
          <div className="ct-empty">
            <p>Codex 已接到 VPS，发一句话试试。</p>
            <small>{session.sessionId ? '已有可恢复线程' : '新窗口会自动建立独立线程'}</small>
          </div>
        )}

        {messages.map((message, index) => (
          <div className={`ct-row ${message.role}`} key={`${message.time || index}-${index}`}>
            {message.role === 'assistant' && <i className="ct-msg-av">C</i>}
            <div className={`ct-message-stack ${message.role}`}>
              <div className={`ct-bubble ${message.role}${message.error ? ' err' : ''}`}>
                {message.role === 'assistant'
                  ? <Markdown text={message.content} />
                  : <span>{message.content}</span>}
              </div>
              {message.role === 'assistant' && !message.error && (
                <button
                  className="ct-voice-btn"
                  onClick={() => speak(message.content, index)}
                  disabled={voiceState.index !== null}
                  title="用 ElevenLabs 3号声线朗读"
                >
                  <LineIcon name="volume" size={14} />
                  <span>{voiceState.index === index ? '生成中…' : '朗读'}</span>
                </button>
              )}
            </div>
            {message.role === 'user' && <i className="ct-msg-av">T</i>}
          </div>
        ))}

        {loading && (
          <div className="ct-row assistant">
            <i className="ct-msg-av">C</i>
            <div className="ct-bubble assistant">
              {stream ? <Markdown text={stream} /> : <span className="ct-typing" />}
            </div>
          </div>
        )}
        {voiceState.error && <div className="ct-voice-error">语音：{voiceState.error}</div>}
      </div>

      <div className="ct-bar">
        <input
          className="ct-input"
          placeholder="给 Codex 发消息…"
          value={input}
          onChange={event => setInput(event.target.value)}
          onKeyDown={event => {
            if (event.key === 'Enter' && !event.shiftKey) {
              event.preventDefault()
              send()
            }
          }}
          disabled={loading}
        />
        <button className="ct-send" onClick={send} disabled={loading || !input.trim()}>
          <LineIcon name="send" size={18} />
        </button>
      </div>
    </div>
  )
}
