package fyi.b612.lovehouse.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import fyi.b612.lovehouse.feature.chat.PersonaRuntimeSource
import kotlinx.coroutines.launch

private sealed interface ToolPage {
    data object Main : ToolPage
    data class McpAdd(val prefillUrl: String, val title: String) : ToolPage
    data class McpDetail(val serviceId: String, val connectionId: String) : ToolPage
    data class ApiEdit(val id: String?, val title: String) : ToolPage
    data class StorageEdit(val id: String?, val title: String) : ToolPage
}

private val TabLabels = listOf("MCP", "API", "存储", "本地")
private val Eyebrows = listOf("Tool Center · MCP", "Tool Center · API", "Tool Center · Storage", "Tool Center · Local")

/**
 * 工具添加页——按定稿 HTML（mcp-tool-center.html）1:1 翻译，挂在「设置 → 系统能力 → 工具添加」入口。
 *
 * HTML → Compose 对照：
 *   .topbar（返回 / 标题 + eyebrow / 加号）  → ToolTopBar + ToolIconButton
 *   .glass 总开关卡片 / .row / .switch       → ToolGlass + ToolRow + ToolSwitch
 *   .section                                  → ToolSection
 *   .server 服务卡片（展开 / 收起）           → ToolServerCard
 *   .chip / .acc / .link-btn / .acc-links     → ToolChip / ToolAcc / ToolAccLink
 *   .nav 底部悬浮四标签                        → ToolBottomNav
 *   .scrim + .dialog / .toast                 → ToolHost（ToolDialogLayer / ToolToastLayer）
 *   二级页 .form-card / .field / .input / .pill / .seg / .tool / .save-btn
 *                                             → ToolFormCard / ToolField / ToolInput / ToolPills / ToolSeg / ToolBlock / ToolSaveButton
 */
@Composable
internal fun SettingsToolCenter(
    registry: CapabilityRegistry,
    connections: ToolConnectionStore,
    probe: ToolConnectionProbe,
    mcpRepository: McpConnectionRepository,
    personaRuntimeSource: PersonaRuntimeSource,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val ps = remember { ToolPreviewState() }
    val model = remember(mcpRepository, personaRuntimeSource) { McpToolsModel(mcpRepository, personaRuntimeSource) }
    var reload by remember { mutableIntStateOf(0) }
    var tab by remember { mutableIntStateOf(0) }
    var page by remember { mutableStateOf<ToolPage>(ToolPage.Main) }

    ToolHost(modifier.fillMaxSize()) {
        val ui = LocalToolUi.current
        LaunchedEffect(model, reload) { model.load()?.let { ui.toast(it) } }
        BackHandler { if (page == ToolPage.Main) onBack() else page = ToolPage.Main }

        when (val current = page) {
            ToolPage.Main -> {
                val scroll = rememberScrollState()
                LaunchedEffect(tab) { scroll.scrollTo(0) }
                PageColumn(scroll, bottom = 96.dp) {
                    val plus: (@Composable () -> Unit)? = if (tab == 3) {
                        null
                    } else {
                        {
                            ToolIconButton(ToolIconKind.Plus, "添加") {
                                page = when (tab) {
                                    0 -> ToolPage.McpAdd("", "添加服务器")
                                    1 -> ToolPage.ApiEdit(null, "添加 API 服务")
                                    else -> ToolPage.StorageEdit(null, "添加存储")
                                }
                            }
                        }
                    }
                    ToolTopBar(title = "工具中心", eyebrow = Eyebrows[tab], onBack = onBack, trailing = plus)
                    when (tab) {
                        0 -> McpTab(
                            model = model,
                            repository = mcpRepository,
                            ps = ps,
                            onReload = { reload++ },
                            onOpenAuthorization = { openMcpAuthorization(context, it) },
                            onOpenDetail = { service, connection -> page = ToolPage.McpDetail(service.id, connection.id) },
                            onAddAccount = { url -> page = ToolPage.McpAdd(url, "接入新账号") },
                        )
                        1 -> ApiTab(connections, probe, ps) { id, title -> page = ToolPage.ApiEdit(id, title) }
                        2 -> {
                            ToolPreviewNote("预览数据：存储还没接后端，下面是示例内容，改动不会保存")
                            StorageTab(ps) { id, title -> page = ToolPage.StorageEdit(id, title) }
                        }
                        else -> LocalTab(registry)
                    }
                }
                ToolBottomNav(
                    labels = TabLabels,
                    selected = tab,
                    onSelect = { tab = it },
                    modifier = Modifier.align(Alignment.BottomCenter).navigationBarsPadding()
                        .padding(start = 24.dp, end = 24.dp, bottom = 14.dp).widthIn(max = 392.dp),
                )
            }
            is ToolPage.McpAdd -> SubPage(current.title, "Service · Detail", { page = ToolPage.Main }) {
                McpAddPage(
                    prefillUrl = current.prefillUrl,
                    repository = mcpRepository,
                    onConnected = { reload++; page = ToolPage.Main },
                    onOpenAuthorization = { openMcpAuthorization(context, it) },
                )
            }
            is ToolPage.McpDetail -> SubPage("工具与权限", "Service · Detail", { page = ToolPage.Main }) {
                McpDetailPage(
                    model = model,
                    repository = mcpRepository,
                    ps = ps,
                    serviceId = current.serviceId,
                    connectionId = current.connectionId,
                    onReload = { reload++ },
                    onDone = { page = ToolPage.Main },
                )
            }
            is ToolPage.ApiEdit -> SubPage(current.title, "API · Detail", { page = ToolPage.Main }) {
                ApiDetailPage(current.id, connections, probe, model, ps) { page = ToolPage.Main }
            }
            is ToolPage.StorageEdit -> SubPage(current.title, "Storage · Detail", { page = ToolPage.Main }) {
                StorageDetailPage(current.id, ps) { page = ToolPage.Main }
            }
        }
    }
}

