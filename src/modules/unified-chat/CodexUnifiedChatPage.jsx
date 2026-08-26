import UnifiedChatPage from './UnifiedChatPage'
import { streamCodexV1 } from '../codex-chat-v1/codexChatV1Service'
import { boundCodexV1History, getCodexV1Identity, loadCodexV1History, saveCodexV1History } from '../codex-chat-v1/codexChatV1State'

export default function CodexUnifiedChatPage() {
  return <UnifiedChatPage
    personaName="Codex"
    personaLetter="C"
    runtimeLabel="codex_cli"
    sceneLabel="work"
    emptyText="发一句话，继续同一条 LoveHouse Thread。"
    placeholder="ring the chime …"
    identity={getCodexV1Identity()}
    initialMessages={loadCodexV1History()}
    saveMessages={saveCodexV1History}
    boundMessages={boundCodexV1History}
    streamMessage={streamCodexV1}
  />
}
