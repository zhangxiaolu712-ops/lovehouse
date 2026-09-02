package fyi.b612.lovehouse.feature.chat

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fyi.b612.lovehouse.R
import fyi.b612.lovehouse.core.designsystem.LoveHouseIcon
import fyi.b612.lovehouse.core.designsystem.LoveHouseIconView

private val WorkflowInk = Color(0xFF374443)
private val WorkflowMuted = Color(0xFF83908D)
private val WorkflowAccent = Color(0xFF6F9086)
private val WorkflowGlass = Color(0xDDF5F3EB)

private data class ToolUi(val name: String, val source: String, val state: String, val icon: LoveHouseIcon)

@Composable
fun TaskWorkflowOverlay(
    task: RemoteAgentTask,
    backgroundKey: String,
    onClose: () -> Unit,
    onForward: () -> Unit,
    onAdvance: () -> Unit,
    onDecision: (String, Boolean) -> Unit,
    onJump: (String) -> Unit,
    onWindowAction: (String) -> Unit,
) {
    BackHandler(onBack = onClose)
    WorkflowBackdrop(backgroundKey)
    Surface(
        modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 10.dp, vertical = 14.dp),
        shape = RoundedCornerShape(28.dp), color = WorkflowGlass,
        border = BorderStroke(1.dp, Color.White.copy(alpha = .82f)),
    ) {
        TaskWorkflowContent(task, onClose, onForward, onAdvance, onDecision, onJump, onWindowAction)
    }
}

@Composable
private fun WorkflowBackdrop(backgroundKey: String) {
    when (backgroundKey) {
        "lavender" -> Image(painterResource(R.drawable.wallpaper_chat_lavender), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
        "rose" -> Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFFF1D8DF), Color(0xFFD9B8C5), Color(0xFFF4E9E4)))))
        else -> Image(painterResource(R.drawable.wallpaper_default_green), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

@Composable
private fun TaskWorkflowContent(
    task: RemoteAgentTask,
    onClose: () -> Unit,
    onForward: () -> Unit,
    onAdvance: () -> Unit,
    onDecision: (String, Boolean) -> Unit,
    onJump: (String) -> Unit,
    onWindowAction: (String) -> Unit,
) {
    var expandedNode by remember(task.taskId) { mutableStateOf<String?>(task.workflow.firstOrNull { it.status == WorkflowEventStatus.Current }?.id) }
    var toolsOpen by remember { mutableStateOf(false) }
    var permissionsOpen by remember { mutableStateOf(false) }
    var metadataOpen by remember { mutableStateOf(false) }
    val complete = task.workflow.count { it.status == WorkflowEventStatus.Completed }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 22.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(task.title, color = WorkflowInk, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                    Text("${task.runtime.label} · ${task.status.label} · $complete/${task.workflow.size}", color = WorkflowMuted, fontSize = 9.sp)
                }
                WorkflowIcon(LoveHouseIcon.Forward, "转发 Workflow", onForward)
                WorkflowIcon(LoveHouseIcon.Close, "关闭", onClose)
            }
            Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp), RoundedCornerShape(18.dp), Color.White.copy(alpha = .38f), border = BorderStroke(1.dp, Color.White.copy(alpha = .62f))) {
                Column(Modifier.padding(14.dp)) {
                    Text(task.summary, color = WorkflowInk, fontSize = 11.sp, lineHeight = 16.sp)
                    Text("当前 · ${task.latestMilestone}", Modifier.padding(top = 7.dp), color = WorkflowAccent, fontSize = 9.sp, fontWeight = FontWeight.Medium)
                }
            }
            SectionLabel("WORKFLOW", "完整施工内容保留在正文；节点用于定位与状态总览。")
        }
        task.workflow.forEachIndexed { index, event ->
            item(key = event.id) {
                WorkflowNode(
                    event = event,
                    last = index == task.workflow.lastIndex,
                    expanded = expandedNode == event.id,
                    onToggle = { expandedNode = if (expandedNode == event.id) null else event.id },
                    onDecision = { approved -> onDecision(event.id, approved) },
                    onJump = { onJump(event.id) },
                )
            }
        }
        if (task.status == RemoteTaskStatus.Running) item {
            Text("推进到下一节点", Modifier.padding(horizontal = 58.dp, vertical = 7.dp).clip(RoundedCornerShape(12.dp)).background(WorkflowAccent.copy(alpha = .14f)).clickable(onClick = onAdvance).padding(horizontal = 12.dp, vertical = 9.dp), color = WorkflowAccent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
        item {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusTile("Tools", "6 个 · 1 调用中", LoveHouseIcon.Wrench, toolsOpen, Modifier.weight(1f)) { toolsOpen = !toolsOpen }
                StatusTile("Permissions", if (task.status == RemoteTaskStatus.WaitingApproval) "等待审批" else "受限运行", LoveHouseIcon.Settings, permissionsOpen, Modifier.weight(1f)) { permissionsOpen = !permissionsOpen }
            }
            if (toolsOpen) ToolsDetail()
            if (permissionsOpen) PermissionsDetail(task)
            Surface(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 5.dp).clickable { metadataOpen = !metadataOpen }, RoundedCornerShape(16.dp), Color.White.copy(alpha = .28f)) {
                Row(Modifier.padding(13.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("任务详情 / 窗口设置", Modifier.weight(1f), color = WorkflowInk, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                    Text(if (metadataOpen) "收起" else "展开", color = WorkflowMuted, fontSize = 8.sp)
                }
            }
            if (metadataOpen) Column(Modifier.padding(horizontal = 28.dp, vertical = 8.dp)) {
                Metadata("Persona", "Codex")
                Metadata("创建时间", "今天 11:44")
                Metadata("生命周期", "72h 临时窗口")
                Metadata("附件与产物", "2 项 · 正文查看")
                Metadata("聊天背景", "当前窗口独立背景")
                Row(Modifier.fillMaxWidth().padding(top = 7.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf("保留窗口", "转为长期", "归档").forEach { action ->
                        Text(action, Modifier.clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = .38f)).clickable { onWindowAction(action) }.padding(horizontal = 9.dp, vertical = 7.dp), color = WorkflowInk, fontSize = 8.sp)
                    }
                }
            }
        }
    }
}

