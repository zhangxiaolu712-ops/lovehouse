package fyi.b612.lovehouse.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fyi.b612.lovehouse.core.designsystem.LoveHouseGlass
import fyi.b612.lovehouse.core.storage.LocalStorage
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay

internal object SettingsSpacing { val CardGap = 12.dp }

@Composable
internal fun SettingsCardStack(content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(SettingsSpacing.CardGap), content = content)
}

@Composable private fun Heading(text: String) {
    Text(text, color = LoveHouseGlass.Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
}

@Composable private fun Note(text: String) {
    Text(text, color = LoveHouseGlass.MutedInk, fontSize = 10.sp, modifier = Modifier.padding(top = 5.dp))
}

@Composable
internal fun PersonaVoiceSettings(storage: LocalStorage) {
    var personas by remember { mutableStateOf(listOf("g" to "G老师", "k" to "小克")) }
    val drafts = remember { mutableStateMapOf<String, PersonaVoiceDraft>() }
    var previewMessage by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(storage) {
        storage.readString(PersonasKey)?.profilesFromJson()?.let { saved ->
            personas = saved.map { it.id to it.name }
        }
    }
    ProductPanel {
        Heading("Persona Voice")
        Note("每个人格独立配置。以下仅为本页配置草稿，未连接 Voice Provider，未远端保存。")
    }
    personas.forEach { (id, name) ->
        val voice = drafts[id] ?: PersonaVoiceDraft()
        ProductPanel {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(name, Modifier.weight(1f), color = LoveHouseGlass.Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                Text(if (voice.enabled) "启用意向" else "停用", color = LoveHouseGlass.MutedInk, fontSize = 9.sp)
                Switch(voice.enabled, { drafts[id] = voice.copy(enabled = it) })
            }
            ProductField("Voice Provider（名称）", voice.provider, { drafts[id] = voice.copy(provider = it) }, true)
            Spacer(Modifier.height(8.dp))
            ProductField("Voice / Voice ID", voice.voiceId, { drafts[id] = voice.copy(voiceId = it) }, true)
            Note("Credential：未配置。未来引用独立安全模块；这里不输入或保存 API Key。")
            Note("连接状态：后端尚未接入。启用意向不代表服务已启用。")
            OutlinedButton(onClick = { previewMessage = "$name：Voice Provider 尚未接入，无法试听；未发起网络请求。" }, modifier = Modifier.fillMaxWidth()) { Text("试听", fontSize = 10.sp) }
        }
    }
    previewMessage?.let { ProductPanel { Note(it) } }
    ProductPanel {
        Heading("语音输入与通话")
        Note("独立于 Persona Voice；以下仅为前端偏好，不代表后端 STT 已接入。")
        listOf("自动朗读", "语音消息转文字", "通话降噪").forEach { label ->
            var enabled by remember(label) { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f), color = LoveHouseGlass.Ink, fontSize = 11.sp)
                Switch(enabled, { enabled = it })
            }
        }
    }
}

