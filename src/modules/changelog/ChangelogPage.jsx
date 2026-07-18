import { Link } from 'react-router-dom'

const LOGS = [
  {
    date: '2026-07-18',
    title: '修复部署构建',
    icon: '🔧',
    items: [
      '创建 .env.production，Vite 构建自动加载 Supabase 配置',
      '简化部署工作流，移除 GitHub Secrets 依赖',
      'PR #2 和 PR #3 合并，GitHub Pages 自动部署成功',
    ],
  },
  {
    date: '2026-07-18',
    title: '经典小屋主题 + 自动部署',
    icon: '🌸',
    items: [
      '新增「恋爱小屋」经典主题 — 完整还原旧版网页风格',
      '经典主题首页：问候语、欢迎卡片、心情天气、花瓣分隔符',
      '配置 GitHub Actions 自动构建部署到 GitHub Pages',
      '使用 HashRouter 兼容静态托管',
      '当前 4 套主题：🌸恋爱小屋、💙浪漫蓝、📜复古手账、🌙夜空紫',
    ],
  },
  {
    date: '2026-07-18',
    title: 'UI 美化重设计',
    icon: '🎨',
    items: [
      '重新设计 cozy 主题 → 浪漫蓝风格',
      '新增 vintage 主题 → 复古手账风格',
      '重新设计首页布局（天数计数、心情打卡、每日语录、功能入口）',
      '更新底部导航样式',
    ],
  },
  {
    date: '2026-07-18',
    title: '初始搭建',
    icon: '🏗️',
    items: [
      '初始化 Vite + React 项目',
      '接入 Supabase 数据库（6 张表）',
      '创建 6 个独立功能模块（日记、记忆、语录、待办、心情、动态流）',
      '创建 3 套可切换主题',
      '创建首页、应用外壳、路由系统',
      '模块化架构：每个零件可单独替换，互不耦合',
    ],
  },
]

export default function ChangelogPage() {
  return (
    <div>
      <div className="page-header">
        <h2 className="page-title">📋 搭建日志</h2>
        <Link to="/" style={{ textDecoration: 'none', fontSize: 14, color: 'var(--accent)' }}>返回首页</Link>
      </div>

      <div className="card" style={{ marginBottom: 16, padding: '16px 20px', fontSize: 13, color: 'var(--text-secondary)', lineHeight: 1.7 }}>
        记录小屋从零搭建的每一步。<br />
        每次修改、新增、修复都会留下痕迹~
      </div>

      <div style={{ position: 'relative' }}>
        <div style={{
          position: 'absolute', left: 16, top: 0, bottom: 0, width: 2,
          background: 'var(--border)', borderRadius: 1,
        }} />

        {LOGS.map((log, i) => (
          <div key={i} style={{ position: 'relative', paddingLeft: 40, marginBottom: 20 }}>
            <div style={{
              position: 'absolute', left: 8, top: 4, width: 18, height: 18,
              background: 'var(--bg-card)', border: '2px solid var(--accent)',
              borderRadius: '50%', display: 'flex', alignItems: 'center', justifyContent: 'center',
              fontSize: 10, zIndex: 1,
            }}>
              {log.icon}
            </div>

            <div className="card" style={{ padding: '16px 18px' }}>
              <div style={{ display: 'flex', justifyContent: 'space-between', alignItems: 'center', marginBottom: 10 }}>
                <div style={{ fontWeight: 600, fontSize: 15 }}>{log.title}</div>
                <div style={{ fontSize: 11, color: 'var(--text-muted)' }}>{log.date}</div>
              </div>
              <ul style={{ margin: 0, paddingLeft: 18, fontSize: 13, color: 'var(--text-secondary)', lineHeight: 1.8 }}>
                {log.items.map((item, j) => (
                  <li key={j}>{item}</li>
                ))}
              </ul>
            </div>
          </div>
        ))}
      </div>

      <div style={{ textAlign: 'center', padding: '16px 0 8px', fontSize: 12, color: 'var(--text-muted)' }}>
        — 小屋的故事，从这里开始 —
      </div>
    </div>
  )
}
