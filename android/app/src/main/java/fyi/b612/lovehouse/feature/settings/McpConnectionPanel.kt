package fyi.b612.lovehouse.feature.settings

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
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
import fyi.b612.lovehouse.core.designsystem.LoveHouseGlass
import fyi.b612.lovehouse.feature.chat.PersonaProfile
import fyi.b612.lovehouse.feature.chat.PersonaRuntimeSource
import kotlinx.coroutines.launch

/**
 * MCP 标签页的数据：服务、账号连接、已注册工具、人格档案、待认领的旧连接。
 * 真实数据全部来自 App Backend（McpConnectionRepository / PersonaRuntimeSource）。
 */
@Stable
internal class McpToolsModel(
    private val repository: McpConnectionRepository,
    private val personaSource: PersonaRuntimeSource,
) {
    var loading by mutableStateOf(true)
    var services by mutableStateOf<List<McpToolService>>(emptyList())
    var connections by mutableStateOf<List<McpBackendConnection>>(emptyList())
    var byService by mutableStateOf<Map<String, List<McpBackendConnection>>>(emptyMap())
    var registry by mutableStateOf<List<McpBackendConnection>>(emptyList())
    var personas by mutableStateOf<List<PersonaProfile>>(emptyList())
    var legacy by mutableStateOf<List<LegacyMcpConnection>>(emptyList())

    /** 读取全部状态；返回需要提示给用户的一句话（没有则为 null）。 */
    suspend fun load(focusConnectionId: String? = null, callbackStatus: String? = null): String? {
        loading = true
        var message: String? = null
        runCatching {
            val focused = focusConnectionId?.takeIf(String::isNotBlank)?.let { repository.connection(it) }
            val listed = repository.connections()
            val registered = repository.registry()
            val merged = mergeConnectionSources(listed + listOfNotNull(focused), registered)
            val loadedServices = repository.toolServices()
            val grouped = loadedServices.associate { service ->
                service.id to serviceConnectionCards(service.id, repository.serviceConnections(service.id), registered)
            }
            val loadedPersonas = runCatching { personaSource.profiles() }
                .getOrElse { message = "无法读取 App Backend 人格档案：${it.message ?: "请检查 App Account"}"; emptyList() }
            val legacyList = runCatching { repository.legacyConnections() }.getOrElse { emptyList() }
            connections = merged
            registry = registered
            services = loadedServices
            byService = grouped
            personas = loadedPersonas
            legacy = legacyList
            if (callbackStatus == "connected") {
                message = focused?.let { "${it.displayName()} 已连接 · ${it.toolCount} 个工具" }
                    ?: "OAuth 授权已完成，连接状态已刷新"
            }
        }.onFailure { message = it.message ?: "无法读取 MCP 连接状态" }
        loading = false
        return message
    }
}

private fun hostOf(url: String): String =
    url.substringAfter("://").substringBefore('/').takeIf(String::isNotBlank) ?: "未返回地址"

private fun accountMeta(
    connection: McpBackendConnection,
    personas: List<PersonaProfile>,
    cliToken: Boolean,
    toolOn: (String) -> Boolean,
    enabled: Boolean,
): String {
    val parts = mutableListOf<String>()
    if (connection.status != McpBackendConnectionStatus.Connected) parts += connection.status.label()
    parts += if (connection.boundIdentityIds.isEmpty()) {
        "未挂人格档案"
    } else {
        "人格档案 " + connection.boundIdentityIds.joinToString("、") { personaDisplayName(it, personas) }
    }
    parts += if (cliToken) "CLI · Token" else "官方 App · URL"
    parts += if (connection.tools.isEmpty()) {
        "${connection.toolCount} 个工具"
    } else {
        "${connection.tools.count { toolOn(it.name) }}/${connection.toolCount} 个工具开启"
    }
    if (!enabled) parts += "已关闭"
    return parts.joinToString(" · ")
}

/**
 * 「MCP」标签的内容：总开关卡片、MCP 服务列表（可展开）、每个服务下挂载的账号。
 * onOpenDetail / onAddAccount 为 null 时不显示对应入口（例如授权结果页）。
 */
