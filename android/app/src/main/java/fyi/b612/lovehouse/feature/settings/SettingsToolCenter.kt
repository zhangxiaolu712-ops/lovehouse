package fyi.b612.lovehouse.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fyi.b612.lovehouse.core.designsystem.LoveHouseGlass
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SettingsToolCenter(
    registry: CapabilityRegistry,
    connections: ToolConnectionStore,
    probe: ToolConnectionProbe,
) {
    var tab by remember { mutableIntStateOf(0) }
    var testResults by remember { mutableStateOf<Map<String, ToolTestResult>>(emptyMap()) }
    var editing by remember { mutableStateOf<StoredToolConnection?>(null) }
    val capabilityState by registry.state.collectAsState()
    val capabilities = capabilityState.capabilities
    val preferred = capabilityState.enabledToolIds
    val saved by connections.connections.collectAsState()
    val scope = rememberCoroutineScope()

    ProductPanel {
        Text("Tool Center", color = LoveHouseGlass.Ink, fontSize = 14.sp)
        Text(
            "${saved.size} 个本机连接 · ${capabilities.count { it.availability == ToolAvailability.Available } + saved.sumOf { it.discoveredTools.size }} 个已发现工具",
            color = LoveHouseGlass.MutedInk,
            fontSize = 10.sp,
        )
        Text("外部连接尚未注册到 Bridge 时，只能在本机测试/发现，不能被 Chat Runtime 调用。", color = LoveHouseGlass.MutedInk, fontSize = 9.sp)
    }
    ProductPanel {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf("已添加", "添加 API", "添加 MCP").forEachIndexed { index, label ->
                Surface(
                    Modifier.weight(1f).clickable { editing = null; tab = index },
                    RoundedCornerShape(11.dp),
                    if (tab == index) Color(0xFFBFD4CF).copy(.74f) else Color.White.copy(.28f),
                    border = BorderStroke(.6.dp, Color.White.copy(.55f)),
                ) { Text(label, Modifier.padding(vertical = 8.dp), color = LoveHouseGlass.Ink, fontSize = 10.sp, textAlign = TextAlign.Center) }
            }
        }
    }
    when (tab) {
        0 -> {
            ProductPanel {
                Text("LoveHouse 内置工具", color = LoveHouseGlass.Ink, fontSize = 13.sp)
                Text(
                    capabilityState.error ?: if (capabilityState.loading) "正在读取真实工具状态…" else "Bridge 返回 ${capabilities.size} 项 capability",
                    color = LoveHouseGlass.MutedInk,
                    fontSize = 9.sp,
                )
                capabilities.forEach { tool ->
                    val available = tool.availability == ToolAvailability.Available
                    Row(Modifier.fillMaxWidth().padding(top = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(tool.displayName, color = LoveHouseGlass.Ink, fontSize = 11.sp)
                            Text(
                                "${tool.availability.label} · ${tool.capabilityKind.name.lowercase()} · ${tool.toolId}",
                                color = LoveHouseGlass.MutedInk,
                                fontSize = 8.5.sp,
                            )
                            testResults[tool.toolId]?.let { Text(it.message, color = if (it.succeeded) Color(0xFF466F63) else Color(0xFF9B4F55), fontSize = 8.5.sp) }
                        }
                        OutlinedButton(
                            onClick = { scope.launch { testResults = testResults + (tool.toolId to runCatching { registry.test(tool.toolId) }.getOrElse { ToolTestResult(tool.toolId, false, it.message ?: "测试失败") }) } },
                            enabled = available,
                        ) { Text("测试", fontSize = 9.sp) }
                        Switch(
                            checked = tool.toolId in preferred,
                            onCheckedChange = {
                                registry.setEnabled(tool.toolId, it)
                            },
                            enabled = available,
                        )
                    }
                }
                if (capabilities.isEmpty()) OutlinedButton(onClick = registry::refresh) { Text("重试真实状态") }
            }
            saved.forEach { connection ->
                ProductPanel {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(connection.name, color = LoveHouseGlass.Ink, fontSize = 13.sp)
                            Text("${connection.kind.name.uppercase()} · ${connection.status.label}", color = LoveHouseGlass.MutedInk, fontSize = 9.sp)
                            Text(connection.endpoint, color = LoveHouseGlass.MutedInk, fontSize = 9.sp, maxLines = 1)
                            Text("发现 ${connection.discoveredTools.size} 个工具 · Chat Runtime 尚未注册", color = LoveHouseGlass.MutedInk, fontSize = 9.sp)
                        }
                        Switch(connection.enabled, { connections.setEnabled(connection.id, it) })
                    }
                    connection.discoveredTools.takeIf(List<String>::isNotEmpty)?.let { tools -> Text(tools.joinToString(" · "), color = LoveHouseGlass.Ink, fontSize = 9.sp) }
                    connection.lastResult?.let { Text(it, color = LoveHouseGlass.MutedInk, fontSize = 9.sp) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            scope.launch {
                                val draft = connection.toDraft()
                                val result = withContext(Dispatchers.IO) { if (draft.kind == ToolConnectionKind.Mcp) probe.discover(draft) else probe.test(draft) }
                                connections.save(draft, result, connection.enabled)
                            }
                        }) { Text(if (connection.kind == ToolConnectionKind.Mcp) "重新发现" else "测试", fontSize = 9.sp) }
                        OutlinedButton(onClick = { editing = connection; tab = if (connection.kind == ToolConnectionKind.Api) 1 else 2 }) { Text("编辑", fontSize = 9.sp) }
                        OutlinedButton(onClick = { connections.delete(connection.id) }) { Text("删除", fontSize = 9.sp) }
                    }
                }
            }
        }
        1 -> ToolConnectionForm(ToolConnectionKind.Api, connections, probe, editing?.takeIf { it.kind == ToolConnectionKind.Api }) { editing = null; tab = 0 }
        else -> ToolConnectionForm(ToolConnectionKind.Mcp, connections, probe, editing?.takeIf { it.kind == ToolConnectionKind.Mcp }) { editing = null; tab = 0 }
    }
}

