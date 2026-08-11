import { Link } from 'react-router'
import LineIcon from './LineIcon'

export function MobilePage({ children, className = '' }) {
  return <section className={`mobile-page ${className}`.trim()}>{children}</section>
}

export function MobileIconButton({ to, icon = 'back', label, onClick }) {
  const content = <LineIcon name={icon} size={19} />
  const className = 'mobile-icon-button'

  if (to) {
    return (
      <Link to={to} className={className} aria-label={label} title={label}>
        {content}
      </Link>
    )
  }

  return (
    <button type="button" className={className} aria-label={label} title={label} onClick={onClick}>
      {content}
    </button>
  )
}

export function MobilePageHeader({ title, icon, subtitle, backTo = '/', action }) {
  return (
    <header className="mobile-page-header">
      {backTo && <MobileIconButton to={backTo} icon="back" label="返回首页" />}
      <div className="mobile-page-heading">
        <h1 className="mobile-page-title">
          {icon && <LineIcon name={icon} size={24} />}
          <span>{title}</span>
        </h1>
        {subtitle && <p className="mobile-page-subtitle">{subtitle}</p>}
      </div>
      <div className="mobile-page-actions">
        {action}
      </div>
    </header>
  )
}

export function MobileCard({ children, className = '', as: Tag = 'section' }) {
  return <Tag className={`mobile-card ${className}`.trim()}>{children}</Tag>
}

export function MobileSection({ title, children, className = '' }) {
  return (
    <section className={`mobile-section ${className}`.trim()}>
      <h2 className="mobile-section-title">{title}</h2>
      <div className="mobile-section-content">{children}</div>
    </section>
  )
}

export function MobileBadge({ tone = 'green', children }) {
  return <span className={`mobile-badge mobile-badge-${tone}`}>{children}</span>
}

export function MobileEmptyState({ icon = 'inbox', title, description, children }) {
  return (
    <MobileCard className="mobile-empty-state">
      <span className="mobile-empty-icon"><LineIcon name={icon} size={32} /></span>
      <h2>{title}</h2>
      {description && <p>{description}</p>}
      {children && <div className="mobile-empty-action">{children}</div>}
    </MobileCard>
  )
}
