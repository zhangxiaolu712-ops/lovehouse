package fyi.b612.lovehouse.feature.settings

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch

private const val MAX_OPENAPI_UPLOAD_BYTES = 1_048_576

@Composable
internal fun ApiCapabilityCatalogSection(
    connection: ApiBackendConnection,
    repository: ApiConnectionRepository,
    ui: TcUi,
) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var catalog by remember(connection.id) { mutableStateOf<ApiCapabilityCatalog?>(null) }
    var preview by remember(connection.id) { mutableStateOf<ApiCapabilityPreview?>(null) }
    var selectedIds by remember(connection.id) { mutableStateOf<Set<String>>(emptySet()) }
    var loading by remember(connection.id) { mutableStateOf(true) }
    var error by remember(connection.id) { mutableStateOf<String?>(null) }
    var busy by remember(connection.id) { mutableStateOf(false) }
    var sourceMode by remember(connection.id) { mutableIntStateOf(if (connection.serviceId == null) 1 else 0) }
    var sourceUrl by remember(connection.id) { mutableStateOf("") }
    var apiKeyHeader by remember(connection.id) { mutableStateOf("") }
    var uploadedName by remember(connection.id) { mutableStateOf<String?>(null) }
    var uploadedJson by remember(connection.id) { mutableStateOf<String?>(null) }
    var query by remember(connection.id) { mutableStateOf("") }
    var filter by remember(connection.id) { mutableIntStateOf(0) }
    var expandedIds by remember(connection.id) { mutableStateOf<Set<String>>(emptySet()) }
    var showManual by remember(connection.id) { mutableStateOf(false) }

    val load: suspend () -> Unit = {
        loading = true
        error = null
        runCatching { repository.capabilityCatalog(connection.id) }
            .onSuccess { loaded ->
                catalog = loaded
                selectedIds = loaded.capabilities.filter(ApiCapability::selected).mapTo(linkedSetOf()) { it.id }
            }
            .onFailure { error = it.message ?: "能力目录读取失败" }
        loading = false
    }
    LaunchedEffect(repository, connection.id) { load() }

    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        runCatching {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: throw IllegalArgumentException("无法读取文件")
            require(bytes.size <= MAX_OPENAPI_UPLOAD_BYTES) { "OpenAPI JSON 不能超过 1 MiB" }
            val text = bytes.toString(Charsets.UTF_8)
            org.json.JSONObject(text)
            uploadedJson = text
            uploadedName = uri.lastPathSegment?.substringAfterLast('/') ?: "openapi.json"
        }.onFailure { ui.toast(it.message ?: "OpenAPI JSON 读取失败") }
    }

    TcSection("能力目录", catalog?.let { "${it.capabilities.size} 项 · 第 ${it.revision} 版" } ?: "")
    TcFormCard("发现能力") {
        TcField("描述来源") {
            TcPills(listOf("官方来源", "描述 URL", "上传 JSON"), setOf(sourceMode)) {
                sourceMode = it
                preview = null
            }
        }
        when (sourceMode) {
            1 -> {
                TcField("OpenAPI 3.0 / 3.1 JSON URL") {
                    TcInput(sourceUrl, { sourceUrl = it }, placeholder = "https://…/openapi.json")
                }
                TcField("API Key Header（仅受保护描述需要）") {
                    TcInput(apiKeyHeader, { apiKeyHeader = it }, placeholder = "例如 X-Api-Key")
                }
            }
            2 -> TcField("JSON 文件") {
                TcHint(uploadedName ?: "尚未选择文件；原始描述不会持久化。")
                TcLink(if (uploadedJson == null) "选择 JSON 文件" else "重新选择") {
                    filePicker.launch("application/json")
                }
            }
            else -> TcField("官方来源") {
                TcHint("只读取预设服务明确声明的官方描述地址，不猜测接口路径。")
            }
        }
        error?.let { TcField("状态", last = true) { TcHint(it) } }
        TcSaveButton(if ((catalog?.revision ?: 0) == 0) "发现能力" else "重新发现", enabled = !busy && !loading) {
            val source = when (sourceMode) {
                0 -> ApiDiscoverySource.Preset
                1 -> sourceUrl.takeIf(String::isNotBlank)?.let { ApiDiscoverySource.Url(it, apiKeyHeader.ifBlank { null }) }
                else -> uploadedJson?.let { ApiDiscoverySource.JsonDocument(uploadedName ?: "openapi.json", it) }
            }
            if (source == null) {
                ui.toast(if (sourceMode == 1) "请填写描述 URL" else "请选择 OpenAPI JSON 文件")
            } else {
                busy = true
                scope.launch {
                    runCatching { repository.previewCapabilities(connection.id, source) }
                        .onSuccess { found ->
                            preview = found
                            selectedIds = buildSet {
                                catalog?.capabilities?.filter { it.source == ApiCapabilitySource.Custom && it.selected }
                                    ?.forEach { add(it.id) }
                                found.capabilities.filter(ApiCapability::selected).forEach { add(it.id) }
                            }
                            error = null
                            ui.toast("已发现 ${found.capabilities.size} 项能力，请预览后保存")
                        }
                        .onFailure {
                            error = it.message ?: "发现失败，原目录未改变"
                            ui.toast(error ?: "发现失败")
                        }
                    busy = false
                }
            }
        }
        TcLink(if (showManual) "收起手动添加" else "无法发现？手动添加能力") { showManual = !showManual }
    }

    if (showManual) ManualApiCapabilityForm(connection.id, repository, ui) { updated ->
        catalog = updated
        selectedIds = updated.capabilities.filter(ApiCapability::selected).mapTo(linkedSetOf()) { it.id }
        showManual = false
    }

    when {
        loading -> TcGlass { TcEmpty("正在读取能力目录…") }
        catalog == null -> TcGlass { TcEmpty(error ?: "能力目录暂不可用") }
        preview != null -> {
            val found = preview ?: return
            val change = found.changes
            TcSection("发现预览", "+${change.added.size} · ~${change.changed.size} · -${change.removed.size}")
            CapabilityBrowser(
                capabilities = found.capabilities,
                selectedIds = selectedIds,
                query = query,
                filter = filter,
                expandedIds = expandedIds,
                onQuery = { query = it },
                onFilter = { filter = it },
                onExpanded = { id -> expandedIds = expandedIds.toggle(id) },
                onSelected = { id, selected -> selectedIds = selectedIds.withSelection(id, selected) },
            )
            TcSaveButton("保存完整目录与选择", enabled = !busy) {
                busy = true
                scope.launch {
                    runCatching { repository.commitCapabilities(connection.id, found.previewId, selectedIds) }
                        .onSuccess { saved ->
                            catalog = saved
                            preview = null
                            selectedIds = saved.capabilities.filter(ApiCapability::selected).mapTo(linkedSetOf()) { it.id }
                            ui.toast("完整能力目录已保存")
                        }
                        .onFailure { ui.toast(it.message ?: "能力目录保存失败") }
                    busy = false
                }
            }
            TcLink("放弃本次预览") { preview = null }
        }
        else -> {
            val saved = catalog ?: return
            if (saved.capabilities.isEmpty()) {
                TcGlass { TcEmpty("尚无能力。可以发现官方能力，或手动添加。") }
            } else {
                saved.lastDiscoverySummary?.let { TcHint("最近发现：$it") }
                CapabilityBrowser(
                    capabilities = saved.capabilities,
                    selectedIds = selectedIds,
                    query = query,
                    filter = filter,
                    expandedIds = expandedIds,
                    onQuery = { query = it },
                    onFilter = { filter = it },
                    onExpanded = { id -> expandedIds = expandedIds.toggle(id) },
                    onSelected = { id, selected -> selectedIds = selectedIds.withSelection(id, selected) },
                )
                TcSaveButton("保存当前选择", enabled = !busy) {
                    busy = true
                    scope.launch {
                        runCatching { repository.selectCapabilities(connection.id, selectedIds) }
                            .onSuccess { authoritative ->
                                catalog = authoritative
                                selectedIds = authoritative.capabilities.filter(ApiCapability::selected)
                                    .mapTo(linkedSetOf()) { it.id }
                                ui.toast("能力选择已保存")
                            }
                            .onFailure { ui.toast(it.message ?: "选择保存失败") }
                        busy = false
                    }
                }
            }
        }
    }
}

