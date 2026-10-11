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

@Composable
internal fun ApiModelResourceSection(
    connection: ApiBackendConnection,
    repository: ApiConnectionRepository,
    ui: TcUi,
    autoDiscoveryRequest: Int = 0,
) {
    val scope = rememberCoroutineScope()
    var catalog by remember(connection.id) { mutableStateOf<ApiResourceCatalog?>(null) }
    var preview by remember(connection.id) { mutableStateOf<ApiResourcePreview?>(null) }
    var selectedIds by remember(connection.id) { mutableStateOf<Set<String>>(emptySet()) }
    var loading by remember(connection.id) { mutableStateOf(true) }
    var busy by remember(connection.id) { mutableStateOf(false) }
    var error by remember(connection.id) { mutableStateOf<String?>(null) }
    var sourceMode by remember(connection.id) { mutableIntStateOf(if (connection.serviceId == null) 1 else 0) }
    var modelsUrl by remember(connection.id) { mutableStateOf("") }
    var apiKeyHeader by remember(connection.id) { mutableStateOf("") }
    var query by remember(connection.id) { mutableStateOf("") }
    var filter by remember(connection.id) { mutableIntStateOf(0) }
    var typeFilter by remember(connection.id) { mutableIntStateOf(0) }
    var showManual by remember(connection.id) { mutableStateOf(false) }

    val load: suspend () -> Unit = {
        loading = true
        error = null
        runCatching {
            val first = repository.resourceCatalog(connection.id)
            var selectedCursor: String? = null
            var selectedPages = 0
            val authoritativeSelected = linkedSetOf<String>()
            do {
                if (++selectedPages > 100) error("资源分页超过安全上限")
                val selectedPage = repository.resourceCatalog(connection.id, cursor = selectedCursor, selected = true)
                selectedPage.resources.forEach { authoritativeSelected += it.id }
                selectedCursor = selectedPage.nextCursor
            } while (selectedCursor != null)
            first to authoritativeSelected
        }
            .onSuccess { (loaded, authoritativeSelected) ->
                catalog = loaded
                selectedIds = authoritativeSelected
            }
            .onFailure { error = it.message ?: "资源目录读取失败" }
        loading = false
    }
    LaunchedEffect(repository, connection.id) { load() }
    LaunchedEffect(autoDiscoveryRequest) {
        if (autoDiscoveryRequest <= 0) return@LaunchedEffect
        busy = true
        runCatching { repository.previewResources(connection.id, ApiResourceDiscoverySource.ConnectionModels) }
            .onSuccess { found ->
                preview = found
                selectedIds = buildSet {
                    catalog?.resources?.filter { it.source == ApiResourceSource.Custom && it.selected }
                        ?.forEach { add(it.id) }
                    found.resources.filter(ApiResource::selected).forEach { add(it.id) }
                }
                error = null
            }
            .onFailure { error = it.message ?: "资源自动发现失败，原目录未改变" }
        busy = false
    }

    TcSection("资源目录", catalog?.let { "已加载 ${it.resources.size}/${it.total} 项 · 第 ${it.revision} 版" } ?: "")
    TcFormCard("发现资源") {
        TcField("发现来源") {
            TcPills(listOf("自动来源", "手动模型 URL"), setOf(sourceMode)) {
                sourceMode = it
                preview = null
            }
        }
        if (sourceMode == 0) {
            TcField("自动来源") {
                TcHint("优先读取服务预设；OpenAI-compatible 连接按标准协议从 Base URL 定位模型列表。")
            }
        } else {
            TcField("OpenAI-compatible 模型列表 URL") {
                TcInput(modelsUrl, { modelsUrl = it }, placeholder = "https://…/v1/models")
            }
            TcField("API Key Header（仅 API Key 凭证需要）") {
                TcInput(apiKeyHeader, { apiKeyHeader = it }, placeholder = "例如 X-Api-Key")
            }
        }
        error?.let { TcField("状态", last = true) { TcHint(it) } }
        TcSaveButton(if ((catalog?.revision ?: 0) == 0) "发现资源" else "重新发现资源", enabled = !busy && !loading) {
            val source = if (sourceMode == 0) {
                ApiResourceDiscoverySource.ConnectionModels
            } else {
                modelsUrl.takeIf(String::isNotBlank)?.let {
                    ApiResourceDiscoverySource.OpenAiModels(it, apiKeyHeader.ifBlank { null })
                }
            }
            if (source == null) {
                ui.toast("请填写明确的模型列表 URL")
            } else {
                busy = true
                scope.launch {
                    runCatching { repository.previewResources(connection.id, source) }
                        .onSuccess { found ->
                            preview = found
                            selectedIds = buildSet {
                                catalog?.resources?.filter { it.source == ApiResourceSource.Custom && it.selected }
                                    ?.forEach { add(it.id) }
                                found.resources.filter(ApiResource::selected).forEach { add(it.id) }
                            }
                            error = null
                            ui.toast("已发现 ${found.resources.size} 项资源，请预览后保存")
                        }
                        .onFailure {
                            error = it.message ?: "资源发现失败，原目录未改变"
                            ui.toast(error ?: "资源发现失败")
                        }
                    busy = false
                }
            }
        }
        TcLink(if (showManual) "收起手动添加" else "无法发现？手动添加资源") { showManual = !showManual }
    }

    if (showManual) ManualApiResourceForm(connection.id, repository, ui) { updated ->
        catalog = updated
        selectedIds = selectedIds + updated.resources.filter(ApiResource::selected).map { it.id }
        showManual = false
    }

    when {
        loading -> TcGlass { TcEmpty("正在读取资源目录…") }
        catalog == null -> TcGlass { TcEmpty(error ?: "资源目录暂不可用") }
        preview != null -> {
            val found = preview ?: return
            TcSection("资源预览", "+${found.changes.added.size} · ~${found.changes.changed.size} · -${found.changes.removed.size}")
            ModelResourceBrowser(found.resources, selectedIds, query, filter, typeFilter,
                { query = it }, { filter = it }, { typeFilter = it }) {
                    id, selected -> selectedIds = selectedIds.withResourceSelection(id, selected)
            }
            TcSaveButton("保存完整资源目录与选择", enabled = !busy) {
                busy = true
                scope.launch {
                    runCatching { repository.commitResources(connection.id, found.previewId, selectedIds) }
                        .onSuccess { saved ->
                            catalog = saved
                            preview = null
                            ui.toast("完整资源目录已保存")
                        }
                        .onFailure { ui.toast(it.message ?: "资源目录保存失败") }
                    busy = false
                }
            }
            TcLink("放弃本次预览") { preview = null }
        }
        else -> {
            val saved = catalog ?: return
            if (saved.resources.isEmpty()) {
                TcGlass { TcEmpty("尚无资源。请使用自动发现、明确来源，或手动添加。") }
            } else {
                saved.lastDiscoverySummary?.let { TcHint("最近发现：$it") }
                ModelResourceBrowser(saved.resources, selectedIds, query, filter, typeFilter,
                    { query = it }, { filter = it }, { typeFilter = it }) {
                        id, selected -> selectedIds = selectedIds.withResourceSelection(id, selected)
                }
                saved.nextCursor?.let { cursor ->
                    TcLink("加载更多资源") {
                        busy = true
                        scope.launch {
                            runCatching { repository.resourceCatalog(connection.id, cursor = cursor) }
                                .onSuccess { next -> catalog = saved.copy(
                                    resources = saved.resources + next.resources,
                                    nextCursor = next.nextCursor,
                                    total = next.total,
                                ) }
                                .onFailure { ui.toast(it.message ?: "更多资源读取失败") }
                            busy = false
                        }
                    }
                }
                TcSaveButton("保存资源选择", enabled = !busy) {
                    busy = true
                    scope.launch {
                        runCatching { repository.selectResources(connection.id, selectedIds) }
                            .onSuccess { authoritative ->
                                catalog = authoritative
                                ui.toast("资源选择已保存")
                            }
                            .onFailure { ui.toast(it.message ?: "模型选择保存失败") }
                        busy = false
                    }
                }
            }
        }
    }
}