@Composable private fun WorkflowNode(event: WorkflowEvent, last: Boolean, expanded: Boolean, onToggle: () -> Unit, onDecision: (Boolean) -> Unit, onJump: () -> Unit) {
    val pulse = if (event.status == WorkflowEventStatus.Current) rememberInfiniteTransition(label = "workflowPulse").animateFloat(.55f, 1f, infiniteRepeatable(tween(850), RepeatMode.Reverse), label = "alpha").value else 1f
    val activeColor by animateColorAsState(when (event.status) {
        WorkflowEventStatus.Completed -> WorkflowAccent
        WorkflowEventStatus.Current -> Color(0xFF7596A8)
        WorkflowEventStatus.WaitingApproval -> Color(0xFFB68B55)
        WorkflowEventStatus.Failed -> Color(0xFFB36D6D)
        else -> WorkflowMuted.copy(alpha = .42f)
    }, label = "nodeColor")
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp).clickable(onClick = onToggle)) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(Modifier.size(22.dp).background(activeColor.copy(alpha = pulse), CircleShape), contentAlignment = Alignment.Center) { Text(event.status.nodeGlyph(), color = Color.White, fontSize = 8.sp, fontWeight = FontWeight.Bold) }
            if (!last) Box(Modifier.width(1.dp).height(if (expanded) 96.dp else 40.dp).background(activeColor.copy(alpha = .34f)))
        }
        Column(Modifier.weight(1f).padding(start = 11.dp, bottom = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(event.action, Modifier.weight(1f), color = if (event.status == WorkflowEventStatus.Pending) WorkflowMuted else WorkflowInk, fontSize = 10.5.sp, fontWeight = FontWeight.Medium)
                Text(event.timestamp, color = WorkflowMuted, fontSize = 7.5.sp)
            }
            Text("${event.stage} · ${event.scope}", color = activeColor, fontSize = 8.sp)
            if (expanded) {
                Text(event.summary, Modifier.padding(top = 6.dp), color = WorkflowMuted, fontSize = 9.sp, lineHeight = 14.sp)
                Text("步骤结果 · ${if (event.status == WorkflowEventStatus.Completed) "已记录" else event.status.labelText()}", Modifier.padding(top = 4.dp), color = WorkflowMuted, fontSize = 8.sp)
                Text("附件 / 产物 · ${if (event.status == WorkflowEventStatus.Completed) "可在正文查看" else "暂无"}", color = WorkflowMuted, fontSize = 8.sp)
                event.approval?.let { approval ->
                    Text("审批 · ${approval.request} · 风险${approval.risk.label}", Modifier.padding(top = 5.dp), color = Color(0xFF9A714C), fontSize = 8.sp)
                    if (event.status == WorkflowEventStatus.WaitingApproval) Row(Modifier.padding(top = 5.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("批准", Modifier.clickable { onDecision(true) }.padding(5.dp), color = WorkflowAccent, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                        Text("拒绝", Modifier.clickable { onDecision(false) }.padding(5.dp), color = Color(0xFF9F6262), fontSize = 9.sp)
                    }
                }
                Text("跳到正文对应位置 ›", Modifier.clickable(onClick = onJump).padding(top = 7.dp, bottom = 4.dp), color = WorkflowAccent, fontSize = 8.5.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable private fun StatusTile(title: String, subtitle: String, icon: LoveHouseIcon, open: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Surface(modifier.clickable(onClick = onClick), RoundedCornerShape(18.dp), Color.White.copy(alpha = if (open) .48f else .3f), border = BorderStroke(1.dp, Color.White.copy(alpha = .58f))) {
        Column(Modifier.padding(12.dp)) {
            Box(Modifier.size(30.dp).background(WorkflowAccent.copy(alpha = .12f), CircleShape), contentAlignment = Alignment.Center) { LoveHouseIconView(icon, null, Modifier.size(15.dp), WorkflowAccent) }
            Text(title, Modifier.padding(top = 8.dp), color = WorkflowInk, fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Text(subtitle, Modifier.padding(top = 2.dp), color = WorkflowMuted, fontSize = 8.sp)
        }
    }
}

@Composable private fun ToolsDetail() {
    val tools = listOf(
        ToolUi("MCP", "DevSpace", "调用中", LoveHouseIcon.Wrench), ToolUi("LoveHouse", "App", "可用", LoveHouseIcon.Home),
        ToolUi("Files", "Workspace", "可用", LoveHouseIcon.File), ToolUi("GitHub", "Connector", "可用", LoveHouseIcon.Contact),
        ToolUi("Web", "Network", "可用", LoveHouseIcon.Search), ToolUi("Shell", "Local", "可用", LoveHouseIcon.Computer),
    )
    Column(Modifier.padding(horizontal = 22.dp, vertical = 4.dp)) { tools.forEach { tool ->
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).background(Color.White.copy(alpha = .48f), CircleShape), contentAlignment = Alignment.Center) { LoveHouseIconView(tool.icon, null, Modifier.size(14.dp), WorkflowAccent) }
            Column(Modifier.weight(1f).padding(start = 9.dp)) { Text(tool.name, color = WorkflowInk, fontSize = 9.sp); Text(tool.source, color = WorkflowMuted, fontSize = 7.5.sp) }
            Text(tool.state, color = if (tool.state == "调用中") Color(0xFF7596A8) else WorkflowAccent, fontSize = 8.sp)
        }
    } }
}

@Composable private fun PermissionsDetail(task: RemoteAgentTask) {
    Column(Modifier.padding(horizontal = 24.dp, vertical = 5.dp)) {
        Metadata("运行边界", "Workspace sandbox")
        Metadata("Network", "允许读取 · 外部写入需审批")
        Metadata("文件 / 命令", "工作区内读写与命令执行")
        Metadata("删除 / 破坏性操作", "禁止 · 必须本人批准")
        Metadata("审批策略", if (task.status == RemoteTaskStatus.WaitingApproval) "有节点正在等待审批" else "按风险触发审批")
        Metadata("临时提权", "未授予")
    }
}

@Composable private fun Metadata(label: String, value: String) { Row(Modifier.fillMaxWidth().padding(vertical = 4.dp)) { Text(label, Modifier.weight(1f), color = WorkflowMuted, fontSize = 8.sp); Text(value, color = WorkflowInk, fontSize = 8.sp) } }
@Composable private fun SectionLabel(title: String, subtitle: String) { Column(Modifier.padding(horizontal = 18.dp, vertical = 13.dp)) { Text(title, color = WorkflowAccent, fontSize = 8.sp, fontWeight = FontWeight.Bold); Text(subtitle, Modifier.padding(top = 3.dp), color = WorkflowMuted, fontSize = 8.sp) } }
@Composable private fun WorkflowIcon(icon: LoveHouseIcon, description: String, onClick: () -> Unit) { Box(Modifier.size(38.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) { LoveHouseIconView(icon, description, Modifier.size(17.dp), WorkflowInk) } }

private fun WorkflowEventStatus.nodeGlyph() = when (this) { WorkflowEventStatus.Completed -> "✓"; WorkflowEventStatus.Current -> "●"; WorkflowEventStatus.Pending -> "○"; WorkflowEventStatus.WaitingApproval -> "!"; WorkflowEventStatus.RequiresLocalUser -> "人"; WorkflowEventStatus.Failed -> "×" }
private fun WorkflowEventStatus.labelText() = when (this) { WorkflowEventStatus.Completed -> "已完成"; WorkflowEventStatus.Current -> "进行中"; WorkflowEventStatus.Pending -> "等待中"; WorkflowEventStatus.WaitingApproval -> "等待审批"; WorkflowEventStatus.RequiresLocalUser -> "需要本人"; WorkflowEventStatus.Failed -> "失败" }
