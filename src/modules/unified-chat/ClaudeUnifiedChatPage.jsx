import UnifiedChatPage from './UnifiedChatPage'
import { streamClaudeV1 } from '../claude-chat-v1/claudeChatV1Service'
import { boundClaudeV1History, getClaudeV1Identity, loadClaudeV1History, saveClaudeV1History } from '../claude-chat-v1/claudeChatV1State'

export default function ClaudeUnifiedChatPage() {
  return <UnifiedChatPage
    personaName="小克"
    personaLetter="K"
    runtimeLabel="claude_cli"
    sceneLabel="casual"
    emptyText="你来了，我就在。"
    placeholder="ring the chime …"
    identity={getClaudeV1Identity()}
    initialMessages={loadClaudeV1History()}
    saveMessages={saveClaudeV1History}
    boundMessages={boundClaudeV1History}
    streamMessage={streamClaudeV1}
  />
}
