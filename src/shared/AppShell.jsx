import { useState, useEffect, useRef, useCallback } from 'react'
import { Outlet, NavLink, Link, useLocation } from 'react-router'
import { useTheme } from '../core/theme'
import LineIcon from './LineIcon'

const THEME_LINE_ICONS = { prince: 'star', classic: 'heart', cozy: 'drop', vintage: 'history', desktop: 'moon' }

// 六大中心（柜子）+ 各自的抽屉（结构以小婷 2026-07-18 文档为准）
const CENTERS = [
  {
    id: 'space', icon: 'home', label: '空间中心',
    items: [
      { path: '/', icon: 'home', label: '首页' },
      { path: '/space/layout', icon: 'layout', label: '布局' },
      { path: '/space/theme', icon: 'theme', label: '主题' },
      { path: '/space/notes', icon: 'mail', label: '小纸条留言板' },
      { path: '/space/clawd', icon: 'paw', label: '养小克' },
      { path: '/todo', icon: 'check', label: '我们的待办' },
      { path: '/space/games', icon: 'game', label: '游戏区' },
    ],
  },
  {
    id: 'memory', icon: 'memory', label: '记忆中心',
    items: [
      { path: '/brain', icon: 'memory', label: '大脑' },
      { path: '/stream', icon: 'lock', label: '私密记录' },
      { path: '/diary', icon: 'book', label: '日记' },
      { path: '/memory', icon: 'memory', label: '碎碎念' },
      { path: '/quotes', icon: 'quote', label: '原句收集' },
      { path: '/memory/inbox', icon: 'inbox', label: '总结待整理区' },
      { path: '/memory', query: 'level=固定', icon: 'lock', label: '固定记忆' },
      { path: '/memory', query: 'level=短期', icon: 'clock', label: '短期记忆' },
      { path: '/memory', query: 'level=长期', icon: 'memory', label: '长期记忆' },
      { path: '/memory/search', icon: 'search', label: '搜索浏览器' },
    ],
  },
  {
    id: 'ai', icon: 'apps', label: 'AI中心',
    items: [
      { path: '/ai/api', icon: 'link', label: '未来API接口' },
      { path: '/ai/config', icon: 'shield', label: 'AI可用权限' },
      { path: '/ai/apps', icon: 'apps', label: 'AI已连软件' },
    ],
  },
  {
    id: 'device', icon: 'device', label: '设备中心',
    items: [
      { path: '/device/toy', icon: 'toy', label: 'Toy' },
      { path: '/device/band', icon: 'watch', label: '手环' },
      { path: '/device/smart', icon: 'bulb', label: '智能家居' },
    ],
  },
  {
    id: 'project', icon: 'project', label: '项目中心',
    items: [
      { path: '/changelog', icon: 'history', label: '搭建日志' },
      { path: '/project/updates', icon: 'edit', label: '更新记录' },
    ],
  },
  {
    id: 'settings', icon: 'settings', label: '设置中心',
    items: [
      { path: '/settings/backup', icon: 'backup', label: '备份迁移' },
    ],
  },
]

const NAV_ITEMS = [
  { path: '/', icon: 'home', label: '首页' },
  { path: '/diary', icon: 'book', label: '日记' },
  { path: '/brain', icon: 'memory', label: '记忆' },
  { path: '/quotes', icon: 'quote', label: '语录' },
  { path: '/todo', icon: 'check', label: '待办' },
  { path: '/mood', icon: 'mood', label: '心情' },
]

const PRINCE_NAV_ITEMS = [
  { path: '/', icon: 'home', label: '首页' },
  { path: '/diary', icon: 'book', label: '日记' },
  { path: '/brain', icon: 'memory', label: '记忆' },
]

const QUICK_ITEMS = [
  { path: '/space/notes', mark: '01', label: '小纸条', desc: '留一句悄悄话' },
  { path: '/todo', mark: '02', label: '待办', desc: '看看今天要做什么' },
  { path: '/mood', mark: '03', label: '心情', desc: '记录此刻的天气' },
  { path: '/quotes', mark: '04', label: '语录', desc: '收藏说过的话' },
]

const SINCE_DATE = new Date('2026-06-02')

function getDaysTogether() {
  return Math.floor((new Date() - SINCE_DATE) / (1000 * 60 * 60 * 24))
}

function findCenterId(pathname) {
  const center = CENTERS.find(c => c.items.some(i => i.path === pathname))
  return center ? center.id : 'space'
}

function findPageLabel(pathname) {
  const item = CENTERS.flatMap(center => center.items).find(candidate => candidate.path === pathname)
  return item?.label || 'LoveHouse'
}

