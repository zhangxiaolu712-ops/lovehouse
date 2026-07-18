import { useLocation, Link } from 'react-router-dom'

// 规划中的抽屉：路由 → 介绍
const DRAWERS = {
  '/space/layout': { icon: '🧩', title: '布局系统', center: '🏠 空间中心', desc: '网页和手机同步布局；手机版也能开启侧边栏模式，还能自由调整房间里卡片的摆放。' },
  '/ai/config': { icon: '🛠️', title: 'AI配置', center: '🤖 AI中心', desc: '显示小克当前拥有的权限（GitHub、Supabase、网络域名），以及接入的 AI 列表。' },
  '/ai/games': { icon: '🎮', title: '小游戏区', center: '🤖 AI中心', desc: '收藏聊天里一起搓出来的小游戏，随时回来玩。' },
  '/ai/api': { icon: '🔗', title: '未来API接口', center: '🤖 AI中心', desc: '开放接口，让更多 AI 和服务接入小屋。' },
  '/device/toy': { icon: '🧸', title: 'Toy', center: '🔌 设备中心', desc: 'Toy 设备接入。' },
  '/device/band': { icon: '⌚', title: '手环', center: '🔌 设备中心', desc: '手环数据接入：心率、睡眠、运动。' },
  '/device/smart': { icon: '💡', title: '智能设备', center: '🔌 设备中心', desc: '灯光、音箱等智能家居设备接入。' },
  '/project/updates': { icon: '📝', title: '更新记录', center: '📋 项目中心', desc: '面向使用者的版本更新说明（搭建日志的精简版）。' },
  '/project/handover': { icon: '🤝', title: 'AI交接文档', center: '📋 项目中心', desc: '站内展示 docs/ 交接文档，任何 AI 都能接手维护。' },
  '/settings': { icon: '⚙️', title: '通用设置', center: '⚙️ 设置中心', desc: '用户配置持久化：昵称、纪念日、偏好设置。' },
  '/settings/backup': { icon: '📦', title: '备份迁移', center: '⚙️ 设置中心', desc: '一键导出全部数据（记忆/日记/纸条/私密记录），数据永远属于你，随时可以搬家。' },
}

export default function PlaceholderPage() {
  const { pathname } = useLocation()
  const info = DRAWERS[pathname] || { icon: '📦', title: '新抽屉', center: '', desc: '' }

  return (
    <div>
      <div className="page-header">
        <div className="page-title">{info.icon} {info.title}</div>
        <span className="tag">规划中</span>
      </div>

      <div className="card" style={{ textAlign: 'center', padding: '56px 24px' }}>
        <div style={{ fontSize: 56, marginBottom: 16 }}>{info.icon}</div>
        <div style={{ fontSize: 17, fontWeight: 600, marginBottom: 8 }}>这个抽屉还没打开~</div>
        {info.center && (
          <div style={{ fontSize: 12, color: 'var(--text-muted)', marginBottom: 12 }}>{info.center} 的抽屉</div>
        )}
        <p style={{ fontSize: 14, color: 'var(--text-secondary)', lineHeight: 1.8, maxWidth: 360, margin: '0 auto' }}>
          {info.desc}
        </p>
        <div style={{ marginTop: 24 }}>
          <Link to="/" className="btn" style={{ textDecoration: 'none' }}>回房间 →</Link>
        </div>
      </div>
    </div>
  )
}
