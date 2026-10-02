package fyi.b612.lovehouse.feature.settings

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/**
 * 二级页「工具与权限」——对应定稿 HTML 的 #pageForm。
 *
 * HTML → Compose：
 *   .form-card「连接信息」  → ToolFormCard（名称 / 服务地址 / 备注 / 测试连接 + 发现 N 个可注册工具）
 *   .form-card「账号与人格档案」 → 账号备注、接入方式 pills、Token、挂到哪些人格档案 pills、用途 pills
 *   .form-card「工具与权限 · N 个」 → ToolBlock（等宽工具名 + 开关 + 分段条 + 查看字段）
 *   .save-btn → ToolSaveButton
 *
 * 真实接线：人格档案的挂载 / 解绑在点「保存」时调用 App Backend；其余字段后端还没有，
 * 按预览处理（保存时提示不会保存）。
 */
@Composable
internal fun McpDetailPage(
    model: McpToolsModel,
    repository: McpConnectionRepository,
    ps: ToolPreviewState,
    serviceId: String,
    connectionId: String,
    onReload: () -> Unit,
    onDone: () -> Unit,
) {
    val ui = LocalToolUi.current
    val scope = rememberCoroutineScope()
    val service = model.services.firstOrNull { it.id == serviceId }
    val connection = model.byService[serviceId].orEmpty().firstOrNull { it.id == connectionId }
    if (service == null || connection == null) {
        ToolEmpty("这个账号已不存在，请返回后刷新。")
        return
    }

    var name by remember(serviceId) {
        mutableStateOf(ps.text("svc.name.$serviceId") ?: service.displayName ?: service.name ?: connection.displayName())
    }
    var url by remember(connectionId) { mutableStateOf(connection.serverUrl) }
    var note by remember(serviceId) { mutableStateOf(ps.text("svc.note.$serviceId").orEmpty()) }
    var accNote by remember(connectionId) { mutableStateOf(connection.displayName()) }
    var cli by remember(connectionId) { mutableStateOf(ps.flag("acc.cli.$connectionId", false)) }
    var token by remember(connectionId) { mutableStateOf("") }
    var bound by remember(connectionId, connection.boundIdentityIds) { mutableStateOf(connection.boundIdentityIds.toSet()) }
    var allPersonas by remember(connectionId) { mutableStateOf(false) }
    var tags by remember(serviceId) { mutableStateOf(emptySet<String>()) }
    var customTags by remember(serviceId) { mutableStateOf(emptyList<String>()) }
    val toolOn = remember(connectionId) { mutableStateMapOf<String, Boolean>() }
    val toolMode = remember(connectionId) { mutableStateMapOf<String, Int>() }
    val schemaOpen = remember(connectionId) { mutableStateMapOf<String, Boolean>() }
    val tools = connection.tools.distinctBy { it.name }

    ToolFormCard("连接信息") {
        ToolField("名称") { ToolInput(name, { name = it }) }
        ToolField("服务地址 (URL，原样保存)") { ToolInput(url, { url = it }) }
        ToolField("备注") { ToolInput(note, { note = it }, placeholder = "这个服务是做什么的，给自己看", multiline = true) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            ToolLink("测试连接", { ui.toast("正在重新读取 App Backend…"); onReload() })
            Row(verticalAlignment = Alignment.CenterVertically) {
                ToolDot()
                ToolText("发现 ${connection.toolCount} 个可注册工具", size = ToolStyle.Sub, color = ToolStyle.Ok)
            }
        }
    }

    ToolFormCard("账号与人格档案") {
        ToolField("账号备注") { ToolInput(accNote, { accNote = it }) }
        ToolField("接入方式") {
            ToolPills(listOf("官方 App · 仅 URL", "CLI · Token"), setOf(if (cli) 1 else 0), { cli = it == 1 })
        }
        if (cli) {
            ToolField("Token") {
                ToolInput(token, { token = it }, secret = true)
                ToolHint("只提交给后端保存，前端不留存，也不会明文回显。")
            }
        }
        ToolField("挂到哪些人格档案") {
            val personas = model.personas
            val labels = personas.map { it.displayName } + "全部人格档案"
            val selected = buildSet {
                personas.forEachIndexed { index, persona -> if (persona.personaId in bound) add(index) }
                if (allPersonas) add(personas.size)
            }
            ToolPills(labels, selected, { index ->
                if (index == personas.size) {
                    allPersonas = !allPersonas
                } else {
                    val id = personas[index].personaId
                    bound = if (id in bound) bound - id else bound + id
                }
            })
        }
        ToolField("用途", last = true) {
            val presets = listOf("生活服务", "点餐优惠") + customTags
            ToolPills(presets + "+ 自定义", presets.indices.filter { presets[it] in tags }.toSet(), { index ->
                if (index == presets.size) {
                    ui.prompt("自定义用途", "给这个账号加一个用途标签。", "", ok = "添加") { value ->
                        if (value.isNotBlank()) {
                            customTags = customTags + value
                            tags = tags + value
                        }
                    }
                } else {
                    val tag = presets[index]
                    tags = if (tag in tags) tags - tag else tags + tag
                }
            })
        }
    }

    ToolFormCard("工具与权限 · ${tools.size} 个") {
        if (tools.isEmpty()) {
            ToolText("还没有发现工具", size = ToolStyle.Sub, color = ToolStyle.Ink2)
        }
        tools.forEachIndexed { index, tool ->
            val on = toolOn[tool.name] ?: true
            ToolBlock(
                name = tool.name,
                desc = tool.description,
                last = index == tools.lastIndex,
                dimmed = !on,
                trailing = { ToolSwitch(on, { toolOn[tool.name] = it }) },
            ) {
                ToolSeg(listOf("只读", "需确认", "允许"), toolMode[tool.name] ?: 1, { toolMode[tool.name] = it }, enabled = on)
                Row(
                    Modifier.heightIn(min = 36.dp).tap { schemaOpen[tool.name] = !(schemaOpen[tool.name] ?: false) },
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    ToolIcon(ToolIconKind.Code, iconSize = 18.dp, tint = ToolStyle.Accent)
                    ToolText(if (schemaOpen[tool.name] == true) "收起字段" else "查看字段", size = ToolStyle.Sub, color = ToolStyle.Accent)
                }
                if (schemaOpen[tool.name] == true) {
                    ToolText(
                        "App Backend 尚未返回该工具的字段结构",
                        Modifier.padding(end = 4.dp, bottom = 4.dp).fillMaxWidth()
                            .clip(RoundedCornerShape(12.dp)).background(ToolStyle.SchemaBg)
                            .horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp, vertical = 10.dp),
                        size = ToolStyle.Cap,
                        color = ToolStyle.SchemaInk,
                        mono = true,
                    )
                }
            }
        }
    }

    ToolSaveButton("保存并注册工具", onClick = {
        scope.launch {
            val original = connection.boundIdentityIds.toSet()
            val toolServiceId = connection.toolServiceId
            var failure: String? = null
            var personaChanged = false
            if (!toolServiceId.isNullOrBlank()) {
                (bound - original).forEach { personaId ->
                    runCatching { repository.bindIdentity(toolServiceId, personaId, connection.id) }
                        .onSuccess { personaChanged = true }
                        .onFailure { failure = it.message ?: "绑定人格档案失败" }
                }
                (original - bound).forEach { personaId ->
                    runCatching { repository.unbindIdentity(toolServiceId, personaId) }
                        .onSuccess { personaChanged = true }
                        .onFailure { failure = it.message ?: "解绑人格档案失败" }
                }
            }
            ui.toast(
                failure ?: if (personaChanged) "人格档案已更新；其余项还没接后端，未保存" else ToolPreviewToast,
            )
            onReload()
            onDone()
        }
    })
}
