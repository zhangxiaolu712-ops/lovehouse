package fyi.b612.lovehouse.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val ApiKinds = listOf("语音", "地图", "自定义")
private val ApiScopes = listOf("所有人格档案共用", "按人格档案分配")

private fun StoredToolConnection.statusColor() = when (status) {
    ToolConnectionStatus.Connected -> ToolStyle.Ok
    ToolConnectionStatus.Failed -> ToolStyle.Danger
    ToolConnectionStatus.Untested -> ToolStyle.Idle
}

private fun StoredToolConnection.statusText(): String = when (status) {
    ToolConnectionStatus.Connected -> "已连接"
    ToolConnectionStatus.Failed -> "连接失败"
    ToolConnectionStatus.Untested -> "未测试"
}

private fun StoredToolConnection.asProbeResult() =
    ToolConnectionProbeResult(status == ToolConnectionStatus.Connected, lastResult.orEmpty(), discoveredTools)

private fun StoredToolConnection.asDraft(name: String = this.name, note: String = this.note) =
    ToolConnectionDraft(id, name, kind, endpoint, auth, credential, note)

private fun hostOfEndpoint(url: String): String =
    url.substringAfter("://").substringBefore('/').takeIf(String::isNotBlank) ?: "未填写地址"

/**
 * 「API」标签（定稿 HTML 的 #tab-api）。
 * 总开关卡片 → 「API 服务」区块 → 每个 API 一张可展开的服务卡片，卡片里挂载「密钥」。
 * 真实数据来自手机本地的 ToolConnectionStore（凭据 Keystore 加密）。
 */
@Composable
internal fun ApiTab(
    connections: ToolConnectionStore,
    probe: ToolConnectionProbe,
    ps: ToolPreviewState,
    onOpenDetail: (id: String?, title: String) -> Unit,
) {
    val ui = LocalToolUi.current
    val scope = rememberCoroutineScope()
    val saved by connections.connections.collectAsState()
    val apis = saved.filter { it.kind == ToolConnectionKind.Api }
    var openIds by remember { mutableStateOf<Set<String>?>(null) }
    val opened = openIds ?: setOfNotNull(apis.firstOrNull()?.id)

    ToolGlass {
        ToolRow("启用 API 工具", "语音、地图等外部服务，统一从这里接入") {
            ToolSwitch(ps.flag("g.api.enabled"), { ps.setFlag("g.api.enabled", it); ui.toast(ToolPreviewToast) })
        }
    }
    ToolSection("API 服务", "${apis.size} 个")
    if (apis.isEmpty()) ToolEmpty("还没有 API 服务。点右上角的 + 添加。")

    apis.forEach { api ->
        val scopeIndex = if (ps.flag("api.scope.${api.id}", true)) 1 else 0
        ToolServerCard(
            letter = api.name,
            title = api.name,
            sub = "${hostOfEndpoint(api.endpoint)} · 1 个密钥",
            expanded = api.id in opened,
            onToggle = { openIds = if (api.id in opened) opened - api.id else opened + api.id },
        ) {
            if (api.note.isNotBlank()) ToolNoteLine(api.note)
            ToolStatusLine(
                api.statusColor(),
                api.statusText() + if (api.discoveredTools.isNotEmpty()) " · " + api.discoveredTools.take(3).joinToString("、") else "",
            )
            ToolActions {
                ToolChip("测试连通", {
                    scope.launch {
                        val draft = api.asDraft()
                        val result = withContext(Dispatchers.IO) { probe.test(draft) }
                        connections.save(draft, result, api.enabled)
                        ui.toast(result.message)
                    }
                }, icon = ToolIconKind.Refresh)
                ToolChip("重命名", {
                    ui.prompt("重命名", "只改显示名称，不影响连接地址。", api.name) { v ->
                        if (v.isNotBlank()) connections.save(api.asDraft(name = v), api.asProbeResult(), api.enabled)
                    }
                }, icon = ToolIconKind.Edit)
                ToolChip("备注", {
                    ui.prompt("备注", "写给自己看的说明。", api.note) { v ->
                        connections.save(api.asDraft(note = v), api.asProbeResult(), api.enabled)
                    }
                }, icon = ToolIconKind.Note)
                ToolChip("删除", {
                    ui.inform("暂时不能删除", "这里仍有 1 个密钥。请先全部移除，再删除整个服务。")
                }, icon = ToolIconKind.Trash, danger = true)
            }
            ToolAccLabel("已挂载的密钥", "+ 接入新密钥") { onOpenDetail(null, "接入新密钥") }
            ToolAcc(
                name = ps.text("api.keynote.${api.id}") ?: when (api.auth) {
                    ToolConnectionAuth.None -> "无需密钥"
                    ToolConnectionAuth.ApiKey -> "API Key"
                    ToolConnectionAuth.BearerToken -> "Bearer Token"
                },
                meta = ApiScopes[scopeIndex] + " · ${api.discoveredTools.size} 项能力" + if (api.enabled) "" else " · 已关闭",
                checked = api.enabled,
                onChecked = { connections.setEnabled(api.id, it) },
            ) {
                ToolAccLink("能力与权限", { onOpenDetail(api.id, "能力与权限") })
                ToolAccLink("移除", {
                    ui.confirm("移除这个密钥？", "只移除这一项的连接，服务本身保留。", ok = "移除", danger = true) {
                        connections.delete(api.id)
                    }
                }, danger = true)
            }
        }
    }
}

