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
            sub = service?.displayName ?: connection.serviceType ?: connection.serviceId ?: "自定义 API",
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
    var services by remember(page.connectionId) { mutableStateOf<List<ApiServiceDescriptor>>(emptyList()) }
    var connections by remember(page.connectionId) { mutableStateOf<List<ApiBackendConnection>>(emptyList()) }
    var credentials by remember(page.connectionId) { mutableStateOf<List<SecretCredential>>(emptyList()) }
    var loading by remember(page.connectionId) { mutableStateOf(true) }
    var loadError by remember(page.connectionId) { mutableStateOf<String?>(null) }

    LaunchedEffect(repository, secretVault, page.connectionId) {
        loading = true
        loadError = null
        runCatching {
            services = repository.services()
            connections = repository.connections()
            credentials = secretVault.list()
            page.connectionId?.let { id ->
                connections.firstOrNull { it.id == id }
                    ?: throw ApiConnectionException("这个 API 连接已不存在，请返回")
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
    if (existing == null || existing.serviceId == null) {
        CustomApiServiceForm(
            existing = existing,
            credentials = credentials,
            repository = repository,
            ui = ui,
            onDone = onDone,
        )
        return
    }
    RegisteredApiConnectionForm(
        existing = existing,
        service = services.firstOrNull { it.serviceId == existing.serviceId },
        credentials = credentials,
        repository = repository,
        ui = ui,
        onDone = onDone,
    )
}

@Composable
private fun RegisteredApiConnectionForm(
    existing: ApiBackendConnection,
    service: ApiServiceDescriptor?,
    credentials: List<SecretCredential>,
    repository: ApiConnectionRepository,
    ui: TcUi,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember(existing.id) { mutableStateOf(existing.displayName) }
    var note by remember(existing.id) { mutableStateOf(existing.note.orEmpty()) }
    var enabled by remember(existing.id) { mutableStateOf(existing.enabled) }
    val compatibleCredentials = credentials.filter { it.credentialType in service?.acceptedCredentialTypes.orEmpty() }
    var credentialIndex by remember(existing.id, compatibleCredentials) {
        mutableIntStateOf(compatibleCredentials.indexOfFirst { it.id == existing.credentialId }.coerceAtLeast(0))
    }
    var credentialEdited by remember(existing.id) { mutableStateOf(false) }
    var busy by remember(existing.id) { mutableStateOf(false) }

    TcFormCard("连接信息") {
        TcField("API 服务") { TcHint(service?.displayName ?: existing.serviceId.orEmpty()) }
        TcField("名称") { TcInput(name, { name = it }) }
        TcField("备注") { TcInput(note, { note = it }, multiline = true) }
        TcField("启用", last = true) { TcSwitch(enabled, "启用 API 连接") { enabled = it } }
    }
    TcFormCard("凭证") {
        TcField("当前绑定", last = compatibleCredentials.isEmpty()) {
            TcHint(if (existing.credentialId == null) "无需凭证" else "密码库凭证 · $FIXED_SECRET_MASK")
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
            }
        }
    }
    TcSaveButton("保存", enabled = !busy) {
        when {
            name.isBlank() -> ui.toast("请填写连接名称")
            service == null -> ui.toast("该预设 API 服务当前不可用")
            else -> {
                busy = true
                scope.launch {
                    val chosenCredential = compatibleCredentials.getOrNull(credentialIndex)?.id
                    runCatching {
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

@Composable
private fun CustomApiServiceForm(
    existing: ApiBackendConnection?,
    credentials: List<SecretCredential>,
    repository: ApiConnectionRepository,
    ui: TcUi,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember(existing?.id) { mutableStateOf(existing?.displayName.orEmpty()) }
    var serviceType by remember(existing?.id) { mutableStateOf(existing?.serviceType.orEmpty()) }
    var endpoint by remember(existing?.id) { mutableStateOf(existing?.baseUrl.orEmpty()) }
    var note by remember(existing?.id) { mutableStateOf(existing?.note.orEmpty()) }
    var credentialMode by remember(existing?.id) { mutableIntStateOf(if (existing == null) 0 else 1) }
    var credentialType by remember(existing?.id) { mutableIntStateOf(0) }
    var secret by remember { mutableStateOf("") }
    val compatibleCredentials = credentials.filter { it.credentialType == "api_key" || it.credentialType == "bearer_token" }
    var credentialIndex by remember(existing?.id, compatibleCredentials) {
        mutableIntStateOf(compatibleCredentials.indexOfFirst { it.id == existing?.credentialId }.coerceAtLeast(0))
    }
    var credentialEdited by remember(existing?.id) { mutableStateOf(false) }
    var busy by remember(existing?.id) { mutableStateOf(false) }
    val idempotencyKey = remember(existing?.id) { java.util.UUID.randomUUID().toString() }

    TcFormCard("连接信息") {
        TcField("名称") { TcInput(name, { name = it }) }
        TcField("服务类型") { TcInput(serviceType, { serviceType = it }, placeholder = "例如：天气、翻译、语音") }
        TcField("备注") {
            TcInput(note, { note = it }, placeholder = "这个服务是做什么的，给自己看", multiline = true)
        }
        TcField("Base URL（原样保存）") {
            TcInput(endpoint, { endpoint = it }, placeholder = "https://")
        }
    }

    TcFormCard("密钥") {
        if (existing == null) {
            TcField("使用方式") {
                TcPills(listOf("直接输入新 Secret", "使用密码库已有凭证"), setOf(credentialMode)) {
                    credentialMode = it
                }
            }
        } else {
            TcField("当前凭证") {
                TcHint(if (existing.credentialId == null) "未绑定" else "密码库凭证 · $FIXED_SECRET_MASK")
            }
        }
        if (credentialMode == 0) {
            TcField("鉴权方式") {
                TcPills(listOf("API Key", "Bearer Token"), setOf(credentialType)) { credentialType = it }
            }
            TcField("Key / Token", last = true) {
                TcInput(secret, { secret = it }, secret = true)
                TcHint("只提交一次给 App Backend，并自动安全保存到密码库；Android 不会持久化。")
            }
        } else {
            TcField(if (existing == null) "密码库凭证" else "更换凭证", last = true) {
                if (compatibleCredentials.isEmpty()) {
                    TcHint("密码库中暂无可选凭证。")
                } else {
                    TcSelect(
                        compatibleCredentials.map { it.displayName },
                        credentialIndex.coerceIn(compatibleCredentials.indices),
                        "选择密码库凭证",
                    ) {
                        credentialIndex = it
                        credentialEdited = true
                    }
                }
            }
        }
    }

    TcSaveButton("保存", enabled = !busy) {
        when {
            name.isBlank() -> ui.toast("请填写名称")
            serviceType.isBlank() -> ui.toast("请填写服务类型")
            endpoint.isBlank() -> ui.toast("请填写 Base URL")
            existing == null && credentialMode == 0 && secret.isBlank() -> ui.toast("请输入 Key / Token")
            existing == null && credentialMode == 1 && compatibleCredentials.isEmpty() ->
                ui.toast("请选择密码库已有凭证，或直接输入新 Secret")
            else -> {
                busy = true
                scope.launch {
                    runCatching {
                        if (existing == null) {
                            val binding = if (credentialMode == 0) {
                                ApiCredentialBinding.NewSecret(
                                    displayName = "$name 密钥",
                                    credentialType = if (credentialType == 0) "api_key" else "bearer_token",
                                    secret = secret,
                                    metadata = SecretCredentialMetadata(serviceName = name),
                                )
                            } else {
                                ApiCredentialBinding.Existing(
                                    compatibleCredentials[credentialIndex.coerceIn(compatibleCredentials.indices)].id,
                                )
                            }
                            repository.create(
                                ApiConnectionCreate(
                                    serviceType = serviceType,
                                    baseUrl = endpoint,
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
                                    serviceType = serviceType,
                                    baseUrl = endpoint,
                                    note = note,
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

private fun apiConnectionStatusLabel(connection: ApiBackendConnection): String = when {
    !connection.enabled || connection.status == "disabled" -> "已关闭"
    connection.status == "active" -> "已连接"
    else -> connection.status
}