@Composable
internal fun McpTab(
    model: McpToolsModel,
    repository: McpConnectionRepository,
    ps: ToolPreviewState,
    onReload: () -> Unit,
    onOpenAuthorization: (String) -> Unit,
    onOpenDetail: ((McpToolService, McpBackendConnection) -> Unit)?,
    onAddAccount: ((String) -> Unit)?,
) {
    val ui = LocalToolUi.current
    val scope = rememberCoroutineScope()
    var openIds by remember { mutableStateOf<Set<String>?>(null) }
    val services = model.services.filter { !ps.flag("svc.removed.${it.id}", false) }
    val opened = openIds ?: setOfNotNull(services.firstOrNull()?.id)

    val openBind: (McpBackendConnection) -> Unit = { connection ->
        if (model.personas.isEmpty()) {
            ui.inform("暂无人格档案", "当前 App Account 下没有可绑定的人格档案，或暂时读不到 App Backend 的人格档案。")
        } else {
            ui.choose(
                "挂到人格档案",
                "选一个人格档案来使用这个账号的工具。",
                model.personas.map { persona ->
                    val elsewhere = model.byService[connection.toolServiceId].orEmpty()
                        .any { it.id != connection.id && persona.personaId in it.boundIdentityIds }
                    ToolChoice(
                        label = persona.displayName + if (elsewhere) " · 更换到此账号" else "",
                        enabled = persona.personaId !in connection.boundIdentityIds,
                        onPick = {
                            val serviceId = connection.toolServiceId
                            if (serviceId.isNullOrBlank()) {
                                ui.toast("App Backend 未返回 tool_service_id，无法绑定")
                            } else {
                                scope.launch {
                                    runCatching { repository.bindIdentity(serviceId, persona.personaId, connection.id) }
                                        .onSuccess { ui.toast("已绑定 ${persona.displayName}"); onReload() }
                                        .onFailure { ui.toast(it.message ?: "绑定失败") }
                                }
                            }
                        },
                    )
                },
            )
        }
    }
    val remove: (McpBackendConnection) -> Unit = { connection ->
        if (!connection.status.canDelete()) {
            ui.inform("暂时不能移除", "已连接的 MCP 目前不能在 App 里删除，避免误删正在使用的账号。")
        } else {
            ui.confirm("移除这个账号？", "只移除这一项的连接，服务本身保留。", ok = "移除", danger = true) {
                scope.launch {
                    runCatching { repository.delete(connection.id) }
                        .onSuccess { ui.toast(it.uiOutcome().message); onReload() }
                        .onFailure { ui.toast(it.message ?: "移除失败") }
                }
            }
        }
    }

    @Composable
    fun AccountRow(service: McpToolService?, connection: McpBackendConnection) {
        val serviceId = service?.id ?: connection.toolServiceId.orEmpty()
        val enabled = ps.flag("acc.on.${connection.id}", connection.enabled)
        ToolAcc(
            name = connection.displayName(),
            meta = accountMeta(
                connection, model.personas,
                cliToken = ps.flag("acc.cli.${connection.id}", false),
                toolOn = { ps.flag("tool.on.$serviceId/$it", true) },
                enabled = enabled,
            ),
            checked = enabled,
            onChecked = { ps.setFlag("acc.on.${connection.id}", it); ui.toast(ToolPreviewToast) },
            error = connection.errorMessage,
        ) {
            if (service != null && onOpenDetail != null) ToolAccLink("工具与权限", { onOpenDetail(service, connection) })
            ToolAccLink("换绑人格档案", {
                if (connection.status == McpBackendConnectionStatus.Connected) {
                    openBind(connection)
                } else {
                    ui.inform("暂时不能换绑", "这个账号还没有连接成功，连接成功后才能挂到人格档案。")
                }
            })
            ToolAccLink("移除", { remove(connection) }, danger = true)
        }
    }

    ToolGlass {
        ToolRow("启用 MCP 工具", "自动发现可用工具，与外部工具统一调用") {
            ToolSwitch(ps.flag("g.mcp.enabled"), { ps.setFlag("g.mcp.enabled", it); ui.toast(ToolPreviewToast) })
        }
        ToolRow("调用前询问", "AI 发起调用时，先在对话里弹出确认卡片", divider = true) {
            ToolSwitch(ps.flag("g.mcp.ask"), { ps.setFlag("g.mcp.ask", it); ui.toast(ToolPreviewToast) })
        }
    }

    if (model.legacy.isNotEmpty()) {
        ToolSection("待认领旧连接", "${model.legacy.size} 个")
        ToolGlass {
            model.legacy.forEachIndexed { index, legacy ->
                ToolRow(legacy.name ?: "未命名 MCP", if (legacy.claimMethod == "oauth_reauthorization") null else "需安全迁移证明", divider = index > 0) {
                    if (legacy.claimMethod == "oauth_reauthorization") {
                        ToolLink("认领", {
                            ui.confirm(
                                "认领旧 MCP 连接？",
                                "将以当前 App Account 重新完成该 MCP 的官方授权。成功后连接归属此账号，其他账号不能认领；不会沿用旧凭证。",
                                ok = "继续授权",
                            ) {
                                scope.launch {
                                    runCatching { repository.beginLegacyClaim(legacy.id) }
                                        .onSuccess { ui.toast("请在 MCP 官方页面完成授权"); onOpenAuthorization(it) }
                                        .onFailure { ui.toast(it.message ?: "旧连接认领失败") }
                                }
                            }
                        })
                    }
                }
            }
        }
    }

    ToolSection("MCP 服务", if (model.loading) "正在读取…" else "${services.size} 个")

    if (!model.loading && services.isEmpty() && model.connections.isEmpty()) {
        ToolEmpty("还没有 MCP 服务。点右上角的 + 添加。")
    }

    Column {
        services.forEach { service ->
            val note = ps.text("svc.note.${service.id}").orEmpty()
            val conns = model.byService[service.id].orEmpty()
            val title = ps.text("svc.name.${service.id}") ?: service.displayName ?: service.name
                ?: conns.firstOrNull()?.displayName() ?: "MCP 服务"
            val host = conns.firstOrNull()?.serverUrl?.let(::hostOf) ?: "暂无地址"
            ToolServerCard(
                letter = title,
                title = title,
                sub = "$host · ${conns.size} 个账号",
                expanded = service.id in opened,
                onToggle = { openIds = if (service.id in opened) opened - service.id else opened + service.id },
            ) {
                if (note.isNotBlank()) ToolNoteLine(note)
                val anyConnected = service.connectedConnectionCount > 0
                ToolStatusLine(
                    if (anyConnected) ToolStyle.Ok else ToolStyle.Idle,
                    when {
                        conns.isEmpty() -> "尚未接入账号"
                        anyConnected -> "已连接 · ${service.connectedConnectionCount}/${service.connectionCount} 个账号在线"
                        else -> "未连接"
                    },
                )
                ToolActions {
                    ToolChip("刷新", { ui.toast("正在刷新…"); onReload() }, icon = ToolIconKind.Refresh)
                    ToolChip("重命名", {
                        ui.prompt("重命名", "只改显示名称，不影响连接地址。", title) { v ->
                            if (v.isNotBlank()) ps.setText("svc.name.${service.id}", v)
                            ui.toast(ToolPreviewToast)
                        }
                    }, icon = ToolIconKind.Edit)
                    ToolChip("备注", {
                        ui.prompt("备注", "写给自己看的说明。", note) { v ->
                            ps.setText("svc.note.${service.id}", v)
                            ui.toast(ToolPreviewToast)
                        }
                    }, icon = ToolIconKind.Note)
                    ToolChip("删除", {
                        if (conns.isNotEmpty()) {
                            ui.inform("暂时不能删除", "这里仍有 ${conns.size} 个账号。请先全部移除，再删除整个服务。")
                        } else {
                            ui.confirm("删除这一项？", "删除后它的相关配置会一并移除。", ok = "删除", danger = true) {
                                ps.setFlag("svc.removed.${service.id}", true)
                                ui.toast(ToolPreviewToast)
                            }
                        }
                    }, icon = ToolIconKind.Trash, danger = true)
                }
                ToolAccLabel(
                    "挂载账号",
                    if (onAddAccount != null) "+ 接入新账号" else null,
                ) { onAddAccount?.invoke(conns.firstOrNull()?.serverUrl.orEmpty()) }
                conns.forEach { AccountRow(service, it) }
                if (conns.isEmpty()) ToolNoteLine("还没有账号，点上面的“接入新账号”添加。")
            }
        }
    }

    if (!model.loading && services.isEmpty() && model.connections.isNotEmpty()) {
        ToolSection("未归入服务的连接", "${model.connections.size} 个")
        ToolGlass {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                ToolNoteLine("Tool Service 数据尚未返回；以下连接按原始记录展示。")
                model.connections.forEach { AccountRow(null, it) }
            }
        }
    }
}

