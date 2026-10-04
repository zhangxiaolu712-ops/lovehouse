package fyi.b612.lovehouse.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch

private data class ApiCatalogState(
    val services: List<ApiServiceDescriptor> = emptyList(),
    val connections: List<ApiBackendConnection> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

@Composable
internal fun ApiTab(repository: ApiConnectionRepository, ui: TcUi, onOpen: (TcPage) -> Unit) {
    val scope = rememberCoroutineScope()
    var catalog by remember { mutableStateOf(ApiCatalogState()) }
    var enabledShell by remember { mutableStateOf(true) }
    var openOverride by remember { mutableStateOf<Set<String>?>(null) }
    val open = openOverride ?: setOfNotNull(catalog.connections.firstOrNull()?.id)
    val reload: suspend () -> Unit = {
        catalog = catalog.copy(loading = true, error = null)
        catalog = runCatching {
            ApiCatalogState(repository.services(), repository.connections(), loading = false)
        }.getOrElse { ApiCatalogState(loading = false, error = it.message ?: "API 服务读取失败") }
    }
    LaunchedEffect(repository) { reload() }

    TcGlass {
        TcRow(first = true) {
            TcRowText("启用 API 工具", "语音、地图等外部服务，统一从这里接入", Modifier.weight(1f))
            TcSwitch(enabledShell, "启用 API 工具") {
                enabledShell = it
                ui.toast("该总开关尚未接入后端，未保存")
            }
        }
    }
    TcSection("API 服务", "${catalog.services.size} 个可接入 · ${catalog.connections.size} 个连接")
    when {
        catalog.loading -> TcGlass { TcEmpty("正在读取 App Backend API 服务…") }
        catalog.error != null -> TcGlass {
            TcEmpty(catalog.error ?: "API 服务读取失败")
            TcLink("重试") { scope.launch { reload() } }
        }
        catalog.services.isEmpty() && catalog.connections.isEmpty() -> TcGlass {
            TcEmpty(apiEmptyRegistryMessage(catalog.services))
        }
    }

    catalog.connections.forEach { connection ->
        val service = catalog.services.firstOrNull { it.serviceId == connection.serviceId }
        val mutate: suspend (ApiConnectionUpdate, String) -> Unit = { update, done ->
            runCatching { repository.update(connection.id, update); reload() }
                .onSuccess { ui.toast(done) }
                .onFailure { ui.toast(it.message ?: "保存失败") }
        }
        TcServerCard(
            letter = connection.displayName.firstOrNull()?.uppercase() ?: "A",
            name = connection.displayName,
            sub = service?.displayName ?: connection.serviceId,
            open = connection.id in open,
            onToggle = {
                openOverride = if (connection.id in open) open - connection.id else open + connection.id
            },
            note = connection.note,
            status = apiConnectionStatusLabel(connection),
            statusOk = connection.enabled && connection.status == "active",
            testLabel = "刷新",
            onTest = { scope.launch { reload() } },
            onRename = {
                ui.dialog = TcDialog("重命名", "修改 App Backend 中的连接显示名称。", input = connection.displayName, ok = "保存") { result ->
                    if (result.text.isNotBlank()) scope.launch {
                        mutate(ApiConnectionUpdate(displayName = result.text), "已保存")
                    }
                }
            },
            onNote = {
                ui.dialog = TcDialog("备注", "写给自己看的说明。", input = connection.note.orEmpty(), ok = "保存") { result ->
                    scope.launch { mutate(ApiConnectionUpdate(note = result.text), "备注已保存") }
                }
            },
            onDelete = {
                ui.dialog = TcDialog(
                    "删除这个 API 连接？",
                    "连接会删除，密码库中的凭证会保留。",
                    ok = "删除",
                    danger = true,
                ) {
                    scope.launch {
                        runCatching { repository.delete(connection.id); reload() }
                            .onSuccess { ui.toast("连接已删除，密码库凭证已保留") }
                            .onFailure { ui.toast(it.message ?: "删除失败") }
                    }
                }
            },
            itemsLabel = "连接凭证",
            addLabel = "编辑连接",
            onAdd = { onOpen(TcPage.Api("编辑 API 连接", connection.id)) },
            emptyText = null,
        ) {
            TcAccount(
                name = if (connection.credentialId == null) "无需凭证" else "密码库凭证",
                meta = "由 App Backend 管理 · ${if (connection.enabled) "已开启" else "已关闭"}",
                checked = connection.enabled,
                onToggle = { on -> scope.launch { mutate(ApiConnectionUpdate(enabled = on), if (on) "已开启" else "已关闭") } },
            ) {
                TcAccLink("编辑") { onOpen(TcPage.Api("编辑 API 连接", connection.id)) }
            }
        }
    }
}

@Composable
internal fun ApiDetailPage(
    page: TcPage.Api,
    repository: ApiConnectionRepository,
    secretVault: SecretVaultRepository,
    ui: TcUi,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var services by remember(page.connectionId) { mutableStateOf<List<ApiServiceDescriptor>>(emptyList()) }
    var connections by remember(page.connectionId) { mutableStateOf<List<ApiBackendConnection>>(emptyList()) }
    var credentials by remember(page.connectionId) { mutableStateOf<List<SecretCredential>>(emptyList()) }
    var loading by remember(page.connectionId) { mutableStateOf(true) }
    var loadError by remember(page.connectionId) { mutableStateOf<String?>(null) }
    var name by remember(page.connectionId) { mutableStateOf("") }
    var note by remember(page.connectionId) { mutableStateOf("") }
    var enabled by remember(page.connectionId) { mutableStateOf(true) }
    var serviceIndex by remember(page.connectionId) { mutableIntStateOf(0) }
    var credentialMode by remember(page.connectionId) { mutableIntStateOf(0) }
    var credentialIndex by remember(page.connectionId) { mutableIntStateOf(0) }
    var credentialEdited by remember(page.connectionId) { mutableStateOf(false) }
    var credentialTypeIndex by remember(page.connectionId) { mutableIntStateOf(0) }
    var credentialName by remember(page.connectionId) { mutableStateOf("") }
    var secret by remember(page.connectionId) { mutableStateOf("") }
    var busy by remember(page.connectionId) { mutableStateOf(false) }
    val idempotencyKey = remember(page.connectionId) { java.util.UUID.randomUUID().toString() }

    LaunchedEffect(repository, secretVault, page.connectionId) {
        loading = true
        loadError = null
        runCatching {
            services = repository.services()
            connections = repository.connections()
            credentials = secretVault.list()
            page.connectionId?.let { id ->
                val current = connections.firstOrNull { it.id == id }
                    ?: throw ApiConnectionException("这个 API 连接已不存在，请返回")
                name = current.displayName
                note = current.note.orEmpty()
                enabled = current.enabled
                serviceIndex = services.indexOfFirst { it.serviceId == current.serviceId }.coerceAtLeast(0)
                credentialMode = 1
                credentialIndex = credentials.indexOfFirst { it.id == current.credentialId }.coerceAtLeast(0)
            }
        }.onFailure { loadError = it.message ?: "API 连接读取失败" }
        loading = false
    }

    if (loading) {
        TcGlass { TcEmpty("正在读取 App Backend API 服务…") }
        return
    }
    loadError?.let {
        TcGlass { TcEmpty(it) }
        return
    }
    val existing = page.connectionId?.let { id -> connections.firstOrNull { it.id == id } }
    val service = existing?.let { value -> services.firstOrNull { it.serviceId == value.serviceId } }
        ?: services.getOrNull(serviceIndex)
    if (existing == null && services.isEmpty()) {
        TcGlass { TcEmpty(apiEmptyRegistryMessage(services)) }
        return
    }

    TcFormCard("连接信息") {
        if (existing == null) {
            TcField("API 服务") {
                TcSelect(services.map { it.displayName }, serviceIndex, "选择 API 服务") { index ->
                    serviceIndex = index
                    credentialTypeIndex = 0
                    credentialIndex = 0
                    if (name.isBlank()) name = services.getOrNull(index)?.displayName.orEmpty()
                }
                service?.let { TcHint("${it.serviceKind.label()} · ${it.serviceId}") }
            }
        } else {
            TcField("API 服务") { TcHint(service?.displayName ?: existing.serviceId) }
        }
        TcField("名称") { TcInput(name, { name = it }) }
        TcField("备注", last = existing == null) { TcInput(note, { note = it }, multiline = true) }
        if (existing != null) {
            TcField("启用", last = true) { TcSwitch(enabled, "启用 API 连接") { enabled = it } }
        }
    }

    val acceptedCredentials = service?.acceptedCredentialTypes.orEmpty()
    val compatibleCredentials = credentials.filter { it.credentialType in acceptedCredentials }
    if (existing == null) {
        val modes = buildList {
            add("直接输入新 Secret")
            add("使用密码库已有凭证")
            if (service?.credentialRequired == false) add("无需凭证")
        }
        TcFormCard("凭证") {
            TcField("使用方式") { TcPills(modes, setOf(credentialMode.coerceIn(modes.indices))) { credentialMode = it } }
            when (credentialMode) {
                0 -> {
                    TcField("凭证类型") {
                        if (acceptedCredentials.isEmpty()) TcHint("该服务没有声明可用凭证类型") else {
                            TcSelect(
                                acceptedCredentials.map(::credentialTypeLabel),
                                credentialTypeIndex.coerceIn(acceptedCredentials.indices),
                                "选择凭证类型",
                            ) { credentialTypeIndex = it }
                        }
                    }
                    TcField("密码库名称") {
                        TcInput(credentialName, { credentialName = it }, placeholder = "例如：${service?.displayName.orEmpty()} 密钥")
                    }
                    TcField("Secret", last = true) {
                        TcInput(secret, { secret = it }, secret = true)
                        TcHint("只提交一次给 App Backend；成功或取消后不会保存在 Android。")
                    }
                }
                1 -> TcField("密码库凭证", last = true) {
                    if (compatibleCredentials.isEmpty()) {
                        TcHint("密码库中没有适用于该服务的凭证。也可以直接在这里输入新 Secret。")
                    } else {
                        TcSelect(
                            compatibleCredentials.map { it.displayName },
                            credentialIndex.coerceIn(compatibleCredentials.indices),
                            "选择密码库凭证",
                        ) { credentialIndex = it }
                    }
                }
                else -> TcField("凭证", last = true) { TcHint("这个服务允许无凭证连接。") }
            }
        }
        TcFormCard("服务配置") {
            TcField("配置合同 v${service?.configContractVersion ?: 1}", last = true) {
                TcHint("当前服务目录尚未声明可编辑字段；Android 不会猜测 endpoint 或供应商参数。")
            }
        }
    } else {
        TcFormCard("凭证") {
            TcField("当前绑定", last = compatibleCredentials.isEmpty()) {
                TcHint(if (existing.credentialId == null) "无需凭证" else "密码库凭证 · ${FIXED_SECRET_MASK}")
            }
            if (compatibleCredentials.isNotEmpty()) {
                TcField("改用已有凭证", last = true) {
                    TcSelect(
                        compatibleCredentials.map { it.displayName },
                        credentialIndex.coerceIn(compatibleCredentials.indices),
                        "选择密码库凭证",
                    ) {
                        credentialIndex = it
                        credentialEdited = true
                    }
                    TcHint("编辑连接只允许绑定已有凭证；新 Secret 请在新建连接时一次提交。")
                }
            }
        }
    }

    TcSaveButton("保存", enabled = !busy) {
        val selectedService = service
        when {
            selectedService == null -> ui.toast("请选择 API 服务")
            name.isBlank() -> ui.toast("请填写连接名称")
            existing == null && credentialMode == 0 && acceptedCredentials.isEmpty() ->
                ui.toast("该服务没有声明可用凭证类型")
            existing == null && credentialMode == 0 && secret.isBlank() -> ui.toast("请输入 Secret")
            existing == null && credentialMode == 1 && compatibleCredentials.isEmpty() ->
                ui.toast("请选择密码库已有凭证，或直接输入新 Secret")
            else -> {
                busy = true
                scope.launch {
                    runCatching {
                        if (existing == null) {
                            val binding = when (credentialMode) {
                                0 -> ApiCredentialBinding.NewSecret(
                                    displayName = credentialName.ifBlank { "$name 密钥" },
                                    credentialType = acceptedCredentials[credentialTypeIndex.coerceIn(acceptedCredentials.indices)],
                                    secret = secret,
                                    metadata = SecretCredentialMetadata(serviceName = selectedService.displayName),
                                )
                                1 -> ApiCredentialBinding.Existing(
                                    compatibleCredentials[credentialIndex.coerceIn(compatibleCredentials.indices)].id,
                                )
                                else -> ApiCredentialBinding.None
                            }
                            repository.create(
                                ApiConnectionCreate(
                                    serviceId = selectedService.serviceId,
                                    displayName = name,
                                    note = note,
                                    credential = binding,
                                    idempotencyKey = idempotencyKey,
                                ),
                            )
                            secret = ""
                        } else {
                            val chosenCredential = compatibleCredentials.getOrNull(credentialIndex)?.id
                            repository.update(
                                existing.id,
                                ApiConnectionUpdate(
                                    displayName = name,
                                    note = note,
                                    enabled = enabled,
                                    credentialId = chosenCredential.takeIf { credentialEdited },
                                    updateCredential = credentialEdited && chosenCredential != existing.credentialId,
                                ),
                            )
                        }
                    }.onSuccess {
                        ui.toast("已保存")
                        onDone()
                    }.onFailure { ui.toast(it.message ?: "保存失败") }
                    busy = false
                }
            }
        }
    }
}

internal fun apiEmptyRegistryMessage(services: List<ApiServiceDescriptor>): String =
    if (services.isEmpty()) "暂无可接入 API 服务。服务目录由 App Backend 提供。" else "还没有 API 连接。"

private fun ApiServiceKind.label(): String = when (this) {
    ApiServiceKind.Tool -> "工具服务"
    ApiServiceKind.Voice -> "语音服务"
}

private fun credentialTypeLabel(type: String): String = when (type) {
    "api_key" -> "API Key"
    "bearer_token" -> "Bearer Token"
    else -> type
}

private fun apiConnectionStatusLabel(connection: ApiBackendConnection): String = when {
    !connection.enabled || connection.status == "disabled" -> "已关闭"
    connection.status == "active" -> "已连接"
    else -> connection.status
}
