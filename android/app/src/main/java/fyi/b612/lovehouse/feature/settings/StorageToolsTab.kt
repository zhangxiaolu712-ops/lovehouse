package fyi.b612.lovehouse.feature.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

private val StorageTypes = listOf("VPS SQLite", "Neon", "本地 SQLite", "Railway", "Supabase", "Cloudflare R2")
private val StorageDomains = listOf("App 账号 / 身份", "生活记忆 / 小客厅", "聊天记录正文", "Engineering", "附件", "其他")

private fun typeIndexOf(service: StorageService): Int = when (service.letter) {
    "V" -> 0
    "N" -> 1
    "L" -> 2
    "R" -> 3
    "S" -> 4
    "C" -> 5
    else -> 1
}

/**
 * 「存储」标签（定稿 HTML 的 #tab-db）：
 *   「数据去向」玻璃卡片（每类数据：标题 + 备用 mini 下拉 + 主用下拉）
 *   「存储连接」分组标签 + 可展开的服务卡片。
 * 目前没有数据来源，内容是 HTML 里的示例数据（预览），所有改动不会保存。
 */
@Composable
internal fun StorageTab(
    ps: ToolPreviewState,
    onOpenDetail: (id: String?, title: String) -> Unit,
) {
    val ui = LocalToolUi.current
    val st = ps.storage
    var openIds by remember { mutableStateOf<Set<String>?>(null) }
    val opened = openIds ?: setOfNotNull(st.services.firstOrNull()?.id)

    ToolSection("数据去向", "每类数据存在哪")
    ToolGlass {
        st.routes.forEachIndexed { index, route ->
            if (index > 0) androidx.compose.foundation.layout.Box(Modifier.fillMaxWidth().height(1.dp).background(ToolStyle.Line))
            Row(
                Modifier.fillMaxWidth().heightIn(min = 52.dp).padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    ToolText(route.label)
                    Row(Modifier.padding(top = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                        ToolText("备用", size = ToolStyle.Cap, color = ToolStyle.Ink2)
                        ToolSelect(
                            label = if (route.backupId.isEmpty()) "无" else st.nameOf(route.backupId),
                            options = listOf("" to "无") + st.services.map { it.id to it.name },
                            onPick = { picked ->
                                st.routes = st.routes.mapIndexed { i, r -> if (i == index) r.copy(backupId = picked) else r }
                                ui.toast("「${route.label}」备用：${if (picked.isEmpty()) "无" else st.nameOf(picked)}（预览，未保存）")
                            },
                            modifier = Modifier.padding(start = 4.dp),
                            mini = true,
                        )
                    }
                }
                ToolSelect(
                    label = st.nameOf(route.primaryId),
                    options = st.services.map { it.id to it.name },
                    onPick = { picked ->
                        if (picked != route.primaryId) {
                            val extra = if (index == 2) "聊天记录正文目前只在这台手机上；改到服务器库，需要后端先支持同步。" else ""
                            ui.confirm(
                                "切换存放位置？",
                                "「${route.label}」改存到 ${st.nameOf(picked)} 后，新数据写入 ${st.nameOf(picked)}；已有数据不会自动搬走，需要迁移时由后端另行执行。$extra",
                                ok = "切换",
                            ) {
                                st.routes = st.routes.mapIndexed { i, r -> if (i == index) r.copy(primaryId = picked) else r }
                                ui.toast("已切换（预览，未保存）")
                            }
                        }
                    },
                )
            }
        }
    }

    ToolSection("存储连接", "${st.services.size} 个")
    var lastGroup: String? = null
    st.services.forEach { service ->
        if (service.group != lastGroup) {
            ToolGroupLabel(service.group)
            lastGroup = service.group
        }
        ToolServerCard(
            letter = service.letter,
            title = service.name,
            sub = "${service.sub} · ${service.accs.size} 个连接",
            expanded = service.id in opened,
            onToggle = { openIds = if (service.id in opened) opened - service.id else opened + service.id },
        ) {
            if (service.note.isNotBlank()) ToolNoteLine(service.note)
            ToolStatusLine(if (service.idle) ToolStyle.Idle else ToolStyle.Ok, service.status)
            ToolActions {
                ToolChip("测试连接", { ui.toast("预览：存储还没接后端，无法真实测试") }, icon = ToolIconKind.Refresh)
                ToolChip("重命名", {
                    ui.prompt("重命名", "只改显示名称，不影响连接地址。", service.name) { v ->
                        if (v.isNotBlank()) st.services = st.services.map { if (it.id == service.id) it.copy(name = v) else it }
                        ui.toast(ToolPreviewToast)
                    }
                }, icon = ToolIconKind.Edit)
                ToolChip("备注", {
                    ui.prompt("备注", "写给自己看的说明。", service.note) { v ->
                        st.services = st.services.map { if (it.id == service.id) it.copy(note = v) else it }
                        ui.toast(ToolPreviewToast)
                    }
                }, icon = ToolIconKind.Note)
                ToolChip("删除", {
                    if (service.accs.isNotEmpty()) {
                        ui.inform("暂时不能删除", "这里仍有 ${service.accs.size} 个连接。请先全部移除，再删除整个服务。")
                    } else {
                        ui.confirm("删除这一项？", "删除后它的相关配置会一并移除。", ok = "删除", danger = true) {
                            st.services = st.services.filterNot { it.id == service.id }
                            ui.toast(ToolPreviewToast)
                        }
                    }
                }, icon = ToolIconKind.Trash, danger = true)
            }
            ToolAccLabel("已挂载的连接", "+ 新增连接") { onOpenDetail(null, "接入新连接") }
            service.accs.forEachIndexed { accIndex, acc ->
                ToolAcc(
                    name = acc.name,
                    meta = acc.meta,
                    checked = acc.on,
                    onChecked = { on ->
                        st.services = st.services.map { s ->
                            if (s.id == service.id) s.copy(accs = s.accs.mapIndexed { i, a -> if (i == accIndex) a.copy(on = on) else a }) else s
                        }
                        ui.toast(ToolPreviewToast)
                    },
                ) {
                    ToolAccLink("数据与权限", { onOpenDetail(service.id, "数据与权限") })
                    ToolAccLink("移除", {
                        ui.confirm("移除这个连接？", "只移除这一项的连接，服务本身保留。", ok = "移除", danger = true) {
                            st.services = st.services.map { s ->
                                if (s.id == service.id) s.copy(accs = s.accs.filterIndexed { i, _ -> i != accIndex }) else s
                            }
                            ui.toast(ToolPreviewToast)
                        }
                    }, danger = true)
                }
            }
            if (service.accs.isEmpty()) ToolNoteLine("还没有连接，点上面的“新增连接”添加。")
        }
    }
}

