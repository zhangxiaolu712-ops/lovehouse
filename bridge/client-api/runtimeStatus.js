import fs from 'node:fs/promises'

const SERVICES = Object.freeze({
  lovehouse: { label: 'Bridge', port: 3000, healthPath: null },
  'lovehouse-claude-chat': { label: 'Claude', port: 3003, healthPath: '/api/claude/health' },
  'lovehouse-codex-chat': { label: 'Codex', port: 3002, healthPath: '/api/codex/health' },
})

function releaseRef(cwd) {
  const match = String(cwd || '').replace(/\\/g, '/').match(/\/lovehouse-deployments\/([0-9a-f]{7,40})(?:\/|$)/i)
  return match?.[1]?.toLowerCase() || null
}

function processUptimeSeconds(stat, hostUptime, ticksPerSecond = 100) {
  const tail = String(stat || '').slice(String(stat || '').lastIndexOf(') ') + 2).split(/\s+/)
  const startedTicks = Number(tail[19])
  if (!Number.isFinite(startedTicks) || !Number.isFinite(hostUptime)) return null
  return Math.max(0, Math.floor(hostUptime - startedTicks / ticksPerSecond))
}

async function defaultDaemonSnapshot(daemonPid) {
  const entries = await fs.readdir('/proc', { withFileTypes: true })
  const daemonPids = []
  await Promise.all(entries.filter(entry => entry.isDirectory() && /^\d+$/.test(entry.name)).map(async entry => {
    try {
      const cmdline = await fs.readFile(`/proc/${entry.name}/cmdline`, 'utf8')
      if (/^PM2 v.*God Daemon/.test(cmdline)) daemonPids.push(Number(entry.name))
    } catch {}
  }))
  let uptimeSeconds = null
  let systemdManaged = false
  try {
    const [stat, uptime, cgroup] = await Promise.all([
      fs.readFile(`/proc/${daemonPid}/stat`, 'utf8'),
      fs.readFile('/proc/uptime', 'utf8'),
      fs.readFile(`/proc/${daemonPid}/cgroup`, 'utf8'),
    ])
    uptimeSeconds = processUptimeSeconds(stat, Number.parseFloat(uptime))
    systemdManaged = cgroup.includes('/system.slice/pm2-root.service')
  } catch {}
  return { count: daemonPids.length, pid: daemonPid, uptime_seconds: uptimeSeconds, systemd_managed: systemdManaged }
}

async function probeHealth(fetchImpl, definition) {
  if (!definition.healthPath) return 'ok'
  try {
    const response = await fetchImpl(`http://127.0.0.1:${definition.port}${definition.healthPath}`, {
      signal: AbortSignal.timeout(1500),
    })
    return response.ok ? 'ok' : 'unavailable'
  } catch {
    return 'unavailable'
  }
}

export function createRuntimeStatusProvider({
  listProcesses,
  daemonSnapshot = defaultDaemonSnapshot,
  fetchImpl = globalThis.fetch,
  daemonPid = process.ppid,
  now = () => new Date(),
} = {}) {
  if (typeof listProcesses !== 'function') throw new TypeError('Runtime status requires a PM2 process reader')
  if (typeof daemonSnapshot !== 'function') throw new TypeError('Runtime status requires a daemon reader')
  if (typeof fetchImpl !== 'function') throw new TypeError('Runtime status requires fetch')

  return Object.freeze({
    async snapshot() {
      const [rows, daemon] = await Promise.all([listProcesses(), daemonSnapshot(daemonPid)])
      const byName = new Map((rows || []).map(row => [row.name, row]))
      const services = await Promise.all(Object.entries(SERVICES).map(async ([name, definition]) => {
        const row = byName.get(name)
        const environment = row?.pm2_env || {}
        const startedAt = Number.isFinite(Number(environment.pm_uptime))
          ? new Date(Number(environment.pm_uptime)).toISOString()
          : null
        return {
          name,
          label: definition.label,
          status: environment.status || 'unavailable',
          health: row ? await probeHealth(fetchImpl, definition) : 'unavailable',
          pid: Number.isInteger(row?.pid) ? row.pid : null,
          port: definition.port,
          release: releaseRef(environment.pm_cwd),
          uptime_seconds: startedAt ? Math.max(0, Math.floor((now().valueOf() - Date.parse(startedAt)) / 1000)) : null,
          restart_count: Number(environment.restart_time) || 0,
          last_started_at: startedAt,
        }
      }))
      return {
        version: 1,
        observed_at: now().toISOString(),
        daemon,
        services,
      }
    },
  })
}
