package fyi.b612.lovehouse.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fyi.b612.lovehouse.core.designsystem.LoveHouseGlass
import java.util.UUID

internal enum class ConnectionKind(val title: String, val fields: List<String>) {
    Backend("LoveHouse Backend", emptyList()),
    Vps("VPS", listOf("Port")),
    Github("GitHub", listOf("Account / Organization", "Repository（可选）")),
    Supabase("Supabase", listOf("Project Ref")),
    Tailscale("Tailscale", listOf("Tailnet", "Node")),
    Ollama("Ollama", listOf("Port")),
    Http("Custom HTTP / API", listOf("Header 名称（不含值）", "参数名称（不含值）")),
}
internal enum class ConnectionAuth(val title: String) {
    None("None"), ApiKey("API Key"), Bearer("Bearer Token"), OAuth("OAuth"),
    Code("Connection Code"), Reference("Credential Reference")
}

// Session-only metadata. Deliberately has no secret/token/header-value field or serializer.
internal data class ConnectionDraft(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val kind: ConnectionKind = ConnectionKind.Backend,
    val endpoint: String = "",
    val description: String = "",
    val auth: ConnectionAuth = ConnectionAuth.None,
    val credentialReference: String = "",
    val parameters: Map<String, String> = emptyMap(),
) {
    fun validationError(): String? = when {
        name.isBlank() -> "请填写连接名称"
        endpoint.isBlank() -> "请填写服务地址或 Host"
        endpoint.any { it == '@' || it == '?' || it == '#' } -> "地址不得包含凭据、查询参数或片段，请仅填写服务地址"
        parameters["Port"]?.let { it.isNotBlank() && (it.toIntOrNull() ?: 0) !in 1..65535 } == true -> "端口应为 1–65535"
        else -> null
    }
}
internal enum class ConnectionPage { Overview, List, Form }
internal class ConsoleConnectionsState {
    var page by mutableStateOf(ConnectionPage.Overview)
    val drafts = mutableStateListOf<ConnectionDraft>()
    var editing by mutableStateOf(ConnectionDraft())
    fun open(draft: ConnectionDraft = ConnectionDraft()) { editing = draft; page = ConnectionPage.Form }
    fun save(draft: ConnectionDraft): Boolean {
        if (draft.validationError() != null) return false
        val index = drafts.indexOfFirst { it.id == draft.id }
        if (index < 0) drafts.add(draft) else drafts[index] = draft
        page = ConnectionPage.List
        return true
    }
    fun back() { page = if (page == ConnectionPage.Form) ConnectionPage.List else ConnectionPage.Overview }
}

@Composable private fun ConnectionNote(text: String) {
    Text(text, color = LoveHouseGlass.MutedInk, fontSize = 11.sp)
}

@Composable internal fun ConnectionSummary(state: ConsoleConnectionsState) {
    ProductPanel {
        Text("接口与连接", color = LoveHouseGlass.Ink, fontSize = 14.sp)
        ConnectionNote("CONNECTIONS & ENDPOINTS")
        Spacer(Modifier.height(8.dp))
        ConnectionNote("${state.drafts.size} 项本地草稿 · 尚未连接")
        state.drafts.forEach { draft ->
            TextButton(onClick = { state.open(draft) }) { Text("${draft.name} · 编辑草稿") }
        }
        ConnectionKind.entries.filter { kind -> state.drafts.none { it.kind == kind } }.take(5).forEach { kind ->
            TextButton(onClick = { state.open(ConnectionDraft(kind = kind)) }) { Text("${kind.title} · 尚未配置", fontSize = 11.sp) }
        }
        TextButton(onClick = { state.page = ConnectionPage.List }) { Text("管理接口与连接") }
        TextButton(onClick = { state.open() }) { Text("+ 添加连接") }
    }
}

@Composable internal fun ConnectionManagement(state: ConsoleConnectionsState) {
    BackHandler { state.back() }
    TextButton(onClick = { state.back() }) { Text(if (state.page == ConnectionPage.Form) "返回接口与连接" else "返回全屋状态") }
    if (state.page == ConnectionPage.Form) {
        key(state.editing.id) { ConnectionEditor(state.editing, state::save, state::back) }
    } else {
        ProductPanel {
            Text("接口与连接", color = LoveHouseGlass.Ink, fontSize = 14.sp)
            ConnectionNote("CONNECTIONS & ENDPOINTS")
            ConnectionNote("仅本次控制台会话内保留非敏感草稿；离开控制台后清除。不是远端配置。")
            TextButton(onClick = { state.open() }) { Text("+ 添加连接") }
        }
        Text("已配置连接 · 本地草稿", color = LoveHouseGlass.Ink)
        if (state.drafts.isEmpty()) ConnectionNote("尚无已配置连接")
        state.drafts.toList().forEach { draft ->
            var deleting by remember(draft.id) { mutableStateOf(false) }
            ProductPanel {
                Text(draft.name, color = LoveHouseGlass.Ink)
                ConnectionNote("${draft.kind.title} · 尚未连接")
                ConnectionNote(draft.endpoint)
                Row {
                    TextButton(onClick = { state.open(draft) }) { Text("编辑") }
                    TextButton(onClick = { deleting = !deleting }) { Text("删除") }
                }
                if (deleting) {
                    ConnectionNote("仅删除本地草稿，不会删除外部服务。")
                    Row {
                        TextButton(onClick = { state.drafts.removeAll { it.id == draft.id } }) { Text("确认删除") }
                        TextButton(onClick = { deleting = false }) { Text("取消") }
                    }
                }
            }
        }
        Text("未配置连接", color = LoveHouseGlass.Ink)
        ConnectionKind.entries.filter { kind -> state.drafts.none { it.kind == kind } }.forEach { kind ->
            ProductPanel { TextButton(onClick = { state.open(ConnectionDraft(kind = kind)) }) { Text("${kind.title} · 尚未配置") } }
        }
    }
}

