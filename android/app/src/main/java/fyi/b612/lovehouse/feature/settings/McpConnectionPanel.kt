package fyi.b612.lovehouse.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fyi.b612.lovehouse.core.designsystem.LoveHouseGlass
import fyi.b612.lovehouse.feature.chat.ChatPersona
import kotlinx.coroutines.launch

@Composable
internal fun McpConnectionsPanel(
    repository: McpConnectionRepository,
    showAddForm: Boolean,
    initialConnectionId: String? = null,
    callbackStatus: String? = null,
    personas: List<ChatPersona> = emptyList(),
    onOpenAuthorization: (String) -> Unit,
) {
    var connections by remember { mutableStateOf<List<McpBackendConnection>>(emptyList()) }
    var registry by remember { mutableStateOf<List<McpBackendConnection>>(emptyList()) }
    var legacyConnections by remember { mutableStateOf<List<LegacyMcpConnection>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var serverUrl by remember { mutableStateOf("") }
    var message by remember { mutableStateOf<String?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    var pendingDelete by remember { mutableStateOf<McpBackendConnection?>(null) }
    var deletingId by remember { mutableStateOf<String?>(null) }
    var bindingTarget by remember { mutableStateOf<McpBackendConnection?>(null) }
    var pendingClaim by remember { mutableStateOf<LegacyMcpConnection?>(null) }
    val scope = rememberCoroutineScope()

    LaunchedEffect(repository, initialConnectionId, callbackStatus, reload) {
        loading = true
        runCatching {
            val focused = initialConnectionId?.takeIf(String::isNotBlank)?.let { repository.connection(it) }
            val listed = repository.connections()
            val registered = repository.registry()
            val legacy = runCatching { repository.legacyConnections() }.getOrElse { emptyList() }
            connections = mergeConnectionSources(listed + listOfNotNull(focused), registered)
            registry = registered
            legacyConnections = legacy
            if (callbackStatus == "connected") {
                message = focused?.let { "${it.displayName()} 已连接 · ${it.toolCount} 个工具" }
                    ?: "OAuth 授权已完成，连接状态已刷新"
            }
        }.onFailure { message = it.message ?: "无法读取 MCP 连接状态" }
        loading = false
    }

    ProductPanel {
        Text("已添加 MCP", color = LoveHouseGlass.Ink, fontSize = 13.sp)
        Text(
            if (loading) "正在读取 App Backend…" else "${connections.size} 个连接 · ${registry.sumOf { it.toolCount }} 个已注册工具",
            color = LoveHouseGlass.MutedInk,
            fontSize = 9.sp,
        )
        if (!loading && connections.isEmpty()) {
            Text("还没有添加 MCP Server", color = LoveHouseGlass.MutedInk, fontSize = 9.sp, modifier = Modifier.padding(top = 8.dp))
        }
        if (legacyConnections.isNotEmpty()) {
            Text("待认领旧连接", color = LoveHouseGlass.Ink, fontSize = 10.sp)
            legacyConnections.forEach { legacy ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(legacy.name ?: "未命名 MCP", color = LoveHouseGlass.MutedInk, fontSize = 9.sp)
                    if (legacy.claimMethod == "oauth_reauthorization") {
                        TextButton(onClick = { pendingClaim = legacy }) { Text("认领", fontSize = 9.sp) }
                    } else {
                        Text("需安全迁移证明", color = LoveHouseGlass.MutedInk, fontSize = 8.sp)
                    }
                }
            }
        }
        connections.forEach { connection ->
            McpConnectionRow(
                connection = connection,
                registered = connection.id in registry.mapTo(hashSetOf()) { it.id },
                deleting = deletingId == connection.id,
                onDelete = { pendingDelete = connection },
            )
            if (connection.status == McpBackendConnectionStatus.Connected) {
                val boundNames = connection.boundIdentityIds.map { identityId ->
                    personas.firstOrNull { it.personaId == identityId }?.name ?: identityId
                }
                Text(
                    if (boundNames.isEmpty()) "尚未绑定身份" else "身份 · ${boundNames.joinToString(" · ")}",
                    color = LoveHouseGlass.MutedInk,
                    fontSize = 8.5.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        enabled = personas.isNotEmpty() && !connection.toolServiceId.isNullOrBlank(),
                        onClick = { bindingTarget = connection },
                    ) { Text("绑定身份", fontSize = 9.sp) }
                    connection.boundIdentityIds.firstOrNull()?.let { identityId ->
                        TextButton(onClick = {
                            val serviceId = connection.toolServiceId
                            if (serviceId.isNullOrBlank()) {
                                message = "App Backend 未返回 tool_service_id，无法解绑身份"
                            } else {
                                scope.launch {
                                    runCatching { repository.unbindIdentity(serviceId, identityId) }
                                        .onSuccess { message = "身份已解绑"; reload++ }
                                        .onFailure { message = it.message ?: "身份解绑失败" }
                                }
                            }
                        }) { Text("解绑", fontSize = 9.sp) }
                    }
                }
            }
        }
        message?.let {
            Text(it, color = if (it.contains("失败") || it.contains("无法") || it.contains("错误")) Color(0xFF9B4F55) else Color(0xFF466F63), fontSize = 9.sp)
        }
        OutlinedButton(enabled = !loading, onClick = { reload++ }) { Text("刷新状态", fontSize = 9.sp) }
    }

    pendingDelete?.let { connection ->
        AlertDialog(
            onDismissRequest = { if (deletingId == null) pendingDelete = null },
            title = { Text("删除未完成连接？") },
            text = { Text("只会删除这条等待授权或失败的连接，不会影响已连接的 MCP。") },
            confirmButton = {
                TextButton(
                    enabled = deletingId == null,
                    onClick = {
                        deletingId = connection.id
                        scope.launch {
                            runCatching { repository.delete(connection.id) }
                                .onSuccess { result ->
                                    val outcome = result.uiOutcome()
                                    message = outcome.message
                                    pendingDelete = null
                                    if (outcome.refreshConnectionsAndRegistry) reload++
                                }
                                .onFailure { message = it.message ?: "删除 MCP connection 失败" }
                            deletingId = null
                        }
                    },
                ) { Text(if (deletingId == connection.id) "删除中…" else "删除") }
            },
            dismissButton = {
                TextButton(
                    enabled = deletingId == null,
                    onClick = { pendingDelete = null },
                ) { Text("取消") }
            },
            containerColor = Color.White.copy(alpha = .90f),
        )
    }

    pendingClaim?.let { legacy ->
        AlertDialog(
            onDismissRequest = { if (!busy) pendingClaim = null },
            title = { Text("认领旧 MCP 连接？") },
            text = { Text("将以当前 App Account 重新完成该 MCP 的官方授权。成功后连接归属此账号，其他账号不能认领；不会沿用旧凭证。") },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    busy = true
                    scope.launch {
                        runCatching { repository.beginLegacyClaim(legacy.id) }
                            .onSuccess { url ->
                                pendingClaim = null
                                message = "请在 MCP 官方页面完成授权"
                                onOpenAuthorization(url)
                            }
                            .onFailure { message = it.message ?: "旧连接认领失败" }
                        busy = false
                    }
                }) { Text("继续授权") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { pendingClaim = null }) { Text("取消") } },
            containerColor = Color.White.copy(alpha = .90f),
        )
    }

    bindingTarget?.let { connection ->
        AlertDialog(
            onDismissRequest = { bindingTarget = null },
            title = { Text("绑定身份") },
            text = {
                Column {
                    personas.forEach { persona ->
                        TextButton(
                            enabled = persona.personaId !in connection.boundIdentityIds,
                            onClick = {
                                val serviceId = connection.toolServiceId
                                bindingTarget = null
                                if (serviceId.isNullOrBlank()) {
                                    message = "App Backend 未返回 tool_service_id，无法绑定身份"
                                } else {
                                    scope.launch {
                                        runCatching { repository.bindIdentity(serviceId, persona.personaId, connection.id) }
                                            .onSuccess { message = "已绑定 ${persona.name}"; reload++ }
                                            .onFailure { message = it.message ?: "身份绑定失败" }
                                    }
                                }
                            },
                        ) { Text("${persona.name} · ${persona.personaId}") }
                    }
                }
            },
            confirmButton = {},
            dismissButton = { TextButton(onClick = { bindingTarget = null }) { Text("取消") } },
            containerColor = Color.White.copy(alpha = .90f),
        )
    }

    if (showAddForm) {
        ProductPanel {
            Text("添加 MCP", color = LoveHouseGlass.Ink, fontSize = 13.sp)
            Text("只需填写完整 MCP Server URL。名称与工具列表由服务端 discovery 获取。", color = LoveHouseGlass.MutedInk, fontSize = 9.sp)
            Column(Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text("Server URL", color = LoveHouseGlass.MutedInk, fontSize = 9.sp)
                Surface(
                    Modifier.fillMaxWidth(),
                    RoundedCornerShape(11.dp),
                    Color.White.copy(.34f),
                    border = BorderStroke(.6.dp, Color.White.copy(.55f)),
                ) {
                    BasicTextField(
                        value = serverUrl,
                        onValueChange = { serverUrl = it; message = null },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
                        textStyle = androidx.compose.ui.text.TextStyle(color = LoveHouseGlass.Ink, fontSize = 11.sp),
                        singleLine = true,
                    )
                }
            }
            Button(
                enabled = !busy && opaqueMcpServerEndpoint(serverUrl) != null,
                onClick = {
                    busy = true
                    scope.launch {
                        runCatching { repository.connect(serverUrl) }
                            .onSuccess { result ->
                                when (result.nextAction()) {
                                    McpConnectionNextAction.OpenAuthorization -> {
                                        val url = result.authorizationUrl
                                        checkNotNull(url)
                                        message = "请在 MCP 官方页面完成登录与授权"
                                        onOpenAuthorization(url)
                                    }
                                    McpConnectionNextAction.RefreshStatus -> {
                                        message = "MCP 已连接"
                                        serverUrl = ""
                                        reload++
                                    }
                                    McpConnectionNextAction.Wait -> {
                                        message = "连接已创建，正在等待 App Backend 完成检查"
                                        reload++
                                    }
                                }
                            }
                            .onFailure { message = it.message ?: "MCP 连接失败" }
                        busy = false
                    }
                },
            ) { Text(if (busy) "连接中…" else "连接", fontSize = 10.sp) }
        }
    }
}

