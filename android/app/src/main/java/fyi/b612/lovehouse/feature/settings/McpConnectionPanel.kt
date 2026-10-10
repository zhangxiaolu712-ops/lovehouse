package fyi.b612.lovehouse.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import fyi.b612.lovehouse.feature.chat.PersonaProfile
import fyi.b612.lovehouse.feature.chat.PersonaRuntimeSource

/** OAuth callback lands on the Tool Center MCP tab with the returned connection in focus. */
@Composable
fun McpOAuthResultScreen(
    repository: McpConnectionRepository,
    personaRuntimeSource: PersonaRuntimeSource,
    capabilityRegistry: CapabilityRegistry,
    apiConnections: ApiConnectionRepository,
    secretVault: SecretVaultRepository,
    connectionId: String,
    callbackStatus: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    ToolCenterPage(
        registry = capabilityRegistry,
        apiConnections = apiConnections,
        secretVault = secretVault,
        mcpRepository = repository,
        personaRuntimeSource = personaRuntimeSource,
        onBack = onBack,
        modifier = modifier,
        initialConnectionId = connectionId,
        callbackStatus = callbackStatus,
    )
}

internal fun openMcpAuthorization(context: Context, authorizationUrl: String) {
    check(isSafeMcpAuthorizationUrl(authorizationUrl)) { "OAuth 授权地址不安全" }
    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(authorizationUrl)))
}

internal fun mergeConnectionSources(
    connections: List<McpBackendConnection>,
    registry: List<McpBackendConnection>,
): List<McpBackendConnection> {
    val byId = linkedMapOf<String, McpBackendConnection>()
    connections.filter { it.id.isNotBlank() }.forEach { byId[it.id] = it }
    registry.filter { it.id.isNotBlank() }.forEach { registered ->
        val current = byId[registered.id]
        byId[registered.id] = if (current == null) registered else current.copy(
            name = registered.name ?: current.name,
            description = registered.description ?: current.description,
            serverUrl = registered.serverUrl.ifBlank { current.serverUrl },
            toolCount = maxOf(current.toolCount, registered.toolCount),
            status = if (registered.status == McpBackendConnectionStatus.Unknown) current.status else registered.status,
        )
    }
    return byId.values.toList()
}

internal fun serviceConnectionCards(
    serviceId: String,
    connections: List<McpBackendConnection>,
    registry: List<McpBackendConnection>,
): List<McpBackendConnection> = mergeConnectionSources(
    connections.filter { it.toolServiceId == serviceId },
    registry.filter { it.toolServiceId == serviceId },
)

internal fun personaDisplayName(personaId: String, profiles: List<PersonaProfile>): String =
    profiles.firstOrNull { it.personaId == personaId }?.displayName ?: personaId

internal fun McpBackendConnection.displayUrl(): String = serverUrl.ifBlank { "App Backend 未返回 MCP URL" }

internal fun McpBackendConnection.displayName(): String = displayName?.takeIf(String::isNotBlank)
    ?: name?.takeIf(String::isNotBlank)
    ?: serverUrl.substringAfter("://").substringBefore('/').takeIf(String::isNotBlank)
    ?: "MCP Server"

internal fun McpBackendConnectionStatus.label(): String = when (this) {
    McpBackendConnectionStatus.Connecting -> "连接中"
    McpBackendConnectionStatus.AuthorizationRequired -> "等待授权"
    McpBackendConnectionStatus.Connected -> "已连接"
    McpBackendConnectionStatus.Failed -> "连接失败"
    McpBackendConnectionStatus.Abandoned -> "已放弃"
    McpBackendConnectionStatus.AuthUpdating -> "认证更新中"
    McpBackendConnectionStatus.Unknown -> "状态未知"
}

internal fun McpBackendConnectionStatus.canDelete(): Boolean = when (this) {
    McpBackendConnectionStatus.Connecting,
    McpBackendConnectionStatus.AuthorizationRequired,
    McpBackendConnectionStatus.Failed,
    McpBackendConnectionStatus.Abandoned,
    -> true
    McpBackendConnectionStatus.Connected,
    McpBackendConnectionStatus.AuthUpdating,
    McpBackendConnectionStatus.Unknown,
    -> false
}

internal data class McpDeleteUiOutcome(
    val message: String,
    val refreshConnectionsAndRegistry: Boolean,
)

internal fun McpConnectionDeleteResult.uiOutcome(): McpDeleteUiOutcome = McpDeleteUiOutcome(
    message = when (this) {
        McpConnectionDeleteResult.Deleted -> "未完成的 MCP connection 已删除"
        McpConnectionDeleteResult.AlreadyAbsent -> "该连接已不存在，列表已刷新"
        McpConnectionDeleteResult.Active -> "已连接的 MCP 不能在这里删除"
    },
    refreshConnectionsAndRegistry = true,
)
