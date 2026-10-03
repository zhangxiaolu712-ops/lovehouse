package fyi.b612.lovehouse.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import fyi.b612.lovehouse.feature.chat.PersonaProfile
import fyi.b612.lovehouse.feature.chat.PersonaRuntimeSource
import kotlinx.coroutines.launch

internal data class McpTcCard(
    val key: String,
    val serviceId: String?,
    val name: String,
    val note: String?,
    val host: String,
    val accounts: List<McpBackendConnection>,
)

@Stable
internal class McpTcState {
    var loading by mutableStateOf(true)
    var cards by mutableStateOf<List<McpTcCard>>(emptyList())
    var personas by mutableStateOf<List<PersonaProfile>>(emptyList())
    var legacy by mutableStateOf<List<LegacyMcpConnection>>(emptyList())
    var open by mutableStateOf<Set<String>>(emptySet())
    var reload by mutableIntStateOf(0)
    var afterReloadToast: String? = null
    var opened = false
    var policyState by mutableStateOf(McpToolPolicyState(0, emptyList()))
    var policyReady by mutableStateOf(false)
    var policyError by mutableStateOf<String?>(null)

    fun connection(id: String): McpBackendConnection? =
        cards.firstNotNullOfOrNull { card -> card.accounts.firstOrNull { it.id == id } }

    fun refresh(toast: String? = null) {
        afterReloadToast = toast
        reload++
    }
}

@Composable
internal fun rememberMcpTcState(
    repository: McpConnectionRepository,
    personaRuntimeSource: PersonaRuntimeSource,
    initialConnectionId: String?,
    callbackStatus: String?,
    ui: TcUi,
): McpTcState {
    val state = remember(repository) { McpTcState() }
    LaunchedEffect(repository, state.reload) {
        state.loading = true
        runCatching {
            val focused = initialConnectionId?.takeIf { state.reload == 0 && it.isNotBlank() }?.let { repository.connection(it) }
            val registered = repository.registry()
            val all = mergeConnectionSources(repository.connections() + listOfNotNull(focused), registered)
            val services = repository.toolServices()
            val grouped = services.associate { service ->
                service.id to serviceConnectionCards(service.id, repository.serviceConnections(service.id), registered)
            }
            state.cards = buildMcpCards(services, grouped, all)
            if (!state.opened) {
                state.opened = true
                state.open = setOfNotNull(
                    focused?.let { f -> state.cards.firstOrNull { card -> card.accounts.any { it.id == f.id } }?.key }
                        ?: state.cards.firstOrNull()?.key,
                )
            }
            if (state.reload == 0 && callbackStatus == "connected") {
                ui.toast(focused?.let { "${it.displayName()} 已连接 · ${it.toolCount} 个工具" } ?: "OAuth 授权已完成，连接状态已刷新")
            }
        }.onFailure { ui.toast(it.message ?: "无法读取 MCP 连接状态") }
        state.personas = runCatching { personaRuntimeSource.profiles() }.getOrElse {
            ui.toast("无法读取 App Backend 人格档案：${it.message ?: "请检查 App Account"}")
            state.personas
        }
        state.legacy = runCatching { repository.legacyConnections() }.getOrElse { emptyList() }
        state.policyReady = false
        state.policyError = null
        runCatching { repository.toolPolicies() }
            .onSuccess { state.policyState = it; state.policyReady = true }
            .onFailure { state.policyError = it.message ?: "Tool Policy 读取失败" }
        state.loading = false
        state.afterReloadToast?.let(ui::toast)
        state.afterReloadToast = null
    }
    return state
}

private const val PreviewNotSaved = "预览：该开关尚未接入后端，未保存"