@Composable
private fun McpConnectionRow(
    connection: McpBackendConnection,
    registered: Boolean,
    deleting: Boolean,
    onDelete: () -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(top = 9.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(connection.displayName(), color = LoveHouseGlass.Ink, fontSize = 11.sp)
            Text(connection.status.label(), color = connection.status.color(), fontSize = 9.sp)
        }
        if (connection.serverUrl.isNotBlank()) Text(connection.serverUrl, color = LoveHouseGlass.MutedInk, fontSize = 8.5.sp, maxLines = 1)
        Text(
            "${connection.toolCount} 个工具${if (registered) " · 已进入 App Backend registry" else ""}",
            color = LoveHouseGlass.MutedInk,
            fontSize = 8.5.sp,
        )
        connection.errorMessage?.let { Text(it, color = Color(0xFF9B4F55), fontSize = 8.5.sp) }
        if (connection.status.canDelete()) {
            TextButton(
                enabled = !deleting,
                onClick = onDelete,
                contentPadding = PaddingValues(horizontal = 0.dp, vertical = 0.dp),
            ) {
                Text(if (deleting) "删除中…" else "删除", color = Color(0xFF9B4F55), fontSize = 9.sp)
            }
        }
    }
}

@Composable
fun McpOAuthResultScreen(
    repository: McpConnectionRepository,
    connectionId: String,
    callbackStatus: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    LazyColumn(
        modifier = modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
        contentPadding = PaddingValues(14.dp, 10.dp, 14.dp, 30.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onBack) { Text("返回") }
                Column {
                    Text("工具中心", color = LoveHouseGlass.Ink, fontSize = 14.sp)
                    Text("MCP 授权结果", color = LoveHouseGlass.MutedInk, fontSize = 9.sp)
                }
            }
        }
        item {
            McpConnectionsPanel(
                repository = repository,
                showAddForm = true,
                initialConnectionId = connectionId,
                callbackStatus = callbackStatus,
                onOpenAuthorization = { openMcpAuthorization(context, it) },
            )
        }
    }
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

