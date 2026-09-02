package fyi.b612.lovehouse.feature.settings

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class MockSettingsRepository : SettingsRepository {
    private val mockConnections = mutableListOf(
        ConnectedCapability(
            connectionId = "mock-codex-vps",
            displayName = "Codex",
            summary = "工程任务与远程工作流",
            status = ConnectionStatus.Connected,
            runtimeLabel = "VPS",
            modelLabel = "Codex",
            capabilities = listOf("工程任务", "Workflow", "审批回执"),
        ),
        ConnectedCapability(
            connectionId = "mock-local-device",
            displayName = "这台电脑",
            summary = "本机施工与真机连接",
            status = ConnectionStatus.Offline,
            runtimeLabel = "Local",
            capabilities = listOf("Android 构建", "真机安装"),
        ),
    )

    private val mutableConnections = MutableStateFlow<ConnectionListState>(ConnectionListState.Content(mockConnections))
    override val connections: StateFlow<ConnectionListState> = mutableConnections
    private val mutableAddState = MutableStateFlow<AddConnectionState>(AddConnectionState.Idle)
    override val addConnectionState: StateFlow<AddConnectionState> = mutableAddState

    override fun addConnection(request: AddConnectionRequest) {
        mutableAddState.value = if (request.credential.isBlank()) {
            AddConnectionState.Error("请输入连接码、邀请码或必要凭据。")
        } else {
            AddConnectionState.WaitingServerConfirmation
        }
    }

    override fun resetAddConnectionState() {
        mutableAddState.value = AddConnectionState.Idle
    }

    override fun refresh() {
        mutableConnections.value = ConnectionListState.Content(mockConnections.toList())
    }
}
