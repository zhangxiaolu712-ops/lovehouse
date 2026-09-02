package fyi.b612.lovehouse.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

class SettingsViewModel(private val repository: SettingsRepository) : ViewModel() {
    val connections = repository.connections
    val addConnectionState = repository.addConnectionState

    fun addConnection(credential: String) = repository.addConnection(AddConnectionRequest(credential.trim()))
    fun dismissAddConnection() = repository.resetAddConnectionState()
    fun refresh() = repository.refresh()

    companion object {
        fun factory(repository: SettingsRepository = MockSettingsRepository()): ViewModelProvider.Factory =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T = SettingsViewModel(repository) as T
            }
    }
}