@Composable
private fun ModelResourceBrowser(
    resources: List<ApiResource>,
    selectedIds: Set<String>,
    query: String,
    filter: Int,
    typeFilter: Int,
    onQuery: (String) -> Unit,
    onFilter: (Int) -> Unit,
    onTypeFilter: (Int) -> Unit,
    onSelected: (String, Boolean) -> Unit,
) {
    TcFormCard("资源列表") {
        TcField("搜索") { TcInput(query, onQuery, placeholder = "资源名称或原始 ID") }
        TcField("类型") {
            TcPills(API_RESOURCE_TYPE_LABELS, setOf(typeFilter), onTypeFilter)
        }
        TcField("状态", last = true) {
            TcPills(listOf("全部", "已选", "未选", "自定义", "失效"), setOf(filter), onFilter)
        }
    }
    val visible = filterApiResources(resources, selectedIds, query, filter, typeFilter)
    if (visible.isEmpty()) {
        TcGlass { TcEmpty("没有符合条件的资源") }
        return
    }
    TcGlass {
        visible.forEachIndexed { index, resource ->
            TcToolCard(last = index == visible.lastIndex) {
                TcToolTop(resource.displayName, resource.description ?: resource.providerResourceId) {
                    TcSwitch(
                        checked = resource.id in selectedIds,
                        label = resource.displayName,
                        enabled = resource.availability == ApiResourceAvailability.Available,
                    ) { onSelected(resource.id, it) }
                }
                TcHint(buildString {
                    append(API_RESOURCE_TYPE_NAMES[resource.type] ?: resource.type)
                    append(" · ")
                    append(if (resource.source == ApiResourceSource.Custom) "自定义" else "服务发现")
                    resource.providerOwner?.let { append(" · $it") }
                    if (resource.availability == ApiResourceAvailability.Missing) append(" · 已下架")
                })
                resource.typeMetadataJson?.takeIf(String::isNotBlank)?.let {
                    TcHint("类型信息：${it.replace(Regex("\\s+"), " ").take(240)}")
                }
            }
        }
    }
}

