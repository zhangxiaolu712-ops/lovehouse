package fyi.b612.lovehouse.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import fyi.b612.lovehouse.core.permissions.PermissionStatusProvider
import fyi.b612.lovehouse.core.storage.LocalStorage
import fyi.b612.lovehouse.core.designsystem.LoveHouseGlass
import fyi.b612.lovehouse.core.designsystem.LoveHouseIcon
import fyi.b612.lovehouse.core.designsystem.LoveHouseIconView

private val Ink = Color(0xFF3F4948)
private val Muted = Color(0xFF7B8785)
private val Accent = Color(0xFF728F88)
private val Hairline = Color.White.copy(alpha = .42f)
private val Glass = Color.White.copy(alpha = .48f)
private val SoftGlass = Color.White.copy(alpha = .32f)

private data class SettingEntry(
    val title: String,
    val subtitle: String,
    val value: String = "",
    val icon: LoveHouseIcon,
)

private data class SettingGroup(val title: String, val entries: List<SettingEntry>)

private val groups = listOf(
    SettingGroup("账号与个性化", listOf(
        SettingEntry("我的个人资料", "头像、昵称与个人简介", "婷", LoveHouseIcon.Contact),
        SettingEntry("美化", "字体、图标、主题与聊天样式", "雾蓝", LoveHouseIcon.Star),
        SettingEntry("AI 档案管理", "人格、专属记忆与头像", "2 个", LoveHouseIcon.Chat),
    )),
    SettingGroup("系统能力", listOf(
        SettingEntry("权限", "查看与管理系统权限", "6/8", LoveHouseIcon.Settings),
        SettingEntry("通知", "消息、任务与审批提醒", "已开启", LoveHouseIcon.Bell),
        SettingEntry("AI 权限", "AI 可读取与使用的信息边界", "受限", LoveHouseIcon.CatPawSend),
        SettingEntry("语音", "每个人格的 Voice · 语音输入与通话", "未连接", LoveHouseIcon.Mic),
        SettingEntry("天气与时间", "Global Location · 天气与当地时间", "未同步", LoveHouseIcon.Weather),
        SettingEntry("主动唤醒", "独立的主动唤醒服务", "尚未启用", LoveHouseIcon.Bell),
        SettingEntry("工具添加", "管理 API、MCP 与本地工具", "5 个", LoveHouseIcon.Wrench),
    )),
    SettingGroup("本机与设备", listOf(
        SettingEntry("本地资源", "照片、文件与离线资源", "12.8 GB", LoveHouseIcon.File),
        SettingEntry("设备", "本机 · Nearby BLE · Trusted Devices", "", LoveHouseIcon.Computer),
        SettingEntry("密码库 / Secret Vault", "集中保存密码、API Key 与恢复码", "尚未启用", LoveHouseIcon.Settings),
        SettingEntry("隐私锁", "进入验证与敏感内容保护", "指纹", LoveHouseIcon.Settings),
        SettingEntry("同步", "云端同步、冲突与离线状态", "正常", LoveHouseIcon.Regenerate),
    )),
    SettingGroup("数据与版本", listOf(
        SettingEntry("控制台", "LoveHouse 全屋运行状态中心", "状态未知", LoveHouseIcon.Computer),
        SettingEntry("工作项目", "工程目录与当前项目", "LoveHouse", LoveHouseIcon.Wrench),
        SettingEntry("数据与迁移", "独立的数据备份与迁移能力", "尚未启用", LoveHouseIcon.Forward),
        SettingEntry("版本更新", "版本信息与更新通道", "0.1.0", LoveHouseIcon.Regenerate),
    )),
)

@Composable
fun SettingsScreen(
    localStorage: LocalStorage,
    permissionStatusProvider: PermissionStatusProvider,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory()),
) {
    var selected by remember { mutableStateOf<SettingEntry?>(null) }
    BackHandler(enabled = selected != null) { selected = null }
    AnimatedContent(selected, label = "settings-page") { detail ->
        if (detail == null) {
            SettingsHome(modifier.statusBarsPadding().navigationBarsPadding(), onSelect = { selected = it })
        } else {
            SettingsDetail(
                detail,
                localStorage = localStorage,
                permissionStatusProvider = permissionStatusProvider,
                onBack = { selected = null },
                modifier = modifier.statusBarsPadding().navigationBarsPadding(),
            )
        }
    }
}