@Composable
private fun CapabilityBrowser(
    capabilities: List<ApiCapability>,
    selectedIds: Set<String>,
    query: String,
    filter: Int,
    expandedIds: Set<String>,
    onQuery: (String) -> Unit,
    onFilter: (Int) -> Unit,
    onExpanded: (String) -> Unit,
    onSelected: (String, Boolean) -> Unit,
) {
    TcFormCard("能力列表") {
        TcField("搜索") { TcInput(query, onQuery, placeholder = "名称、用途或路径") }
        TcField("筛选", last = true) {
            TcPills(listOf("全部", "已选", "未选", "自定义", "失效"), setOf(filter), onFilter)
        }
    }
    val visible = filterApiCapabilities(capabilities, selectedIds, query, filter)
    if (visible.isEmpty()) {
        TcGlass { TcEmpty("没有符合条件的能力") }
        return
    }
    TcGlass {
        visible.forEachIndexed { index, capability ->
            TcToolCard(last = index == visible.lastIndex) {
                TcToolTop(
                    capability.displayName,
                    capability.description ?: if (capability.source == ApiCapabilitySource.Custom) "用户自定义" else "官方描述",
                ) {
                    TcSwitch(
                        checked = capability.id in selectedIds,
                        label = capability.displayName,
                        enabled = capability.availability == ApiCapabilityAvailability.Available,
                    ) { onSelected(capability.id, it) }
                }
                TcHint(buildString {
                    append(if (capability.source == ApiCapabilitySource.Custom) "自定义" else "官方发现")
                    if (capability.availability == ApiCapabilityAvailability.Missing) append(" · 已失效")
                })
                TcLink(if (capability.id in expandedIds) "收起技术字段" else "查看技术字段") {
                    onExpanded(capability.id)
                }
                if (capability.id in expandedIds) {
                    TcSchema("${capability.method} ${capability.path}")
                    capability.inputSchema?.let { TcSchema("Request\n$it") }
                    capability.outputSchema?.let { TcSchema("Response\n$it") }
                }
            }
        }
    }
}

