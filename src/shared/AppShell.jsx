import { useState, useEffect } from 'react'
import { Outlet, NavLink, useLocation } from 'react-router-dom'
import { useTheme } from '../core/theme'

// 六大中心（柜子）+ 各自的抽屉
const CENTERS = [
  {
    id: 'space', icon: '🏠', label: '空间中心',
    items: [
      { path: '/', icon: '🛋️', label: '主界面' },
      { path: '/space/layout', icon: '🧩', label: '布局系统' },
      { path: '/space/theme', icon: '🎨', label: '主题系统' },
      { path: '/space/notes', icon: '💌', label: '小纸条留言板' },
      { path: '/quotes', icon: '💬', label: '语录墙' },
      { path: '/todo', icon: '✅', label: '待办事项' },
    ],
  },
  {
    id: 'memory', icon: '🧠', label: '记忆中心',
    items: [
      { path: '/diary', icon: '📖', label: '日常记录 · 日记' },
      { path: '/mood', icon: '🌈', label: '日常记录 · 心情' },
      { path: '/stream', icon: '🌊', label: '日常记录 · 动态' },
      { path: '/memory', icon: '💎', label: '长期记忆' },
      { path: '/memory/tags', icon: '🏷️', label: '标签分类' },
      { path: '/memory/search', icon: '🔍', label: '搜索整理' },
    ],
  },
  {
    id: 'ai', icon: '🤖', label: 'AI中心',
    items: [
      { path: '/ai/config', icon: '🛠️', label: 'AI配置' },
      { path: '/ai/tasks', icon: '📮', label: 'AI任务箱' },
      { path: '/ai/app', icon: '📱', label: 'App AI模式' },
      { path: '/ai/api', icon: '🔗', label: '未来API接口' },
    ],
  },
  {
    id: 'device', icon: '🔌', label: '设备中心',
    items: [
      { path: '/device/toy', icon: '🧸', label: 'Toy' },
      { path: '/device/band', icon: '⌚', label: '手环' },
      { path: '/device/smart', icon: '💡', label: '智能设备' },
    ],
  },
  {
    id: 'project', icon: '📋', label: '项目中心',
    items: [
      { path: '/changelog', icon: '🧱', label: '搭建日志' },
      { path: '/project/updates', icon: '📝', label: '更新记录' },
      { path: '/project/handover', icon: '🤝', label: 'AI交接文档' },
    ],
  },
  {
    id: 'settings', icon: '⚙️', label: '设置中心',
    items: [
      { path: '/settings', icon: '⚙️', label: '通用设置' },
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
  const { pathname } = useLocation()
  const days = getDaysTogether()
  const [expanded, setExpanded] = useState(() => new Set([findCenterId(pathname)]))

  // 从别处跳转时，自动展开当前页所在的中心
  useEffect(() => {
    const id = findCenterId(pathname)
    setExpanded(prev => (prev.has(id) ? prev : new Set(prev).add(id)))
  }, [pathname])

  function toggleCenter(id) {
    setExpanded(prev => {
      const next = new Set(prev)
      next.has(id) ? next.delete(id) : next.add(id)
      return next
    })
  }

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
          {CENTERS.map(center => (
            <div key={center.id} className={`sidebar-group ${expanded.has(center.id) ? 'open' : ''}`}>
              <button className="sidebar-group-header" onClick={() => toggleCenter(center.id)}>
                <span className="icon">{center.icon}</span>
                <span>{center.label}</span>
                <span className="chevron">▶</span>
              </button>
              <div className="sidebar-group-items">
                {center.items.map(item => (
                  <NavLink
                    key={item.path}
                    to={item.path}
                    end
                    className={({ isActive }) => `sidebar-link sub ${isActive ? 'active' : ''}`}
                  >
                    <span className="icon">{item.icon}</span>
                    <span>{item.label}</span>
                  </NavLink>
                ))}
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