@Composable
private fun SettingsHome(modifier: Modifier, onSelect: (SettingEntry) -> Unit) {
    var query by remember { mutableStateOf("") }
    val filtered = remember(query) {
        if (query.isBlank()) groups else groups.map { group ->
            group.copy(entries = group.entries.filter {
                it.title.contains(query, ignoreCase = true) || it.subtitle.contains(query, ignoreCase = true)
            })
        }.filter { it.entries.isNotEmpty() }
    }
    Column(modifier.fillMaxSize()) {
        SettingsTopBar(title = "设置", subtitle = "LoveHouse")
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 14.dp, end = 14.dp, top = 7.dp, bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(SettingsSpacing.CardGap),
        ) {
            item { AccountHero() }
            item { SettingsSearch(query, onValueChange = { query = it }) }
            if (filtered.isEmpty()) item { EmptySearch(query) }
            filtered.forEach { group ->
                item(key = group.title) { GroupLabel(group.title) }
                item(key = "${group.title}-card") {
                    SettingsGroupCard(group.entries, onSelect)
                }
            }
        }
    }
}

@Composable
private fun SettingsTopBar(title: String, subtitle: String? = null, onBack: (() -> Unit)? = null) {
    Box(
        Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp),
    ) {
        if (onBack != null) {
            IconTouch(icon = LoveHouseIcon.Back, label = "返回", onClick = onBack, modifier = Modifier.align(Alignment.CenterStart))
        }
        Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Bold)
            if (subtitle != null) Text(subtitle, color = Muted, fontSize = 8.sp, maxLines = 1)
        }
    }
}

@Composable
private fun AccountHero() {
    GlassPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(54.dp).clip(CircleShape).background(Color(0xFFD4E0DD).copy(alpha = .88f)),
                contentAlignment = Alignment.Center,
            ) { Text("婷", color = Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("婷", color = Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text("LoveHouse", color = Accent, fontSize = 12.sp, fontWeight = FontWeight.Medium)
                Text("账号 · 云服务 · 设备管理", color = Muted, fontSize = 11.sp)
            }
            LoveHouseIconView(LoveHouseIcon.Expand, null, Modifier.size(16.dp), tint = Muted)
        }
    }
}

@Composable
private fun SettingsSearch(value: String, onValueChange: (String) -> Unit) {
    Surface(shape = RoundedCornerShape(14.dp), color = Glass, border = androidx.compose.foundation.BorderStroke(1.dp, Hairline)) {
        Row(Modifier.fillMaxWidth().height(40.dp).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            LoveHouseIconView(LoveHouseIcon.Search, null, Modifier.size(17.dp), tint = Muted)
            Spacer(Modifier.width(8.dp))
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.weight(1f),
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = Ink),
                decorationBox = { inner -> if (value.isEmpty()) Text("搜索设置项", color = Muted, fontSize = 13.sp); inner() },
            )
            if (value.isNotEmpty()) IconTouch(LoveHouseIcon.Close, "清空", { onValueChange("") }, Modifier.size(30.dp))
        }
    }
}

@Composable
private fun GroupLabel(title: String) {
    Text(title, modifier = Modifier.padding(start = 5.dp, top = 2.dp), color = Muted, fontSize = 11.sp, fontWeight = FontWeight.Medium)
}

@Composable
private fun SettingsGroupCard(entries: List<SettingEntry>, onSelect: (SettingEntry) -> Unit) {
    Surface(shape = RoundedCornerShape(17.dp), color = Glass, border = androidx.compose.foundation.BorderStroke(1.dp, Hairline)) {
        Column {
            entries.forEachIndexed { index, entry ->
                SettingRow(entry, onClick = { onSelect(entry) })
                if (index != entries.lastIndex) HorizontalDivider(Modifier.padding(start = 54.dp), color = Color.White.copy(alpha = .38f))
            }
        }
    }
}

