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
    var showManual by remember(connection.id) { mutableStateOf(false) }

    val load: suspend () -> Unit = {
        loading = true
        error = null
        runCatching { repository.resourceCatalog(connection.id) }
            .onSuccess { loaded ->
                catalog = loaded
                selectedIds = loaded.resources.filter(ApiResource::selected).mapTo(linkedSetOf()) { it.id }
            }
            .onFailure { error = it.message ?: "模型目录读取失败" }
        loading = false
    }
    LaunchedEffect(repository, connection.id) { load() }

    TcSection("模型目录", catalog?.let { "${it.resources.size} 项 · 第 ${it.revision} 版" } ?: "")
    TcFormCard("发现模型") {
        TcField("发现来源") {
            TcPills(listOf("服务声明", "模型列表 URL"), setOf(sourceMode)) {
                sourceMode = it
                preview = null
            }
        }
        if (sourceMode == 0) {
            TcField("服务声明") {
                TcHint("只读取服务目录明确声明的模型列表地址，不从 Base URL 猜测路径。")
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
        TcSaveButton(if ((catalog?.revision ?: 0) == 0) "发现模型" else "重新发现模型", enabled = !busy && !loading) {
            val source = if (sourceMode == 0) {
                ApiResourceDiscoverySource.PresetModels
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
                            ui.toast("已发现 ${found.resources.size} 个模型，请预览后保存")
                        }
                        .onFailure {
                            error = it.message ?: "模型发现失败，原目录未改变"
                            ui.toast(error ?: "模型发现失败")
                        }
                    busy = false
                }
            }
        }
        TcLink(if (showManual) "收起手动添加" else "无法发现？手动添加模型") { showManual = !showManual }
    }

    if (showManual) ManualApiResourceForm(connection.id, repository, ui) { updated ->
        catalog = updated
        selectedIds = updated.resources.filter(ApiResource::selected).mapTo(linkedSetOf()) { it.id }
        showManual = false
    }

    when {
        loading -> TcGlass { TcEmpty("正在读取模型目录…") }
        catalog == null -> TcGlass { TcEmpty(error ?: "模型目录暂不可用") }
        preview != null -> {
            val found = preview ?: return
            TcSection("模型预览", "+${found.changes.added.size} · ~${found.changes.changed.size} · -${found.changes.removed.size}")
            ModelResourceBrowser(found.resources, selectedIds, query, filter, { query = it }, { filter = it }) {
                    id, selected -> selectedIds = selectedIds.withResourceSelection(id, selected)
            }
            TcSaveButton("保存完整模型目录与选择", enabled = !busy) {
                busy = true
                scope.launch {
                    runCatching { repository.commitResources(connection.id, found.previewId, selectedIds) }
                        .onSuccess { saved ->
                            catalog = saved
                            preview = null
                            selectedIds = saved.resources.filter(ApiResource::selected).mapTo(linkedSetOf()) { it.id }
                            ui.toast("完整模型目录已保存")
                        }
                        .onFailure { ui.toast(it.message ?: "模型目录保存失败") }
                    busy = false
                }
            }
            TcLink("放弃本次预览") { preview = null }
        }
        else -> {
            val saved = catalog ?: return
            if (saved.resources.isEmpty()) {
                TcGlass { TcEmpty("尚无模型。请提供明确的模型列表 URL，或手动添加。") }
            } else {
                saved.lastDiscoverySummary?.let { TcHint("最近发现：$it") }
                ModelResourceBrowser(saved.resources, selectedIds, query, filter, { query = it }, { filter = it }) {
                        id, selected -> selectedIds = selectedIds.withResourceSelection(id, selected)
                }
                TcSaveButton("保存模型选择", enabled = !busy) {
                    busy = true
                    scope.launch {
                        runCatching { repository.selectResources(connection.id, selectedIds) }
                            .onSuccess { authoritative ->
                                catalog = authoritative
                                selectedIds = authoritative.resources.filter(ApiResource::selected)
                                    .mapTo(linkedSetOf()) { it.id }
                                ui.toast("模型选择已保存")
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
    onQuery: (String) -> Unit,
    onFilter: (Int) -> Unit,
    onSelected: (String, Boolean) -> Unit,
) {
    TcFormCard("模型列表") {
        TcField("搜索") { TcInput(query, onQuery, placeholder = "模型名称或原始 ID") }
        TcField("筛选", last = true) {
            TcPills(listOf("全部", "已选", "未选", "自定义", "失效"), setOf(filter), onFilter)
        }
    }
    val visible = filterApiResources(resources, selectedIds, query, filter)
    if (visible.isEmpty()) {
        TcGlass { TcEmpty("没有符合条件的模型") }
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
                    append(if (resource.source == ApiResourceSource.Custom) "自定义" else "服务发现")
                    resource.providerOwner?.let { append(" · $it") }
                    if (resource.availability == ApiResourceAvailability.Missing) append(" · 已下架")
                })
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
    var busy by remember { mutableStateOf(false) }
    TcFormCard("手动添加模型") {
        TcField("原始模型 ID") { TcInput(modelId, { modelId = it }, placeholder = "服务端要求的完整模型 ID") }
        TcField("显示名称") { TcInput(name, { name = it }, placeholder = "留空时使用原始 ID") }
        TcField("用途（可选）") { TcInput(description, { description = it }, multiline = true) }
        TcField("当前选择", last = true) { TcSwitch(selected, "保存后选中") { selected = it } }
        TcSaveButton("添加模型", enabled = !busy) {
            if (modelId.isBlank()) {
                ui.toast("请填写原始模型 ID")
            } else {
                busy = true
                scope.launch {
                    runCatching { repository.createCustomResource(connectionId, ApiCustomResourceInput(
                        providerResourceId = modelId,
                        displayName = name.ifBlank { modelId },
                        description = description.ifBlank { null },
                        selected = selected,
                    )) }.onSuccess {
                        ui.toast("自定义模型已添加")
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
        matchesText && matchesFilter
    }
}

private fun Set<String>.withResourceSelection(value: String, selected: Boolean): Set<String> =
    if (selected) this + value else this - value
