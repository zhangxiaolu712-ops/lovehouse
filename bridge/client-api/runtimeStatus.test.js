import assert from 'node:assert/strict'
import test from 'node:test'

import { createRuntimeStatusProvider } from './runtimeStatus.js'

test('runtime snapshot exposes only read-only process facts', async () => {
  const startedAt = Date.parse('2026-08-27T11:58:00.000Z')
  const provider = createRuntimeStatusProvider({
    daemonPid: 684943,
    now: () => new Date('2026-08-27T12:00:00.000Z'),
    daemonSnapshot: async pid => ({ count: 1, pid, uptime_seconds: 3600, systemd_managed: true }),
    fetchImpl: async url => {
      assert.match(url, /^http:\/\/127\.0\.0\.1:300[23]\/api\/(?:claude|codex)\/health$/)
      return { ok: true }
    },
    listProcesses: async () => [
      {
        name: 'lovehouse', pid: 686862,
        pm2_env: {
          status: 'online', pm_cwd: '/root/lovehouse-deployments/22df726/bridge',
          pm_uptime: startedAt, restart_time: 1,
          env: { SERVICE_ROLE_KEY: 'must-not-leak' }, pm_exec_path: '/secret/server.js',
        },
      },
      {
        name: 'lovehouse-claude-chat', pid: 685659,
        pm2_env: { status: 'online', pm_cwd: '/root/lovehouse-deployments/142877726e70c9a906da4620dba191579db44f55/bridge', pm_uptime: startedAt, restart_time: 0 },
      },
      {
        name: 'lovehouse-codex-chat', pid: 684964,
        pm2_env: { status: 'online', pm_cwd: '/root/lovehouse-deployments/142877726e70c9a906da4620dba191579db44f55/bridge', pm_uptime: startedAt, restart_time: 0 },
      },
    ],
  })

  const snapshot = await provider.snapshot()
  assert.deepEqual(snapshot.daemon, { count: 1, pid: 684943, uptime_seconds: 3600, systemd_managed: true })
  assert.equal(snapshot.observed_at, '2026-08-27T12:00:00.000Z')
  assert.equal(snapshot.services.length, 3)
  assert.deepEqual(snapshot.services[0], {
    name: 'lovehouse', label: 'Bridge', status: 'online', health: 'ok', pid: 686862,
    port: 3000, release: '22df726', uptime_seconds: 120, restart_count: 1,
    last_started_at: '2026-08-27T11:58:00.000Z',
  })
  assert.equal(snapshot.services[1].health, 'ok')
  assert.equal(snapshot.services[2].health, 'ok')
  const serialized = JSON.stringify(snapshot)
  assert.equal(serialized.includes('must-not-leak'), false)
  assert.equal(serialized.includes('/secret/server.js'), false)
  assert.equal(serialized.includes('pm_cwd'), false)
})

test('missing service is reported unavailable without a health probe', async () => {
  let probes = 0
  const provider = createRuntimeStatusProvider({
    listProcesses: async () => [],
    daemonSnapshot: async () => ({ count: 1, pid: 1, uptime_seconds: 1, systemd_managed: true }),
    fetchImpl: async () => { probes += 1; return { ok: true } },
  })
  const snapshot = await provider.snapshot()
  assert.equal(probes, 0)
  assert.ok(snapshot.services.every(service => service.status === 'unavailable' && service.health === 'unavailable'))
})