/**
 * 二级页「API 详情」（定稿 HTML 的 #pageFormApi）。
 * 真实接线：名称 / Base URL / 备注 / API Key 点「保存」时先真实测试再写入 ToolConnectionStore。
 * 服务类型、密钥备注、谁能用、音色、能力开关与档位目前没有数据来源，按预览处理。
 */
@Composable
internal fun ApiDetailPage(
    id: String?,
    connections: ToolConnectionStore,
    probe: ToolConnectionProbe,
    model: McpToolsModel,
    ps: ToolPreviewState,
    onDone: () -> Unit,
) {
    val ui = LocalToolUi.current
    val scope = rememberCoroutineScope()
    val saved by connections.connections.collectAsState()
    val existing = id?.let { key -> saved.firstOrNull { it.id == key } }

    var name by remember(id) { mutableStateOf(existing?.name.orEmpty()) }
    var kind by remember(id) { mutableStateOf(2) }
    var endpoint by remember(id) { mutableStateOf(existing?.endpoint.orEmpty()) }
    var note by remember(id) { mutableStateOf(existing?.note.orEmpty()) }
    var keyNote by remember(id) { mutableStateOf(ps.text("api.keynote.${id.orEmpty()}").orEmpty()) }
    var key by remember(id) { mutableStateOf(existing?.credential.orEmpty()) }
    var scopeIndex by remember(id) { mutableStateOf(if (ps.flag("api.scope.${id.orEmpty()}", true)) 1 else 0) }
    var voices by remember(id) { mutableStateOf(emptyList<Pair<String, String?>>()) }
    var extraCapabilities by remember(id) { mutableStateOf(emptyList<String>()) }
    val capOn = remember(id) { mutableStateMapOf<String, Boolean>() }
    val capMode = remember(id) { mutableStateMapOf<String, Int>() }
    var tested by remember(id) { mutableStateOf<ToolConnectionProbeResult?>(null) }
    var busy by remember { mutableStateOf(false) }

    fun draft() = ToolConnectionDraft(
        id = existing?.id ?: java.util.UUID.randomUUID().toString(),
        name = name,
        kind = ToolConnectionKind.Api,
        endpoint = endpoint,
        auth = when {
            key.isBlank() -> ToolConnectionAuth.None
            existing != null && existing.auth != ToolConnectionAuth.None -> existing.auth
            else -> ToolConnectionAuth.ApiKey
        },
        credential = key,
        note = note,
    )

    ToolFormCard("连接信息") {
        ToolField("名称") { ToolInput(name, { name = it }) }
        ToolField("服务类型") { ToolPills(ApiKinds, setOf(kind), { kind = it }) }
        ToolField("Base URL（原样保存）") { ToolInput(endpoint, { endpoint = it }, placeholder = "https://") }
        ToolField("备注") { ToolInput(note, { note = it }, placeholder = "这个服务是做什么的，给自己看", multiline = true) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            ToolLink(if (busy) "测试中…" else "测试连通", {
                if (endpoint.isBlank()) {
                    ui.toast("请先填写 Base URL")
                } else {
                    busy = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) { probe.test(draft()) }
                        tested = result
                        busy = false
                        ui.toast(result.message)
                    }
                }
            }, enabled = !busy)
            val last = tested
            Row(verticalAlignment = Alignment.CenterVertically) {
                val ok = when {
                    last != null -> last.succeeded
                    existing != null -> existing.status == ToolConnectionStatus.Connected
                    else -> null
                }
                ToolDot(
                    when (ok) {
                        true -> ToolStyle.Ok
                        false -> ToolStyle.Danger
                        null -> ToolStyle.Idle
                    },
                )
                ToolText(
                    when (ok) {
                        true -> "上次测试：通过"
                        false -> "上次测试：失败"
                        null -> "尚未测试"
                    },
                    size = ToolStyle.Sub,
                    color = when (ok) {
                        true -> ToolStyle.Ok
                        false -> ToolStyle.Danger
                        null -> ToolStyle.Ink2
                    },
                )
            }
        }
    }

    ToolFormCard("密钥") {
        ToolField("密钥备注") { ToolInput(keyNote, { keyNote = it }) }
        ToolField("API Key") {
            ToolInput(key, { key = it }, secret = true)
            ToolHint("只提交给后端保存，前端不留存，也不会明文回显。")
        }
        ToolField("谁能用", last = true) {
            ToolPills(ApiScopes, setOf(scopeIndex), { scopeIndex = it })
            ToolHint("地图这类工具，所有人格档案共用；语音这类带个人音色的，一个密钥下按人格档案分配。")
        }
    }

    if (scopeIndex == 1) {
        ToolFormCard("按人格档案分配 · 音色") {
            voices.forEachIndexed { index, voice ->
                ToolBlock(
                    name = voice.first,
                    desc = "同一个密钥下的一个声音",
                    last = index == voices.lastIndex,
                    trailing = {
                        ToolSelect(
                            label = model.personas.firstOrNull { it.personaId == voice.second }?.displayName ?: "选择",
                            options = model.personas.map { it.personaId to it.displayName },
                            onPick = { picked -> voices = voices.mapIndexed { i, v -> if (i == index) v.first to picked else v } },
                        )
                    },
                )
            }
            ToolLink("+ 添加音色", {
                voices = voices + ("音色 " + ('A' + voices.size.coerceAtMost(25)) to null)
            })
        }
    }

    val capabilities = (existing?.discoveredTools.orEmpty() + (tested?.tools.orEmpty()) + extraCapabilities).distinct()
    ToolFormCard("能力与权限 · ${capabilities.size} 项") {
        capabilities.forEachIndexed { index, capability ->
            val on = capOn[capability] ?: true
            ToolBlock(
                name = capability,
                desc = null,
                last = index == capabilities.lastIndex,
                dimmed = !on,
                trailing = { ToolSwitch(on, { capOn[capability] = it }) },
            ) {
                ToolSeg(listOf("每次询问", "直接允许"), capMode[capability] ?: 0, { capMode[capability] = it }, enabled = on)
            }
        }
        ToolLink("+ 添加能力", {
            ui.prompt("添加能力", "给这个服务加一项能力。", "", ok = "添加") { v ->
                if (v.isNotBlank()) extraCapabilities = extraCapabilities + v
            }
        })
    }

    ToolSaveButton(if (busy) "保存中…" else "保存", enabled = !busy, onClick = {
        if (name.isBlank() || endpoint.isBlank()) {
            ui.toast("请先填写名称和 Base URL")
        } else {
            busy = true
            scope.launch {
                val d = draft()
                val result = withContext(Dispatchers.IO) { probe.test(d) }
                connections.save(d, result, existing?.enabled ?: true)
                if (keyNote.isNotBlank()) ps.setText("api.keynote.${d.id}", keyNote)
                ps.setFlag("api.scope.${d.id}", scopeIndex == 1)
                busy = false
                ui.toast("已保存连接信息（" + result.message + "）；服务类型、音色、能力档位还没接后端")
                onDone()
            }
        }
    })
}
