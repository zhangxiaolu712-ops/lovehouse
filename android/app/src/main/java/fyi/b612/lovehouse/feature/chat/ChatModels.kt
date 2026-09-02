package fyi.b612.lovehouse.feature.chat

enum class ChatThreadKind {
    Direct,
    LivingRoom,
    TemporaryTask,
    Agent,
    Archive,
}

enum class ChatPresence {
    Online,
    Working,
    Waiting,
    Offline,
}

data class ChatThreadSummary(
    val threadId: String,
    val kind: ChatThreadKind,
    val title: String,
    val preview: String,
    val updatedAt: String,
    val unreadCount: Int = 0,
    val pinned: Boolean = false,
    val presence: ChatPresence? = null,
    val speakerLabel: String? = null,
    val expiresAtLabel: String? = null,
    val taskId: String? = null,
    val avatarGlyph: String? = null,
)

sealed interface ChatListState {
    data object Loading : ChatListState
    data class Content(val threads: List<ChatThreadSummary>) : ChatListState
    data object Empty : ChatListState
    data class Error(val message: String) : ChatListState
    data class Offline(val cachedThreads: List<ChatThreadSummary>) : ChatListState
}
