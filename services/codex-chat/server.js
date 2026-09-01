import { createCodexChatServer } from './app.js'
import { CodexCliRuntimeAdapter } from './codexCliRuntimeAdapter.js'
import { createSupabaseOwnerAuth } from './supabaseOwnerAuth.js'
import { FileThreadBindingStore } from './threadBindingStore.js'
import { createSupabaseRest } from '../../bridge/memory/repository.js'
import { CODEX_VPS_ROUTE } from '../../bridge/livingroom-tasks/routing.js'
import { SupabaseLivingroomTaskRepository } from '../../bridge/livingroom-tasks/repository.js'
import { FileTransientThreadStore } from '../../bridge/livingroom-tasks/transientStore.js'
import { LivingroomTaskDispatcher } from '../../bridge/livingroom-tasks/dispatcher.js'
import { CodexRuntimeEndpoint } from '../../bridge/livingroom-tasks/codexRuntimeEndpoint.js'

const port = Number.parseInt(process.env.CODEX_CHAT_PORT || '3002', 10)
const authenticate = createSupabaseOwnerAuth({
  supabaseUrl: process.env.SUPABASE_URL || 'https://cvyguanuaxcypsvoozeo.supabase.co',
  anonKey: process.env.SUPABASE_ANON_KEY,
  ownerUserId: process.env.OWNER_USER_ID,
})
const runtime = new CodexCliRuntimeAdapter({
  executable: process.env.CODEX_CHAT_PROVIDER_EXECUTABLE || '/usr/bin/codex',
  cwd: process.env.CODEX_CHAT_CWD || '/tmp',
})
const threadBindings = new FileThreadBindingStore({
  filePath: process.env.CODEX_CHAT_BINDINGS_FILE
    || '/root/lovehouse-codex-chat-state/thread-bindings.json',
})

const taskRest = createSupabaseRest({
  url: process.env.SUPABASE_URL || 'https://cvyguanuaxcypsvoozeo.supabase.co',
  serverKey: process.env.SUPABASE_SECRET_KEY || process.env.SUPABASE_SERVICE_ROLE_KEY || '',
})
const transientStore = new FileTransientThreadStore({
  filePath: process.env.LIVINGROOM_TRANSIENT_FILE
    || '/root/lovehouse-codex-chat-state/livingroom-transient-threads.json',
  ttlMs: Number.parseInt(process.env.LIVINGROOM_THREAD_TTL_MS || '259200000', 10),
})
const taskRepository = process.env.LIVINGROOM_CODEX_DISPATCH_ENABLED === 'true'
  ? new SupabaseLivingroomTaskRepository({
        rest: taskRest, ownerId: process.env.OWNER_USER_ID, route: CODEX_VPS_ROUTE,
        transientStore,
      })
  : null
const taskDispatcher = taskRepository
  ? new LivingroomTaskDispatcher({
      repository: taskRepository,
      endpoint: new CodexRuntimeEndpoint({ runtime }),
      transientStore,
      pollMs: Number.parseInt(process.env.LIVINGROOM_DISPATCH_POLL_MS || '3000', 10),
    })
  : null
const server = createCodexChatServer({ authenticate, runtime, threadBindings, taskRepository, transientStore })
server.requestTimeout = 120_000
server.headersTimeout = 10_000
server.listen(port, '127.0.0.1', () => {
  console.log(`[codex-chat] listening on 127.0.0.1:${port}`)
  taskDispatcher?.start()
})

function shutdown(signal) {
  console.log(`[codex-chat] ${signal}, shutting down`)
  taskDispatcher?.stop()
  transientStore.close()
  server.close(error => process.exit(error ? 1 : 0))
  setTimeout(() => process.exit(1), 10_000).unref()
}

process.on('SIGINT', () => shutdown('SIGINT'))
process.on('SIGTERM', () => shutdown('SIGTERM'))
