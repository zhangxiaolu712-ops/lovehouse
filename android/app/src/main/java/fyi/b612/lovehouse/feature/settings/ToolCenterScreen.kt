package fyi.b612.lovehouse.feature.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import fyi.b612.lovehouse.core.designsystem.LoveHouseCard
import fyi.b612.lovehouse.core.designsystem.LoveHouseSpacing
import fyi.b612.lovehouse.core.designsystem.StatusPill

@Composable
fun ToolCenterLabScreen(
    repository: ToolCenterRepository,
    profiles: ToolProfilePreferenceStore,
    personaId: String,
    threadId: String,
    onBack: () -> Unit,
    onReconnect: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ToolCenterViewModel = viewModel(
        factory = ToolCenterViewModel.factory(repository, profiles, personaId, threadId),
    ),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val preferred by viewModel.preferred.collectAsStateWithLifecycle()
    val tests by viewModel.tests.collectAsStateWithLifecycle()

    LazyColumn(
        modifier = modifier.fillMaxSize().safeDrawingPadding(),
        contentPadding = PaddingValues(LoveHouseSpacing.Page),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedButton(onClick = onBack, contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp)) { Text("返回") }
                Column {
                    Text("Tool Center Lab", style = MaterialTheme.typography.titleLarge)
                    Text("MCP TOOLS LAB · agent-codex", style = MaterialTheme.typography.labelSmall)
                }
            }
        }
        item {
            Text(
                "临时测试页：这里保存的是本机窗口工具偏好，不是正式 Settings，也不是最终权限。Bridge 仍会按 Owner、工具风险和作用域重新校验。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when (val current = state) {
            ToolCenterUiState.Loading -> item { LoveHouseCard { Text("正在读取真实工具状态…") } }
            is ToolCenterUiState.AuthenticationRequired -> item {
                LoveHouseCard {
                    Text("Owner 登录已失效", style = MaterialTheme.typography.titleMedium)
                    Text(current.message, color = MaterialTheme.colorScheme.error)
                    Text("请重新登录 LoveHouse，或重新连接服务器后再检测工具。", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = onReconnect) { Text("重新登录 / 重新连接服务器") }
                }
            }
            is ToolCenterUiState.Error -> item {
                LoveHouseCard {
                    Text("连接失败", style = MaterialTheme.typography.titleMedium)
                    Text(current.message, color = MaterialTheme.colorScheme.error)
                    Text("Lab 保留详细错误，方便真机排查。", style = MaterialTheme.typography.bodySmall)
                    Button(onClick = viewModel::refresh) { Text("重试") }
                }
            }
            is ToolCenterUiState.Ready -> {
                current.tools.groupBy { it.group }.forEach { (_, tools) ->
                    item(key = tools.first().group) {
                        ToolGroupCard(
                            tools = tools,
                            preferred = preferred,
                            tests = tests,
                            onToggle = viewModel::setEnabled,
                            onTest = viewModel::test,
                        )
                    }
                }
                item {
                    LoveHouseCard {
                        Text("外部 MCP", style = MaterialTheme.typography.titleMedium)
                        Text("自定义 MCP Server 将在后续版本开放。当前不会保存 URL 或凭据。")
                        StatusPill("暂未开放")
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolGroupCard(
    tools: List<ToolCapability>,
    preferred: Set<String>,
    tests: Map<String, ToolTestResult>,
    onToggle: (String, Boolean) -> Unit,
    onTest: (String) -> Unit,
) {
    LoveHouseCard(modifier = Modifier.fillMaxWidth()) {
        val groupAvailable = tools.any { it.availability == ToolAvailability.Available }
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Column(modifier = Modifier.weight(1f)) {
                Text(tools.first().groupLabel, style = MaterialTheme.typography.titleLarge)
                Text(tools.joinToString(" · ") { it.displayName }, style = MaterialTheme.typography.bodySmall)
            }
            StatusPill(availabilityLabel(tools.first().availability))
        }
        tools.forEach { tool ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(tool.displayName, style = MaterialTheme.typography.titleSmall)
                    Text(tool.summary, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Switch(
                    checked = tool.toolId in preferred,
                    onCheckedChange = { onToggle(tool.toolId, it) },
                    enabled = tool.availability == ToolAvailability.Available,
                )
            }
        }
        OutlinedButton(
            onClick = { tools.firstOrNull { it.availability == ToolAvailability.Available }?.let { onTest(it.toolId) } },
            enabled = groupAvailable,
            modifier = Modifier.fillMaxWidth(),
        ) { Text("Test Tool") }
        tests.values.firstOrNull { result -> tools.any { it.toolId == result.toolId } }?.let { result ->
            Text(
                result.message,
                style = MaterialTheme.typography.bodySmall,
                color = if (result.succeeded) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
            )
        }
        if (!groupAvailable) Text(tools.first().detail, style = MaterialTheme.typography.bodySmall)
    }
}

private fun availabilityLabel(value: ToolAvailability) = when (value) {
    ToolAvailability.Available -> "可用"
    ToolAvailability.Unconfigured -> "未配置"
    ToolAvailability.NoPermission -> "无权限"
    ToolAvailability.ConnectionFailed -> "连接失败"
}
