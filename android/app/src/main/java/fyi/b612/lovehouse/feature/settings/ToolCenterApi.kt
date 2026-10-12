package fyi.b612.lovehouse.feature.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch

private data class GenericApiUiState(
    val providers: List<GenericApiProvider> = emptyList(),
    val connections: List<GenericApiConnection> = emptyList(),
    val loading: Boolean = true,
    val error: String? = null,
)

@Composable
internal fun ApiTab(repository: GenericApiRepository, ui: TcUi, onOpen: (TcPage) -> Unit) {
    val scope = rememberCoroutineScope()
    var state by remember { mutableStateOf(GenericApiUiState()) }
    var openIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    val reload: suspend () -> Unit = {
        state = state.copy(loading = true, error = null)
        state = runCatching {
            GenericApiUiState(repository.providers(), repository.connections(), loading = false)
        }.getOrElse { GenericApiUiState(loading = false, error = it.message ?: "API 连接读取失败") }
    }
    LaunchedEffect(repository) { reload() }

    TcSection("我的 API 服务", "${state.connections.size} 个")
    when {
        state.loading -> TcGlass { TcEmpty("正在读取 App Backend API 连接…") }
        state.error != null -> TcGlass {
            TcEmpty(state.error ?: "API 连接读取失败")
            TcLink("重试") { scope.launch { reload() } }
        }
        state.connections.isEmpty() -> TcGlass {
            TcEmpty("还没有 API 服务。点击右上角添加，填写服务地址和密钥后即可测试。")
        }
    }

    state.connections.forEach { connection ->
        val providerName = state.providers.firstOrNull { it.id == connection.providerId }?.displayName
            ?: if (connection.providerId == "custom") "自定义 API" else connection.providerId
        TcServerCard(
            letter = connection.displayName.firstOrNull()?.uppercase() ?: "A",
            name = connection.displayName,
            sub = providerName,
            open = connection.id in openIds,
            onToggle = {
                openIds = if (connection.id in openIds) openIds - connection.id else openIds + connection.id
            },
            note = connection.baseUrl,
            status = "已保存 · ${connection.selectedModels.size} 个模型",
            statusOk = true,
            testLabel = "刷新",
            onTest = { scope.launch { reload() } },
            onRename = { onOpen(TcPage.Api("编辑 API 服务", connection.id)) },
            onNote = { onOpen(TcPage.Api("编辑 API 服务", connection.id)) },
            onDelete = {
                ui.dialog = TcDialog("删除这个 API 服务？", "连接会删除，密码库中的凭证会保留。", ok = "删除", danger = true) {
                    scope.launch {
                        runCatching { repository.delete(connection.id); reload() }
                            .onSuccess { ui.toast("连接已删除，密码库凭证已保留") }
                            .onFailure { ui.toast(it.message ?: "删除失败") }
                    }
                }
            },
            itemsLabel = "已选模型",
            addLabel = "编辑连接",
            onAdd = { onOpen(TcPage.Api("编辑 API 服务", connection.id)) },
            emptyText = if (connection.selectedModels.isEmpty()) "尚未选择模型。" else null,
        ) {
            connection.selectedModels.forEach { model ->
                TcAccount(model.displayName, model.id, checked = null) {
                    TcAccLink("查看") { onOpen(TcPage.Api("编辑 API 服务", connection.id)) }
                }
            }
        }
    }
}