@Composable
private fun ManualApiResourceForm(
    connectionId: String,
    repository: ApiConnectionRepository,
    ui: TcUi,
    onSaved: (ApiResourceCatalog) -> Unit,
) {
    val scope = rememberCoroutineScope()
    var modelId by remember { mutableStateOf("") }
    var name by remember { mutableStateOf("") }
    var description by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf(false) }
    var resourceType by remember { mutableIntStateOf(1) }
    var busy by remember { mutableStateOf(false) }
    TcFormCard("手动添加资源") {
        TcField("资源类型") { TcPills(API_RESOURCE_TYPE_LABELS.drop(1), setOf(resourceType - 1)) { resourceType = it + 1 } }
        TcField("原始资源 ID") { TcInput(modelId, { modelId = it }, placeholder = "服务端要求的完整资源 ID") }
        TcField("显示名称") { TcInput(name, { name = it }, placeholder = "留空时使用原始 ID") }
        TcField("用途（可选）") { TcInput(description, { description = it }, multiline = true) }
        TcField("当前选择", last = true) { TcSwitch(selected, "保存后选中") { selected = it } }
        TcSaveButton("添加资源", enabled = !busy) {
            if (modelId.isBlank()) {
                ui.toast("请填写原始资源 ID")
            } else {
                busy = true
                scope.launch {
                    runCatching { repository.createCustomResource(connectionId, ApiCustomResourceInput(
                        providerResourceId = modelId,
                        displayName = name.ifBlank { modelId },
                        description = description.ifBlank { null },
                        selected = selected,
                        resourceType = API_RESOURCE_TYPES[resourceType],
                    )) }.onSuccess {
                        ui.toast("自定义资源已添加")
                        onSaved(it)
                    }.onFailure { ui.toast(it.message ?: "添加失败") }
                    busy = false
                }
            }
        }
    }
}

internal fun filterApiResources(
    resources: List<ApiResource>,
    selectedIds: Set<String>,
    query: String,
    filter: Int,
    typeFilter: Int = 0,
): List<ApiResource> {
    val needle = query.trim().lowercase()
    return resources.filter { resource ->
        val matchesText = needle.isEmpty() || listOf(
            resource.displayName,
            resource.providerResourceId,
            resource.description.orEmpty(),
            resource.providerOwner.orEmpty(),
        ).any { needle in it.lowercase() }
        val matchesFilter = when (filter) {
            1 -> resource.id in selectedIds
            2 -> resource.id !in selectedIds
            3 -> resource.source == ApiResourceSource.Custom
            4 -> resource.availability == ApiResourceAvailability.Missing
            else -> true
        }
        val matchesType = typeFilter == 0 || resource.type == API_RESOURCE_TYPES[typeFilter]
        matchesText && matchesFilter && matchesType
    }
}

private fun Set<String>.withResourceSelection(value: String, selected: Boolean): Set<String> =
    if (selected) this + value else this - value

private val API_RESOURCE_TYPES = listOf("ALL", "LLM", "EMBEDDING", "TTS", "STT", "IMAGE", "VIDEO", "RERANK", "MODERATION", "VOICE")
private val API_RESOURCE_TYPE_LABELS = listOf("全部", "模型", "向量", "文字转语音", "语音转文字", "图像", "视频", "重排", "审核", "音色")
private val API_RESOURCE_TYPE_NAMES = API_RESOURCE_TYPES.zip(API_RESOURCE_TYPE_LABELS).toMap()