/** 二级页「存储详情」（定稿 HTML 的 #pageFormDb）。保存只给出预览提示。 */
@Composable
internal fun StorageDetailPage(id: String?, ps: ToolPreviewState, onDone: () -> Unit) {
    val ui = LocalToolUi.current
    val st = ps.storage
    val service = id?.let { key -> st.services.firstOrNull { it.id == key } }
    var name by remember(id) { mutableStateOf(service?.accs?.firstOrNull()?.name ?: service?.name.orEmpty()) }
    var type by remember(id) { mutableStateOf(service?.let(::typeIndexOf) ?: 1) }
    var address by remember(id) { mutableStateOf("") }
    var note by remember(id) { mutableStateOf(service?.note.orEmpty()) }
    var credential by remember(id) { mutableStateOf("") }
    var usage by remember(id) { mutableStateOf(0) }
    var domains by remember(id) {
        mutableStateOf(
            st.routes.filter { it.primaryId == service?.id }
                .map { StorageDomains.indexOf(it.label) }.filter { it >= 0 }.toSet(),
        )
    }
    var writable by remember(id) { mutableStateOf(1) }

    ToolFormCard("连接信息") {
        ToolField("名称") { ToolInput(name, { name = it }) }
        ToolField("类型") { ToolPills(StorageTypes, setOf(type), { type = it }) }
        ToolField("连接地址 (URL / Host)") { ToolInput(address, { address = it }, placeholder = "https://") }
        ToolField("备注") { ToolInput(note, { note = it }, placeholder = "给自己看的说明", multiline = true) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
            ToolLink("测试连接", { ui.toast("预览：存储还没接后端，无法真实测试") })
            Row(verticalAlignment = Alignment.CenterVertically) {
                ToolDot()
                ToolText("上次测试：通过", size = ToolStyle.Sub, color = ToolStyle.Ok)
            }
        }
    }
    ToolFormCard("凭证") {
        ToolField("API Key / 连接串", last = true) {
            ToolInput(credential, { credential = it }, secret = true)
            ToolHint("只提交给后端保存，前端不留存，也不会明文回显。")
        }
    }
    ToolFormCard("使用方式与权限") {
        ToolField("使用方式") { ToolPills(listOf("主用", "备用"), setOf(usage), { usage = it }) }
        ToolField("存放的数据") {
            ToolPills(StorageDomains, domains, { index -> domains = if (index in domains) domains - index else domains + index })
        }
        ToolField("读写权限", last = true) { ToolSeg(listOf("只读", "读写"), writable, { writable = it }) }
    }
    ToolSaveButton("保存", onClick = {
        ui.toast(ToolPreviewToast)
        onDone()
    })
}