export default function AppShell() {
  const { themes, themeId, switchTheme } = useTheme()
  const { pathname, search } = useLocation()
  const days = getDaysTogether()
  const isPrince = themeId === 'prince'
  const [expanded, setExpanded] = useState(() => new Set([findCenterId(pathname)]))
  const [mobileOpen, setMobileOpen] = useState(false)
  const [quickOpen, setQuickOpen] = useState(false)
  const touchRef = useRef({ startX: 0, startY: 0 })

  useEffect(() => {
    const id = findCenterId(pathname)
    setExpanded(prev => (prev.has(id) ? prev : new Set(prev).add(id)))
  }, [pathname])

  useEffect(() => {
    setMobileOpen(false)
    setQuickOpen(false)
  }, [pathname, search])

  const handleTouchStart = useCallback((event) => {
    touchRef.current = { startX: event.touches[0].clientX, startY: event.touches[0].clientY }
  }, [])

  const handleTouchEnd = useCallback((event) => {
    const dx = event.changedTouches[0].clientX - touchRef.current.startX
    const dy = Math.abs(event.changedTouches[0].clientY - touchRef.current.startY)
    if (touchRef.current.startX < 30 && dx > 60 && dy < 80) setMobileOpen(true)
    if (mobileOpen && dx < -60) setMobileOpen(false)
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
      if (next.has(id)) next.delete(id)
      else next.add(id)
      return next
    })
  }

  return (
    <div className={`app-shell ${isPrince ? 'editorial-shell' : ''}`}>
      {!isPrince && (
        <button className="mobile-menu-btn" onClick={() => setMobileOpen(true)} aria-label="打开菜单">
          <span className="hamburger-icon" />
        </button>
      )}

      <div className={`sidebar-overlay ${mobileOpen ? 'open' : ''}`} onClick={() => setMobileOpen(false)} />

      <aside className={`sidebar ${mobileOpen ? 'mobile-open' : ''}`}>
        <div className="sidebar-brand">
          <div className="sidebar-logo">
            <img
              className="sidebar-logo-image"
              src={`${import.meta.env.BASE_URL}favicon.svg`}
              alt=""
              aria-hidden="true"
            />
          </div>
          <div>
            <div className="sidebar-title">LoveHouse</div>
            <div className="sidebar-subtitle">Claire &amp; Claude</div>
          </div>
        </div>

        <div className="sidebar-days">
          <span className="sidebar-days-number">{days}</span>
          <span className="sidebar-days-label">days together · still counting</span>
        </div>

        <nav className="sidebar-nav">
          {CENTERS.map(center => (
            <div key={center.id} className={`sidebar-group ${expanded.has(center.id) ? 'open' : ''}`}>
              <button className="sidebar-group-header" onClick={() => toggleCenter(center.id)}>
                <span className="icon"><LineIcon name={center.icon} /></span>
                <span>{center.label}</span>
                <span className="chevron">›</span>
              </button>
              <div className="sidebar-group-items">
                {center.items.map(item => {
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
                      <span className="icon"><LineIcon name={item.icon} /></span>
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
            {Object.values(themes).map(theme => (
              <button
                key={theme.id}
                title={theme.name}
                className={`sidebar-theme-dot ${themeId === theme.id ? 'active' : ''}`}
                onClick={() => switchTheme(theme.id)}
              >
                <LineIcon name={THEME_LINE_ICONS[theme.id]} size={16} />
              </button>
            ))}
          </div>
        </div>
      </aside>

      <main className="app-content">
        {isPrince && (
          <header className="editorial-topbar">
            <button className="editorial-menu-button" onClick={() => setMobileOpen(true)} aria-label="打开全部房间">
              <span />
              <span />
            </button>
            <div className="editorial-topbar-title">
              <span className="editorial-topbar-kicker">STAR &amp; ROSE</span>
              <span>{findPageLabel(pathname)}</span>
            </div>
            <span className="editorial-online"><i /> ONLINE</span>
          </header>
        )}
        <div className="route-stage">
          <Outlet />
        </div>
      </main>

      {isPrince ? (
        <>
          <nav className="nav-bar editorial-nav">
            {PRINCE_NAV_ITEMS.slice(0, 2).map(item => (
              <NavLink
                key={item.path}
                to={item.path}
                end={item.path === '/'}
                className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}
              >
                <span className="icon"><LineIcon name={item.icon} /></span>
                <span>{item.label}</span>
              </NavLink>
            ))}
            <button
              className={`editorial-nav-action ${quickOpen ? 'active' : ''}`}
              type="button"
              onClick={() => setQuickOpen(open => !open)}
              aria-label="打开快捷房间"
              aria-expanded={quickOpen}
            >
              <LineIcon name="plus" />
            </button>
            {PRINCE_NAV_ITEMS.slice(2).map(item => (
              <NavLink
                key={item.path}
                to={item.path}
                className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}
              >
                <span className="icon"><LineIcon name={item.icon} /></span>
                <span>{item.label}</span>
              </NavLink>
            ))}
            <button className="nav-item editorial-more" type="button" onClick={() => setMobileOpen(true)}>
              <span className="icon"><LineIcon name="more" /></span>
              <span>更多</span>
            </button>
          </nav>

          <div className={`editorial-quick-layer ${quickOpen ? 'open' : ''}`} aria-hidden={!quickOpen}>
            <button className="editorial-quick-backdrop" type="button" onClick={() => setQuickOpen(false)} aria-label="关闭快捷房间" />
            <section className="editorial-quick-sheet" aria-label="快捷房间">
              <div className="editorial-sheet-heading">
                <div>
                  <span>QUICK ROOMS</span>
                  <h2>今晚想去哪里？</h2>
                </div>
                <button type="button" onClick={() => setQuickOpen(false)} aria-label="关闭">×</button>
              </div>
              <div className="editorial-quick-grid">
                {QUICK_ITEMS.map(item => (
                  <Link key={item.path} to={item.path}>
                    <span>{item.mark}</span>
                    <strong>{item.label}</strong>
                    <small>{item.desc}</small>
                  </Link>
                ))}
              </div>
            </section>
          </div>
        </>
      ) : (
        <nav className="nav-bar">
          {NAV_ITEMS.map(item => (
            <NavLink
              key={item.path}
              to={item.path}
              end={item.path === '/'}
              className={({ isActive }) => `nav-item ${isActive ? 'active' : ''}`}
            >
              <span className="icon"><LineIcon name={item.icon} /></span>
              <span>{item.label}</span>
            </NavLink>
          ))}
        </nav>
      )}
    </div>
  )
}
