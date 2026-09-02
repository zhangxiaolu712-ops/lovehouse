package fyi.b612.lovehouse.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import fyi.b612.lovehouse.core.designsystem.LoveHouseCard
import fyi.b612.lovehouse.core.designsystem.LoveHouseSpacing
import fyi.b612.lovehouse.core.designsystem.SectionLabel
import fyi.b612.lovehouse.core.designsystem.StatusPill

@Composable
fun SettingsScreen(
    onOpenNativeLab: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory()),
) {
    val connections by viewModel.connections.collectAsStateWithLifecycle()
    val addState by viewModel.addConnectionState.collectAsStateWithLifecycle()
    var showAddConnection by remember { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize().safeDrawingPadding(),
        contentPadding = PaddingValues(
            start = LoveHouseSpacing.Page,
            end = LoveHouseSpacing.Page,
            top = LoveHouseSpacing.XLarge,
            bottom = LoveHouseSpacing.XLarge,
        ),
        verticalArrangement = Arrangement.spacedBy(LoveHouseSpacing.Large),
    ) {
        item {
            SectionLabel("连接入口 · 工程控制台")
            Text("设置", style = MaterialTheme.typography.headlineMedium)
            Text(
                "先用一个入口完成连接；连接成功后，再管理运行方式、能力和权限。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            LoveHouseCard(modifier = Modifier.fillMaxWidth()) {
                Text("添加连接", style = MaterialTheme.typography.titleLarge)
                Text(
                    "输入一次性连接码、邀请码或必要凭据并提交一次。无需理解 Runtime、Endpoint 或 Session。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Button(onClick = { showAddConnection = true }, modifier = Modifier.fillMaxWidth()) { Text("添加连接") }
            }
        }
        item { Text("已连接", style = MaterialTheme.typography.titleMedium) }
        when (val state = connections) {
            ConnectionListState.Loading -> item { SettingsStateCard("正在读取连接…") }
            ConnectionListState.Empty -> item { SettingsStateCard("还没有连接。使用上面的统一入口添加。") }
            is ConnectionListState.Error -> item { SettingsStateCard("连接读取失败：${state.message}") }
            is ConnectionListState.Offline -> {
                item { SettingsStateCard("当前离线，显示最近保存的连接。") }
                items(state.cachedConnections, key = { it.connectionId }) { ConnectionCard(it) }
            }
            is ConnectionListState.Content -> items(state.connections, key = { it.connectionId }) { ConnectionCard(it) }
        }
        item {
            Text("工程控制", style = MaterialTheme.typography.titleMedium)
            Text(
                "仅在连接完成后展开。当前为本地 UI 骨架，不调用后端管理接口。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        items(EngineeringControlArea.entries, key = { it.name }) { EngineeringAreaCard(it) }
        item {
            LoveHouseCard(modifier = Modifier.fillMaxWidth()) {
                Text("原生能力", style = MaterialTheme.typography.titleMedium)
                HorizontalDivider(color = MaterialTheme.colorScheme.outline.copy(alpha = 0.35f))
                Row(
                    modifier = Modifier.fillMaxWidth().clickable(onClick = onOpenNativeLab).padding(vertical = LoveHouseSpacing.Small),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("原生能力测试", style = MaterialTheme.typography.titleLarge)
                        Text("查看这台设备已经开放给 LoveHouse 的能力。", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    StatusPill("阶段 0")
                }
            }
        }
    }

    if (showAddConnection) {
        AddConnectionDialog(
            state = addState,
            onSubmit = viewModel::addConnection,
            onDismiss = {
                showAddConnection = false
                viewModel.dismissAddConnection()
            },
        )
    }
}

@Composable
private fun AddConnectionDialog(state: AddConnectionState, onSubmit: (String) -> Unit, onDismiss: () -> Unit) {
    var credential by remember { mutableStateOf("") }
    val waiting = state == AddConnectionState.Submitting || state == AddConnectionState.WaitingServerConfirmation
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("添加连接") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(LoveHouseSpacing.Medium)) {
                Text("输入服务提供的一次性连接码、邀请码或必要凭据。")
                OutlinedTextField(
                    value = credential,
                    onValueChange = { credential = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("连接信息") },
                    singleLine = true,
                    enabled = !waiting,
                )
                when (state) {
                    AddConnectionState.WaitingServerConfirmation -> Text("已提交，等待一次服务端确认…", color = MaterialTheme.colorScheme.primary)
                    is AddConnectionState.Error -> Text(state.message, color = MaterialTheme.colorScheme.error)
                    is AddConnectionState.Connected -> Text("连接成功，可以开始使用。", color = MaterialTheme.colorScheme.primary)
                    else -> Unit
                }
                Text(
                    "Mock 只演示状态，不会发送凭据，也不会创建任何后端协议。",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            Button(onClick = { onSubmit(credential) }, enabled = !waiting) {
                Text(if (waiting) "等待确认" else "提交一次")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(if (waiting) "稍后查看" else "取消") } },
    )
}

@Composable
private fun ConnectionCard(connection: ConnectedCapability) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.extraLarge,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)),
    ) {
        Column(modifier = Modifier.padding(LoveHouseSpacing.Large), verticalArrangement = Arrangement.spacedBy(LoveHouseSpacing.Small)) {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(connection.displayName, style = MaterialTheme.typography.titleLarge)
                StatusPill(connection.status.label())
            }
            Text(connection.summary, color = MaterialTheme.colorScheme.onSurfaceVariant)
            val technicalSummary = listOfNotNull(connection.runtimeLabel, connection.modelLabel).joinToString(" · ")
            if (technicalSummary.isNotBlank()) Text(technicalSummary, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            if (connection.capabilities.isNotEmpty()) Text(connection.capabilities.joinToString(" · "), style = MaterialTheme.typography.bodySmall)
            OutlinedButton(onClick = {}, modifier = Modifier.fillMaxWidth()) { Text("管理与工程控制") }
        }
    }
}

@Composable
private fun EngineeringAreaCard(area: EngineeringControlArea) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.28f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(LoveHouseSpacing.Large),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(area.label, style = MaterialTheme.typography.titleMedium)
                Text(area.summary, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            StatusPill("预留")
        }
    }
}

@Composable
private fun SettingsStateCard(message: String) {
    Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))) {
        Text(message, modifier = Modifier.fillMaxWidth().padding(LoveHouseSpacing.Large))
    }
}

private fun ConnectionStatus.label(): String = when (this) {
    ConnectionStatus.Connected -> "已连接"
    ConnectionStatus.WaitingConfirmation -> "等待确认"
    ConnectionStatus.Offline -> "离线"
    ConnectionStatus.Error -> "异常"
}
