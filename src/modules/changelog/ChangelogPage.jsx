import LineIcon from '../../shared/LineIcon'
import { MobileCard, MobilePage, MobilePageHeader } from '../../shared/MobileUI'

const LOGS = [
  {
    date: '2026-07-27',
    title: '星球玫瑰 · 新格局与视觉统一',
    icon: 'theme',
    items: [
      '重做星球玫瑰首页格局：主视觉、纪念天数、周历、天气、今日心情与房间索引重新排版',
      '导航、门锁和主要页面标题统一为简洁的 SVG 线性图标',
      '侧边栏、首页与内容区统一衬线字体，标题和重点数字增加轻微倾斜',
      '卡片改为半透明玻璃效果，可以看见后方背景但仍保持文字清楚',
      '背景系统独立管理，接入可商用的粉色纸纹玫瑰素材，今后可以单独换图',
    ],
  },
  {
    date: '2026-07-26',
    title: 'LoveHouse App · 登录与 6 位 PIN 门锁',
    icon: 'lock',
    items: [
      '增加账号登录：更换手机或电脑时登录一次即可',
      '增加本机 6 位 PIN 门锁、离开自动锁定和连续输错保护',
      '补齐 PWA 安装能力，让小屋可以放到手机与电脑桌面',
      '完成 Cloudflare 自动构建、预览与正式版本切换流程',
      '主人个人数据启用专属 RLS；Toy 与 ADB 控制链路保持原样，未修改',
    ],
  },
  {
    date: '2026-07-18',
    title: '搭建日志 + 记忆系统',
    icon: 'memory',
    items: [
      '新增「搭建日志」页面，时间线展示开发历程',
      '搭建分级记忆系统（固定/长期/短期/临时）',
      'memories 表新增 level 字段，53条记忆自动分级',
      'CLAUDE.md 加入身份信息和 AI 自动读取指令',
      '新对话自动加载记忆，不再丢失上下文',
    ],
  },
  {
    date: '2026-07-18',
    title: '修复部署构建',
    icon: 'settings',
    items: [
      '创建 .env.production，Vite 构建自动加载 Supabase 配置',
      '简化部署工作流，移除 GitHub Secrets 依赖',
      'PR #2 和 PR #3 合并，GitHub Pages 自动部署成功',
    ],
  },
  {
    date: '2026-07-18',
    title: '经典小屋主题 + 自动部署',
    icon: 'theme',
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
    icon: 'theme',
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
    icon: 'project',
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
    <MobilePage className="changelog-page">
      <MobilePageHeader title="搭建日志" icon="history" subtitle="Building notes" />

      <MobileCard className="changelog-intro">
        记录小屋从零搭建的每一步。<br />
        每次修改、新增、修复都会留下痕迹~
      </MobileCard>

      <div className="changelog-timeline">
        <div className="changelog-line" aria-hidden="true" />

        {LOGS.map(log => (
          <article key={`${log.date}-${log.title}`} className="changelog-entry">
            <div className="changelog-marker" aria-hidden="true">
              <LineIcon name={log.icon} size={11} />
            </div>

            <MobileCard className="changelog-card">
              <div className="changelog-card-head">
                <h2>{log.title}</h2>
                <time dateTime={log.date}>{log.date}</time>
              </div>
              <ul>
                {log.items.map((item, j) => (
                  <li key={j}>{item}</li>
                ))}
              </ul>
            </MobileCard>
          </article>
        ))}
      </div>

      <footer className="changelog-footer">
        — 小屋的故事，从这里开始 —
      </footer>
    </MobilePage>
  )
}
