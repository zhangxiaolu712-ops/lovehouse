package fyi.b612.lovehouse.feature.settings

enum class ToolAvailability { Available, Unconfigured, NoPermission, ConnectionFailed }
enum class ToolRiskLevel { Low, Medium, High }
enum class ToolCapabilityKind { Read, Write, Execute, Admin }

data class ToolCapability(
    val toolId: String,
    val group: String,
    val groupLabel: String,
    val displayName: String,
    val summary: String,
    val availability: ToolAvailability,
    val detail: String,
    val riskLevel: ToolRiskLevel,
    val capabilityKind: ToolCapabilityKind,
    val requiresApproval: Boolean,
    val scope: List<String>,
)

data class ToolTestResult(
    val toolId: String,
    val succeeded: Boolean,
    val message: String,
)

data class LocalToolProfile(
    val personaId: String,
    val threadId: String,
    val preferredToolIds: Set<String>,
)

sealed interface ToolCenterUiState {
    data object Loading : ToolCenterUiState
    data class Ready(val tools: List<ToolCapability>) : ToolCenterUiState
    data class AuthenticationRequired(val message: String) : ToolCenterUiState
    data class Error(val message: String) : ToolCenterUiState
}

object BuiltInToolIds {
    const val MemoryRead = "builtin.memory.read"
    const val MemoryOpen = "builtin.memory.open"
    const val EngineeringReadCurrent = "builtin.engineering.read_current"
    const val EngineeringOpen = "builtin.engineering.open"
    const val LivingRoomRead = "builtin.livingroom.read"
}