@Composable
internal fun ApiDetailPage(
    page: TcPage.Api,
    repository: GenericApiRepository,
    secretVault: SecretVaultRepository,
    ui: TcUi,
    onDone: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var providers by remember(page.connectionId) { mutableStateOf<List<GenericApiProvider>>(emptyList()) }
    var credentials by remember(page.connectionId) { mutableStateOf<List<SecretCredential>>(emptyList()) }
    var existing by remember(page.connectionId) { mutableStateOf<GenericApiConnection?>(null) }
    var loading by remember(page.connectionId) { mutableStateOf(true) }
    var loadError by remember(page.connectionId) { mutableStateOf<String?>(null) }
    var providerIndex by remember(page.connectionId) { mutableIntStateOf(0) }
    var name by remember(page.connectionId) { mutableStateOf("") }
    var baseUrl by remember(page.connectionId) { mutableStateOf("") }
    var customProtocol by remember(page.connectionId) { mutableIntStateOf(0) }
    var credentialMode by remember(page.connectionId) { mutableIntStateOf(0) }
    var credentialType by remember(page.connectionId) { mutableIntStateOf(0) }
    var credentialIndex by remember(page.connectionId) { mutableIntStateOf(0) }
    var secret by remember(page.connectionId) { mutableStateOf("") }
    var workingCredentialId by remember(page.connectionId) { mutableStateOf<String?>(null) }
    var discoveredModels by remember(page.connectionId) { mutableStateOf<List<GenericApiModel>>(emptyList()) }
    var selectedModelIds by remember(page.connectionId) { mutableStateOf<Set<String>>(emptySet()) }
    var manualModelId by remember(page.connectionId) { mutableStateOf("") }
    var testStatus by remember(page.connectionId) { mutableStateOf<String?>(null) }
    var tested by remember(page.connectionId) { mutableStateOf(false) }
    var busy by remember(page.connectionId) { mutableStateOf(false) }

    LaunchedEffect(repository, secretVault, page.connectionId) {
        loading = true
        loadError = null
        runCatching {
            providers = repository.providers()
            credentials = secretVault.list()
            existing = page.connectionId?.let { repository.connection(it) }
            val loaded = existing
            val providerIds = providers.map { it.id } + "custom"
            providerIndex = loaded?.let { providerIds.indexOf(it.providerId).takeIf { index -> index >= 0 } } ?: 0
            name = loaded?.displayName.orEmpty()
            baseUrl = loaded?.baseUrl ?: providers.firstOrNull()?.defaultBaseUrl.orEmpty()
            customProtocol = if (loaded?.discoveryKind == "MANUAL") 1 else 0
            workingCredentialId = loaded?.credentialId
            credentialIndex = credentials.indexOfFirst { it.id == loaded?.credentialId }.coerceAtLeast(0)
            credentialMode = if (loaded == null) 0 else 1
            discoveredModels = loaded?.selectedModels.orEmpty()
            selectedModelIds = loaded?.selectedModels?.mapTo(linkedSetOf()) { it.id }.orEmpty()
        }.onFailure { loadError = it.message ?: "API 连接读取失败" }
        loading = false
    }

    if (loading) { TcGlass { TcEmpty("正在读取 API 服务与密码库…") }; return }
    loadError?.let { TcGlass { TcEmpty(it) }; return }

    val providerOptions = providers.map { it.displayName } + "自定义"
    val selectedProvider = providers.getOrNull(providerIndex)
    val providerId = selectedProvider?.id ?: "custom"
    val discoveryKind = selectedProvider?.discoveryKind ?: if (customProtocol == 0) "OPENAI_COMPATIBLE" else "MANUAL"
    val usableCredentials = credentials.filter { it.credentialType == "api_key" || it.credentialType == "bearer_token" }

    suspend fun resolveCredential(): String {
        val type = if (credentialType == 0) "api_key" else "bearer_token"
        val id = resolveGenericApiCredential(
            vault = secretVault,
            bindExisting = credentialMode == 1,
            selectedCredentialId = usableCredentials.getOrNull(credentialIndex)?.id,
            currentCredentialId = workingCredentialId,
            secret = secret,
            credentialType = type,
            displayName = name.ifBlank { selectedProvider?.displayName ?: "API" },
        )
        secret = ""
        workingCredentialId = id
        credentials = secretVault.list()
        return id
    }

    fun draft(credentialId: String) = GenericApiDraft(
        providerId = providerId,
        displayName = name,
        baseUrl = baseUrl,
        discoveryKind = discoveryKind,
        credentialId = credentialId,
        selectedModels = discoveredModels.filter { it.id in selectedModelIds },
    )

    TcFormCard("服务信息") {
        TcField("服务商") {
            TcSelect(providerOptions, providerIndex.coerceIn(providerOptions.indices), "选择服务商") { index ->
                providerIndex = index
                providers.getOrNull(index)?.let {
                    if (existing == null) baseUrl = it.defaultBaseUrl
                    if (name.isBlank()) name = it.displayName
                }
                tested = false
            }
        }
        TcField("名称") { TcInput(name, { name = it }) }
        TcField("Base URL") { TcInput(baseUrl, { baseUrl = it; tested = false }, placeholder = "https://") }
        if (selectedProvider == null) {
            TcField("模型发现", last = true) {
                TcPills(listOf("OpenAI-compatible", "手动填写"), setOf(customProtocol)) { customProtocol = it; tested = false }
            }
        } else TcField("模型发现", last = true) { TcHint("${selectedProvider.displayName} · ${selectedProvider.discoveryKind}") }
    }

    TcFormCard("密钥") {
        TcField("使用方式") {
            TcPills(listOf(if (existing == null) "输入新 Secret" else "替换当前 Secret", "选择密码库凭证"), setOf(credentialMode)) {
                credentialMode = it; tested = false
            }
        }
        if (credentialMode == 0) {
            TcField("鉴权方式") { TcPills(listOf("API Key", "Bearer Token"), setOf(credentialType)) { credentialType = it } }
            TcField("Key / Token", last = true) {
                TcInput(secret, { secret = it; tested = false }, secret = true)
                TcHint(if (workingCredentialId != null) "已配置 · $FIXED_SECRET_MASK；留空会继续使用当前密钥。" else "Secret 只提交给 App Backend Vault，Android 不持久化。")
            }
        } else TcField("密码库凭证", last = true) {
            if (usableCredentials.isEmpty()) TcHint("密码库中暂无 API Key / Bearer Token。")
            else TcSelect(usableCredentials.map { it.displayName }, credentialIndex.coerceIn(usableCredentials.indices), "选择凭证") {
                credentialIndex = it; tested = false
            }
        }
    }

    TcFormCard("测试与模型") {
        TcInlineStatus("测试连接", testStatus ?: "尚未测试", ok = tested, busy = busy) {
            when {
                name.isBlank() -> ui.toast("请填写名称")
                baseUrl.isBlank() -> ui.toast("请填写 Base URL")
                else -> scope.launch {
                    busy = true
                    runCatching { repository.test(draft(resolveCredential())) }
                        .onSuccess { result ->
                            tested = true
                            if (result.status == "CONNECTED") {
                                discoveredModels = result.models
                                selectedModelIds = selectedModelIds.intersect(result.models.mapTo(linkedSetOf()) { it.id })
                                testStatus = "连接成功 · ${result.models.size} 个模型"
                            } else testStatus = "当前服务需要手动填写模型 ID"
                        }.onFailure { testStatus = it.message ?: "测试失败" }
                    busy = false
                }
            }
        }
        if (discoveredModels.isNotEmpty()) TcField("选择模型", last = discoveryKind != "MANUAL") {
            discoveredModels.forEach { model ->
                TcAccount(model.displayName, model.id, checked = model.id in selectedModelIds, onToggle = { checked ->
                    selectedModelIds = if (checked) selectedModelIds + model.id else selectedModelIds - model.id
                }) {}
            }
        }
        if (discoveryKind == "MANUAL") TcField("手动模型 ID", last = true) {
            TcInput(manualModelId, { manualModelId = it }, placeholder = "例如：provider/model-name")
            TcLink("添加模型") {
                val id = manualModelId.trim()
                if (id.isBlank()) ui.toast("请填写模型 ID") else {
                    if (discoveredModels.none { it.id == id }) discoveredModels = discoveredModels + GenericApiModel(id, id)
                    selectedModelIds = selectedModelIds + id
                    manualModelId = ""
                }
            }
        }
    }

    TcSaveButton("保存", enabled = !busy) {
        when {
            name.isBlank() -> ui.toast("请填写名称")
            baseUrl.isBlank() -> ui.toast("请填写 Base URL")
            !tested -> ui.toast("请先测试连接")
            selectedModelIds.isEmpty() -> ui.toast("请至少选择或添加一个模型")
            else -> scope.launch {
                busy = true
                runCatching {
                    val input = draft(resolveCredential())
                    val saved = existing?.let { repository.update(it.id, input) } ?: repository.create(input)
                    repository.connection(saved.id)
                }.onSuccess { secret = ""; ui.toast("API 服务已保存"); onDone() }
                    .onFailure { ui.toast(it.message ?: "保存失败") }
                busy = false
            }
        }
    }
    TcLink("取消") { secret = ""; onDone() }
}
