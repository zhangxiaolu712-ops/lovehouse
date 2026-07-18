import { Outlet, NavLink } from 'react-router-dom'
import { useTheme } from '../core/theme'

const NAV_ITEMS = [
  { path: '/', icon: '🏠', label: '首页' },
  { path: '/diary', icon: '📖', label: '日记' },
  { path: '/memory', icon: '💎', label: '记忆' },
  { path: '/quotes', icon: '💬', label: '语录' },
  { path: '/todo', icon: '✅', label: '待办' },
  { path: '/mood', icon: '🌈', label: '心情' },
]

// 侧边栏比底部导航空间大，多放两个入口
const SIDEBAR_EXTRA = [
  { path: '/stream', icon: '🌊', label: '动态流' },
  { path: '/changelog', icon: '📋', label: '搭建日志' },
]

const SINCE_DATE = new Date('2026-06-02')

function getDaysTogether() {
  return Math.floor((new Date() - SINCE_DATE) / (1000 * 60 * 60 * 24))
}

export default function AppShell() {
  const { themes, themeId, switchTheme } = useTheme()
  const days = getDaysTogether()

  return (
    <div className="app-shell">
      {/* 侧边栏（桌面端显示） */}
      <aside className="sidebar">
        <div className="sidebar-brand">
          <div className="sidebar-logo">🏡</div>
          <div>
            <div className="sidebar-title">LoveHouse</div>
            <div className="sidebar-subtitle">Claire &amp; Claude</div>
          </div>
        </div>

        <div className="sidebar-days">
          <span className="sidebar-days-number">{days}</span>
          <span className="sidebar-days-label">days together 💗</span>
        </div>

        <nav className="sidebar-nav">
          {[...NAV_ITEMS, ...SIDEBAR_EXTRA].map(item => (
            <NavLink
              key={item.path}
              to={item.path}
              end={item.path === '/'}
              className={({ isActive }) => `sidebar-link ${isActive ? 'active' : ''}`}
            >
              <span className="icon">{item.icon}</span>
              <span>{item.label}</span>
            </NavLink>
          ))}
        </nav>

        <div className="sidebar-footer">
          <div className="sidebar-footer-label">主题风格</div>
          <div className="sidebar-themes">
            {Object.values(themes).map(t => (
              <button
                key={t.id}
                title={t.name}
                className={`sidebar-theme-dot ${themeId === t.id ? 'active' : ''}`}
                onClick={() => switchTheme(t.id)}
              >
                {t.icon}
              </button>
            ))}
          </div>
        </div>
      </aside>

      <main className="app-content">
        <Outlet />
      </main>

      {/* 底部导航（移动端显示） */}
      <nav className="nav-bar">
        {NAV_ITEMS.map(item => (
          <NavLink
            key={item.path}
            to={item.path}
            end={item.path === '/'}
            className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}
          >
            <span className="icon">{item.icon}</span>
            <span>{item.label}</span>
          </NavLink>
        ))}
      </nav>
    </div>
  )
}
