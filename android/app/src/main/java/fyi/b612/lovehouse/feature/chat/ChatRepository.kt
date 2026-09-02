package fyi.b612.lovehouse.feature.chat

import kotlinx.coroutines.flow.StateFlow

interface ChatRepository {
    val listState: StateFlow<ChatListState>

    fun refresh()
}