private fun McpBackendConnection.displayName(): String = name?.takeIf(String::isNotBlank)
    ?: serverUrl.substringAfter("://").substringBefore('/').takeIf(String::isNotBlank)
    ?: "MCP Server"

private fun McpBackendConnectionStatus.label(): String = when (this) {
    McpBackendConnectionStatus.Connecting -> "连接中"
    McpBackendConnectionStatus.AuthorizationRequired -> "等待授权"
    McpBackendConnectionStatus.Connected -> "已连接 ✓"
    McpBackendConnectionStatus.Failed -> "连接失败"
    McpBackendConnectionStatus.Abandoned -> "已放弃"
    McpBackendConnectionStatus.Unknown -> "状态未知"
}

private fun McpBackendConnectionStatus.color(): Color = when (this) {
    McpBackendConnectionStatus.Connected -> Color(0xFF466F63)
    McpBackendConnectionStatus.Failed -> Color(0xFF9B4F55)
    McpBackendConnectionStatus.Abandoned -> Color(0xFF9B4F55)
    else -> LoveHouseGlass.MutedInk
}

internal fun McpBackendConnectionStatus.canDelete(): Boolean = when (this) {
    McpBackendConnectionStatus.Connecting,
    McpBackendConnectionStatus.AuthorizationRequired,
    McpBackendConnectionStatus.Failed,
    McpBackendConnectionStatus.Abandoned,
    -> true
    McpBackendConnectionStatus.Connected,
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
