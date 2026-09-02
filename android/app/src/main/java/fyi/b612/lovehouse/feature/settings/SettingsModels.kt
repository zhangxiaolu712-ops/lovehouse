package fyi.b612.lovehouse.feature.settings

enum class ConnectionStatus { Connected, WaitingConfirmation, Offline, Error }

data class ConnectedCapability(
    val connectionId: String,
    val displayName: String,
    val summary: String,
    val status: ConnectionStatus,
    val runtimeLabel: String? = null,
    val modelLabel: String? = null,
    val capabilities: List<String> = emptyList(),
)

data class AddConnectionRequest(val credential: String)

sealed interface ConnectionListState {
    data object Loading : ConnectionListState
    data object Empty : ConnectionListState
    data class Content(val connections: List<ConnectedCapability>) : ConnectionListState
    data class Error(val message: String) : ConnectionListState
    data class Offline(val cachedConnections: List<ConnectedCapability>) : ConnectionListState
}

sealed interface AddConnectionState {
    data object Idle : AddConnectionState
    data object Submitting : AddConnectionState
    data object WaitingServerConfirmation : AddConnectionState
    data class Connected(val connectionId: String) : AddConnectionState
    data class Error(val message: String) : AddConnectionState
}

enum class EngineeringControlArea(val label: String, val summary: String) {
    Agent("Agent", "成员、身份与连接状态"),
    Runtime("Runtime", "运行位置、在线状态与诊断"),
    ProviderModel("Provider / Model", "服务提供方与模型选择"),
    Capabilities("能力与权限", "可用能力、权限范围与安全边界"),
    Notifications("通知与审批", "提醒方式与审批偏好"),
    Security("账号与安全", "连接安全、重连、解绑与移除"),
    TemporaryData("临时数据 / 缓存", "离线数据与临时任务清理"),
}