@Composable
internal fun GlobalLocationSettings() {
    var state by remember { mutableStateOf(GlobalLocationState()) }
    var adding by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    var zone by remember { mutableStateOf("Asia/Shanghai") }
    var candidate by remember { mutableStateOf<GlobalPlace?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var format24 by remember { mutableStateOf(true) }
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { while (true) { now = Instant.now(); delay(30_000) } }
    BackHandler(adding != null || candidate != null) { candidate = null; adding = null; message = null }

    ProductPanel {
        Heading("Global Location · 全局地点")
        Text(state.current.name, color = LoveHouseGlass.Ink, fontSize = 23.sp, fontWeight = FontWeight.Medium)
        Text(DateTimeFormatter.ofPattern(if (format24) "HH:mm · MM/dd" else "hh:mm a · MM/dd").withZone(ZoneId.of(state.current.zoneId)).format(now), color = LoveHouseGlass.Ink, fontSize = 17.sp)
        Note("${state.current.zoneId} · ${if (state.travel == null) "常住地点" else "Travel Location"}")
        Note("天气摘要：未知 · 天气服务尚未连接")
        Note("仅本页状态，未同步；当地时间由设备时钟与所选时区计算。")
    }
    ProductPanel {
        Heading("常住地点 · ${state.homes.size}/5")
        state.homes.forEach { place ->
            TextButton(onClick = { state = state.selectHome(place.id) }, modifier = Modifier.fillMaxWidth()) {
                Text("${place.name} · ${place.zoneId}${if (state.current.id == place.id) " · 当前" else ""}", fontSize = 11.sp)
            }
        }
        OutlinedButton(onClick = { adding = "home"; name = ""; zone = "Asia/Shanghai"; message = null }, enabled = state.homes.size < 5, modifier = Modifier.fillMaxWidth()) { Text(if (state.homes.size < 5) "添加常住地点" else "最多 5 个常住地点", fontSize = 10.sp) }
    }
    ProductPanel {
        Heading("临时地点 / Travel Location")
        Note(state.travel?.name ?: "尚未设置临时地点")
        OutlinedButton(onClick = { adding = "travel"; name = ""; zone = state.current.zoneId; message = null }, modifier = Modifier.fillMaxWidth()) { Text("设置临时地点", fontSize = 10.sp) }
        if (state.travel != null) TextButton(onClick = { state = state.returnHome() }) { Text("返回常住地点", fontSize = 10.sp) }
        Row {
            TextButton(onClick = { message = "GPS 检测入口已预留。本轮不读取 GPS；未来检测后先展示候选地点，必须由你确认才切换。" }, modifier = Modifier.weight(1f)) { Text("GPS 检测", fontSize = 10.sp) }
            TextButton(onClick = { message = "AI 建议地点尚未启用。未来只提供建议，不会静默替换全局地点。" }, modifier = Modifier.weight(1f)) { Text("AI 建议地点", fontSize = 10.sp) }
        }
    }
    if (adding != null) ProductPanel {
        Heading(if (adding == "home") "添加常住地点" else "临时地点")
        ProductField("地点名称", name, { name = it }, true)
        Spacer(Modifier.height(8.dp))
        ProductField("时区 ID", zone, { zone = it }, true)
        Note("例如 Asia/Shanghai、Asia/Tokyo、Europe/London。时区需显式指定，不猜测地点。")
        Row {
            TextButton(onClick = { adding = null; candidate = null }, Modifier.weight(1f)) { Text("取消") }
            TextButton(onClick = {
                val place = runCatching { GlobalPlace("place-${System.nanoTime()}", name.trim(), zone.trim()) }.getOrNull()
                if (place == null) message = "请填写地点名称与有效的时区 ID。" else candidate = place
            }, Modifier.weight(1f)) { Text("继续确认") }
        }
    }
    candidate?.let { place -> ProductPanel {
        Heading("确认${if (adding == "home") "添加" else "切换到"} ${place.name}？")
        Note("${place.zoneId} · 不会同步到远端")
        Row {
            TextButton(onClick = { candidate = null }, Modifier.weight(1f)) { Text("返回修改") }
            TextButton(onClick = {
                state = if (adding == "home") state.addHome(place) else state.confirmTravel(place)
                candidate = null; adding = null; message = null
            }, Modifier.weight(1f)) { Text("确认") }
        }
    } }
    message?.let { ProductPanel { Note(it) } }
    ProductPanel {
        Heading("天气与时间偏好")
        var source by remember { mutableStateOf("自动") }
        var unit by remember { mutableStateOf("摄氏度") }
        TextButton(onClick = { source = if (source == "自动") "待配置来源" else "自动" }) { Text("天气来源：$source · 未连接", fontSize = 11.sp) }
        TextButton(onClick = { format24 = !format24 }) { Text("时间格式：${if (format24) "24" else "12"} 小时制", fontSize = 11.sp) }
        TextButton(onClick = { unit = if (unit == "摄氏度") "华氏度" else "摄氏度" }) { Text("温度单位：$unit", fontSize = 11.sp) }
        listOf("桌面天气", "恶劣天气提醒").forEach { label ->
            var enabled by remember(label) { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) { Text(label, Modifier.weight(1f), color = LoveHouseGlass.Ink, fontSize = 11.sp); Switch(enabled, { enabled = it }) }
        }
        Note("本页偏好草稿，不修改冻结的 Desktop，也不启用实际天气推送。")
    }
}

@Composable
internal fun TrustedDevicesSettings() {
    var expanded by remember { mutableStateOf(false) }
    ProductPanel {
        Heading("LoveHouse Trusted Devices")
        Note("需要后端设备配对服务 / 尚未启用")
        OutlinedButton(onClick = { expanded = !expanded }, modifier = Modifier.fillMaxWidth()) { Text(if (expanded) "收起配对流程" else "添加新设备", fontSize = 10.sp) }
        if (expanded) {
            listOf("1. 生成 Pairing Code / QR", "2. 新手机扫码或输入", "3. 旧设备确认", "4. 新设备成为 Trusted", "5. 按需撤销旧设备").forEach { Note(it) }
            Note("流程预留：当前不会生成可用配对码、授信或撤销任何设备。")
        }
    }
}

@Composable
internal fun HouseStatusConsole(connections: ConsoleConnectionsState) {
    if (connections.page != ConnectionPage.Overview) {
        ConnectionManagement(connections)
        return
    }
    ProductPanel {
        Heading("LoveHouse 全屋运行状态中心")
        Note("11 项核心服务 · 全部状态未知。尚未连接状态源；不执行 restart、deploy、rollback 或数据修改。")
    }
    houseServicePlaceholders.groupBy { it.category }.forEach { (category, services) ->
        Heading(category)
        services.forEach { service -> ServiceStatusCard(service) }
    }
    ConnectionSummary(connections)
}

@Composable
private fun ServiceStatusCard(service: HouseServiceStatus) {
    var expanded by remember(service.name) { mutableStateOf(false) }
    ProductPanel(Modifier.clickable { expanded = !expanded }) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(service.name, Modifier.weight(1f), color = LoveHouseGlass.Ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
            Text("状态未知 ${if (expanded) "−" else "+"}", color = LoveHouseGlass.MutedInk, fontSize = 10.sp)
        }
        Note("尚未连接 / 后端尚未接入")
        if (expanded) {
            Note("Version：${service.version ?: "未知"} · Release：${service.release ?: "未知"}")
            Note("Latency：${service.latencyMillis?.let { "${it}ms" } ?: "未知"}")
            Note("Last heartbeat：${service.lastHeartbeat ?: "未知"}")
            if (service.category == "AI Runtime") Note("Runtime：${service.runtime ?: "未知"} · Model：${service.model ?: "未知"}")
        }
    }
}

@Composable
internal fun FutureSettingsPanel(title: String, message: String) {
    ProductPanel {
        Heading(title)
        Note("尚未启用")
        Note(message)
    }
}
