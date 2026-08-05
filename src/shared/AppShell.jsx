import { useEffect } from 'react'
import { Outlet, NavLink, useLocation } from 'react-router'
import LineIcon from './LineIcon'

const TABS = [
  { to: '/chat',     icon: 'chat',     label: '聊天' },
  { to: '/brain',    icon: 'memory',   label: '记忆' },
  { to: '/moments',  icon: 'interact', label: '朋友圈' },
  { to: '/settings', icon: 'settings', label: '设置' },
]

export default function AppShell() {
  const { pathname } = useLocation()

  useEffect(() => { window.scrollTo(0, 0) }, [pathname])

  return (
    <div className="shell">
      <main className="shell-main">
        <Outlet />
      </main>

      <nav className="shell-nav">
        {TABS.map(t => (
          <NavLink
            key={t.to}
            to={t.to}
            end={t.to === '/'}
            className={({ isActive }) =>
              `shell-tab${isActive ? ' on' : ''}`
            }
          >
            <LineIcon name={t.icon} size={21} />
            <span>{t.label}</span>
          </NavLink>
        ))}
      </nav>
    </div>
  )
}
