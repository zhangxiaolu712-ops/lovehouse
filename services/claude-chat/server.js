import { createChatRuntimeServer } from '../codex-chat/app.js'
import { createSupabaseOwnerAuth } from '../codex-chat/supabaseOwnerAuth.js'
import { FileThreadBindingStore } from '../codex-chat/threadBindingStore.js'
import { ClaudeCliRuntimeAdapter } from './claudeCliRuntimeAdapter.js'

const port = Number.parseInt(process.env.CLAUDE_CHAT_PORT || '3003', 10)
const authenticate = createSupabaseOwnerAuth({
  supabaseUrl: process.env.SUPABASE_URL || 'https://cvyguanuaxcypsvoozeo.supabase.co',
  anonKey: process.env.SUPABASE_ANON_KEY,
  ownerUserId: process.env.OWNER_USER_ID,
})
const runtime = new ClaudeCliRuntimeAdapter({
  executable: process.env.CLAUDE_CHAT_PROVIDER_EXECUTABLE || '/usr/bin/claude',
  cwd: process.env.CLAUDE_CHAT_CWD || '/tmp',
})
const threadBindings = new FileThreadBindingStore({
  filePath: process.env.CLAUDE_CHAT_BINDINGS_FILE
    || '/root/lovehouse-claude-chat-state/thread-bindings.json',
  runtimeType: 'claude_cli',
})

const server = createChatRuntimeServer({
  authenticate,
  runtime,
  threadBindings,
  routePrefix: '/api/claude',
  serviceName: 'lovehouse-claude-chat',
})
server.requestTimeout = 120_000
server.headersTimeout = 10_000
server.listen(port, '127.0.0.1', () => {
  console.log(`[claude-chat] listening on 127.0.0.1:${port}`)
})

function shutdown(signal) {
  console.log(`[claude-chat] ${signal}, shutting down`)
  server.close(error => process.exit(error ? 1 : 0))
  setTimeout(() => process.exit(1), 10_000).unref()
}

process.on('SIGINT', () => shutdown('SIGINT'))
process.on('SIGTERM', () => shutdown('SIGTERM'))