@Composable
private fun SettingRow(entry: SettingEntry, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(34.dp).clip(RoundedCornerShape(11.dp)).background(Color.White.copy(alpha = .46f)), contentAlignment = Alignment.Center) {
            LoveHouseIconView(entry.icon, null, Modifier.size(19.dp), tint = Accent)
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text(entry.title, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
            Text(entry.subtitle, color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        if (entry.value.isNotBlank()) Text(entry.value, color = Muted, fontSize = 10.sp, modifier = Modifier.padding(start = 6.dp))
        LoveHouseIconView(LoveHouseIcon.Expand, null, Modifier.size(14.dp), tint = Muted.copy(alpha = .72f))
    }
}

@Composable
private fun EmptySearch(query: String) {
    GlassPanel { Text("没有找到“$query”", modifier = Modifier.fillMaxWidth(), color = Muted, fontSize = 13.sp) }
}

@Composable
private fun SettingsDetail(
    entry: SettingEntry,
    localStorage: LocalStorage,
    permissionStatusProvider: PermissionStatusProvider,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val consoleConnections = remember(entry.title) { ConsoleConnectionsState() }
    val consoleNested = entry.title == "控制台" && consoleConnections.page != ConnectionPage.Overview
    BackHandler(enabled = consoleNested) { consoleConnections.back() }
    Column(modifier.fillMaxSize()) {
        SettingsTopBar(if (consoleNested) "接口与连接" else entry.title, entry.subtitle) {
            if (consoleNested) consoleConnections.back() else onBack()
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(14.dp, 8.dp, 14.dp, 30.dp),
            verticalArrangement = Arrangement.spacedBy(SettingsSpacing.CardGap),
        ) {
            when (entry.title) {
                "我的个人资料" -> item { SettingsCardStack { OwnerProfileSettings(localStorage) } }
                "美化" -> appearanceDetail()
                "AI 档案管理" -> item { SettingsCardStack { AiProfileManager(localStorage) } }
                "权限" -> item { SettingsCardStack { NativeCapabilitySettings(permissionStatusProvider) } }
                "通知" -> {
                    item { NotificationProductSettings(permissionStatusProvider) }
                    toggleDetail(notificationRows)
                }
                "AI 权限" -> {
                    item { AiPermissionExplanation() }
                    toggleDetail(proactiveRows)
                }
                "语音" -> item { SettingsCardStack { PersonaVoiceSettings(localStorage) } }
                "天气与时间" -> item { SettingsCardStack { GlobalLocationSettings() } }
                "工具添加" -> toolsDetail()
                "本地资源" -> {
                    item { LocalResourceSettings(permissionStatusProvider) }
                    storageDetail()
                }
                "设备" -> {
                    item { SettingsCardStack { DeviceProductSettings(permissionStatusProvider); TrustedDevicesSettings() } }
                }
                "隐私锁" -> {
                    item { BiometricProductSettings() }
                    privacyDetail()
                }
                "同步" -> syncDetail()
                "控制台" -> item { SettingsCardStack { HouseStatusConsole(consoleConnections) } }
                "密码库 / Secret Vault" -> item { FutureSettingsPanel("独立安全模块", "Secret Vault 将作为独立安全子系统提供，目前尚未启用。不会保存密码、API Key 或恢复码，也不会允许 AI 读取 Secret。") }
                "主动唤醒" -> item { FutureSettingsPanel("主动唤醒", "尚未启用。未来主动唤醒服务独立接线，本页不启动 scheduler 或 Agent。") }
                "工作项目" -> projectDetail()
                "数据与迁移" -> item { FutureSettingsPanel("数据与迁移", "尚未启用。本页不会启动备份、导入、恢复或覆盖现有资料。") }
                "版本更新" -> versionDetail()
            }
        }
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.accountDetail() {
    item { DetailHero("婷", "LoveHouse Owner", "账号与云服务已连接", LoveHouseIcon.Contact) }
    item { DetailSection("管理", listOf("个人资料" to "昵称、头像与简介", "账号与安全" to "登录方式与安全记录", "云服务" to "同步状态正常")) }
    item { DetailSection("常用设备", listOf("备用机" to "当前设备 · 在线", "新电脑" to "Windows · 最近活跃")) }
    item { DetailSection("安全记录", listOf("本机验证" to "今天 09:18", "新电脑登录" to "昨天 22:41")) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.appearanceDetail() {
    item { ChoicePanel("字体", listOf("默认", "圆体", "衬线"), 0) }
    item { ChoicePanel("图标样式", listOf("LoveHouse", "线性", "柔和"), 0) }
    item { ChoicePanel("主题", listOf("跟随壁纸", "浅色", "深色"), 0) }
    item { ChoicePanel("背景", listOf("雾蓝", "森绿", "柔粉", "自定义"), 0) }
    item { ChoicePanel("聊天气泡", listOf("玻璃", "轻雾", "简洁"), 0) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.personaDetail() {
    item { PersonaCard("G 老师", "稳定 Persona · 专属记忆", "GPT-5.6") }
    item { PersonaCard("小克", "长期陪伴 · 独立记忆", "Claude") }
    item { MockAction("添加 AI 档案", "选择或导入一个 Persona") }
}

private fun androidx.compose.foundation.lazy.LazyListScope.permissionDetail(onOpenNativeLab: () -> Unit) {
    item { DetailSection("系统权限", listOf("相机" to "询问时允许", "照片与文件" to "允许", "麦克风" to "允许", "位置" to "使用时允许", "通知" to "允许")) }
    item { MockAction("原生能力状态", "查看设备当前实际权限", onOpenNativeLab) }
}

private val notificationRows = listOf("新消息" to true, "任务进度" to true, "等待审批" to true, "完成与失败" to true, "静默时段" to false)
private val proactiveRows = listOf("允许读取设备状态" to true, "允许使用当前位置" to false, "允许读取已选择文件" to true, "允许主动发起对话" to true, "外部写入前确认" to true)

@Composable
private fun AiPermissionExplanation() {
    GlassPanel {
        Text("LoveHouse AI 授权层", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text("LoveHouse 已取得某项系统能力后，AI 是否可以通过工具使用它。这里不会改变 Android 系统权限，也不负责配置 MCP、Runtime 或 Provider。", color = Muted, fontSize = 9.sp)
    }
}

private fun androidx.compose.foundation.lazy.LazyListScope.toggleDetail(rows: List<Pair<String, Boolean>>) {
    item { TogglePanel(rows) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.toolsDetail() {
    item { ToolsManager() }
}

private fun androidx.compose.foundation.lazy.LazyListScope.storageDetail() {
    item { StorageCard() }
    item { DetailSection("分类", listOf("照片与视频" to "6.4 GB", "文件与附件" to "3.1 GB", "离线模型资源" to "2.8 GB", "缓存" to "0.5 GB")) }
    item { MockAction("清理临时缓存", "不会删除聊天、记忆或正式产物") }
}

private fun androidx.compose.foundation.lazy.LazyListScope.privacyDetail() {
    item { TogglePanel(listOf("打开 LoveHouse 时验证" to true, "隐藏最近任务预览" to true, "敏感操作再次验证" to true)) }
    item { ChoicePanel("验证方式", listOf("指纹", "系统密码"), 0) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.syncDetail() {
    item { DetailHero("同步正常", "刚刚完成", "聊天、设置和正式产物已同步", LoveHouseIcon.Regenerate) }
    item { TogglePanel(listOf("自动同步" to true, "仅 Wi-Fi 同步大文件" to true, "移动网络同步" to false)) }
    item { MockAction("立即同步", "检查本地 Mock 状态") }
}

private fun androidx.compose.foundation.lazy.LazyListScope.projectDetail() {
    item { DetailHero("LoveHouse", "当前工作项目", "D:\\lovehouse\\lovehouse-main", LoveHouseIcon.Wrench) }
    item { DetailSection("项目状态", listOf("分支" to "Android 前端施工线", "工作区" to "已连接", "最近活动" to "刚刚")) }
}

private fun androidx.compose.foundation.lazy.LazyListScope.versionDetail() {
    item { DetailHero("LoveHouse 0.1.0", "Native Android", "当前已是最新版本", LoveHouseIcon.Star) }
    item { ChoicePanel("更新通道", listOf("稳定版", "预览版"), 0) }
    item { MockAction("检查更新", "仅更新本地显示状态") }
}

@Composable
private fun DetailHero(title: String, subtitle: String, description: String, icon: LoveHouseIcon) {
    GlassPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background(Color.White.copy(.44f)), contentAlignment = Alignment.Center) {
                LoveHouseIconView(icon, null, Modifier.size(24.dp), tint = Accent)
            }
            Spacer(Modifier.width(11.dp))
            Column {
                Text(title, color = Ink, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text(subtitle, color = Accent, fontSize = 11.sp)
                Text(description, color = Muted, fontSize = 10.sp)
            }
        }
    }
}

@Composable
private fun DetailSection(title: String, rows: List<Pair<String, String>>) {
    GlassPanel(contentPadding = 0.dp) {
        Text(title, modifier = Modifier.padding(12.dp, 10.dp, 12.dp, 5.dp), color = Muted, fontSize = 11.sp)
        rows.forEachIndexed { index, row ->
            Row(Modifier.fillMaxWidth().clickable { }.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(row.first, Modifier.weight(1f), color = Ink, fontSize = 13.sp)
                Text(row.second, color = Muted, fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(3.dp))
                LoveHouseIconView(LoveHouseIcon.Expand, null, Modifier.size(13.dp), tint = Muted)
            }
            if (index != rows.lastIndex) HorizontalDivider(Modifier.padding(start = 12.dp), color = Hairline)
        }
    }
}

@Composable
private fun ChoicePanel(title: String, choices: List<String>, initial: Int) {
    var selected by remember { mutableIntStateOf(initial) }
    GlassPanel {
        Text(title, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            choices.forEachIndexed { index, label ->
                Surface(
                    modifier = Modifier.weight(1f).clickable { selected = index },
                    shape = RoundedCornerShape(12.dp),
                    color = if (selected == index) Color(0xFFCADBD6).copy(.82f) else Color.White.copy(.31f),
                    border = androidx.compose.foundation.BorderStroke(1.dp, if (selected == index) Accent.copy(.38f) else Hairline),
                ) { Text(label, Modifier.padding(vertical = 9.dp), color = if (selected == index) Ink else Muted, fontSize = 10.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center) }
            }
        }
    }
}

@Composable
private fun TogglePanel(rows: List<Pair<String, Boolean>>) {
    val values = remember(rows) { mutableStateMapOf<String, Boolean>().apply { putAll(rows.toMap()) } }
    GlassPanel(contentPadding = 0.dp) {
        rows.forEachIndexed { index, row ->
            Row(Modifier.fillMaxWidth().clickable { values[row.first] = !(values[row.first] ?: false) }.padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(row.first, Modifier.weight(1f), color = Ink, fontSize = 13.sp)
                Switch(checked = values[row.first] ?: false, onCheckedChange = { values[row.first] = it }, modifier = Modifier.size(42.dp, 26.dp))
            }
            if (index != rows.lastIndex) HorizontalDivider(Modifier.padding(start = 12.dp), color = Hairline)
        }
    }
}

@Composable
private fun PersonaCard(name: String, summary: String, model: String) {
    GlassPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(CircleShape).background(Color(0xFFD7E1DE)), contentAlignment = Alignment.Center) { Text(name.take(1), color = Ink, fontWeight = FontWeight.SemiBold) }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(summary, color = Muted, fontSize = 10.sp)
            }
            Text(model, color = Accent, fontSize = 10.sp)
        }
    }
}

@Composable
private fun MockAction(title: String, summary: String, action: () -> Unit = {}) {
    var done by remember { mutableStateOf(false) }
    GlassPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(title, color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                Text(if (done) "已完成本地演示" else summary, color = if (done) Accent else Muted, fontSize = 10.sp)
            }
            OutlinedButton(onClick = { action(); done = true }, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) { Text(if (done) "完成" else "打开", fontSize = 10.sp) }
        }
    }
}

@Composable
private fun ToolsManager() {
    var tab by remember { mutableIntStateOf(0) }
    var tested by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    GlassPanel {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("已添加", "添加 API", "添加 MCP").forEachIndexed { index, label ->
                Surface(Modifier.weight(1f).clickable { tab = index }, RoundedCornerShape(11.dp), if (tab == index) Color(0xFFCADBD6).copy(.82f) else SoftGlass) {
                    Text(label, Modifier.padding(vertical = 8.dp), color = if (tab == index) Ink else Muted, fontSize = 10.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                }
            }
        }
        Spacer(Modifier.height(10.dp))
        when (tab) {
            0 -> Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                listOf("LoveHouse", "Files", "GitHub", "Web", "Shell").forEach { ToolRow(it, "可用") }
            }
            else -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(if (tab == 1) "添加 API 连接" else "添加 MCP 服务", color = Ink, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                MiniInput(name, { name = it }, if (tab == 1) "连接名称 / 必要凭据" else "服务名称 / 地址")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { tested = true }, Modifier.weight(1f)) { Text(if (tested) "连接正常" else "测试", fontSize = 10.sp) }
                    Button(onClick = { saved = name.isNotBlank() }, Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = Accent)) { Text(if (saved) "已保存" else "保存", fontSize = 10.sp) }
                }
                Text("本轮仅保存本地 Mock 状态，不会发送凭据或调用后端。", color = Muted, fontSize = 9.sp)
            }
        }
    }
}