@Composable
private fun ManualApiCapabilityForm(
    connectionId: String,
    repository: ApiConnectionRepository,
    ui: TcUi,
    onSaved: (ApiCapabilityCatalog) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var method by remember { mutableIntStateOf(0) }
    var path by remember { mutableStateOf("") }
    var inputSchema by remember { mutableStateOf("") }
    var outputSchema by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    val methods = listOf("GET", "POST", "PUT", "PATCH", "DELETE")
    TcFormCard("手动添加能力") {
        TcField("名称") { TcInput(name, { name = it }, placeholder = "用户首先看到的能力名称") }
        TcField("用途") { TcInput(description, { description = it }, multiline = true) }
        TcField("请求方式") { TcPills(methods, setOf(method)) { method = it } }
        TcField("相对路径") { TcInput(path, { path = it }, placeholder = "/v1/example") }
        TcField("Request Schema（可选，高级）") { TcInput(inputSchema, { inputSchema = it }, multiline = true) }
        TcField("Response Schema（可选，高级）") { TcInput(outputSchema, { outputSchema = it }, multiline = true) }
        TcField("当前选择", last = true) { TcSwitch(selected, "保存后选中") { selected = it } }
        TcSaveButton("添加能力", enabled = !busy) {
            when {
                name.isBlank() -> ui.toast("请填写能力名称")
                path.isBlank() || !path.startsWith('/') -> ui.toast("请填写以 / 开头的相对路径")
                !validOptionalJsonObject(inputSchema) || !validOptionalJsonObject(outputSchema) ->
                    ui.toast("Schema 必须是 JSON object")
                else -> {
                    busy = true
                    scope.launch {
                        runCatching {
                            repository.createCustomCapability(connectionId, ApiCustomCapabilityInput(
                                displayName = name,
                                description = description.ifBlank { null },
                                method = methods[method],
                                path = path,
                                inputSchemaJson = inputSchema.ifBlank { null },
                                outputSchemaJson = outputSchema.ifBlank { null },
                                selected = selected,
                            ))
                        }.onSuccess {
                            ui.toast("自定义能力已添加")
                            onSaved(it)
                        }.onFailure { ui.toast(it.message ?: "添加失败") }
                        busy = false
                    }
                }
            }
        }
    }
}

internal fun filterApiCapabilities(
    capabilities: List<ApiCapability>,
    selectedIds: Set<String>,
    query: String,
    filter: Int,
): List<ApiCapability> {
    val needle = query.trim().lowercase()
    return capabilities.filter { capability ->
        val matchesText = needle.isEmpty() || listOf(
            capability.displayName,
            capability.description.orEmpty(),
            capability.method,
            capability.path,
        ).any { needle in it.lowercase() }
        val matchesFilter = when (filter) {
            1 -> capability.id in selectedIds
            2 -> capability.id !in selectedIds
            3 -> capability.source == ApiCapabilitySource.Custom
            4 -> capability.availability == ApiCapabilityAvailability.Missing
            else -> true
        }
        matchesText && matchesFilter
    }
}

private fun Set<String>.toggle(value: String): Set<String> = if (value in this) this - value else this + value
private fun Set<String>.withSelection(value: String, selected: Boolean): Set<String> =
    if (selected) this + value else this - value

private fun validOptionalJsonObject(value: String): Boolean = value.isBlank() || runCatching {
    org.json.JSONObject(value)
}.isSuccess
