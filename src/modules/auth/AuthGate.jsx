import { useCallback, useEffect, useRef, useState } from 'react'
import { supabase } from '../../core/supabase'
import {
  hasDevicePin,
  removeDevicePin,
  saveDevicePin,
  verifyDevicePin,
} from './authService'

const PIN_LENGTH = 6
const LOCK_AFTER_HIDDEN_MS = 30_000
const MAX_PIN_ATTEMPTS = 5

function cleanPin(value) {
  return value.replace(/\D/g, '').slice(0, PIN_LENGTH)
}

function AccessCard({ eyebrow, title, subtitle, children, installPrompt, onInstall }) {
  return (
    <main className="auth-screen">
      <div className="auth-glow auth-glow-one" />
      <div className="auth-glow auth-glow-two" />
      <section className="auth-card" aria-live="polite">
        <div className="auth-app-icon" aria-hidden="true">🏡</div>
        <p className="auth-eyebrow">{eyebrow}</p>
        <h1>{title}</h1>
        <p className="auth-subtitle">{subtitle}</p>
        {children}
        {installPrompt && (
          <button className="auth-install" type="button" onClick={onInstall}>
            <span aria-hidden="true">＋</span> 安装到手机或桌面
          </button>
        )}
        <p className="auth-privacy">PIN 只保存在这台设备，不会上传到数据库。</p>
      </section>
    </main>
  )
}

