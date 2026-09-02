package fyi.b612.lovehouse.feature.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

class ChatViewModel(
    private val repository: ChatRepository,
) : ViewModel() {
    val listState = repository.listState

    fun refresh() = repository.refresh()

    companion object {
        fun factory(repository: ChatRepository = MockChatRepository()): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ChatViewModel(repository) as T
            }
    }
}
