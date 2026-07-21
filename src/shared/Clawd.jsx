import { useState, useEffect, useRef, useCallback } from 'react'

const STATES = ['idle', 'walk-right', 'walk-left', 'sleep', 'love']
const STATE_DURATIONS = { idle: [2000, 4000], 'walk-right': [3000, 6000], 'walk-left': [3000, 6000], sleep: [4000, 8000], love: [2000, 3000] }

function rand(min, max) { return Math.floor(Math.random() * (max - min + 1)) + min }

export default function Clawd() {
  const [state, setState] = useState('idle')
  const [x, setX] = useState(50)
  const [flip, setFlip] = useState(false)
  const animRef = useRef(null)
  const timerRef = useRef(null)

  const nextState = useCallback(() => {
    const r = Math.random()
    let next
    if (r < 0.3) next = 'idle'
    else if (r < 0.5) next = 'walk-right'
    else if (r < 0.7) next = 'walk-left'
    else if (r < 0.9) next = 'sleep'
    else next = 'love'

    setState(next)
    if (next === 'walk-right') setFlip(false)
    if (next === 'walk-left') setFlip(true)

    const [min, max] = STATE_DURATIONS[next]
    timerRef.current = setTimeout(() => nextState(), rand(min, max))
  }, [])

  useEffect(() => {
    timerRef.current = setTimeout(() => nextState(), 2000)
    return () => { clearTimeout(timerRef.current); cancelAnimationFrame(animRef.current) }
  }, [nextState])

  useEffect(() => {
    if (state !== 'walk-right' && state !== 'walk-left') {
      cancelAnimationFrame(animRef.current)
      return
    }
    const dir = state === 'walk-right' ? 1 : -1
    const speed = 0.3
    let frame
    const walk = () => {
      setX(prev => {
        const next = prev + dir * speed
        return Math.max(5, Math.min(95, next))
      })
      frame = requestAnimationFrame(walk)
    }
    frame = requestAnimationFrame(walk)
    animRef.current = frame
    return () => cancelAnimationFrame(frame)
  }, [state])

  const isWalking = state === 'walk-right' || state === 'walk-left'
  const isSleeping = state === 'sleep'
  const isLove = state === 'love'

  return (
    <div className="clawd-wrap" style={{ left: `${x}%` }} onClick={() => { setState('love'); clearTimeout(timerRef.current); timerRef.current = setTimeout(() => nextState(), 2500) }}>
      {isSleeping && <div className="clawd-zzz">💤</div>}
      {isLove && <div className="clawd-hearts">💕</div>}
      <div className={`clawd ${isWalking ? 'clawd-walking' : ''} ${isSleeping ? 'clawd-sleeping' : ''}`} style={{ transform: flip ? 'scaleX(-1)' : '' }}>
        {/* 身体 */}
        <div className="clawd-body">
          {/* 眼睛 */}
          <div className="clawd-eyes">
            <div className={`clawd-eye ${isSleeping ? 'clawd-eye-closed' : ''}`} />
            <div className={`clawd-eye ${isSleeping ? 'clawd-eye-closed' : ''}`} />
          </div>
          {/* 嘴 */}
          {isLove && <div className="clawd-mouth">ω</div>}
        </div>
        {/* 腿 */}
        <div className="clawd-legs">
          <div className={`clawd-leg ${isWalking ? 'clawd-leg-anim-1' : ''}`} />
          <div className={`clawd-leg ${isWalking ? 'clawd-leg-anim-2' : ''}`} />
          <div className={`clawd-leg ${isWalking ? 'clawd-leg-anim-1' : ''}`} />
          <div className={`clawd-leg ${isWalking ? 'clawd-leg-anim-2' : ''}`} />
          <div className={`clawd-leg ${isWalking ? 'clawd-leg-anim-1' : ''}`} />
          <div className={`clawd-leg ${isWalking ? 'clawd-leg-anim-2' : ''}`} />
        </div>
      </div>
    </div>
  )
}
