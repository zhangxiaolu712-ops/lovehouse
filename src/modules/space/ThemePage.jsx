import { useTheme } from '../../core/theme'

export default function ThemePage() {
  const { themes, themeId, switchTheme } = useTheme()

  return (
    <div>
      <div className="page-header">
        <div className="page-title">🎨 主题系统</div>
        <span className="tag">🏠 空间中心</span>
      </div>

      <div className="card">
        <div className="section-title">切换风格</div>
        <div className="theme-switcher">
          {Object.values(themes).map(t => (
            <button
              key={t.id}
              className={`theme-option ${themeId === t.id ? 'active' : ''}`}
              onClick={() => switchTheme(t.id)}
            >
              <div style={{ fontSize: 24, marginBottom: 6 }}>{t.icon}</div>
              <div>{t.name}</div>
            </button>
          ))}
        </div>
      </div>

      <div className="card" style={{ marginTop: 12 }}>
        <div className="section-title">关于主题</div>
        <p style={{ fontSize: 13, color: 'var(--text-secondary)', lineHeight: 1.8 }}>
          每套主题是一个独立的 CSS 零件，放在 <code>src/themes/</code> 目录。
          想要新风格的话，告诉小克想要的感觉，加一个新文件就行，不影响其他部分~
        </p>
      </div>
    </div>
  )
}
