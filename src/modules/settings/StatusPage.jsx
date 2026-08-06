import { Link } from 'react-router'
import LineIcon from '../../shared/LineIcon'

const URLS = [
  {
    name: 'Cloudflare Workers',
    url: 'https://tight-heart-93aa.zhangxiaolu712.workers.dev',
    tags: ['HTTPS', 'Chat', 'PIN'],
    recommended: true,
    desc: 'Full version. HTTPS + chat via Cloudflare proxy to VPS. Deploy: npx wrangler deploy',
  },
  {
    name: 'VPS Direct (DuckDNS)',
    url: 'https://tingtunehouse.duckdns.org',
    tags: ['HTTPS', 'Chat', 'PIN'],
    recommended: true,
    desc: 'Full version. nginx + Let\'s Encrypt cert. Deploy: SSH pull + rebuild',
  },
  {
    name: 'GitHub Pages',
    url: 'https://zhangxiaolu712-ops.github.io/lovehouse/',
    tags: ['HTTPS', 'UI Only'],
    desc: 'Static only, no chat backend. Auto-deploys on merge to main',
  },
]

const VPS_SERVICES = [
  { name: 'nginx', status: 'running', desc: 'Web server. Proxies /api/ to bridge:3000, serves static files. HTTPS via certbot' },
  { name: 'lovehouse-bridge', status: 'running', desc: 'Chat backend on port 3000. Spawns claude CLI, streams SSE. Managed by pm2' },
]

const INFRA = [
  { label: 'VPS', value: 'Vultr · 139.180.146.26 · Ubuntu' },
  { label: 'Domain', value: 'tingtunehouse.duckdns.org (DuckDNS)' },
  { label: 'HTTPS', value: 'Let\'s Encrypt · certbot auto-renew' },
  { label: 'CDN', value: 'Cloudflare Workers · tight-heart-93aa' },
  { label: 'Database', value: 'Supabase · ap-southeast-1' },
  { label: 'Repo', value: 'GitHub · zhangxiaolu712-ops/lovehouse' },
  { label: 'Process', value: 'pm2 · keeps bridge alive' },
]

function Badge({ type, children }) {
  const colors = {
    green: { bg: 'var(--green-bg, #e8f5ec)', color: 'var(--green, #5a9e6f)' },
    yellow: { bg: 'var(--yellow-bg, #fdf5e0)', color: 'var(--yellow, #c49a2a)' },
  }
  const c = colors[type] || colors.green
  return (
    <span style={{
      display: 'inline-flex', alignItems: 'center', gap: 3,
      fontSize: 10, fontWeight: 600, padding: '2px 7px',
      borderRadius: 12, background: c.bg, color: c.color,
    }}>{children}</span>
  )
}

export default function StatusPage() {
  return (
    <div>
      <div className="page-header">
        <h2 className="page-title page-title-with-icon"><LineIcon name="workbench" />Config</h2>
        <Link to="/" style={{ textDecoration: 'none', fontSize: 14, color: 'var(--accent)' }}>Home</Link>
      </div>

      <section style={{ marginBottom: 24 }}>
        <h3 style={{ fontSize: 12, fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.05em', color: 'var(--text-muted)', marginBottom: 10 }}>URLs</h3>

        {URLS.map((u, i) => (
          <div key={i} className="card" style={{ padding: '14px 16px', marginBottom: 8, position: 'relative', borderLeft: u.recommended ? '3px solid var(--accent)' : undefined }}>
            {u.recommended && (
              <span style={{
                position: 'absolute', top: -1, right: -1,
                background: 'var(--accent)', color: '#fff',
                fontSize: 9, fontWeight: 600, padding: '2px 8px',
                borderRadius: '0 10px 0 8px',
              }}>REC</span>
            )}
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 6, flexWrap: 'wrap' }}>
              <span style={{ fontWeight: 600, fontSize: 14 }}>{u.name}</span>
              {u.tags.map(t => (
                <Badge key={t} type={t === 'UI Only' ? 'yellow' : 'green'}>{t}</Badge>
              ))}
            </div>
            <code style={{ fontSize: 11, color: 'var(--accent)', wordBreak: 'break-all', display: 'block', marginBottom: 4, fontFamily: 'var(--font-num)' }}>{u.url}</code>
            <p style={{ fontSize: 12, color: 'var(--text-secondary)', margin: 0 }}>{u.desc}</p>
          </div>
        ))}
      </section>

      <section style={{ marginBottom: 24 }}>
        <h3 style={{ fontSize: 12, fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.05em', color: 'var(--text-muted)', marginBottom: 10 }}>VPS Services</h3>

        {VPS_SERVICES.map((s, i) => (
          <div key={i} className="card" style={{ padding: '12px 16px', marginBottom: 8 }}>
            <div style={{ display: 'flex', alignItems: 'center', gap: 8, marginBottom: 4 }}>
              <span style={{ fontWeight: 600, fontSize: 14 }}>{s.name}</span>
              <Badge type="green">{s.status}</Badge>
            </div>
            <p style={{ fontSize: 12, color: 'var(--text-secondary)', margin: 0 }}>{s.desc}</p>
          </div>
        ))}
      </section>

      <section style={{ marginBottom: 24 }}>
        <h3 style={{ fontSize: 12, fontWeight: 600, textTransform: 'uppercase', letterSpacing: '0.05em', color: 'var(--text-muted)', marginBottom: 10 }}>Infrastructure</h3>

        <div className="card" style={{ padding: '10px 16px' }}>
          {INFRA.map((row, i) => (
            <div key={i} style={{
              display: 'flex', alignItems: 'baseline', gap: 8,
              padding: '7px 0',
              borderBottom: i < INFRA.length - 1 ? '1px solid var(--border)' : 'none',
              fontSize: 13,
            }}>
              <span style={{ fontWeight: 600, minWidth: 70, flexShrink: 0 }}>{row.label}</span>
              <span style={{ color: 'var(--text-secondary)', fontSize: 12, wordBreak: 'break-all' }}>{row.value}</span>
            </div>
          ))}
        </div>
      </section>

      <div className="card" style={{
        padding: '12px 14px', fontSize: 12,
        color: 'var(--text-secondary)', lineHeight: 1.7,
        borderLeft: '3px solid var(--accent)',
      }}>
        <strong style={{ color: 'var(--accent)' }}>Daily use:</strong> Cloudflare or DuckDNS — both full HTTPS + chat.<br />
        <strong style={{ color: 'var(--accent)' }}>Updates:</strong> Cloudflare = <code>npx wrangler deploy</code> on PC; VPS = SSH + pull + rebuild.<br />
        <strong style={{ color: 'var(--accent)' }}>VPS auto-runs:</strong> pm2 keeps bridge alive, no need to stay online.
      </div>
    </div>
  )
}