@Composable
private fun ToolConnectionForm(
    kind: ToolConnectionKind,
    store: ToolConnectionStore,
    probe: ToolConnectionProbe,
    initial: StoredToolConnection?,
    onSaved: () -> Unit,
) {
    var draft by remember(kind, initial?.id) { mutableStateOf(initial?.toDraft() ?: ToolConnectionDraft(kind = kind)) }
    var result by remember(kind, initial?.id) { mutableStateOf<ToolConnectionProbeResult?>(initial?.let { ToolConnectionProbeResult(it.status == ToolConnectionStatus.Connected, it.lastResult ?: "尚未重新测试", it.discoveredTools) }) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    ProductPanel {
        Text(if (kind == ToolConnectionKind.Api) "添加 API" else "添加 MCP", color = LoveHouseGlass.Ink, fontSize = 13.sp)
        ConnectionField("名称", draft.name) { draft = draft.copy(name = it); result = null }
        ConnectionField("${if (kind == ToolConnectionKind.Api) "Base URL / Endpoint" else "MCP URL"}", draft.endpoint) { draft = draft.copy(endpoint = it); result = null }
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text("认证方式", Modifier.weight(1f), color = LoveHouseGlass.Ink, fontSize = 10.sp)
            OutlinedButton(onClick = { draft = draft.copy(auth = ToolConnectionAuth.entries[(draft.auth.ordinal + 1) % ToolConnectionAuth.entries.size], credential = ""); result = null }) {
                Text(draft.auth.label, fontSize = 9.sp)
            }
        }
        if (draft.auth != ToolConnectionAuth.None) ConnectionField("API Key / Token（加密保存）", draft.credential, secret = true) { draft = draft.copy(credential = it); result = null }
        ConnectionField("备注（可选）", draft.note) { draft = draft.copy(note = it) }
        Text("凭据使用 Android Keystore AES-GCM 加密，不写普通 DataStore、日志或聊天历史。", color = LoveHouseGlass.MutedInk, fontSize = 8.5.sp)
        result?.let { Text(it.message, color = if (it.succeeded) Color(0xFF466F63) else Color(0xFF9B4F55), fontSize = 9.sp) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(
                enabled = !busy && draft.name.isNotBlank() && draft.endpoint.isNotBlank(),
                onClick = {
                    busy = true
                    scope.launch {
                        result = withContext(Dispatchers.IO) { if (kind == ToolConnectionKind.Mcp) probe.discover(draft) else probe.test(draft) }
                        busy = false
                    }
                },
            ) { Text(if (busy) "检查中…" else if (kind == ToolConnectionKind.Mcp) "发现工具" else "测试连接", fontSize = 9.sp) }
            Button(
                enabled = result != null && draft.name.isNotBlank() && draft.endpoint.isNotBlank(),
                onClick = { store.save(draft, result!!); draft = ToolConnectionDraft(kind = kind); result = null; onSaved() },
            ) { Text("保存并启用", fontSize = 9.sp) }
        }
        if (result == null) Text("必须先完成真实${if (kind == ToolConnectionKind.Mcp) "发现" else "连接测试"}，才可保存。", color = LoveHouseGlass.MutedInk, fontSize = 8.5.sp)
    }
}

@Composable
private fun ConnectionField(label: String, value: String, secret: Boolean = false, onChange: (String) -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        Text(label, color = LoveHouseGlass.MutedInk, fontSize = 9.sp)
        Surface(Modifier.fillMaxWidth(), RoundedCornerShape(11.dp), Color.White.copy(.34f), border = BorderStroke(.6.dp, Color.White.copy(.55f))) {
            BasicTextField(
                value, onChange, Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 9.dp),
                textStyle = androidx.compose.ui.text.TextStyle(color = LoveHouseGlass.Ink, fontSize = 11.sp),
                singleLine = true,
                visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
            )
        }
    }
}

private val ToolAvailability.label: String get() = when (this) {
    ToolAvailability.Available -> "可用"
    ToolAvailability.Unconfigured -> "未配置"
    ToolAvailability.NoPermission -> "无权限"
    ToolAvailability.ConnectionFailed -> "连接失败"
}
private val ToolConnectionStatus.label: String get() = when (this) {
    ToolConnectionStatus.Untested -> "未测试"
    ToolConnectionStatus.Connected -> "最近测试成功"
    ToolConnectionStatus.Failed -> "最近测试失败"
}
private val ToolConnectionAuth.label: String get() = when (this) {
    ToolConnectionAuth.None -> "None"
    ToolConnectionAuth.ApiKey -> "API Key"
    ToolConnectionAuth.BearerToken -> "Bearer Token"
}
private fun StoredToolConnection.toDraft() = ToolConnectionDraft(id, name, kind, endpoint, auth, credential, note)