@Composable private fun ConnectionEditor(initial: ConnectionDraft, onSave: (ConnectionDraft) -> Boolean, onCancel: () -> Unit) {
    var draft by remember { mutableStateOf(initial) }
    var transientCredential by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<String?>(null) }
    ProductPanel {
        Text("基础信息", color = LoveHouseGlass.Ink)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            ProductField("连接名称", draft.name, { draft = draft.copy(name = it) }, true)
            ConnectionChoice("连接类型", ConnectionKind.entries, draft.kind, { it.title }) {
                draft = draft.copy(kind = it, parameters = emptyMap())
                result = null
            }
            ProductField(if (draft.kind in listOf(ConnectionKind.Vps, ConnectionKind.Ollama)) "Host" else "Server / Base URL / Endpoint", draft.endpoint, { draft = draft.copy(endpoint = it) }, true)
            ProductField("描述（可选，请勿填写凭据）", draft.description, { draft = draft.copy(description = it) }, false)
        }
    }
    ProductPanel {
        Text("认证", color = LoveHouseGlass.Ink)
        ConnectionChoice("Authentication Type", ConnectionAuth.entries, draft.auth, { it.title }) {
            transientCredential = ""
            draft = draft.copy(auth = it, credentialReference = "")
        }
        ConnectionNote("安全凭据存储尚未启用。请勿输入真实 Secret；临时输入不测试、不保存，退出表单即丢弃。")
        when (draft.auth) {
            ConnectionAuth.None -> ConnectionNote("无需认证（仅草稿声明）")
            ConnectionAuth.OAuth -> ConnectionNote("OAuth 授权服务尚未接入；本轮不会打开授权或获取 Token。")
            ConnectionAuth.Reference -> ProductField("Credential Reference（仅引用名称，不是密钥）", draft.credentialReference, { draft = draft.copy(credentialReference = it) }, true)
            else -> {
                Text("${draft.auth.title} · 临时输入，不保存", color = LoveHouseGlass.MutedInk, fontSize = 11.sp)
                BasicTextField(transientCredential, { transientCredential = it }, Modifier.fillMaxWidth().padding(12.dp), singleLine = true, visualTransformation = PasswordVisualTransformation())
            }
        }
    }
    ProductPanel {
        Text("连接参数", color = LoveHouseGlass.Ink)
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            draft.kind.fields.forEach { field ->
                ProductField(field, draft.parameters[field].orEmpty(), { draft = draft.copy(parameters = draft.parameters + (field to it)) }, true)
            }
            if (draft.kind.fields.isEmpty()) ConnectionNote("Server URL 使用上方地址；Connection Key 通过认证区临时输入或凭据引用表示。")
            if (draft.kind == ConnectionKind.Http) ConnectionNote("Header / 参数值待安全服务接入；本轮仅记录名称，不保存 Authorization 等值。")
        }
    }
    ProductPanel {
        ConnectionNote("连接状态：尚未连接 / 后端尚未接入")
        result?.let { ConnectionNote(it) }
        TextButton(onClick = { result = "后端连接服务尚未接入" }) { Text("测试连接") }
        Row {
            TextButton(onClick = {
                result = draft.validationError()
                if (result == null) { transientCredential = ""; onSave(draft) }
            }) { Text("保存非敏感草稿") }
            TextButton(onClick = { transientCredential = ""; onCancel() }) { Text("取消") }
        }
    }
}

@Composable private fun <T> ConnectionChoice(label: String, values: List<T>, selected: T, title: (T) -> String, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { expanded = true }) { Text("$label：${title(selected)} ▾", fontSize = 11.sp) }
        DropdownMenu(expanded, { expanded = false }) {
            values.forEach { value -> DropdownMenuItem(text = { Text(title(value)) }, onClick = { onSelect(value); expanded = false }) }
        }
    }
}