@Composable
private fun ToolRow(name: String, status: String) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(Color.White.copy(.27f)).padding(9.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(30.dp).clip(RoundedCornerShape(9.dp)).background(Color.White.copy(.44f)), contentAlignment = Alignment.Center) { LoveHouseIconView(LoveHouseIcon.Wrench, null, Modifier.size(17.dp), tint = Accent) }
        Spacer(Modifier.width(8.dp)); Text(name, Modifier.weight(1f), color = Ink, fontSize = 12.sp); Text(status, color = Accent, fontSize = 10.sp)
    }
}

@Composable
private fun MiniInput(value: String, onValueChange: (String) -> Unit, hint: String) {
    Surface(shape = RoundedCornerShape(12.dp), color = Color.White.copy(.34f), border = androidx.compose.foundation.BorderStroke(1.dp, Hairline)) {
        BasicTextField(value, onValueChange, Modifier.fillMaxWidth().padding(11.dp), singleLine = true, textStyle = MaterialTheme.typography.bodySmall.copy(color = Ink), decorationBox = { inner -> if (value.isEmpty()) Text(hint, color = Muted, fontSize = 11.sp); inner() })
    }
}

@Composable
private fun StorageCard() {
    GlassPanel {
        Text("本地资源", color = Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text("已使用 12.8 GB / 64 GB", color = Muted, fontSize = 10.sp)
        Spacer(Modifier.height(9.dp))
        Box(Modifier.fillMaxWidth().height(7.dp).clip(CircleShape).background(Color.White.copy(.38f))) {
            Box(Modifier.fillMaxWidth(.20f).height(7.dp).background(Accent))
        }
    }
}

@Composable
private fun GlassPanel(contentPadding: androidx.compose.ui.unit.Dp = 12.dp, content: @Composable ColumnScope.() -> Unit) {
    Surface(shape = RoundedCornerShape(17.dp), color = Glass, border = androidx.compose.foundation.BorderStroke(1.dp, Hairline)) {
        Column(Modifier.fillMaxWidth().padding(contentPadding), content = content)
    }
}

@Composable
private fun IconTouch(icon: LoveHouseIcon, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.size(40.dp).clip(CircleShape).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        LoveHouseIconView(icon, label, Modifier.size(20.dp), tint = LoveHouseGlass.Ink)
    }
}