/** 页面容器：宽度 440 上限、左右 24 的页边距，整页一起滚动（顶栏也随页面滚动，和 HTML 一致）。 */
@Composable
private fun PageColumn(scroll: ScrollState, bottom: Dp, content: @Composable ColumnScope.() -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Column(
            Modifier.widthIn(max = 440.dp).fillMaxWidth().fillMaxHeight().statusBarsPadding()
                .verticalScroll(scroll).padding(start = 24.dp, end = 24.dp, bottom = bottom),
            content = content,
        )
    }
}

/** 二级页：顶栏（返回 / 标题 + eyebrow / 右侧占位）+ 内容；底部没有悬浮导航。 */
@Composable
private fun SubPage(title: String, eyebrow: String, onBack: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    val scroll = rememberScrollState()
    PageColumn(scroll, bottom = 24.dp) {
        ToolTopBar(title = title, eyebrow = eyebrow, onBack = onBack)
        content()
        Box(Modifier.navigationBarsPadding())
    }
}

/**
 * 「本地」标签。定稿 HTML 里这一页只有占位文案（「该页面还没设计，先占位」），没有设计稿。
 * 为了不丢掉现有的真实内置能力开关，临时用 HTML 已有的 .row 组件承接；待 Owner 确认设计。
 */
@Composable
private fun LocalTab(registry: CapabilityRegistry) {
    val ui = LocalToolUi.current
    val scope = rememberCoroutineScope()
    val state by registry.state.collectAsState()
    val capabilities = state.capabilities

    ToolSection("本机与内置能力", "${capabilities.size} 个")
    if (capabilities.isEmpty()) {
        ToolEmpty(
            state.error
                ?: if (state.loading) {
                    "正在读取真实工具状态…"
                } else {
                    "这里放本机与内置能力（相机、定位、通知等）。\n本地 SQL 属于数据库，在「存储」标签里。\n（该页面还没设计，先占位）"
                },
        )
    } else {
        ToolGlass {
            capabilities.forEachIndexed { index, tool ->
                val available = tool.availability == ToolAvailability.Available
                ToolRow(
                    label = tool.displayName,
                    sub = "${tool.availability.label()} · ${tool.capabilityKind.name.lowercase()}",
                    divider = index > 0,
                ) {
                    ToolChip("测试", {
                        scope.launch {
                            val outcome = runCatching { registry.test(tool.toolId) }
                                .getOrElse { ToolTestResult(tool.toolId, false, it.message ?: "测试失败") }
                            ui.toast(outcome.message)
                        }
                    }, enabled = available)
                    ToolSwitch(
                        checked = tool.toolId in state.enabledToolIds,
                        onChange = { registry.setEnabled(tool.toolId, it) },
                        enabled = available,
                    )
                }
            }
        }
    }
}

private fun ToolAvailability.label(): String = when (this) {
    ToolAvailability.Available -> "可用"
    ToolAvailability.Unconfigured -> "未配置"
    ToolAvailability.NoPermission -> "无权限"
    ToolAvailability.ConnectionFailed -> "连接失败"
}
