package fyi.b612.lovehouse.feature.settings

import kotlinx.coroutines.flow.StateFlow

interface SettingsRepository {
    val connections: StateFlow<ConnectionListState>
    val addConnectionState: StateFlow<AddConnectionState>

    fun addConnection(request: AddConnectionRequest)
    fun resetAddConnectionState()
    fun refresh()
}
