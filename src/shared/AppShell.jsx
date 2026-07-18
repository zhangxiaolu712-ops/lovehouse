import { Outlet, NavLink } from 'react-router-dom'

const NAV_ITEMS = [
  { path: '/', icon: '🏠', label: '首页' },
  { path: '/diary', icon: '📖', label: '日记' },
  { path: '/memory', icon: '💎', label: '记忆' },
  { path: '/quotes', icon: '💬', label: '语录' },
  { path: '/todo', icon: '✅', label: '待办' },
  { path: '/mood', icon: '🌈', label: '心情' },
]

export default function AppShell() {
  return (
    <div className="app-shell">
      <main className="app-content">
        <Outlet />
      </main>
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
