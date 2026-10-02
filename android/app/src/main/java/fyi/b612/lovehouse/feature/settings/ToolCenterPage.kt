package fyi.b612.lovehouse.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fyi.b612.lovehouse.feature.chat.PersonaRuntimeSource
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

internal enum class TcTab(val label: String, val eyebrow: String) {
    Mcp("MCP", "Tool Center · MCP"),
    Api("API", "Tool Center · API"),
    Db("存储", "Tool Center · Storage"),
    Local("本地", "Tool Center · Local"),
}

internal sealed interface TcPage {
    data object Main : TcPage
    data class Mcp(val title: String, val cardKey: String?, val connectionId: String?) : TcPage
    data class Api(val title: String, val connectionId: String?) : TcPage
    data class Db(val title: String) : TcPage
}

internal data class TcDialog(
    val title: String,
    val body: String,
    val input: String? = null,
    val pills: List<String> = emptyList(),
    val selected: Set<Int> = emptySet(),
    val ok: String = "确定",
    val danger: Boolean = false,
    val single: Boolean = false,
    val onOk: (TcDialogResult) -> Unit = {},
)

internal data class TcDialogResult(val text: String, val selected: Set<Int>)

/** Toast + dialog host shared by every Tool Center page (`#toast`, `#scrim`). */
@Stable
internal class TcUi {
    var toast by mutableStateOf<String?>(null)
        private set
    var toastSeq by mutableIntStateOf(0)
        private set
    var dialog by mutableStateOf<TcDialog?>(null)

    fun toast(text: String) {
        toast = text
        toastSeq++
    }
}

@Composable
internal fun ToolCenterPage(
    registry: CapabilityRegistry,
    connections: ToolConnectionStore,
    probe: ToolConnectionProbe,
    mcpRepository: McpConnectionRepository,
    personaRuntimeSource: PersonaRuntimeSource,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    initialConnectionId: String? = null,
    callbackStatus: String? = null,
) {
    var tab by rememberSaveable { mutableStateOf(TcTab.Mcp) }
    var page by remember { mutableStateOf<TcPage>(TcPage.Main) }
    val ui = remember { TcUi() }
    val mcp = rememberMcpTcState(mcpRepository, personaRuntimeSource, initialConnectionId, callbackStatus, ui)
    val toMain = { page = TcPage.Main }

    BackHandler(enabled = page != TcPage.Main && ui.dialog == null) { page = TcPage.Main }
    BackHandler(enabled = ui.dialog != null) { ui.dialog = null }

    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().statusBarsPadding().imePadding()) {
            key(page) {
                Column(
                    Modifier
                        .align(Alignment.TopCenter)
                        .widthIn(max = 440.dp)
                        .fillMaxWidth()
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState())
                        .navigationBarsPadding()
                        .padding(start = 24.dp, end = 24.dp, bottom = 96.dp),
                ) {
                    when (val current = page) {
                        TcPage.Main -> {
                            TcTopBar("工具中心", tab.eyebrow, onBack) {
                                TcIconButton(
                                    TcIcon.Plus,
                                    "添加",
                                    visible = tab != TcTab.Local,
                                    onClick = {
                                        page = when (tab) {
                                            TcTab.Mcp -> TcPage.Mcp("添加服务器", null, null)
                                            TcTab.Api -> TcPage.Api("添加 API 服务", null)
                                            TcTab.Db -> TcPage.Db("添加存储")
                                            TcTab.Local -> TcPage.Main
                                        }
                                    },
                                )
                            }
                            when (tab) {
                                TcTab.Mcp -> McpTab(mcp, mcpRepository, ui, onOpen = { page = it })
                                TcTab.Api -> ApiTab(connections, probe, ui, onOpen = { page = it })
                                TcTab.Db -> StorageTab()
                                TcTab.Local -> LocalTab(registry, ui)
                            }
                        }
                        is TcPage.Mcp -> {
                            TcTopBar(current.title, "Service · Detail", toMain)
                            McpDetailPage(current, mcp, mcpRepository, ui, onDone = toMain)
                        }
                        is TcPage.Api -> {
                            TcTopBar(current.title, "API · Detail", toMain)
                            ApiDetailPage(current, connections, probe, personaRuntimeSource, ui, onDone = toMain)
                        }
                        is TcPage.Db -> {
                            TcTopBar(current.title, "Storage · Detail", toMain)
                            StorageDetailPage(ui, onDone = toMain)
                        }
                    }
                }
            }
            TcBottomNav(
                selected = tab,
                modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 14.dp),
            ) {
                tab = it
                page = TcPage.Main
            }
        }
        TcToastHost(ui, Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 96.dp))
        TcDialogHost(ui)
    }
}

