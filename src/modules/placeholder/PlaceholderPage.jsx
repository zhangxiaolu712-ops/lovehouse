import { useLocation, Link } from 'react-router-dom'

// 规划中的抽屉：路由 → 介绍
const DRAWERS = {
  '/space/layout': { icon: '🧩', title: '布局', center: '🏠 空间中心', desc: '侧边栏式/底部导航式切换，手机和网页布局保持一致。' },
  '/space/games': { icon: '🎮', title: '游戏区', center: '🏠 空间中心', desc: '收藏聊天记录里一起搓出来的小游戏文件，随时回来玩。' },
  '/memory/inbox': { icon: '📥', title: '总结待整理区', center: '🧠 记忆中心', desc: '还没归类好的聊天记录总结先放这里，整理好再入库。' },
  '/ai/api': { icon: '🔗', title: '未来API接口', center: '🤖 AI中心', desc: '开放接口，让更多 AI 和服务接入小屋。' },
  '/ai/config': { icon: '🛡️', title: 'AI可用权限', center: '🤖 AI中心', desc: '展示小克已有的权限：Supabase、GitHub、网络域名白名单等。' },
  '/ai/apps': { icon: '📬', title: 'AI已连软件', center: '🤖 AI中心', desc: '展示已连接的软件：Gmail 等。' },
  '/device/toy': { icon: '🧸', title: 'Toy', center: '🔌 设备中心', desc: '成人玩具接口预留。' },
  '/device/band': { icon: '⌚', title: '手环', center: '🔌 设备中心', desc: '运动手环接入，界面里可以看心跳和睡眠。' },
  '/device/smart': { icon: '💡', title: '智能家居', center: '🔌 设备中心', desc: '灯光、音箱等智能家居设备接入。' },
  '/project/updates': { icon: '📝', title: '更新记录', center: '📋 项目中心', desc: '面向使用者的版本更新说明（搭建日志的精简版）。' },
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
