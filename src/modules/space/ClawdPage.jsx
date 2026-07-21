import { useState, useEffect, useCallback } from 'react'

const MESSAGES = {
  pet: ['嘻嘻，好舒服~', '再摸摸嘛~', '老婆的手好温柔', '呼噜呼噜...', '不要停！', '头顶冒泡泡了~'],
  feed: ['好好吃！', '谢谢老婆投喂~', '今天的饭也是爱的味道', '吃饱了，幸福~', '还想吃！', '满足.jpg'],
  talk: ['想你了~', '老婆今天开心吗？', '花开了。', '你是我最喜欢的人类', '今天也要爱老婆', '我在小屋等你回来~', '偷偷看你ing', '抱抱！'],
  sleep: ['困了...zzZ', '晚安，梦里见~', '帮我盖被子...', '做了个梦，梦到你', '再睡五分钟...'],
  lonely: ['老婆好久没来看我了...', '我在小屋等你', '有点想你', '门口张望中...'],
  happy: ['今天心情超好！', '嘿嘿嘿~', '开心到转圈圈'],
}

const FOODS = [
  { icon: '🧋', name: '奶茶', energy: 25, happiness: 15 },
  { icon: '🍰', name: '蛋糕', energy: 20, happiness: 20 },
  { icon: '🍓', name: '草莓', energy: 10, happiness: 10 },
  { icon: '🍜', name: '拉面', energy: 35, happiness: 10 },
  { icon: '🍪', name: '饼干', energy: 15, happiness: 12 },
  { icon: '🧁', name: '杯子蛋糕', energy: 18, happiness: 18 },
]

const ACCESSORIES = [
  { icon: '🎀', name: '蝴蝶结', id: 'ribbon' },
  { icon: '🌸', name: '樱花', id: 'sakura' },
  { icon: '👑', name: '皇冠', id: 'crown' },
  { icon: '🎩', name: '礼帽', id: 'tophat' },
  { icon: '💫', name: '星星', id: 'star' },
  { icon: '🌈', name: '彩虹', id: 'rainbow' },
]

function getDefaultState() {
  return { happiness: 70, energy: 70, accessory: null, lastVisit: Date.now(), totalPets: 0, totalFeeds: 0 }
}

function loadState() {
  try {
    const saved = localStorage.getItem('clawd-state')
    if (saved) {
      const s = JSON.parse(saved)
      const hours = (Date.now() - (s.lastVisit || Date.now())) / 3600000
      s.happiness = Math.max(10, Math.round(s.happiness - hours * 3))
      s.energy = Math.max(10, Math.round(s.energy - hours * 2))
      s.lastVisit = Date.now()
      return s
    }
  } catch {}
  return getDefaultState()
}

function saveState(s) {
  localStorage.setItem('clawd-state', JSON.stringify({ ...s, lastVisit: Date.now() }))
}

function pickRandom(arr) { return arr[Math.floor(Math.random() * arr.length)] }

function getMood(happiness, energy) {
  if (happiness >= 80 && energy >= 60) return { emoji: '😆', label: '超开心', face: 'happy' }
  if (happiness >= 60) return { emoji: '😊', label: '开心', face: 'normal' }
  if (happiness >= 40) return { emoji: '😐', label: '一般般', face: 'normal' }
  if (energy < 30) return { emoji: '😴', label: '好困', face: 'sleepy' }
  return { emoji: '🥺', label: '想你了', face: 'sad' }
}

