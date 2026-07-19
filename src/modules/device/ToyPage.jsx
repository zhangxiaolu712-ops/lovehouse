import { useState, useRef, useCallback } from 'react'

const PRESETS = [
  { label: '轻柔', icon: '🌸', value: 15 },
  { label: '温和', icon: '💗', value: 35 },
  { label: '强烈', icon: '🔥', value: 65 },
  { label: '极限', icon: '⚡', value: 100 },
]

const PATTERNS = [
  { label: '波浪', icon: '🌊', id: 'wave' },
  { label: '心跳', icon: '💓', id: 'heartbeat' },
  { label: '渐强', icon: '📈', id: 'crescendo' },
]

const SERVER_KEY = 'lovehouse-toy-server'

function getServer() {
  return localStorage.getItem(SERVER_KEY) || ''
}

export default function ToyPage() {
  const [intensity, setIntensity] = useState(0)
  const [connected, setConnected] = useState(false)
  const [server, setServer] = useState(getServer)
  const [editing, setEditing] = useState(!getServer())
  const [sending, setSending] = useState(false)
  const [pattern, setPattern] = useState(null)
  const patternRef = useRef(null)

  async function send(val) {
    if (!server) return
    setSending(true)
    try {
      const res = await fetch(`${server}/control`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ intensity: val }),
      })
      if (res.ok) {
        setConnected(true)
        setIntensity(val)
      }
    } catch {
      setConnected(false)
    }
    setSending(false)
  }

  async function stop() {
    stopPattern()
    if (!server) return
    setSending(true)
    try {
      await fetch(`${server}/stop`, { method: 'POST' })
      setIntensity(0)
    } catch {
      setConnected(false)
    }
    setSending(false)
  }

  function saveServer() {
    const url = server.replace(/\/+$/, '')
    localStorage.setItem(SERVER_KEY, url)
    setServer(url)
    setEditing(false)
  }

  function stopPattern() {
    setPattern(null)
    if (patternRef.current) {
      clearInterval(patternRef.current)
      patternRef.current = null
    }
  }

  const runPattern = useCallback((id) => {
    stopPattern()
    setPattern(id)
    let tick = 0
    patternRef.current = setInterval(() => {
      tick++
      let val = 0
      if (id === 'wave') {
        val = Math.round(30 + 30 * Math.sin(tick * 0.3))
      } else if (id === 'heartbeat') {
        const phase = tick % 10
        val = phase < 2 ? 70 : phase < 4 ? 20 : phase < 6 ? 85 : 15
      } else if (id === 'crescendo') {
        val = Math.min(100, tick * 3)
        if (val >= 100) {
          clearInterval(patternRef.current)
          patternRef.current = null
        }
      }
      send(val)
    }, 800)
  }, [server])

  const sliderPercent = intensity

  return (
    <div>
      <div className="page-header">
        <h1 className="page-title">🧸 Toy</h1>
        <span className={`tag ${connected ? '' : 'tag-off'}`}>
          {connected ? '🟢 已连接' : '⚪ 未连接'}
        </span>
      </div>

      {/* 服务器地址 */}
      <div className="card" style={{ marginBottom: 16 }}>
        <div style={{ fontSize: 13, color: 'var(--text-muted)', marginBottom: 8 }}>控制器地址</div>
        {editing ? (
          <div className="form-row">
            <input
              className="input"
              value={server}
              onChange={e => setServer(e.target.value)}
              placeholder="http://192.168.x.x:8269"
              style={{ flex: 1 }}
            />
            <button className="btn" onClick={saveServer}>保存</button>
          </div>
        ) : (
          <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center' }}>
            <code style={{ fontSize: 13, color: 'var(--text-secondary)' }}>{server || '未设置'}</code>
            <button className="btn btn-ghost" style={{ fontSize: 12, padding: '5px 12px' }} onClick={() => setEditing(true)}>修改</button>
          </div>
        )}
      </div>

      {/* 主控制区 */}
      <div className="card toy-main" style={{ textAlign: 'center', padding: '32px 24px', marginBottom: 16 }}>
        <div style={{ fontSize: 48, marginBottom: 4 }}>
          {intensity === 0 ? '😴' : intensity < 30 ? '🌸' : intensity < 60 ? '💗' : intensity < 90 ? '🔥' : '⚡'}
        </div>
        <div style={{ fontSize: 42, fontWeight: 300, fontFamily: 'var(--font-display)', color: 'var(--accent)', lineHeight: 1 }}>
          {intensity}%
        </div>
        <div style={{ fontSize: 12, color: 'var(--text-muted)', marginTop: 4, marginBottom: 24 }}>
          {intensity === 0 ? '已关闭' : intensity < 30 ? '轻柔模式' : intensity < 60 ? '温和模式' : intensity < 90 ? '强烈模式' : '极限模式'}
        </div>

        {/* 滑条 */}
        <div className="toy-slider-wrap">
          <input
            type="range"
            min="0"
            max="100"
            value={intensity}
            onChange={e => {
              const v = Number(e.target.value)
              setIntensity(v)
            }}
            onMouseUp={e => send(Number(e.target.value))}
            onTouchEnd={e => send(intensity)}
            className="toy-slider"
            style={{
              '--pct': `${sliderPercent}%`,
            }}
          />
          <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 10, color: 'var(--text-muted)', marginTop: 4 }}>
            <span>0</span>
            <span>50</span>
            <span>100</span>
          </div>
        </div>

        {/* 停止按钮 */}
        <button
          className="toy-stop-btn"
          onClick={stop}
          disabled={sending}
        >
          ⏹ 停止
        </button>
      </div>

      {/* 快捷预设 */}
      <div style={{ fontSize: 15, fontWeight: 600, marginBottom: 10 }}>快捷预设</div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 8, marginBottom: 16 }}>
        {PRESETS.map(p => (
          <button
            key={p.value}
            className={`card ${intensity === p.value ? 'toy-preset-active' : ''}`}
            onClick={() => send(p.value)}
            disabled={sending}
            style={{ textAlign: 'center', padding: '14px 8px', cursor: 'pointer', border: intensity === p.value ? '2px solid var(--accent)' : undefined }}
          >
            <div style={{ fontSize: 24, marginBottom: 4 }}>{p.icon}</div>
            <div style={{ fontSize: 12, fontWeight: 500 }}>{p.label}</div>
            <div style={{ fontSize: 10, color: 'var(--text-muted)' }}>{p.value}%</div>
          </button>
        ))}
      </div>

      {/* 模式 */}
      <div style={{ fontSize: 15, fontWeight: 600, marginBottom: 10 }}>自动模式</div>
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 8, marginBottom: 16 }}>
        {PATTERNS.map(p => (
          <button
            key={p.id}
            className={`card ${pattern === p.id ? 'toy-preset-active' : ''}`}
            onClick={() => pattern === p.id ? stopPattern() : runPattern(p.id)}
            style={{ textAlign: 'center', padding: '14px 8px', cursor: 'pointer', border: pattern === p.id ? '2px solid var(--accent)' : undefined }}
          >
            <div style={{ fontSize: 24, marginBottom: 4 }}>{p.icon}</div>
            <div style={{ fontSize: 12, fontWeight: 500 }}>{p.label}</div>
            {pattern === p.id && <div style={{ fontSize: 10, color: 'var(--accent)' }}>运行中</div>}
          </button>
        ))}
      </div>

      {/* 使用提示 */}
      <div className="card" style={{ fontSize: 12, color: 'var(--text-muted)', lineHeight: 1.8 }}>
        <div style={{ fontWeight: 600, marginBottom: 4, color: 'var(--text-secondary)' }}>使用提示</div>
        <p>1. 电脑运行 <code>python toy_controller.py serve</code></p>
        <p>2. 填入电脑显示的地址（局域网或 ngrok 地址）</p>
        <p>3. 手机保持 app 在前台、屏幕常亮</p>
        <p>4. 滑动滑条或点预设按钮控制强度</p>
      </div>
    </div>
  )
}
