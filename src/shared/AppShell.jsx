import { useState, useEffect, useRef, useCallback } from 'react'
import { Outlet, NavLink, Link, useLocation } from 'react-router-dom'
import { useTheme } from '../core/theme'

// 六大中心（柜子）+ 各自的抽屉（结构以小婷 2026-07-18 文档为准）
const CENTERS = [
  {
    id: 'space', icon: '🏠', label: '空间中心',
    items: [
      { path: '/', icon: '🛋️', label: '首页' },
      { path: '/space/layout', icon: '🧩', label: '布局' },
      { path: '/space/theme', icon: '🎨', label: '主题' },
      { path: '/space/notes', icon: '💌', label: '小纸条留言板' },
      { path: '/space/clawd', icon: '🐾', label: '养小克' },
      { path: '/todo', icon: '✅', label: '我们的待办' },
      { path: '/space/games', icon: '🎮', label: '游戏区' },
    ],
  },
  {
    id: 'memory', icon: '🧠', label: '记忆中心',
    items: [
      { path: '/stream', icon: '🔒', label: '私密记录' },
      { path: '/diary', icon: '📖', label: '日记' },
      { path: '/memory', icon: '💭', label: '碎碎念' },
      { path: '/quotes', icon: '💬', label: '原句收集' },
      { path: '/memory/inbox', icon: '📥', label: '总结待整理区' },
      { path: '/memory', query: 'level=固定', icon: '🔒', label: '固定记忆' },
      { path: '/memory', query: 'level=短期', icon: '⏳', label: '短期记忆' },
      { path: '/memory', query: 'level=长期', icon: '💎', label: '长期记忆' },
      { path: '/memory/search', icon: '🔍', label: '搜索浏览器' },
    ],
  },
  {
    id: 'ai', icon: '🤖', label: 'AI中心',
    items: [
      { path: '/ai/api', icon: '🔗', label: '未来API接口' },
      { path: '/ai/config', icon: '🛡️', label: 'AI可用权限' },
      { path: '/ai/apps', icon: '📬', label: 'AI已连软件' },
    ],
  },
  {
    id: 'device', icon: '🔌', label: '设备中心',
    items: [
      { path: '/device/toy', icon: '🧸', label: 'Toy' },
      { path: '/device/band', icon: '⌚', label: '手环' },
      { path: '/device/smart', icon: '💡', label: '智能家居' },
    ],
  },
  {
    id: 'project', icon: '📋', label: '项目中心',
    items: [
      { path: '/changelog', icon: '🧱', label: '搭建日志' },
      { path: '/project/updates', icon: '📝', label: '更新记录' },
    ],
  },
  {
    id: 'settings', icon: '⚙️', label: '设置中心',
    items: [
      { path: '/settings/backup', icon: '📦', label: '备份迁移' },
    ],
  },
]

// 移动端底部导航保持常用 6 项
const NAV_ITEMS = [
  { path: '/', icon: '🏠', label: '首页' },
  { path: '/diary', icon: '📖', label: '日记' },
  { path: '/memory', icon: '💎', label: '记忆' },
  { path: '/quotes', icon: '💬', label: '语录' },
  { path: '/todo', icon: '✅', label: '待办' },
  { path: '/mood', icon: '🌈', label: '心情' },
]

const SINCE_DATE = new Date('2026-06-02')

function getDaysTogether() {
  return Math.floor((new Date() - SINCE_DATE) / (1000 * 60 * 60 * 24))
}

function findCenterId(pathname) {
  const center = CENTERS.find(c => c.items.some(i => i.path === pathname))
  return center ? center.id : 'space'
}

export default function AppShell() {
  const { themes, themeId, switchTheme } = useTheme()
  const { pathname, search } = useLocation()
  const days = getDaysTogether()
  const [expanded, setExpanded] = useState(() => new Set([findCenterId(pathname)]))
  const [mobileOpen, setMobileOpen] = useState(false)
  const touchRef = useRef({ startX: 0, startY: 0 })

  // 从别处跳转时，自动展开当前页所在的中心
  useEffect(() => {
    const id = findCenterId(pathname)
    setExpanded(prev => (prev.has(id) ? prev : new Set(prev).add(id)))
  }, [pathname])

  // 跳转页面时自动关闭移动端侧边栏
  useEffect(() => {
    setMobileOpen(false)
  }, [pathname, search])

  // 左边缘滑动手势打开侧边栏
  const handleTouchStart = useCallback((e) => {
    touchRef.current = { startX: e.touches[0].clientX, startY: e.touches[0].clientY }
  }, [])

  const handleTouchEnd = useCallback((e) => {
    const dx = e.changedTouches[0].clientX - touchRef.current.startX
    const dy = Math.abs(e.changedTouches[0].clientY - touchRef.current.startY)
    if (touchRef.current.startX < 30 && dx > 60 && dy < 80) {
      setMobileOpen(true)
    }
    if (mobileOpen && dx < -60) {
      setMobileOpen(false)
    }
  }, [mobileOpen])

  useEffect(() => {
    document.addEventListener('touchstart', handleTouchStart, { passive: true })
    document.addEventListener('touchend', handleTouchEnd, { passive: true })
    return () => {
      document.removeEventListener('touchstart', handleTouchStart)
      document.removeEventListener('touchend', handleTouchEnd)
    }
  }, [handleTouchStart, handleTouchEnd])

  function toggleCenter(id) {
    setExpanded(prev => {
      const next = new Set(prev)
      next.has(id) ? next.delete(id) : next.add(id)
      return next
    })
  }

  return (
    <div className="app-shell">
      {/* 移动端汉堡按钮 */}
      <button className="mobile-menu-btn" onClick={() => setMobileOpen(true)} aria-label="打开菜单">
        <span className="hamburger-icon" />
      </button>

      {/* 移动端遮罩 */}
      <div className={`sidebar-overlay ${mobileOpen ? 'open' : ''}`} onClick={() => setMobileOpen(false)} />

      {/* 侧边栏（桌面固定 + 移动侧滑） */}
      <aside className={`sidebar ${mobileOpen ? 'mobile-open' : ''}`}>
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
          {CENTERS.map(center => (
            <div key={center.id} className={`sidebar-group ${expanded.has(center.id) ? 'open' : ''}`}>
              <button className="sidebar-group-header" onClick={() => toggleCenter(center.id)}>
                <span className="icon">{center.icon}</span>
                <span>{center.label}</span>
                <span className="chevron">▶</span>
              </button>
              <div className="sidebar-group-items">
                {center.items.map(item => {
                  // 带 query 的抽屉（固定/短期/长期记忆）需自行判断高亮
                  const to = item.query ? `${item.path}?${item.query}` : item.path
                  const onPath = pathname === item.path
                  const isActive = item.query
                    ? onPath && search === `?${item.query}`
                    : onPath && (item.path !== '/memory' || !search.includes('level='))
                  return (
                    <Link
                      key={`${item.path}${item.query || ''}`}
                      to={to}
                      className={`sidebar-link sub ${isActive ? 'active' : ''}`}
                    >
                      <span className="icon">{item.icon}</span>
                      <span>{item.label}</span>
                    </Link>
                  )
                })}
              </div>
            </div>
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
