import { createCodexChatServer } from './app.js'
import { CodexCliRuntimeAdapter } from './codexCliRuntimeAdapter.js'
import { createSupabaseOwnerAuth } from './supabaseOwnerAuth.js'
import { FileThreadBindingStore } from './threadBindingStore.js'

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

const server = createCodexChatServer({ authenticate, runtime, threadBindings })
server.requestTimeout = 120_000
server.headersTimeout = 10_000
server.listen(port, '127.0.0.1', () => {
  console.log(`[codex-chat] listening on 127.0.0.1:${port}`)
})

function shutdown(signal) {
  console.log(`[codex-chat] ${signal}, shutting down`)
  server.close(error => process.exit(error ? 1 : 0))
  setTimeout(() => process.exit(1), 10_000).unref()
}

process.on('SIGINT', () => shutdown('SIGINT'))
process.on('SIGTERM', () => shutdown('SIGTERM'))
