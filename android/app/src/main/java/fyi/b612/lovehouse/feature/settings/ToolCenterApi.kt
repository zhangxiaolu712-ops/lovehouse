package fyi.b612.lovehouse.feature.settings

import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import fyi.b612.lovehouse.feature.chat.PersonaProfile
import fyi.b612.lovehouse.feature.chat.PersonaRuntimeSource
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ApiTab(store: ToolConnectionStore, probe: ToolConnectionProbe, ui: TcUi, onOpen: (TcPage) -> Unit) {
    val saved by store.connections.collectAsState()
    val apis = saved.filter { it.kind == ToolConnectionKind.Api }
    var enabled by remember { mutableStateOf(true) }
    var openOverride by remember { mutableStateOf<Set<String>?>(null) }
    val open = openOverride ?: setOfNotNull(apis.firstOrNull()?.id)
    val scope = rememberCoroutineScope()
    val save: (ToolConnectionDraft, ToolConnectionProbeResult, Boolean, String) -> Unit = { draft, result, on, done ->
        runCatching { store.save(draft, result, on) }
            .onSuccess { ui.toast(done) }
            .onFailure { ui.toast(it.message ?: "保存失败") }
    }

    TcGlass {
        TcRow(first = true) {
            TcRowText("启用 API 工具", "语音、地图等外部服务，统一从这里接入", Modifier.weight(1f))
            TcSwitch(enabled, "启用 API 工具") { enabled = it; ui.toast("预览：该开关尚未接入后端，未保存") }
        }
    }
    TcSection("API 服务", "${apis.size} 个")
    if (apis.isEmpty()) TcGlass { TcEmpty("还没有 API 服务，点右上角“+”添加。") }

    apis.forEach { api ->
        val hasKey = api.hasKey()
        TcServerCard(
            letter = api.name.firstOrNull()?.uppercase() ?: "A",
            name = api.name,
            sub = "${mcpHost(api.endpoint)} · ${if (hasKey) 1 else 0} 个密钥",
            open = api.id in open,
            onToggle = { openOverride = if (api.id in open) open - api.id else open + api.id },
            note = api.note,
            status = api.status.tcLabel(),
            statusOk = api.status == ToolConnectionStatus.Connected,
            testLabel = "测试连通",
            onTest = {
                scope.launch {
                    val draft = api.toTcDraft()
                    val result = withContext(Dispatchers.IO) { probe.test(draft) }
                    save(draft, result, api.enabled, result.message)
                }
            },
            onRename = {
                ui.dialog = TcDialog("重命名", "只改显示名称，不影响连接地址。", input = api.name, ok = "保存") { result ->
                    if (result.text.isNotEmpty()) save(api.toTcDraft().copy(name = result.text), api.lastProbe(), api.enabled, "已重命名")
                }
            },
            onNote = {
                ui.dialog = TcDialog("备注", "写给自己看的说明。", input = api.note, ok = "保存") { result ->
                    save(api.toTcDraft().copy(note = result.text), api.lastProbe(), api.enabled, "备注已保存")
                }
            },
            onDelete = {
                ui.dialog = if (hasKey) {
                    TcDialog("暂时不能删除", "这里仍有 1 个密钥。请先全部移除，再删除整个服务。", ok = "知道了", single = true)
                } else {
                    TcDialog("删除这一项？", "删除后它的相关配置会一并移除。", ok = "删除", danger = true) {
                        runCatching { store.delete(api.id) }
                            .onSuccess { ui.toast("已删除") }
                            .onFailure { ui.toast(it.message ?: "删除失败") }
                    }
                }
            },
            itemsLabel = "已挂载的密钥",
            addLabel = "+ 接入新密钥",
            onAdd = { onOpen(TcPage.Api("接入新密钥", api.id)) },
            emptyText = if (hasKey) null else "还没有密钥，点上面的“接入新密钥”添加。",
        ) {
            if (hasKey) {
                TcAccount(
                    name = api.auth.tcLabel(),
                    meta = "本机加密保存 · ${if (api.enabled) "已开启" else "已关闭"}",
                    checked = api.enabled,
                    onToggle = { on ->
                        runCatching { store.setEnabled(api.id, on) }.onFailure { ui.toast(it.message ?: "保存失败") }
                    },
                ) {
                    TcAccLink("能力与权限") { onOpen(TcPage.Api("能力与权限", api.id)) }
                    TcAccLink("移除", danger = true) {
                        ui.dialog = TcDialog("移除这个密钥？", "只移除这一项的连接，服务本身保留。", ok = "移除", danger = true) {
                            save(
                                api.toTcDraft().copy(auth = ToolConnectionAuth.None, credential = ""),
                                api.lastProbe(),
                                api.enabled,
                                "密钥已移除",
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ApiDetailPage(
    page: TcPage.Api,
    store: ToolConnectionStore,
    probe: ToolConnectionProbe,
    personaRuntimeSource: PersonaRuntimeSource,
    ui: TcUi,
    onDone: () -> Unit,
) {
    val saved by store.connections.collectAsState()
    val existing = page.connectionId?.let { id -> saved.firstOrNull { it.id == id } }
    if (page.connectionId != null && existing == null) {
        TcGlass { TcEmpty("这个 API 服务已不存在，请返回。") }
        return
    }
    val id = remember { existing?.id ?: UUID.randomUUID().toString() }
    val scope = rememberCoroutineScope()
    var name by remember { mutableStateOf(existing?.name.orEmpty()) }
    var kind by remember { mutableIntStateOf(2) }
    var endpoint by remember { mutableStateOf(existing?.endpoint.orEmpty()) }
    var note by remember { mutableStateOf(existing?.note.orEmpty()) }
    var keyNote by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var sharing by remember { mutableIntStateOf(1) }
    val voices = remember { mutableStateListOf<Int>() }
    val capabilities = remember { mutableStateListOf<String>() }
    val capabilityOn = remember { mutableStateMapOf<Int, Boolean>() }
    val capabilityPermission = remember { mutableStateMapOf<Int, ToolPermission>() }
    var result by remember { mutableStateOf(existing?.takeIf { it.status != ToolConnectionStatus.Untested }?.lastProbe()) }
    var busy by remember { mutableStateOf(false) }
    var previewEdited by remember { mutableStateOf(false) }
    var personas by remember { mutableStateOf<List<PersonaProfile>>(emptyList()) }
    LaunchedEffect(personaRuntimeSource) {
        personas = runCatching { personaRuntimeSource.profiles() }.getOrDefault(emptyList())
    }
    val draft = {
        val credential = key.ifBlank { existing?.credential.orEmpty() }
        ToolConnectionDraft(
            id = id,
            name = name,
            kind = ToolConnectionKind.Api,
            endpoint = endpoint,
            auth = apiAuthFor(existing?.auth, credential),
            credential = credential,
            note = note,
        )
    }

    TcFormCard("连接信息") {
        TcField("名称") { TcInput(name, { name = it }) }
        TcField("服务类型") { TcPills(listOf("语音", "地图", "自定义"), setOf(kind)) { kind = it; previewEdited = true } }
        TcField("Base URL（原样保存）") { TcInput(endpoint, { endpoint = it; result = null }, placeholder = "https://") }
        TcField("备注") { TcInput(note, { note = it }, placeholder = "这个服务是做什么的，给自己看", multiline = true) }
        TcInlineStatus(
            "测试连通",
            when (result?.succeeded) {
                true -> "上次测试：通过"
                false -> "上次测试：未通过"
                null -> "尚未测试"
            },
            ok = result?.succeeded == true,
            busy = busy,
        ) {
            busy = true
            scope.launch {
                val tested = withContext(Dispatchers.IO) { probe.test(draft()) }
                result = tested
                ui.toast(tested.message)
                busy = false
            }
        }
    }

    TcFormCard("密钥") {
        TcField("密钥备注") { TcInput(keyNote, { keyNote = it; previewEdited = true }) }
        TcField("API Key") {
            TcInput(
                key,
                { key = it; result = null },
                placeholder = if (existing?.hasKey() == true) "已保存，不回显；留空则保持不变" else "",
                secret = true,
            )
            TcHint("仅在本机用 Android Keystore 加密保存，不会明文回显。")
        }
        TcField("谁能用", last = true) {
            TcPills(listOf("所有人格档案共用", "按人格档案分配"), setOf(sharing)) { sharing = it; previewEdited = true }
            TcHint("地图这类工具，所有人格档案共用；语音这类带个人音色的，一个密钥下按人格档案分配。")
        }
    }

    if (sharing == 1) {
        TcFormCard("按人格档案分配 · 音色") {
            voices.forEachIndexed { index, owner ->
                val label = "音色 ${'A' + index}"
                TcToolCard(last = index == voices.lastIndex) {
                    TcToolTop(label, "同一个密钥下的一个声音") {
                        TcSelect(personas.map { it.displayName }, owner, "$label 属于哪个人格档案") { voices[index] = it; previewEdited = true }
                    }
                }
            }
            TcLink("+ 添加音色", Modifier.padding(top = 6.dp)) {
                if (personas.isEmpty()) ui.toast("当前 App Account 尚无人格档案") else { voices += 0; previewEdited = true }
            }
        }
    }

    TcFormCard("能力与权限 · ${capabilities.size} 项") {
        capabilities.forEachIndexed { index, capability ->
            val on = capabilityOn[index] ?: true
            TcToolCard(last = index == capabilities.lastIndex) {
                TcToolTop(capability, "") {
                    TcSwitch(on, capability) { capabilityOn[index] = it; previewEdited = true }
                }
                val choices = listOf(ToolPermission.Ask, ToolPermission.Allow)
                TcSeg(
                    choices.map { it.label },
                    choices.indexOf(capabilityPermission[index] ?: ToolPermission.Ask),
                    Modifier.padding(top = 4.dp, end = 6.dp, bottom = 2.dp),
                    enabled = on,
                ) { capabilityPermission[index] = choices[it]; previewEdited = true }
            }
        }
        TcLink("+ 添加能力", Modifier.padding(top = 6.dp)) {
            ui.dialog = TcDialog("添加能力", "给这个 API 加一项能力名称。", input = "", ok = "添加") { added ->
                if (added.text.isNotEmpty()) { capabilities += added.text; previewEdited = true }
            }
        }
    }

    TcSaveButton("保存", enabled = !busy) {
        val tested = result
        when {
            name.isBlank() || endpoint.isBlank() -> ui.toast("请填写名称和 Base URL")
            tested == null -> ui.toast("请先测试连通")
            else -> runCatching { store.save(draft(), tested, existing?.enabled ?: true) }
                .onSuccess {
                    val unsaved = if (previewEdited) "服务类型、密钥备注、谁能用、音色与能力权限尚未接入后端，未保存" else null
                    ui.toast(listOfNotNull("已保存", unsaved).joinToString("；"))
                    onDone()
                }
                .onFailure { ui.toast(it.message ?: "保存失败") }
        }
    }
}

internal fun apiAuthFor(current: ToolConnectionAuth?, credential: String): ToolConnectionAuth = when {
    credential.isBlank() -> ToolConnectionAuth.None
    current != null && current != ToolConnectionAuth.None -> current
    else -> ToolConnectionAuth.ApiKey
}

private fun StoredToolConnection.hasKey(): Boolean = auth != ToolConnectionAuth.None && credential.isNotBlank()

private fun StoredToolConnection.toTcDraft() = ToolConnectionDraft(id, name, kind, endpoint, auth, credential, note)

private fun StoredToolConnection.lastProbe() =
    ToolConnectionProbeResult(status == ToolConnectionStatus.Connected, lastResult ?: "尚未测试", discoveredTools)

private fun ToolConnectionStatus.tcLabel(): String = when (this) {
    ToolConnectionStatus.Untested -> "尚未测试"
    ToolConnectionStatus.Connected -> "已连接"
    ToolConnectionStatus.Failed -> "连接失败"
}

private fun ToolConnectionAuth.tcLabel(): String = when (this) {
    ToolConnectionAuth.None -> "无密钥"
    ToolConnectionAuth.ApiKey -> "API Key"
    ToolConnectionAuth.BearerToken -> "Bearer Token"
}
