package fyi.b612.lovehouse.feature.settings

import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.MutableStateFlow

interface SettingsRepository {
    val connections: StateFlow<ConnectionListState>
    val addConnectionState: StateFlow<AddConnectionState>

    fun addConnection(request: AddConnectionRequest)
    fun resetAddConnectionState()
    fun refresh()
}

internal class UnavailableSettingsRepository : SettingsRepository {
    private val connectionState = MutableStateFlow<ConnectionListState>(ConnectionListState.Empty)
    override val connections: StateFlow<ConnectionListState> = connectionState
    private val addState = MutableStateFlow<AddConnectionState>(AddConnectionState.Idle)
    override val addConnectionState: StateFlow<AddConnectionState> = addState

    override fun addConnection(request: AddConnectionRequest) {
        addState.value = AddConnectionState.Error("统一连接服务尚未接入；请使用已接通的正式账号或 Tool Center 入口")
    }

    override fun resetAddConnectionState() {
        addState.value = AddConnectionState.Idle
    }

    override fun refresh() {
        connectionState.value = ConnectionListState.Empty
    }
}