@Composable
internal fun McpTab(state: McpTcState, repository: McpConnectionRepository, ui: TcUi, onOpen: (TcPage) -> Unit) {
    var enabled by remember { mutableStateOf(true) }
    var askFirst by remember { mutableStateOf(true) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    TcGlass {
        TcRow(first = true) {
            TcRowText("智能挂载工具", "根据当前对话自动加载相关工具，减少上下文占用", Modifier.weight(1f))
            TcSwitch(enabled, "智能挂载工具") { enabled = it; ui.toast(PreviewNotSaved) }
        }
        TcRow(first = false) {
            TcRowText("全局默认权限", "设置未单独配置工具的默认使用权限", Modifier.weight(1f))
            TcSwitch(askFirst, "全局默认权限") { askFirst = it; ui.toast(PreviewNotSaved) }
        }
    }

    TcSection("MCP 服务", if (state.loading && state.cards.isEmpty()) "读取中…" else "${state.cards.size} 个")
    if (!state.loading && state.cards.isEmpty()) {
        TcGlass { TcEmpty("还没有添加 MCP 服务，点右上角“+”添加。") }
    }

    state.cards.forEach { card ->
        val (status, ok) = mcpCardStatus(card.accounts)
        TcServerCard(
            letter = card.name.firstOrNull()?.uppercase() ?: "M",
            name = card.name,
            sub = "${card.host} · ${card.accounts.size} 个账号",
            open = card.key in state.open,
            onToggle = { state.open = if (card.key in state.open) state.open - card.key else state.open + card.key },
            note = card.note,
            status = status,
            statusOk = ok,
            testLabel = "刷新",
            onTest = { state.refresh("已刷新") },
            onRename = {
                ui.dialog = TcDialog("重命名", "只改显示名称，不影响连接地址。", input = card.name, ok = "保存") {
                    result ->
                    val serviceId = card.serviceId
                    if (serviceId == null) ui.toast("App Backend 未返回 tool_service_id，无法重命名")
                    else scope.launch {
                        runCatching { repository.updateToolService(serviceId, McpToolServiceUpdate(result.text, card.note)) }
                            .onSuccess { state.refresh("已重命名") }
                            .onFailure { ui.toast(it.message ?: "重命名失败") }
                    }
                }
            },
            onNote = {
                ui.dialog = TcDialog("备注", "写给自己看的说明。", input = card.note.orEmpty(), ok = "保存") { result ->
                    val serviceId = card.serviceId
                    if (serviceId == null) ui.toast("App Backend 未返回 tool_service_id，无法保存备注")
                    else scope.launch {
                        runCatching { repository.updateToolService(serviceId, McpToolServiceUpdate(card.name, result.text)) }
                            .onSuccess { state.refresh("备注已保存") }
                            .onFailure { ui.toast(it.message ?: "备注保存失败") }
                    }
                }
            },
            onDelete = {
                ui.dialog = if (card.accounts.isNotEmpty()) {
                    TcDialog("暂时不能删除", "这里仍有 ${card.accounts.size} 个账号。请先全部移除，再删除整个服务。", ok = "知道了", single = true)
                } else {
                    TcDialog("删除这一项？", "删除后它的相关配置会一并移除。", ok = "删除", danger = true) {
                        val serviceId = card.serviceId
                        if (serviceId == null) ui.toast("App Backend 未返回 tool_service_id，无法删除")
                        else scope.launch {
                            runCatching { repository.deleteToolService(serviceId) }
                                .onSuccess { outcome ->
                                    when (outcome) {
                                        McpToolServiceDeleteResult.Deleted,
                                        McpToolServiceDeleteResult.AlreadyAbsent -> state.refresh("服务已删除")
                                        McpToolServiceDeleteResult.HasConnections -> ui.toast("服务仍有账号，不能删除")
                                    }
                                }
                                .onFailure { ui.toast(it.message ?: "服务删除失败") }
                        }
                    }
                }
            },
            itemsLabel = "挂载账号",
            addLabel = "+ 接入新账号",
            onAdd = { onOpen(TcPage.Mcp("接入新账号", card.key, null)) },
            emptyText = if (card.accounts.isEmpty()) "还没有账号，点上面的“接入新账号”添加。" else null,
        ) {
            card.accounts.forEach { connection ->
                TcAccount(
                    name = connection.displayName(),
                    meta = mcpAccountMeta(connection, state.personas),
                    checked = connection.enabled,
                    onToggle = { on ->
                        scope.launch {
                            runCatching { repository.updateConnection(connection.id, McpConnectionUpdate(enabled = on)) }
                                .onSuccess { state.refresh(if (on) "账号已开启" else "账号已关闭") }
                                .onFailure { ui.toast(it.message ?: "账号状态保存失败") }
                        }
                    },
                ) {
                    TcAccLink("工具与权限") { onOpen(TcPage.Mcp("工具与权限", card.key, connection.id)) }
                    if (connection.status == McpBackendConnectionStatus.Connected) {
                        TcAccLink("换绑人格档案") {
                            openPersonaBinding(state, ui, connection, card.serviceId) { serviceId, before, after ->
                                scope.launch {
                                    runCatching { applyPersonaBindings(repository, serviceId, connection.id, before, after) }
                                        .onSuccess { state.refresh("人格档案已更新") }
                                        .onFailure { ui.toast(it.message ?: "人格档案保存失败") }
                                }
                            }
                        }
                    }
                    TcAccLink("移除", danger = true) {
                        ui.dialog = TcDialog("移除这个账号？", "只移除这一项的连接，服务本身保留。", ok = "移除", danger = true) {
                            scope.launch {
                                runCatching { repository.delete(connection.id) }
                                    .onSuccess { result ->
                                        val outcome = result.uiOutcome()
                                        if (outcome.refreshConnectionsAndRegistry) state.refresh(outcome.message) else ui.toast(outcome.message)
                                    }
                                    .onFailure { ui.toast(it.message ?: "移除失败") }
                            }
                        }
                    }
                }
            }
        }
    }

    if (state.legacy.isNotEmpty()) {
        TcGroupLabel("待认领旧连接")
        state.legacy.forEach { legacy ->
            val claimable = legacy.claimMethod == "oauth_reauthorization"
            TcAccount(
                name = legacy.name ?: "未命名 MCP",
                meta = if (claimable) "重新完成官方授权后归属当前账号" else "需安全迁移证明，暂不能认领",
                checked = null,
            ) {
                if (claimable) {
                    TcAccLink("认领") {
                        ui.dialog = TcDialog(
                            "认领旧 MCP 连接？",
                            "将以当前 App Account 重新完成该 MCP 的官方授权。成功后连接归属此账号，其他账号不能认领；不会沿用旧凭证。",
                            ok = "继续授权",
                        ) {
                            scope.launch {
                                runCatching { repository.beginLegacyClaim(legacy.id) }
                                    .onSuccess { url ->
                                        runCatching { openMcpAuthorization(context, url) }
                                            .onSuccess { ui.toast("请在 MCP 官方页面完成授权") }
                                            .onFailure { ui.toast(it.message ?: "无法打开授权页面") }
                                    }
                                    .onFailure { ui.toast(it.message ?: "旧连接认领失败") }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun openPersonaBinding(
    state: McpTcState,
    ui: TcUi,
    connection: McpBackendConnection,
    cardServiceId: String?,
    apply: (serviceId: String, before: Set<String>, after: Set<String>) -> Unit,
) {
    val personas = state.personas
    val serviceId = connection.toolServiceId ?: cardServiceId
    when {
        personas.isEmpty() -> ui.toast("当前 App Account 尚无可绑定的人格档案")
        serviceId.isNullOrBlank() -> ui.toast("App Backend 未返回 tool_service_id，无法绑定人格档案")
        else -> {
            val before = connection.boundIdentityIds.toSet()
            ui.dialog = TcDialog(
                title = "换绑人格档案",
                body = "选择这个账号要挂到的人格档案。",
                pills = personas.map { it.displayName },
                selected = personas.indices.filterTo(mutableSetOf()) { personas[it].personaId in before },
                ok = "保存",
            ) { result ->
                val after = result.selected.mapTo(mutableSetOf()) { personas[it].personaId }
                if (after != before) apply(serviceId, before, after)
            }
        }
    }
}

@Composable
internal fun McpDetailPage(
    page: TcPage.Mcp,
    state: McpTcState,
    repository: McpConnectionRepository,
    ui: TcUi,
    onDone: () -> Unit,
) {
    val card = page.cardKey?.let { key -> state.cards.firstOrNull { it.key == key } }
    val connection = page.connectionId?.let(state::connection)
    val adding = page.connectionId == null
    if (!adding && connection == null) {
        TcGlass { TcEmpty(if (state.loading) "正在读取…" else "这个账号已不存在，请返回刷新列表。") }
        return
    }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var busy by remember { mutableStateOf(false) }
    var shellEdited by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf(if (page.cardKey == null) "" else card?.name.orEmpty()) }
    var url by remember { mutableStateOf(connection?.serverUrl ?: card?.accounts?.firstOrNull()?.serverUrl.orEmpty()) }
    var note by remember { mutableStateOf(card?.note.orEmpty()) }
    var accountName by remember { mutableStateOf(connection?.displayName.orEmpty()) }
    var accountNote by remember { mutableStateOf(connection?.note.orEmpty()) }
    var credential by remember(connection?.id) { mutableStateOf(McpCredentialDraft.from(connection)) }
    var credentialBusy by remember { mutableStateOf(false) }
    var boundIds by remember { mutableStateOf(connection?.boundIdentityIds.orEmpty().toSet()) }
    val purposes = remember { mutableStateListOf<String>() }
    var purposeOn by remember { mutableStateOf(setOf<Int>()) }
    val toolOn = remember { mutableStateMapOf<String, Boolean>() }
    var authoritativeTools by remember(connection?.id, state.reload) { mutableStateOf<List<McpDiscoveredTool>>(emptyList()) }
    var toolsLoading by remember(connection?.id, state.reload) { mutableStateOf(connection != null) }
    var toolsError by remember(connection?.id, state.reload) { mutableStateOf<String?>(null) }

    LaunchedEffect(connection?.id, state.reload) {
        val id = connection?.id ?: return@LaunchedEffect
        toolsLoading = true
        toolsError = null
        runCatching { repository.connectionTools(id) }
            .onSuccess { authoritativeTools = it }
            .onFailure { toolsError = it.message ?: "工具字段读取失败" }
        toolsLoading = false
    }

    TcFormCard("连接信息") {
        TcField("服务名称") { TcInput(name, { name = it }) }
        TcField("服务地址 (URL，原样保存)") {
            TcInput(url, { if (adding) url = it }, placeholder = "https://", enabled = adding)
            if (!adding) TcHint("现有 Connection 地址只读；本页不会改写 endpoint。")
        }
        TcField("服务备注") {
            TcInput(note, { note = it }, placeholder = "这个服务是做什么的，给自己看", multiline = true)
        }
        if (connection == null) {
            TcInlineStatus("测试连接", "尚未连接", ok = false) { ui.toast("保存后由 App Backend 连接并发现工具") }
        } else {
            val connected = connection.status == McpBackendConnectionStatus.Connected
            TcInlineStatus(
                "测试连接",
                if (connected) "发现 ${connection.toolCount} 个可注册工具" else connection.status.label(),
                ok = connected,
                busy = busy,
            ) {
                busy = true
                scope.launch {
                    runCatching { repository.connection(connection.id) }
                        .onSuccess { fresh -> state.refresh("${fresh.status.label()} · 发现 ${fresh.toolCount} 个可注册工具") }
                        .onFailure { ui.toast(it.message ?: "测试连接失败") }
                    busy = false
                }
            }
        }
    }

    TcFormCard("账号与人格档案") {
        TcField("账号名称") { TcInput(accountName, { accountName = it }) }
        TcField("账号备注") { TcInput(accountNote, { accountNote = it }, multiline = true) }
        McpCredentialInputs(credential, connection) { credential = it }
        if (connection != null) {
            val currentType = connection.authType
            val updating = connection.status == McpBackendConnectionStatus.AuthUpdating
            val action = when (credential.choice) {
                McpAuthChoice.Bearer, McpAuthChoice.ApiKey -> "保存凭证"
                McpAuthChoice.None -> if (currentType == McpAuthType.None) null else "切换为无鉴权"
                McpAuthChoice.OAuth -> if (currentType == McpAuthType.OAuth) "重新授权 OAuth" else "切换到 OAuth 授权"
            }
            val removeToNone = {
                ui.dialog = TcDialog(
                    "改为无鉴权？",
                    "将删除这个连接保存在后端的凭证，并以无鉴权方式重新发现工具；只有发现成功才会生效。",
                    ok = "改为无鉴权",
                    danger = true,
                ) {
                    credentialBusy = true
                    scope.launch {
                        runCatching { runMcpCredentialMutation(repository, connection.id) { removeCredential(connection.id) } }
                            .onSuccess { fresh ->
                                credential = McpCredentialDraft.from(fresh)
                                state.refresh("已改为无鉴权 · ${fresh.status.label()}")
                            }
                            .onFailure { ui.toast(it.mcpSafeText()) }
                        credentialBusy = false
                    }
                }
            }
            val switchToOAuth = {
                ui.dialog = TcDialog(
                    "切换到 OAuth 授权？",
                    "将把这个连接切换为 OAuth，并移除当前保存的凭证；需要在官方页面重新完成授权。",
                    ok = "继续授权",
                ) {
                    credentialBusy = true
                    scope.launch {
                        runCatching {
                            var start: McpConnectionStart? = null
                            val fresh = runMcpCredentialMutation(repository, connection.id) {
                                start = reauthorizeOAuth(connection.id)
                            }
                            openMcpAuthorization(context, checkNotNull(start?.authorizationUrl))
                            fresh
                        }.onSuccess { fresh ->
                            credential = McpCredentialDraft.from(fresh)
                            state.refresh("请在 MCP 官方页面完成授权")
                        }.onFailure { ui.toast(it.mcpSafeText()) }
                        credentialBusy = false
                    }
                }
            }
            when {
                updating -> TcHint("认证更新中，请稍后刷新查看结果。")
                action != null -> Box(Modifier.padding(bottom = 11.dp)) {
                    TcInlineStatus(
                        action,
                        connection.status.label(),
                        ok = connection.status == McpBackendConnectionStatus.Connected,
                        busy = credentialBusy,
                    ) {
                        when (credential.choice) {
                            McpAuthChoice.Bearer, McpAuthChoice.ApiKey -> {
                                val invalid = credential.validationError()
                                val input = credential.toInput()
                                if (invalid != null || input == null) {
                                    ui.toast(invalid ?: "请选择凭证类型")
                                } else {
                                    credentialBusy = true
                                    scope.launch {
                                        runCatching {
                                            runMcpCredentialMutation(repository, connection.id) { updateCredential(connection.id, input) }
                                        }.onSuccess { fresh ->
                                            credential = McpCredentialDraft.from(fresh)
                                            state.refresh("凭证已更新 · ${fresh.status.label()}")
                                        }.onFailure { ui.toast(it.mcpSafeText()) }
                                        credentialBusy = false
                                    }
                                }
                            }
                            McpAuthChoice.None -> removeToNone()
                            McpAuthChoice.OAuth -> switchToOAuth()
                        }
                    }
                }
            }
            if (!updating && !credentialBusy && currentType != null &&
                currentType in setOf(McpAuthType.Bearer, McpAuthType.ApiKey) &&
                credential.choice == currentType.toChoice()
            ) {
                TcAccLink("移除凭证", danger = true) { removeToNone() }
            }
        }
        TcField("挂到哪些人格档案") {
            val personas = state.personas
            if (personas.isEmpty()) {
                TcHint("当前 App Account 尚无可绑定的人格档案。")
            } else {
                val allIndex = personas.size
                val selected = personas.indices.filterTo(mutableSetOf()) { personas[it].personaId in boundIds }
                    .apply { if (personas.all { it.personaId in boundIds }) add(allIndex) }
                TcPills(personas.map { it.displayName } + "全部人格档案", selected) { index ->
                    boundIds = if (index == allIndex) {
                        if (allIndex in selected) emptySet() else personas.mapTo(mutableSetOf()) { it.personaId }
                    } else {
                        val id = personas[index].personaId
                        if (id in boundIds) boundIds - id else boundIds + id
                    }
                }
            }
        }
        TcField("用途", last = true) {
            TcPills(purposes + "+ 自定义", purposeOn) { index ->
                if (index == purposes.size) {
                    ui.dialog = TcDialog("自定义用途", "给这个账号加一个用途标签。", input = "", ok = "添加") { result ->
                        if (result.text.isNotEmpty()) {
                            purposes += result.text
                            purposeOn = purposeOn + (purposes.size - 1)
                            shellEdited = true
                        }
                    }
                } else {
                    purposeOn = if (index in purposeOn) purposeOn - index else purposeOn + index
                    shellEdited = true
                }
            }
        }
    }

    val tools = authoritativeTools
    TcFormCard("工具与权限 · ${tools.size} 个") {
        when {
            tools.isNotEmpty() -> tools.forEachIndexed { index, tool ->
                val toolId = checkNotNull(tool.toolId)
                val currentConnection = checkNotNull(connection)
                val explicit = state.policyState.accountConnectionToolPolicy(currentConnection.id, toolId)
                val selected = explicit?.decision?.toToolPermission() ?: ToolPermission.Ask
                val on = toolOn[toolId] ?: true
                TcToolCard(last = index == tools.lastIndex) {
                    TcToolTop(tool.name, tool.description.orEmpty()) {
                        TcSwitch(on, tool.name) {
                            toolOn[toolId] = it
                            shellEdited = true
                            ui.toast("单工具开关尚未接入后端，未保存")
                        }
                    }
                    TcSeg(
                        ToolPermission.entries.map { it.label },
                        selected.ordinal,
                        Modifier.padding(top = 4.dp, end = 6.dp, bottom = 2.dp),
                        enabled = on && state.policyReady && !busy,
                    ) { selectedIndex ->
                        busy = true
                        scope.launch {
                            runCatching {
                                saveAccountConnectionToolPolicy(
                                    repository = repository,
                                    connectionId = currentConnection.id,
                                    toolId = toolId,
                                    decision = ToolPermission.entries[selectedIndex].toPolicyDecision(),
                                )
                            }.onSuccess { refreshed ->
                                state.policyState = refreshed
                                state.policyReady = true
                                ui.toast("工具权限已保存")
                            }.onFailure { ui.toast(it.message ?: "工具权限保存失败") }
                            busy = false
                        }
                    }
                    TcHint(
                        when {
                            !state.policyReady -> state.policyError ?: "正在读取 authoritative Tool Policy…"
                            explicit == null -> "当前：默认 ASK（继承，未保存 override）"
                            else -> "当前：显式 ${selected.label}"
                        },
                    )
                    if (explicit != null && !busy) {
                        TcAccLink("恢复默认（继承）") {
                            busy = true
                            scope.launch {
                                runCatching { deleteAccountConnectionToolPolicy(repository, explicit.id) }
                                    .onSuccess { refreshed ->
                                        state.policyState = refreshed
                                        state.policyReady = true
                                        ui.toast("已恢复默认 ASK")
                                    }
                                    .onFailure { ui.toast(it.message ?: "恢复默认失败") }
                                busy = false
                            }
                        }
                    }
                    TcSchema(tool.inputSchema ?: "App Backend 未返回该工具的字段结构。")
                }
            }
            toolsLoading -> TcHint("正在从 App Backend 读取 authoritative 工具与字段…")
            toolsError != null -> TcHint(toolsError!!)
            connection == null -> TcHint("保存并完成授权后，这里会列出服务发现的工具。")
            connection.toolCount > 0 -> TcHint("App Backend 报告 ${connection.toolCount} 个工具，但没有返回工具明细。")
            else -> TcHint("这个账号还没有发现工具。")
        }
    }

    TcSaveButton(if (busy && adding) "连接中…" else "保存并注册工具", enabled = !busy) {
        val shellNotice = listOfNotNull(
            if (shellEdited) "用途或单工具开关仍是界面预览，未保存" else null,
            if (connection != null && credential.hasSecret) "Token / API Key 需点「保存凭证」单独提交" else null,
        ).joinToString("；").ifEmpty { null }
        if (connection == null) {
            val endpoint = opaqueMcpServerEndpoint(url.trim())
            if (endpoint == null) {
                ui.toast("请输入完整的 MCP Server URL")
                return@TcSaveButton
            }
            val invalidCredential = credential.validationError()
            if (invalidCredential != null) {
                ui.toast(invalidCredential)
                return@TcSaveButton
            }
            val credentialInput = credential.toInput()
            busy = true
            scope.launch {
                val result = runCatching { repository.connect(endpoint, credentialInput) }.getOrElse {
                    ui.toast(if (credentialInput != null) it.mcpSafeText() else it.message ?: "MCP 连接失败")
                    busy = false
                    return@launch
                }
                credential = credential.cleared()
                val metadataFailure = runCatching {
                    val created = result.connection ?: error("App Backend 未返回新 Connection，名称和备注未保存")
                    repository.updateConnection(
                        result.connectionId,
                        McpConnectionUpdate(
                            displayName = accountName.trim(),
                            note = accountNote.trim(),
                            updateMetadata = true,
                        ),
                    )
                    created.toolServiceId?.let { serviceId ->
                        repository.updateToolService(
                            serviceId,
                            McpToolServiceUpdate(name.trim(), note.trim()),
                        )
                    } ?: error("App Backend 未返回 tool_service_id，服务名称和备注未保存")
                }.exceptionOrNull()
                val message = when (result.nextAction()) {
                    McpConnectionNextAction.OpenAuthorization -> runCatching {
                        openMcpAuthorization(context, checkNotNull(result.authorizationUrl))
                        "请在 MCP 官方页面完成登录与授权"
                    }.getOrElse { it.message ?: "无法打开授权页面" }
                    McpConnectionNextAction.RefreshStatus -> "MCP 已连接"
                    McpConnectionNextAction.Wait -> "连接已创建，正在等待 App Backend 完成检查"
                }
                val bindingNote = if (boundIds.isNotEmpty()) "连接完成后在「换绑人格档案」挂载人格档案" else null
                val metadataNotice = metadataFailure?.let { "连接已创建，但名称/备注保存失败：${it.message ?: "未知错误"}" }
                state.refresh(listOfNotNull(message, metadataNotice, bindingNote, shellNotice).joinToString("；"))
                onDone()
                busy = false
            }
        } else {
            val before = connection.boundIdentityIds.toSet()
            val after = boundIds
            val serviceId = connection.toolServiceId ?: card?.serviceId
            if (serviceId.isNullOrBlank()) {
                ui.toast("App Backend 未返回 tool_service_id，无法保存服务信息")
                return@TcSaveButton
            }
            busy = true
            scope.launch {
                runCatching {
                    repository.updateToolService(serviceId, McpToolServiceUpdate(name.trim(), note.trim()))
                    repository.updateConnection(
                        connection.id,
                        McpConnectionUpdate(
                            displayName = accountName.trim(),
                            note = accountNote.trim(),
                            updateMetadata = true,
                        ),
                    )
                    applyPersonaBindings(repository, serviceId, connection.id, before, after)
                }
                    .onSuccess {
                        state.refresh(listOfNotNull("服务、账号与人格档案已保存", shellNotice).joinToString("；"))
                        onDone()
                    }
                    .onFailure { ui.toast(it.message ?: "人格档案保存失败") }
                busy = false
            }
        }
    }
}

internal fun McpToolPolicyState.accountConnectionToolPolicy(
    connectionId: String,
    toolId: String,
): McpToolPolicyRecord? = policies.singleOrNull {
    it.scopeType == McpToolPolicyScopeType.ACCOUNT_CONNECTION_TOOL &&
        it.connectionId == connectionId &&
        it.toolId == toolId
}

internal fun ToolPermission.toPolicyDecision(): McpToolPolicyDecision = when (this) {
    ToolPermission.Deny -> McpToolPolicyDecision.DENY
    ToolPermission.Ask -> McpToolPolicyDecision.ASK
    ToolPermission.Allow -> McpToolPolicyDecision.ALLOW
}

internal fun McpToolPolicyDecision.toToolPermission(): ToolPermission = when (this) {
    McpToolPolicyDecision.DENY -> ToolPermission.Deny
    McpToolPolicyDecision.ASK -> ToolPermission.Ask
    McpToolPolicyDecision.ALLOW -> ToolPermission.Allow
}

internal suspend fun saveAccountConnectionToolPolicy(
    repository: McpConnectionRepository,
    connectionId: String,
    toolId: String,
    decision: McpToolPolicyDecision,
): McpToolPolicyState {
    val mutation = repository.putToolPolicy(
        McpToolPolicyDraft.accountConnectionTool(connectionId, toolId, decision),
    )
    val authoritative = repository.toolPolicies()
    check(authoritative.policyRevision >= mutation.policyRevision) {
        "Tool Policy revision 尚未收敛"
    }
    return authoritative
}

internal suspend fun deleteAccountConnectionToolPolicy(
    repository: McpConnectionRepository,
    policyId: String,
): McpToolPolicyState {
    val mutation = repository.deleteToolPolicy(policyId)
    val authoritative = repository.toolPolicies()
    check(authoritative.policyRevision >= mutation.policyRevision) {
        "Tool Policy revision 尚未收敛"
    }
    return authoritative
}

internal suspend fun applyPersonaBindings(
    repository: McpConnectionRepository,
    serviceId: String,
    connectionId: String,
    before: Set<String>,
    after: Set<String>,
) {
    (after - before).forEach { repository.bindIdentity(serviceId, it, connectionId) }
    (before - after).forEach { repository.unbindIdentity(serviceId, it) }
}

internal fun buildMcpCards(
    services: List<McpToolService>,
    grouped: Map<String, List<McpBackendConnection>>,
    connections: List<McpBackendConnection>,
): List<McpTcCard> = if (services.isNotEmpty()) {
    services.map { service ->
        val accounts = grouped[service.id].orEmpty()
        McpTcCard(
            key = "service:${service.id}",
            serviceId = service.id,
            name = service.displayName ?: service.name ?: accounts.firstOrNull()?.displayName() ?: "MCP 服务",
            note = service.note,
            host = mcpHost(accounts.firstOrNull()?.serverUrl),
            accounts = accounts,
        )
    }
} else {
    connections.map { connection ->
        McpTcCard(
            key = "connection:${connection.id}",
            serviceId = connection.toolServiceId,
            name = connection.displayName(),
            note = null,
            host = mcpHost(connection.serverUrl),
            accounts = listOf(connection),
        )
    }
}

internal fun mcpHost(url: String?): String =
    url?.substringAfter("://")?.substringBefore('/')?.takeIf(String::isNotBlank) ?: "App Backend"

internal fun mcpCardStatus(accounts: List<McpBackendConnection>): Pair<String, Boolean> {
    val connected = accounts.count { it.status == McpBackendConnectionStatus.Connected }
    return when {
        accounts.isEmpty() -> "还没有账号" to false
        connected == accounts.size -> "已连接" to true
        connected > 0 -> "已连接 · $connected/${accounts.size} 个账号可用" to true
        accounts.any { it.status == McpBackendConnectionStatus.AuthorizationRequired } -> "等待授权" to false
        accounts.any { it.status == McpBackendConnectionStatus.Connecting } -> "连接中" to false
        accounts.any { it.status == McpBackendConnectionStatus.Failed } -> "连接失败" to false
        else -> "状态未知" to false
    }
}

internal fun mcpAccountMeta(connection: McpBackendConnection, personas: List<PersonaProfile>): String = buildList {
    add(
        if (connection.boundIdentityIds.isEmpty()) "未绑定人格档案"
        else "人格档案 " + connection.boundIdentityIds.joinToString("、") { personaDisplayName(it, personas) },
    )
    mcpCredentialSummary(connection)?.let(::add)
    add(connection.status.label())
    add("${connection.toolCount} 个工具")
    if (!connection.enabled) add("已关闭")
}.joinToString(" · ")