/** `.nav` floating tab bar. */
@Composable
private fun TcBottomNav(selected: TcTab, modifier: Modifier, onSelect: (TcTab) -> Unit) {
    Row(
        modifier
            .padding(horizontal = 24.dp)
            .widthIn(max = 392.dp)
            .fillMaxWidth()
            .tcGlass(RoundedCornerShape(24.dp))
            .padding(4.dp),
    ) {
        TcTab.entries.forEach { entry ->
            val on = entry == selected
            val shape = RoundedCornerShape(20.dp)
            Box(
                Modifier
                    .weight(1f)
                    .heightIn(min = 38.dp)
                    .clip(shape)
                    .background(if (on) Tc.AccentSoft else Color.Transparent)
                    .then(if (on) Modifier.border(1.dp, Tc.Line, shape) else Modifier)
                    .clickable(remember { MutableInteractionSource() }, null, role = Role.Tab) { onSelect(entry) },
                contentAlignment = Alignment.Center,
            ) {
                TcText(
                    entry.label,
                    Tc.FsSub,
                    color = if (on) Tc.Accent else Tc.Ink2,
                    weight = if (on) FontWeight.Medium else FontWeight.Normal,
                    align = TextAlign.Center,
                )
            }
        }
    }
}

@Composable
private fun TcToastHost(ui: TcUi, modifier: Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(ui.toastSeq) {
        if (ui.toast == null) return@LaunchedEffect
        visible = true
        delay(1800)
        visible = false
    }
    val lift = with(LocalDensity.current) { 12.dp.roundToPx() }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier.padding(horizontal = 24.dp),
        enter = fadeIn(tween(250)) + slideInVertically(tween(250)) { lift },
        exit = fadeOut(tween(250)) + slideOutVertically(tween(250)) { lift },
    ) {
        Box(
            Modifier
                .clip(RoundedCornerShape(22.dp))
                .background(Tc.ToastBg)
                .padding(horizontal = 18.dp, vertical = 12.dp),
        ) { TcText(ui.toast.orEmpty(), Tc.FsSub, color = Color.White, align = TextAlign.Center) }
    }
}

/** `#scrim` + `.dialog`: a light scrim, not a dark one. */
@Composable
private fun TcDialogHost(ui: TcUi) {
    val dialog = ui.dialog ?: return
    var input by remember(dialog) { mutableStateOf(dialog.input.orEmpty()) }
    var selected by remember(dialog) { mutableStateOf(dialog.selected) }
    val focus = remember(dialog) { FocusRequester() }
    LaunchedEffect(dialog) { if (dialog.input != null) runCatching { focus.requestFocus() } }
    Box(
        Modifier
            .fillMaxSize()
            .background(Tc.Scrim)
            .clickable(remember { MutableInteractionSource() }, null) { ui.dialog = null }
            .padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier
                .widthIn(max = 330.dp)
                .fillMaxWidth()
                .tcGlass(fill = Tc.GlassStrong, elevation = 16.dp, shadowColor = Tc.PopShadow)
                .clickable(remember { MutableInteractionSource() }, null) {}
                .padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 10.dp),
        ) {
            TcText(dialog.title, 15f, Modifier.padding(bottom = 6.dp), weight = FontWeight.Medium)
            TcText(dialog.body, Tc.FsSub, Modifier.padding(bottom = 14.dp), color = Tc.Ink2)
            if (dialog.input != null) {
                TcInput(input, { input = it }, Modifier.padding(bottom = 6.dp).focusRequester(focus))
            }
            if (dialog.pills.isNotEmpty()) {
                Box(Modifier.padding(bottom = 6.dp)) {
                    TcPills(dialog.pills, selected) { index ->
                        selected = if (index in selected) selected - index else selected + index
                    }
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            ) {
                if (!dialog.single) TcDialogButton("取消", Color.Transparent, Tc.Ink2) { ui.dialog = null }
                TcDialogButton(dialog.ok, if (dialog.danger) Tc.Danger else Tc.Accent, Color.White) {
                    val result = TcDialogResult(input.trim(), selected)
                    ui.dialog = null
                    dialog.onOk(result)
                }
            }
        }
    }
}

