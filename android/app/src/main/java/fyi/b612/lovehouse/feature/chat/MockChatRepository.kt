package fyi.b612.lovehouse.feature.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MockChatRepository : ChatRepository {
    private val mutableListState = MutableStateFlow<ChatListState>(ChatListState.Content(mockThreads))
    override val listState: StateFlow<ChatListState> = mutableListState

    override fun refresh() {
        mutableListState.value = ChatListState.Content(mockThreads)
    }

    companion object {
        val mockThreads = listOf(
            ChatThreadSummary(
                threadId = "living-room",
                kind = ChatThreadKind.LivingRoom,
                title = "小客厅",
                preview = "本地界面演示 · 尚未连接真实小客厅数据",
                updatedAt = "",
                pinned = true,
                presence = null,
                speakerLabel = "GPT · Claude · Codex",
            ),
            ChatThreadSummary(
                threadId = "persona-gpt",
                kind = ChatThreadKind.Direct,
                title = "G老师",
                preview = "本地界面演示 · Persona Runtime 尚未接入",
                updatedAt = "",
                pinned = true,
                presence = null,
                speakerLabel = "长期单聊",
            ),
            ChatThreadSummary(
                threadId = "task-remote-ui",
                kind = ChatThreadKind.TemporaryTask,
                title = "Codex · Chat 页面迁移",
                preview = "本地 Workflow 演示 · 不代表真实任务状态",
                updatedAt = "",
                presence = null,
                speakerLabel = "Codex · Local",
                expiresAtLabel = "演示",
                taskId = "mock-running-001",
                avatarGlyph = "⌘",
            ),
            ChatThreadSummary(
                threadId = ClaudeRuntime.threadId,
                kind = ChatThreadKind.Agent,
                title = "Claude",
                preview = "现有 Web Thread · 本地历史将在打开窗口时读取",
                updatedAt = "",
                pinned = true,
                presence = null,
                speakerLabel = "Claude · 长期单聊",
                avatarGlyph = "C",
            ),
            ChatThreadSummary(
                threadId = "agent-codex",
                kind = ChatThreadKind.Agent,
                title = "Codex",
                preview = "真实本机历史将在打开窗口时读取",
                updatedAt = "",
                avatarGlyph = "⌘",
            ),
            ChatThreadSummary(
                threadId = "archive",
                kind = ChatThreadKind.Archive,
                title = "Archive",
                preview = "已归档的聊天与临时任务",
                updatedAt = "",
                avatarGlyph = "藏",
            ),
        )
    }
}
