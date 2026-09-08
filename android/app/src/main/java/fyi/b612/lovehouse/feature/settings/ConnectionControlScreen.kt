package fyi.b612.lovehouse.feature.settings

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import fyi.b612.lovehouse.core.auth.OwnerSessionSource
import fyi.b612.lovehouse.core.auth.OwnerSessionInput
import fyi.b612.lovehouse.core.auth.OwnerSessionStatus
import fyi.b612.lovehouse.core.auth.OwnerSessionStore
import fyi.b612.lovehouse.core.auth.parseOwnerSessionPayload
import fyi.b612.lovehouse.core.designsystem.LoveHouseCard
import fyi.b612.lovehouse.core.designsystem.LoveHouseSpacing
import fyi.b612.lovehouse.core.designsystem.SectionLabel
import fyi.b612.lovehouse.core.designsystem.StatusPill
import java.text.DateFormat
import java.util.Date
import kotlinx.coroutines.launch

@Composable
fun ConnectionControlScreen(
    ownerSession: OwnerSessionStore,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SettingsViewModel = viewModel(factory = SettingsViewModel.factory()),
) {
    BackHandler(onBack = onBack)
    val connections by viewModel.connections.collectAsStateWithLifecycle()
    val addState by viewModel.addConnectionState.collectAsStateWithLifecycle()
    val ownerSessionState by ownerSession.state.collectAsStateWithLifecycle()
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
            TextButton(onClick = onBack) { Text("返回 Lab") }
            Text("设置", style = MaterialTheme.typography.headlineMedium)
            Text(
                "先用一个入口完成连接；连接成功后，再管理运行方式、能力和权限。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        item {
            OwnerSessionCard(ownerSession, ownerSessionState)
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
private fun OwnerSessionCard(
    ownerSession: OwnerSessionStore,
    state: fyi.b612.lovehouse.core.auth.OwnerSessionSummary,
) {
    var tokenInput by remember { mutableStateOf("") }
    var refreshInput by remember { mutableStateOf("") }
    var sessionPayloadInput by remember { mutableStateOf("") }
    var feedback by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    LoveHouseCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Owner Session", style = MaterialTheme.typography.titleLarge)
                Text(
                    "Chat 与 Tool Center 共用的运行时登录会话",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusPill(
                when (state.status) {
                    OwnerSessionStatus.Active -> "已连接"
                    OwnerSessionStatus.Expired -> "已过期"
                    OwnerSessionStatus.Rejected -> "需重连"
                    OwnerSessionStatus.Missing -> "未连接"
                },
            )
        }
        state.expiresAtEpochSeconds?.let { expiresAt ->
            Text("有效期至 ${formatOwnerSessionExpiry(expiresAt)}", style = MaterialTheme.typography.bodySmall)
        }
        state.fingerprint?.let { fingerprint ->
            Text("会话指纹 $fingerprint", style = MaterialTheme.typography.bodySmall)
        }
        if (state.canRefresh) {
            Text("自动续期已启用", style = MaterialTheme.typography.bodySmall)
        }
        if (state.source == OwnerSessionSource.DebugBootstrap) {
            Text(
                "当前来自 Debug bootstrap；保存运行时会话后将优先使用安全存储。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        OutlinedTextField(
            value = sessionPayloadInput,
            onValueChange = { sessionPayloadInput = it; feedback = null },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Supabase session JSON（推荐）") },
            visualTransformation = PasswordVisualTransformation(),
            supportingText = { Text("可直接粘贴正式登录 session；保存后立即清空且不回显。") },
        )
        OutlinedTextField(
            value = tokenInput,
            onValueChange = { tokenInput = it; feedback = null },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Owner access token") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            supportingText = { Text("仅保存到 Android Keystore 加密的本机私有存储，不会回显或写入日志。") },
        )
        OutlinedTextField(
            value = refreshInput,
            onValueChange = { refreshInput = it; feedback = null },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Owner refresh token（可选）") },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            supportingText = { Text("与 access token 组成可自动续期的本机加密会话。") },
        )
        Row(horizontalArrangement = Arrangement.spacedBy(LoveHouseSpacing.Medium)) {
            Button(
                onClick = {
                    feedback = runCatching {
                        val session = if (sessionPayloadInput.isNotBlank()) {
                            parseOwnerSessionPayload(
                                sessionPayloadInput,
                                System.currentTimeMillis() / 1_000,
                            )
                        } else {
                            OwnerSessionInput(
                                accessToken = tokenInput,
                                refreshToken = refreshInput.takeIf(String::isNotBlank),
                            )
                        }
                        ownerSession.saveSession(session)
                    }
                        .fold(
                            onSuccess = {
                                tokenInput = ""
                                refreshInput = ""
                                sessionPayloadInput = ""
                                "Owner Session 已安全保存"
                            },
                            onFailure = { it.message ?: "Owner Session 无法保存" },
                        )
                },
                enabled = sessionPayloadInput.isNotBlank() || tokenInput.isNotBlank(),
            ) { Text("保存会话") }
            OutlinedButton(
                onClick = {
                    ownerSession.clear()
                    tokenInput = ""
                    refreshInput = ""
                    sessionPayloadInput = ""
                    feedback = "Owner Session 已清除"
                },
                enabled = state.status != OwnerSessionStatus.Missing,
            ) { Text("清除") }
        }
        if (state.canRefresh) {
            OutlinedButton(
                onClick = {
                    scope.launch {
                        feedback = runCatching { ownerSession.currentBearer(forceRefresh = true) }
                            .fold(
                                onSuccess = { "自动续期验证成功" },
                                onFailure = { it.message ?: "自动续期验证失败" },
                            )
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) { Text("测试自动续期") }
        }
        feedback?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        if (state.status != OwnerSessionStatus.Active) {
            Text(
                "请重新登录 LoveHouse，并在这里重新连接服务器。",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

private fun formatOwnerSessionExpiry(epochSeconds: Long): String =
    DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(epochSeconds * 1_000))

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
