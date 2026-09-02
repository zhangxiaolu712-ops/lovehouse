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
                preview = "Codex：已接受工单，临时任务窗口已创建。",
                updatedAt = "11:46",
                unreadCount = 3,
                pinned = true,
                presence = ChatPresence.Online,
                speakerLabel = "GPT · Claude · Codex",
            ),
            ChatThreadSummary(
                threadId = "persona-gpt",
                kind = ChatThreadKind.Direct,
                title = "G老师",
                preview = "我们先把聊天页完整交给 Codex。",
                updatedAt = "11:45",
                pinned = true,
                presence = ChatPresence.Online,
                speakerLabel = "长期单聊",
            ),
            ChatThreadSummary(
                threadId = "task-remote-ui",
                kind = ChatThreadKind.TemporaryTask,
                title = "Codex · Chat 页面迁移",
                preview = "来源：小客厅 @Codex · 已接受工单 · 正在施工",
                updatedAt = "11:44",
                presence = ChatPresence.Working,
                speakerLabel = "Codex · Local",
                expiresAtLabel = "2天 23小时",
                taskId = "mock-running-001",
                avatarGlyph = "⌘",
            ),
            ChatThreadSummary(
                threadId = "task-claude-copy",
                kind = ChatThreadKind.TemporaryTask,
                title = "Claude · 文案整理",
                preview = "来源：小客厅 @Claude · 等待最终回执",
                updatedAt = "昨天",
                presence = ChatPresence.Waiting,
                speakerLabel = "Claude · VPS",
                expiresAtLabel = "1天 8小时",
                avatarGlyph = "C",
            ),
            ChatThreadSummary(
                threadId = "agent-codex",
                kind = ChatThreadKind.Agent,
                title = "Codex",
                preview = "VPS Runtime · 最近一次会话已归档。",
                updatedAt = "周日",
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