@Composable
private fun TcDialogButton(label: String, fill: Color, ink: Color, onClick: () -> Unit) {
    Box(
        Modifier
            .heightIn(min = 40.dp)
            .clip(RoundedCornerShape(Tc.RadiusCtl))
            .background(fill)
            .clickable(remember { MutableInteractionSource() }, null, role = Role.Button, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { TcText(label, Tc.FsSub, color = ink) }
}

/** `article.glass.server` shared by the MCP and API tabs. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun TcServerCard(
    letter: String,
    name: String,
    sub: String,
    open: Boolean,
    onToggle: () -> Unit,
    note: String?,
    status: String,
    statusOk: Boolean,
    testLabel: String,
    onTest: () -> Unit,
    onRename: () -> Unit,
    onNote: () -> Unit,
    onDelete: () -> Unit,
    itemsLabel: String,
    addLabel: String,
    onAdd: () -> Unit,
    emptyText: String?,
    items: @Composable ColumnScope.() -> Unit,
) {
    Column(Modifier.padding(bottom = 12.dp).fillMaxWidth().tcGlass()) {
        Row(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 60.dp)
                .clickable(remember { MutableInteractionSource() }, null, role = Role.Button, onClick = onToggle)
                .padding(horizontal = 14.dp, vertical = 11.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            val avatarShape = RoundedCornerShape(12.dp)
            Box(
                Modifier.size(38.dp).clip(avatarShape).background(Tc.Fill35).border(1.dp, Tc.Line, avatarShape),
                contentAlignment = Alignment.Center,
            ) { TcText(letter, 20f, color = Tc.Accent, weight = FontWeight.Medium, family = Tc.Latin, lineHeight = 1.2f) }
            Column(Modifier.weight(1f)) {
                TcText(name, 14f, weight = FontWeight.Medium, lineHeight = 1.35f)
                TcText(sub, Tc.FsCap, color = Tc.Ink2)
            }
            TcChevron(open)
        }
        if (open) {
            Column(Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 12.dp)) {
                if (!note.isNullOrBlank()) {
                    TcText(note, Tc.FsCap, Modifier.padding(start = 2.dp, end = 2.dp, bottom = 8.dp), color = Tc.Ink2, italic = true)
                }
                Row(Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    TcDot(if (statusOk) Tc.Ok else Tc.Idle)
                    TcText(status, Tc.FsCap, color = Tc.Ink2)
                }
                FlowRow(
                    Modifier.padding(bottom = 10.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TcChip(TcIcon.Refresh, testLabel, onClick = onTest)
                    TcChip(TcIcon.Edit, "重命名", onClick = onRename)
                    TcChip(TcIcon.Note, "备注", onClick = onNote)
                    TcChip(TcIcon.Trash, "删除", danger = true, onClick = onDelete)
                }
                Row(
                    Modifier.fillMaxWidth().padding(start = 2.dp, end = 2.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TcText(itemsLabel, Tc.FsCap, color = Tc.Ink2, letterSpacing = .1f)
                    TcLink(addLabel, onClick = onAdd)
                }
                items()
                if (emptyText != null) {
                    TcText(emptyText, Tc.FsCap, Modifier.padding(start = 2.dp, end = 2.dp, bottom = 8.dp), color = Tc.Ink2, italic = true)
                }
            }
        }
    }
}

/** `.acc` row inside a server card. */
@Composable
internal fun TcAccount(
    name: String,
    meta: String,
    checked: Boolean?,
    onToggle: (Boolean) -> Unit = {},
    links: @Composable () -> Unit,
) {
    Column(
        Modifier.padding(bottom = 6.dp).fillMaxWidth().tcInner()
            .padding(start = 12.dp, top = 2.dp, end = 4.dp, bottom = 2.dp),
    ) {
        Row(Modifier.fillMaxWidth().heightIn(min = 46.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.weight(1f)) {
                TcText(name, Tc.FsSub, weight = FontWeight.Medium)
                TcText(meta, Tc.FsCap, color = Tc.Ink2)
            }
            if (checked != null) TcSwitch(checked, name, onToggle = onToggle)
        }
        TcAccLinks(links)
    }
}

@Composable
private fun LocalTab(registry: CapabilityRegistry, ui: TcUi) {
    val state by registry.state.collectAsState()
    val scope = rememberCoroutineScope()
    val capabilities = state.capabilities
    TcSection("本机与内置能力", "${capabilities.size} 个")
    TcGlass {
        when {
            capabilities.isNotEmpty() -> capabilities.forEachIndexed { index, tool ->
                val available = tool.availability == ToolAvailability.Available
                TcRow(first = index == 0) {
                    TcRowText(tool.displayName, "${tool.availability.tcLabel()} · ${tool.toolId}", Modifier.weight(1f))
                    TcLink("测试", color = if (available) Tc.Accent else Tc.Ink2, enabled = available) {
                        scope.launch {
                            val result = runCatching { registry.test(tool.toolId) }
                                .getOrElse { ToolTestResult(tool.toolId, false, it.message ?: "测试失败") }
                            ui.toast(result.message)
                        }
                    }
                    TcSwitch(tool.toolId in state.enabledToolIds, tool.displayName, enabled = available) {
                        registry.setEnabled(tool.toolId, it)
                    }
                }
            }
            state.loading -> TcEmpty("正在读取真实工具状态…")
            else -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                TcEmpty(state.error ?: "Bridge 没有返回任何内置能力。")
                TcLink("重试", Modifier.padding(bottom = 12.dp), onClick = registry::refresh)
            }
        }
    }
}

private fun ToolAvailability.tcLabel(): String = when (this) {
    ToolAvailability.Available -> "可用"
    ToolAvailability.Unconfigured -> "未配置"
    ToolAvailability.NoPermission -> "无权限"
    ToolAvailability.ConnectionFailed -> "连接失败"
}

@Composable
private fun StorageTab() {
    TcSection("数据去向", "每类数据存在哪")
    TcGlass { TcEmpty("后端还没有提供存储配置，这里暂时没有可显示的数据去向。") }
    TcSection("存储连接", "0 个")
    TcGlass { TcEmpty("还没有存储连接。") }
}

private val StorageKinds = listOf("VPS SQLite", "Neon", "本地 SQLite", "Railway", "Supabase", "Cloudflare R2")
private val StorageDomains = listOf("App 账号 / 身份", "生活记忆 / 小客厅", "聊天记录正文", "Engineering", "附件", "其他")

/** Storage detail is a visual shell: there is no storage backend, so nothing here is saved or tested. */
@Composable
private fun ColumnScope.StorageDetailPage(ui: TcUi, onDone: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var kind by remember { mutableIntStateOf(1) }
    var url by remember { mutableStateOf("") }
    var note by remember { mutableStateOf("") }
    var key by remember { mutableStateOf("") }
    var usage by remember { mutableIntStateOf(0) }
    var domains by remember { mutableStateOf(setOf(1)) }
    var access by remember { mutableIntStateOf(1) }
    TcFormCard("连接信息") {
        TcField("名称") { TcInput(name, { name = it }) }
        TcField("类型") { TcPills(StorageKinds, setOf(kind)) { kind = it } }
        TcField("连接地址 (URL / Host)") { TcInput(url, { url = it }, placeholder = "https://") }
        TcField("备注") { TcInput(note, { note = it }, placeholder = "给自己看的说明", multiline = true) }
        TcInlineStatus("测试连接", "尚未测试", ok = false) { ui.toast("存储连接尚未接入后端，无法测试") }
    }
    TcFormCard("凭证") {
        TcField("API Key / 连接串", last = true) {
            TcInput(key, { key = it }, secret = true)
            TcHint("只提交给后端保存，前端不留存，也不会明文回显。")
        }
    }
    TcFormCard("使用方式与权限") {
        TcField("使用方式") { TcPills(listOf("主用", "备用"), setOf(usage)) { usage = it } }
        TcField("存放的数据") {
            TcPills(StorageDomains, domains) { domains = if (it in domains) domains - it else domains + it }
        }
        TcField("读写权限", last = true) { TcSeg(listOf("只读", "读写"), access) { access = it } }
    }
    TcSaveButton("保存") {
        ui.toast("存储配置尚未接入后端，未保存")
        onDone()
    }
}
