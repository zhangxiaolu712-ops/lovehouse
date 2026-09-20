package fyi.b612.lovehouse.feature.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Product chat windows only. Message bodies always come from local persistence or a real runtime. */
internal object LoveHouseChatCatalog {
    val threads = listOf(
        ChatThreadSummary(
            threadId = "living-room",
            kind = ChatThreadKind.LivingRoom,
            title = "小客厅",
            preview = "尚未连接真实小客厅数据",
            updatedAt = "",
            pinned = true,
            speakerLabel = "LoveHouse",
        ),
        ChatThreadSummary(
            threadId = "persona-gpt",
            kind = ChatThreadKind.Direct,
            title = "G老师",
            preview = "本地窗口 · Runtime 尚未连接",
            updatedAt = "",
            pinned = true,
            speakerLabel = "长期单聊",
        ),
        ChatThreadSummary(
            threadId = ClaudeRuntime.threadId,
            kind = ChatThreadKind.Agent,
            title = "Claude",
            preview = "现有 Web Thread · 本地历史",
            updatedAt = "",
            pinned = true,
            speakerLabel = "Claude · 长期单聊",
            avatarGlyph = "C",
        ),
        ChatThreadSummary(
            threadId = "agent-codex",
            kind = ChatThreadKind.Agent,
            title = "Codex",
            preview = "真实本机历史",
            updatedAt = "",
            avatarGlyph = "⌘",
        ),
    )
}

internal class LoveHouseChatRepository : ChatRepository {
    private val state = MutableStateFlow<ChatListState>(ChatListState.Content(LoveHouseChatCatalog.threads))
    override val listState: StateFlow<ChatListState> = state

    override fun refresh() {
        state.value = ChatListState.Content(LoveHouseChatCatalog.threads)
    }
}
