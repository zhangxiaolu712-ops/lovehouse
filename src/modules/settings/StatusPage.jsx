import {
  MobileBadge,
  MobileCard,
  MobilePage,
  MobilePageHeader,
  MobileSection,
} from '../../shared/MobileUI'

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

export default function StatusPage() {
  return (
    <MobilePage className="status-page">
      <MobilePageHeader title="Config" icon="workbench" />

      <MobileSection title="URLs">
        {URLS.map(url => (
          <MobileCard
            key={url.url}
            className={`status-url-card${url.recommended ? ' is-recommended' : ''}`}
          >
            {url.recommended && <span className="status-rec">REC</span>}
            <div className="status-card-title-row">
              <h3>{url.name}</h3>
              <div className="status-badges">
                {url.tags.map(tag => (
                  <MobileBadge key={tag} tone={tag === 'UI Only' ? 'gold' : 'green'}>{tag}</MobileBadge>
                ))}
              </div>
            </div>
            <code className="status-url">{url.url}</code>
            <p>{url.desc}</p>
          </MobileCard>
        ))}
      </MobileSection>

      <MobileSection title="VPS Services">
        {VPS_SERVICES.map(service => (
          <MobileCard key={service.name} className="status-service-card">
            <div className="status-card-title-row">
              <h3>{service.name}</h3>
              <MobileBadge>{service.status}</MobileBadge>
            </div>
            <p>{service.desc}</p>
          </MobileCard>
        ))}
      </MobileSection>

      <MobileSection title="Infrastructure">
        <MobileCard className="status-infra-card">
          {INFRA.map(row => (
            <div className="status-infra-row" key={row.label}>
              <strong>{row.label}</strong>
              <span>{row.value}</span>
            </div>
          ))}
        </MobileCard>
      </MobileSection>

      <MobileCard className="status-notice">
        <p><strong>Daily use:</strong> Cloudflare or DuckDNS — both full HTTPS + chat.</p>
        <p><strong>Updates:</strong> Cloudflare = <code>npx wrangler deploy</code> on PC; VPS = SSH + pull + rebuild.</p>
        <p><strong>VPS auto-runs:</strong> pm2 keeps bridge alive, no need to stay online.</p>
      </MobileCard>
    </MobilePage>
  )
}