/** 二级页：添加服务器 / 接入新账号。只需要完整的 MCP Server URL，名称与工具由服务端发现。 */
@Composable
internal fun McpAddPage(
    prefillUrl: String,
    repository: McpConnectionRepository,
    onConnected: () -> Unit,
    onOpenAuthorization: (String) -> Unit,
) {
    val ui = LocalToolUi.current
    val scope = rememberCoroutineScope()
    var url by remember(prefillUrl) { mutableStateOf(prefillUrl) }
    var busy by remember { mutableStateOf(false) }

    ToolFormCard("连接信息") {
        ToolField("服务地址 (URL，原样保存)", last = true) {
            ToolInput(url, { url = it }, placeholder = "https://")
            ToolHint("只需填完整的 MCP Server URL。名称与工具列表由服务端发现，连接后可在服务卡片里重命名、加备注。")
        }
    }
    ToolSaveButton(
        label = if (busy) "连接中…" else "连接并发现工具",
        enabled = !busy && opaqueMcpServerEndpoint(url) != null,
        onClick = {
            busy = true
            scope.launch {
                runCatching { repository.connect(url) }
                    .onSuccess { result ->
                        when (result.nextAction()) {
                            McpConnectionNextAction.OpenAuthorization -> {
                                val authUrl = checkNotNull(result.authorizationUrl)
                                ui.toast("请在 MCP 官方页面完成登录与授权")
                                onOpenAuthorization(authUrl)
                            }
                            McpConnectionNextAction.RefreshStatus -> {
                                ui.toast("MCP 已连接")
                                onConnected()
                            }
                            McpConnectionNextAction.Wait -> {
                                ui.toast("连接已创建，正在等待 App Backend 完成检查")
                                onConnected()
                            }
                        }
                    }
                    .onFailure { ui.toast(it.message ?: "MCP 连接失败") }
                busy = false
            }
        },
    )
}

@Composable
fun McpOAuthResultScreen(
    repository: McpConnectionRepository,
    personaRuntimeSource: PersonaRuntimeSource,
    connectionId: String,
    callbackStatus: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val model = remember(repository, personaRuntimeSource) { McpToolsModel(repository, personaRuntimeSource) }
    val ps = remember { ToolPreviewState() }
    var reload by remember { mutableIntStateOf(0) }
    ToolHost(modifier.fillMaxSize()) {
        val ui = LocalToolUi.current
        LaunchedEffect(model, connectionId, callbackStatus, reload) {
            model.load(connectionId, callbackStatus)?.let { ui.toast(it) }
        }
        Column(
            Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()
                .verticalScroll(rememberScrollState()).padding(horizontal = 24.dp).padding(bottom = 24.dp),
        ) {
            ToolTopBar("工具中心", "MCP · Authorization", onBack)
            Spacer(Modifier.height(8.dp))
            McpTab(
                model = model,
                repository = repository,
                ps = ps,
                onReload = { reload++ },
                onOpenAuthorization = { openMcpAuthorization(context, it) },
                onOpenDetail = null,
                onAddAccount = null,
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