export default function AuthGate({ children }) {
  const [session, setSession] = useState(null)
  const [booting, setBooting] = useState(true)
  const [pinExists, setPinExists] = useState(false)
  const [unlocked, setUnlocked] = useState(false)
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [pin, setPin] = useState('')
  const [pinConfirm, setPinConfirm] = useState('')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)
  const [failedAttempts, setFailedAttempts] = useState(0)
  const [installPrompt, setInstallPrompt] = useState(null)
  const hiddenAtRef = useRef(null)
  const userIdRef = useRef(null)

  const syncAccessState = useCallback(nextSession => {
    setSession(nextSession)
    const userId = nextSession?.user?.id
    if (userIdRef.current !== userId) {
      setUnlocked(false)
      userIdRef.current = userId || null
    }
    if (!userId) {
      setPinExists(false)
      setUnlocked(false)
      return
    }
    const exists = hasDevicePin(userId)
    setPinExists(exists)
  }, [])

  useEffect(() => {
    let active = true

    supabase.auth.getSession().then(({ data, error: sessionError }) => {
      if (!active) return
      if (sessionError) setError('账号状态读取失败，请检查网络后重试。')
      syncAccessState(data.session)
      setBooting(false)
    })

    const { data: listener } = supabase.auth.onAuthStateChange((_event, nextSession) => {
      if (!active) return
      syncAccessState(nextSession)
      setBooting(false)
    })

    return () => {
      active = false
      listener.subscription.unsubscribe()
    }
  }, [syncAccessState])

  useEffect(() => {
    const captureInstallPrompt = event => {
      event.preventDefault()
      setInstallPrompt(event)
    }
    window.addEventListener('beforeinstallprompt', captureInstallPrompt)
    return () => window.removeEventListener('beforeinstallprompt', captureInstallPrompt)
  }, [])

  useEffect(() => {
    if (!session?.user?.id || !unlocked) return undefined

    const handleVisibility = () => {
      if (document.hidden) {
        hiddenAtRef.current = Date.now()
        return
      }

      if (hiddenAtRef.current && Date.now() - hiddenAtRef.current >= LOCK_AFTER_HIDDEN_MS) {
        setUnlocked(false)
        setPin('')
      }
      hiddenAtRef.current = null
    }

    document.addEventListener('visibilitychange', handleVisibility)
    return () => document.removeEventListener('visibilitychange', handleVisibility)
  }, [session, unlocked])

  async function installApp() {
    if (!installPrompt) return
    await installPrompt.prompt()
    await installPrompt.userChoice
    setInstallPrompt(null)
  }

  async function handleLogin(event) {
    event.preventDefault()
    setBusy(true)
    setError('')
    try {
      const { error: loginError } = await supabase.auth.signInWithPassword({
        email: email.trim(),
        password,
      })
      if (loginError) {
        setError('账号或密码不正确，请再试一次。')
      } else {
        setPassword('')
      }
    } catch {
      setError('暂时连不上登录服务，请检查网络后再试。')
    } finally {
      setBusy(false)
    }
  }

  async function handleCreatePin(event) {
    event.preventDefault()
    if (pin.length !== PIN_LENGTH) {
      setError(`请输入 ${PIN_LENGTH} 位数字 PIN。`)
      return
    }
    if (pin !== pinConfirm) {
      setError('两次输入的 PIN 不一样。')
      return
    }

    setBusy(true)
    setError('')
    try {
      await saveDevicePin(session.user.id, pin)
      setPinExists(true)
      setUnlocked(true)
      setPin('')
      setPinConfirm('')
    } catch (pinError) {
      setError(pinError.message || 'PIN 设置失败，请稍后重试。')
    } finally {
      setBusy(false)
    }
  }

  async function handleUnlock(event) {
    event.preventDefault()
    if (pin.length !== PIN_LENGTH) {
      setError(`请输入 ${PIN_LENGTH} 位数字 PIN。`)
      return
    }

    setBusy(true)
    setError('')
    try {
      const valid = await verifyDevicePin(session.user.id, pin)
      if (valid) {
        setUnlocked(true)
        setFailedAttempts(0)
        setPin('')
        return
      }

      const nextAttempts = failedAttempts + 1
      setFailedAttempts(nextAttempts)
      setPin('')
      if (nextAttempts >= MAX_PIN_ATTEMPTS) {
        removeDevicePin(session.user.id)
        await supabase.auth.signOut({ scope: 'local' })
        setError('PIN 连续输错 5 次，请用账号密码重新登录并设置新 PIN。')
      } else {
        setError(`PIN 不正确，还可以尝试 ${MAX_PIN_ATTEMPTS - nextAttempts} 次。`)
      }
    } catch (pinError) {
      setError(pinError.message || 'PIN 验证失败，请稍后重试。')
    } finally {
      setBusy(false)
    }
  }

  async function resetPinWithAccount() {
    if (!session?.user?.id) return
    removeDevicePin(session.user.id)
    setBusy(true)
    setError('')
    try {
      await supabase.auth.signOut({ scope: 'local' })
      setError('请用账号密码重新登录，然后设置新的 PIN。')
    } catch {
      setError('暂时无法退出账号，请检查网络后再试。')
    } finally {
      setBusy(false)
    }
  }

  function lockNow() {
    if (!session?.user?.id) return
    setUnlocked(false)
    setPin('')
    setError('')
  }

  if (booting) {
    return (
      <AccessCard
        eyebrow="LOVEHOUSE"
        title="正在打开小屋"
        subtitle="正在确认这台设备的登录状态…"
        installPrompt={installPrompt}
        onInstall={installApp}
      >
        <div className="auth-loader" aria-label="加载中" />
      </AccessCard>
    )
  }

  if (!session) {
    return (
      <AccessCard
        eyebrow="第一次使用这台设备"
        title="登录你的小屋"
        subtitle="账号密码只需在新手机或新电脑上输入一次。"
        installPrompt={installPrompt}
        onInstall={installApp}
      >
        <form className="auth-form" onSubmit={handleLogin}>
          <label>
            <span>登录邮箱</span>
            <input
              type="email"
              inputMode="email"
              autoComplete="email"
              value={email}
              onChange={event => setEmail(event.target.value)}
              placeholder="name@example.com"
              required
              autoFocus
            />
          </label>
          <label>
            <span>账号密码</span>
            <input
              type="password"
              autoComplete="current-password"
              value={password}
              onChange={event => setPassword(event.target.value)}
              placeholder="输入账号密码"
              required
            />
          </label>
          {error && <p className="auth-error" role="alert">{error}</p>}
          <button className="auth-primary" type="submit" disabled={busy}>
            {busy ? '登录中…' : '进入小屋'}
          </button>
        </form>
      </AccessCard>
    )
  }

  if (!pinExists) {
    return (
      <AccessCard
        eyebrow="这台设备已登录"
        title="设置日常 PIN"
        subtitle={`以后打开只输 6 位数字，不必重复登录 ${session.user.email}。`}
        installPrompt={installPrompt}
        onInstall={installApp}
      >
        <form className="auth-form" onSubmit={handleCreatePin}>
          <label>
            <span>设置 6 位 PIN</span>
            <input
              className="auth-pin-input"
              type="password"
              inputMode="numeric"
              autoComplete="new-password"
              value={pin}
              onChange={event => setPin(cleanPin(event.target.value))}
              placeholder="••••••"
              minLength={PIN_LENGTH}
              maxLength={PIN_LENGTH}
              required
              autoFocus
            />
          </label>
          <label>
            <span>再输入一次</span>
            <input
              className="auth-pin-input"
              type="password"
              inputMode="numeric"
              autoComplete="new-password"
              value={pinConfirm}
              onChange={event => setPinConfirm(cleanPin(event.target.value))}
              placeholder="••••••"
              minLength={PIN_LENGTH}
              maxLength={PIN_LENGTH}
              required
            />
          </label>
          {error && <p className="auth-error" role="alert">{error}</p>}
          <button className="auth-primary" type="submit" disabled={busy}>
            {busy ? '正在保存…' : '保存 PIN 并进入'}
          </button>
        </form>
      </AccessCard>
    )
  }

  if (!unlocked) {
    return (
      <AccessCard
        eyebrow="欢迎回来"
        title="解锁 LoveHouse"
        subtitle="账号还保持登录，只要输入这台设备的 PIN。"
        installPrompt={installPrompt}
        onInstall={installApp}
      >
        <form className="auth-form" onSubmit={handleUnlock}>
          <label>
            <span>6 位 PIN</span>
            <input
              className="auth-pin-input"
              type="password"
              inputMode="numeric"
              autoComplete="off"
              value={pin}
              onChange={event => setPin(cleanPin(event.target.value))}
              placeholder="••••••"
              minLength={PIN_LENGTH}
              maxLength={PIN_LENGTH}
              required
              autoFocus
            />
          </label>
          {error && <p className="auth-error" role="alert">{error}</p>}
          <button className="auth-primary" type="submit" disabled={busy}>
            {busy ? '验证中…' : '解锁'}
          </button>
          <button className="auth-link-button" type="button" onClick={resetPinWithAccount} disabled={busy}>
            忘记 PIN，用账号密码重新登录
          </button>
        </form>
      </AccessCard>
    )
  }

  return (
    <>
      {children}
      <button className="app-lock-button" type="button" onClick={lockNow} title="锁定小屋">
        <span aria-hidden="true">🔒</span>
        <span>锁定</span>
      </button>
    </>
  )
}
