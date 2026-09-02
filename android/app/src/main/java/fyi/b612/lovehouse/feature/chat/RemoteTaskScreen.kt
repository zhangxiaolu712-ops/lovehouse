package fyi.b612.lovehouse.feature.chat

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import fyi.b612.lovehouse.core.designsystem.LoveHouseCard
import fyi.b612.lovehouse.core.designsystem.LoveHouseColors
import fyi.b612.lovehouse.core.designsystem.LoveHouseSpacing
import fyi.b612.lovehouse.core.designsystem.SectionLabel
import fyi.b612.lovehouse.core.designsystem.StatusPill

@Composable
fun RemoteTaskChatScreen(
    modifier: Modifier = Modifier,
    initiallyOpenTaskId: String? = null,
) {
    var tasks by remember { mutableStateOf(RemoteTaskMocks.scenarios) }
    var previewTaskId by remember { mutableStateOf(tasks.first().taskId) }
    var openTaskId by remember(initiallyOpenTaskId) { mutableStateOf(initiallyOpenTaskId) }
    val previewTask = tasks.first { it.taskId == previewTaskId }
    val openTask = tasks.firstOrNull { it.taskId == openTaskId }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = LoveHouseSpacing.Page),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = LoveHouseSpacing.XLarge),
        verticalArrangement = Arrangement.spacedBy(LoveHouseSpacing.Large),
    ) {
        item {
            SectionLabel("对话房间")
            Text("小客厅", style = MaterialTheme.typography.headlineMedium)
            Text(
                "远程任务会以任务卡出现，不会混进普通聊天气泡。以下内容均为本地 Mock。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        item {
            Text("切换验收场景", style = MaterialTheme.typography.titleMedium)
            LazyRow(horizontalArrangement = Arrangement.spacedBy(LoveHouseSpacing.Small)) {
                items(tasks, key = { it.taskId }) { task ->
                    FilterChip(
                        selected = task.taskId == previewTaskId,
                        onClick = { previewTaskId = task.taskId },
                        label = { Text(task.status.label) },
                    )
                }
            }
        }

        item {
            RemoteTaskCard(task = previewTask, onClick = { openTaskId = previewTask.taskId })
        }

        item {
            Text(
                "点击任务卡查看完整 Workflow。审批按钮只更新本地 Mock 状态。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (openTask != null) {
        RemoteTaskDialog(
            task = openTask,
            onDismiss = { openTaskId = null },
            onApprovalDecision = { eventId, approved ->
                tasks = tasks.map { task ->
                    if (task.taskId == openTask.taskId) task.applyMockApproval(eventId, approved) else task
                }
            },
        )
    }
}

@Composable
fun RemoteTaskWorkflowOverlay(taskId: String, onDismiss: () -> Unit) {
    var tasks by remember { mutableStateOf(RemoteTaskMocks.scenarios) }
    val task = tasks.firstOrNull { it.taskId == taskId } ?: tasks.first()
    RemoteTaskDialog(
        task = task,
        onDismiss = onDismiss,
        onApprovalDecision = { eventId, approved ->
            tasks = tasks.map { candidate ->
                if (candidate.taskId == task.taskId) candidate.applyMockApproval(eventId, approved) else candidate
            }
        },
    )
}

@Composable
private fun RemoteTaskCard(task: RemoteAgentTask, onClick: () -> Unit) {
    LoveHouseCard(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Codex ${task.runtime.label}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            StatusPill(task.status.label, color = task.status.color())
        }
        Text(task.title, style = MaterialTheme.typography.titleLarge)
        Text(
            task.summary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.25f))
        Text("最近进度 · ${task.latestMilestone}", style = MaterialTheme.typography.labelLarge)
        Text(task.updatedAt, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun RemoteTaskDialog(
    task: RemoteAgentTask,
    onDismiss: () -> Unit,
    onApprovalDecision: (String, Boolean) -> Unit,
) {
    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxWidth(0.94f).heightIn(max = 720.dp),
            shape = MaterialTheme.shapes.extraLarge,
            color = MaterialTheme.colorScheme.surface,
            tonalElevation = 6.dp,
        ) {
            Column(modifier = Modifier.padding(LoveHouseSpacing.Large)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Agent Runtime · ${task.runtime.label}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                        Text(task.title, style = MaterialTheme.typography.titleLarge)
                    }
                    Spacer(Modifier.width(LoveHouseSpacing.Small))
                    StatusPill(task.status.label, color = task.status.color())
                }
                Text(
                    task.summary,
                    modifier = Modifier.padding(top = LoveHouseSpacing.Small),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text("任务 ID · ${task.taskId}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                HorizontalDivider(modifier = Modifier.padding(vertical = LoveHouseSpacing.Medium))
                Text("Workflow", style = MaterialTheme.typography.titleMedium)

                LazyColumn(
                    modifier = Modifier.weight(1f, fill = false).padding(top = LoveHouseSpacing.Small),
                    verticalArrangement = Arrangement.spacedBy(LoveHouseSpacing.Small),
                ) {
                    items(task.workflow, key = { it.id }) { event ->
                        WorkflowEventRow(
                            event = event,
                            onApprovalDecision = { approved -> onApprovalDecision(event.id, approved) },
                        )
                    }
                    task.finalResult?.let { result ->
                        item {
                            ResultPanel("最终结果", result, MaterialTheme.colorScheme.primary)
                        }
                    }
                    task.failureReason?.let { reason ->
                        item {
                            ResultPanel("失败原因", reason, MaterialTheme.colorScheme.error)
                            Text(
                                "查看详情（预留）  ·  稍后重试（预留）",
                                style = MaterialTheme.typography.labelLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                OutlinedButton(onClick = onDismiss, modifier = Modifier.fillMaxWidth().padding(top = LoveHouseSpacing.Medium)) {
                    Text("关闭")
                }
            }
        }
    }
}

@Composable
private fun WorkflowEventRow(event: WorkflowEvent, onApprovalDecision: (Boolean) -> Unit) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Box(
                modifier = Modifier
                    .size(22.dp)
                    .background(event.status.color(), CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(event.status.glyph(), color = Color.White, style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Bold)
            }
            Box(Modifier.width(2.dp).height(36.dp).background(MaterialTheme.colorScheme.outline.copy(alpha = 0.3f)))
        }
        Column(modifier = Modifier.weight(1f).padding(start = LoveHouseSpacing.Medium, bottom = LoveHouseSpacing.Small)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(event.action, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                Text(event.timestamp, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("${event.stage} · ${event.scope}", style = MaterialTheme.typography.labelMedium, color = event.status.color())
            Text(event.summary, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            event.approval?.let { approval ->
                ApprovalPanel(approval = approval, onDecision = onApprovalDecision)
            }
            if (event.status == WorkflowEventStatus.RequiresLocalUser) {
                Text(
                    "请本人完成后再继续；此节点不能远程 Approve。",
                    modifier = Modifier.padding(top = LoveHouseSpacing.Small),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.tertiary,
                )
            }
        }
    }
}

@Composable
private fun ApprovalPanel(approval: ApprovalRequest, onDecision: (Boolean) -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(top = LoveHouseSpacing.Small),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.45f)),
    ) {
        Column(modifier = Modifier.padding(LoveHouseSpacing.Medium), verticalArrangement = Arrangement.spacedBy(LoveHouseSpacing.Small)) {
            Text("需要批准", style = MaterialTheme.typography.titleSmall)
            Text("要做什么 · ${approval.request}", style = MaterialTheme.typography.bodyMedium)
            Text("影响范围 · ${approval.impact}", style = MaterialTheme.typography.bodyMedium)
            Text("风险 · ${approval.risk.label}", style = MaterialTheme.typography.labelLarge, color = approval.risk.color())
            Row(horizontalArrangement = Arrangement.spacedBy(LoveHouseSpacing.Small)) {
                Button(onClick = { onDecision(true) }, enabled = approval.risk == ApprovalRisk.Low) {
                    Text(if (approval.risk == ApprovalRisk.Low) "批准" else "询问 GPT")
                }
                OutlinedButton(onClick = { onDecision(false) }) { Text("拒绝") }
            }
        }
    }
}

@Composable
private fun ResultPanel(title: String, text: String, color: Color) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = LoveHouseSpacing.Small),
        colors = CardDefaults.cardColors(containerColor = color.copy(alpha = 0.09f)),
    ) {
        Column(Modifier.padding(LoveHouseSpacing.Medium)) {
            Text(title, style = MaterialTheme.typography.titleSmall, color = color)
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
private fun RemoteTaskStatus.color(): Color = when (this) {
    RemoteTaskStatus.Queued -> MaterialTheme.colorScheme.outline
    RemoteTaskStatus.Running -> MaterialTheme.colorScheme.primary
    RemoteTaskStatus.WaitingApproval -> MaterialTheme.colorScheme.secondary
    RemoteTaskStatus.RequiresLocalUser -> MaterialTheme.colorScheme.tertiary
    RemoteTaskStatus.Completed -> LoveHouseColors.Moss
    RemoteTaskStatus.Failed -> MaterialTheme.colorScheme.error
}

@Composable
private fun WorkflowEventStatus.color(): Color = when (this) {
    WorkflowEventStatus.Completed -> LoveHouseColors.Moss
    WorkflowEventStatus.Current -> MaterialTheme.colorScheme.primary
    WorkflowEventStatus.Pending -> MaterialTheme.colorScheme.outline
    WorkflowEventStatus.WaitingApproval -> MaterialTheme.colorScheme.secondary
    WorkflowEventStatus.RequiresLocalUser -> MaterialTheme.colorScheme.tertiary
    WorkflowEventStatus.Failed -> MaterialTheme.colorScheme.error
}

private fun WorkflowEventStatus.glyph(): String = when (this) {
    WorkflowEventStatus.Completed -> "✓"
    WorkflowEventStatus.Current -> "●"
    WorkflowEventStatus.Pending -> "○"
    WorkflowEventStatus.WaitingApproval -> "!"
    WorkflowEventStatus.RequiresLocalUser -> "人"
    WorkflowEventStatus.Failed -> "×"
}

@Composable
private fun ApprovalRisk.color(): Color = when (this) {
    ApprovalRisk.Low -> LoveHouseColors.Moss
    ApprovalRisk.Medium -> MaterialTheme.colorScheme.secondary
    ApprovalRisk.High -> MaterialTheme.colorScheme.error
}