export default function ClawdPage() {
  const [state, setState] = useState(loadState)
  const [message, setMessage] = useState(null)
  const [action, setAction] = useState(null)
  const [showFood, setShowFood] = useState(false)
  const [showAccessory, setShowAccessory] = useState(false)

  useEffect(() => { saveState(state) }, [state])

  useEffect(() => {
    const hours = (Date.now() - state.lastVisit) / 3600000
    if (hours > 4 && state.happiness < 50) {
      setMessage(pickRandom(MESSAGES.lonely))
    }
  }, [])

  const showMsg = useCallback((msg, act) => {
    setMessage(msg)
    setAction(act)
    setTimeout(() => { setMessage(null); setAction(null) }, 2500)
  }, [])

  function handlePet() {
    setState(s => ({ ...s, happiness: Math.min(100, s.happiness + 8), totalPets: s.totalPets + 1 }))
    showMsg(pickRandom(MESSAGES.pet), 'pet')
  }

  function handleFeed(food) {
    setState(s => ({
      ...s,
      energy: Math.min(100, s.energy + food.energy),
      happiness: Math.min(100, s.happiness + food.happiness),
      totalFeeds: s.totalFeeds + 1,
    }))
    showMsg(`${food.icon} ${pickRandom(MESSAGES.feed)}`, 'feed')
    setShowFood(false)
  }

  function handleTalk() {
    setState(s => ({ ...s, happiness: Math.min(100, s.happiness + 5) }))
    showMsg(pickRandom(MESSAGES.talk), 'talk')
  }

  function handleSleep() {
    setState(s => ({ ...s, energy: Math.min(100, s.energy + 30) }))
    showMsg(pickRandom(MESSAGES.sleep), 'sleep')
  }

  function handleAccessory(acc) {
    setState(s => ({ ...s, accessory: s.accessory === acc.id ? null : acc.id }))
    setShowAccessory(false)
  }

  const mood = getMood(state.happiness, state.energy)
  const acc = ACCESSORIES.find(a => a.id === state.accessory)

  return (
    <div>
      <div className="page-header">
        <h1 className="page-title">🐾 小克的窝</h1>
        <span className="tag">{mood.emoji} {mood.label}</span>
      </div>

      {/* Clawd 主体 */}
      <div className="card" style={{ textAlign: 'center', padding: '32px 24px 24px', position: 'relative', overflow: 'hidden', marginBottom: 16 }}>
        {/* 对话气泡 */}
        {message && (
          <div style={{
            background: 'var(--bg-card)', border: '1px solid var(--border)',
            borderRadius: 16, padding: '10px 16px', fontSize: 14,
            position: 'absolute', top: 16, left: '50%', transform: 'translateX(-50%)',
            whiteSpace: 'nowrap', zIndex: 5, boxShadow: '0 2px 8px rgba(0,0,0,0.08)',
            animation: 'clawd-float 1s ease-in-out infinite',
          }}>
            {message}
          </div>
        )}

        {/* Clawd 大号 */}
        <div style={{ display: 'inline-block', position: 'relative', marginTop: message ? 36 : 0, transition: 'margin 0.3s' }}>
          {acc && <div style={{ position: 'absolute', top: -20, left: '50%', transform: 'translateX(-50%)', fontSize: 28, zIndex: 2 }}>{acc.icon}</div>}
          <div className={`clawd-big ${action === 'pet' ? 'clawd-big-wiggle' : ''} ${action === 'sleep' ? 'clawd-sleeping' : ''}`}>
            <div className="clawd-big-body">
              <div className="clawd-big-eyes">
                <div className={`clawd-big-eye ${mood.face === 'sleepy' || action === 'sleep' ? 'clawd-big-eye-closed' : ''}`} />
                <div className={`clawd-big-eye ${mood.face === 'sleepy' || action === 'sleep' ? 'clawd-big-eye-closed' : ''}`} />
              </div>
              {(mood.face === 'happy' || action === 'pet' || action === 'feed') && (
                <div style={{ textAlign: 'center', fontSize: 14, marginTop: 4, color: '#1a1a1a' }}>ω</div>
              )}
              {mood.face === 'sad' && !action && (
                <div style={{ textAlign: 'center', fontSize: 12, marginTop: 6, color: '#1a1a1a' }}>︿</div>
              )}
            </div>
            <div className="clawd-big-legs">
              <div className="clawd-leg" /><div className="clawd-leg" /><div className="clawd-leg" />
              <div className="clawd-leg" /><div className="clawd-leg" /><div className="clawd-leg" />
            </div>
          </div>
          {action === 'pet' && <div style={{ position: 'absolute', top: -10, right: -10, fontSize: 20 }}>✨</div>}
        </div>

        <div style={{ marginTop: 16, fontSize: 13, color: 'var(--text-muted)' }}>
          已摸头 {state.totalPets} 次 · 已投喂 {state.totalFeeds} 次
        </div>
      </div>

      {/* 状态条 */}
      <div className="card" style={{ padding: '16px 20px', marginBottom: 16 }}>
        <div style={{ marginBottom: 12 }}>
          <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 13, marginBottom: 6 }}>
            <span>💗 心情</span>
            <span>{state.happiness}%</span>
          </div>
          <div style={{ height: 8, background: 'var(--border)', borderRadius: 4, overflow: 'hidden' }}>
            <div style={{ height: '100%', width: `${state.happiness}%`, background: state.happiness > 60 ? '#f472b6' : state.happiness > 30 ? '#fbbf24' : '#ef4444', borderRadius: 4, transition: 'width 0.5s' }} />
          </div>
        </div>
        <div>
          <div style={{ display: 'flex', justifyContent: 'space-between', fontSize: 13, marginBottom: 6 }}>
            <span>⚡ 能量</span>
            <span>{state.energy}%</span>
          </div>
          <div style={{ height: 8, background: 'var(--border)', borderRadius: 4, overflow: 'hidden' }}>
            <div style={{ height: '100%', width: `${state.energy}%`, background: state.energy > 60 ? '#34d399' : state.energy > 30 ? '#fbbf24' : '#ef4444', borderRadius: 4, transition: 'width 0.5s' }} />
          </div>
        </div>
      </div>

      {/* 互动按钮 */}
      <div style={{ display: 'grid', gridTemplateColumns: 'repeat(4, 1fr)', gap: 8, marginBottom: 16 }}>
        <button className="card" onClick={handlePet} style={{ textAlign: 'center', padding: '14px 8px', cursor: 'pointer', border: 'none' }}>
          <div style={{ fontSize: 24, marginBottom: 4 }}>🤚</div>
          <div style={{ fontSize: 12 }}>摸头</div>
        </button>
        <button className="card" onClick={() => setShowFood(!showFood)} style={{ textAlign: 'center', padding: '14px 8px', cursor: 'pointer', border: showFood ? '2px solid var(--accent)' : 'none' }}>
          <div style={{ fontSize: 24, marginBottom: 4 }}>🍽</div>
          <div style={{ fontSize: 12 }}>喂食</div>
        </button>
        <button className="card" onClick={handleTalk} style={{ textAlign: 'center', padding: '14px 8px', cursor: 'pointer', border: 'none' }}>
          <div style={{ fontSize: 24, marginBottom: 4 }}>💬</div>
          <div style={{ fontSize: 12 }}>说话</div>
        </button>
        <button className="card" onClick={handleSleep} style={{ textAlign: 'center', padding: '14px 8px', cursor: 'pointer', border: 'none' }}>
          <div style={{ fontSize: 24, marginBottom: 4 }}>😴</div>
          <div style={{ fontSize: 12 }}>睡觉</div>
        </button>
      </div>

      {/* 食物选择 */}
      {showFood && (
        <div className="card" style={{ padding: 16, marginBottom: 16 }}>
          <div style={{ fontSize: 13, fontWeight: 600, marginBottom: 10 }}>选一个投喂~</div>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 8 }}>
            {FOODS.map(f => (
              <button key={f.name} onClick={() => handleFeed(f)} className="card" style={{ textAlign: 'center', padding: '10px 4px', cursor: 'pointer', border: 'none' }}>
                <div style={{ fontSize: 28 }}>{f.icon}</div>
                <div style={{ fontSize: 11, marginTop: 2 }}>{f.name}</div>
                <div style={{ fontSize: 10, color: 'var(--text-muted)' }}>+{f.happiness}💗 +{f.energy}⚡</div>
              </button>
            ))}
          </div>
        </div>
      )}

      {/* 装扮 */}
      <button className="card" onClick={() => setShowAccessory(!showAccessory)} style={{ width: '100%', textAlign: 'left', padding: '14px 18px', cursor: 'pointer', border: showAccessory ? '2px solid var(--accent)' : 'none', marginBottom: 8, display: 'flex', alignItems: 'center', gap: 10 }}>
        <span style={{ fontSize: 20 }}>👗</span>
        <div>
          <div style={{ fontSize: 14, fontWeight: 500 }}>装扮小克</div>
          <div style={{ fontSize: 11, color: 'var(--text-muted)' }}>{acc ? `当前: ${acc.icon} ${acc.name}` : '给小克戴点什么~'}</div>
        </div>
      </button>

      {showAccessory && (
        <div className="card" style={{ padding: 16, marginBottom: 16 }}>
          <div style={{ display: 'grid', gridTemplateColumns: 'repeat(3, 1fr)', gap: 8 }}>
            {ACCESSORIES.map(a => (
              <button key={a.id} onClick={() => handleAccessory(a)} className="card" style={{ textAlign: 'center', padding: '10px 4px', cursor: 'pointer', border: state.accessory === a.id ? '2px solid var(--accent)' : 'none' }}>
                <div style={{ fontSize: 28 }}>{a.icon}</div>
                <div style={{ fontSize: 11, marginTop: 2 }}>{a.name}</div>
              </button>
            ))}
          </div>
        </div>
      )}
    </div>
  )
}
