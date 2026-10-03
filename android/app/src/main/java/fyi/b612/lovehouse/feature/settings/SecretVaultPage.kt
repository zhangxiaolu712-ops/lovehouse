package fyi.b612.lovehouse.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.UUID
import kotlinx.coroutines.delay

internal enum class VaultKeyType(val label: String) { ApiKey("API Key"), Bearer("Bearer Token") }

/** One vault entry as the list shows it. Only the masked form is kept; the full key never is. */
internal data class VaultKey(
    val id: String,
    val name: String,
    val type: VaultKeyType,
    val maskedKey: String,
    val notes: String,
    val updatedAt: String,
)

/** Preview entries from the HTML, in memory only, so the real vault source can replace them in one place. */
internal object VaultSamples {
    val keys = listOf(
        VaultKey("key_1", "ElevenLabs", VaultKeyType.ApiKey, "sk-••••••7mXa", "用于配音及语音生成模型接口", "2026-09-28 14:30"),
        VaultKey("key_2", "OpenAI GPT-4o", VaultKeyType.Bearer, "sk-proj-••••••9kLq", "高频对话与文本逻辑处理核心 Key", "2026-10-02 09:15"),
        VaultKey("key_3", "DeepSeek API", VaultKeyType.ApiKey, "sk-••••••3xP9", "代码生成与深度逻辑推理辅助凭证", "2026-10-03 18:04"),
    )
}

/** The HTML's preview mask. A real vault should show the mask the backend returns instead. */
internal fun maskVaultKey(raw: String): String {
    val clean = raw.trim()
    return when {
        clean.isEmpty() -> "sk-••••••••"
        clean.length <= 8 -> clean.take(2) + "••••" + clean.takeLast(2)
        else -> clean.take(3) + "••••••" + clean.takeLast(4)
    }
}

private val VaultTime = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
private const val PreviewSuffix = "（预览，未接后端）"

private object VaultIcon {
    const val Key = "M21 2l-2 2m-7.61 7.61a5.5 5.5 0 1 1-7.78 7.78a5.5 5.5 0 0 1 7.78-7.78zm0 0L15.5 7.5m0 0l3 3L22 7l-3-3m-3.5 3.5L19 4"
    const val ChevRight = "M9 6l6 6-6 6"
    const val Lock = "M6 11h12v10H6zM8 11V7a4 4 0 0 1 8 0v4"
    const val Close = "M6 6l12 12M18 6L6 18"
    const val Eye = "M2 12s3.5-7 10-7s10 7 10 7s-3.5 7-10 7S2 12 2 12zM12 9a3 3 0 1 0 0 6a3 3 0 1 0 0-6z"
    const val EyeOff = "M3 3l18 18M10.6 6.1A10 10 0 0 1 12 6c6.5 0 10 6 10 6a17 17 0 0 1-3.2 3.9M6.6 6.6C3.9 8.3 2 12 2 12s3.5 6 10 6c1.6 0 3-.4 4.3-1M9.9 9.9a3 3 0 0 0 4.2 4.2"
    const val Clipboard = "M9 3h6v4H9zM9 5H6v16h12V5h-3"
    const val Shield = "M12 3l8 3v6c0 5-3.5 8-8 9c-4.5-1-8-4-8-9V6z"
    const val Alert = "M12 3l10 18H2zM12 10v4M12 17v.01"
}

private val DangerWash = Color(0x1A9A3B32)
private val OkWash = Color(0x1A4F7F68)
private val OkLine = Color(0x334F7F68)
private val Handle = Color(0x40A55F52)

private sealed interface VaultSheet {
    data object Add : VaultSheet
    data class Edit(val id: String) : VaultSheet
}

@Composable
internal fun SecretVaultPage(onBack: () -> Unit, modifier: Modifier = Modifier) {
    val keys = remember { mutableStateListOf<VaultKey>().apply { addAll(VaultSamples.keys) } }
    var sheet by remember { mutableStateOf<VaultSheet?>(null) }
    var confirmDelete by remember { mutableStateOf<String?>(null) }
    var toast by remember { mutableStateOf<String?>(null) }
    var toastSeq by remember { mutableIntStateOf(0) }
    val showToast: (String) -> Unit = { toast = it; toastSeq++ }
    var shownSheet by remember { mutableStateOf<VaultSheet?>(null) }
    if (sheet != null && sheet != shownSheet) shownSheet = sheet

    BackHandler(enabled = sheet != null && confirmDelete == null) { sheet = null }
    BackHandler(enabled = confirmDelete != null) { confirmDelete = null }

    Box(modifier.fillMaxSize()) {
        Box(Modifier.fillMaxSize().statusBarsPadding()) {
            Column(
                Modifier
                    .align(Alignment.TopCenter)
                    .widthIn(max = 448.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .navigationBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, bottom = 96.dp),
            ) {
                TcTopBar("密码库", "Secret Vault", onBack) {
                    TcIconButton(TcIcon.Plus, "添加", onClick = { sheet = VaultSheet.Add })
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    TcText("集中管理你的 API 密钥，完整密钥不会再次显示。", Tc.FsSub, Modifier.weight(1f), color = Tc.Ink2)
                    Pill("本地脱敏", Tc.Ok, OkWash, OkLine, icon = VaultIcon.Shield)
                }
                if (keys.isEmpty()) {
                    EmptyVault { sheet = VaultSheet.Add }
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        keys.forEach { key -> VaultCard(key) { sheet = VaultSheet.Edit(key.id) } }
                    }
                }
            }
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .navigationBarsPadding()
                    .padding(24.dp)
                    .size(48.dp)
                    .shadow(10.dp, RoundedCornerShape(16.dp), ambientColor = Tc.PopShadow, spotColor = Tc.PopShadow)
                    .clip(RoundedCornerShape(16.dp))
                    .background(Tc.Accent)
                    .clickable(onClickLabel = "添加密钥", role = Role.Button) { sheet = VaultSheet.Add },
                contentAlignment = Alignment.Center,
            ) { TcIconView(TcIcon.Plus, 20.dp, Color.White) }
        }

        AnimatedVisibility(sheet != null, enter = fadeIn(tween(200)), exit = fadeOut(tween(200))) {
            Box(
                Modifier.fillMaxSize().background(Tc.Scrim)
                    .clickable(remember { MutableInteractionSource() }, null) { sheet = null },
            )
        }
        AnimatedVisibility(
            visible = sheet != null,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically(tween(300)) { it },
            exit = slideOutVertically(tween(300)) { it },
        ) {
            when (val current = shownSheet) {
                VaultSheet.Add -> AddKeySheet(
                    onClose = { sheet = null },
                    onToast = showToast,
                ) { added ->
                    keys.add(0, added)
                    sheet = null
                    showToast("密钥已添加，完整值已脱敏$PreviewSuffix")
                }
                is VaultSheet.Edit -> keys.firstOrNull { it.id == current.id }?.let { key ->
                    EditKeySheet(
                        key = key,
                        onClose = { sheet = null },
                        onToast = showToast,
                        onDelete = { confirmDelete = key.id },
                    ) { updated, replaced ->
                        val index = keys.indexOfFirst { it.id == updated.id }
                        if (index >= 0) keys[index] = updated
                        sheet = null
                        showToast((if (replaced) "已替换为新密钥并脱敏" else "已更新密钥基本信息") + PreviewSuffix)
                    }
                }
                null -> Unit
            }
        }

        confirmDelete?.let { id ->
            DeleteConfirm(
                onCancel = { confirmDelete = null },
                onConfirm = {
                    keys.removeAll { it.id == id }
                    confirmDelete = null
                    sheet = null
                    showToast("密钥已移除$PreviewSuffix")
                },
            )
        }

        VaultToast(toast, toastSeq, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 64.dp))
    }
}

@Composable
private fun VaultCard(key: VaultKey, onClick: () -> Unit) {
    val shape = RoundedCornerShape(16.dp)
    Column(
        Modifier.fillMaxWidth().tcGlass(shape).clickable(onClick = onClick).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            KeyBadge(32.dp, 13.dp)
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                TcText(key.name, Tc.FsSub, weight = FontWeight.SemiBold, letterSpacing = .025f, maxLines = 1, overflow = TextOverflow.Ellipsis)
                TcText(key.type.label, 10f, color = Tc.Ink2)
            }
            ConfiguredBadge()
        }
        Row(
            Modifier.fillMaxWidth().tcInner(RoundedCornerShape(12.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            TcText(key.maskedKey, Tc.FsSub, Modifier.weight(1f), color = Tc.Ink, family = Tc.Mono, letterSpacing = .05f, maxLines = 1)
            TcPathIcon(VaultIcon.ChevRight, 12.dp, Tc.Ink2)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TcText(key.notes.ifBlank { "无备注" }, 10f, Modifier.weight(1f).padding(end = 8.dp), color = Tc.Ink2, maxLines = 1, overflow = TextOverflow.Ellipsis)
            TcText("最近更新 ${key.updatedAt}", 10f, color = Tc.Ink2)
        }
    }
}

@Composable
private fun KeyBadge(size: Dp, icon: Dp) {
    val shape = RoundedCornerShape(if (size > 40.dp) 16.dp else 12.dp)
    Box(
        Modifier.size(size).clip(shape).background(Tc.Fill35).border(1.dp, Tc.Line, shape),
        contentAlignment = Alignment.Center,
    ) { TcPathIcon(VaultIcon.Key, icon, Tc.Accent) }
}

@Composable
private fun ConfiguredBadge() {
    val pulse by rememberInfiniteTransition(label = "configured").animateFloat(
        1f, .35f, infiniteRepeatable(tween(1000), RepeatMode.Reverse), label = "configured-dot",
    )
    Row(
        Modifier.clip(CircleShape).background(OkWash).border(1.dp, OkLine, CircleShape).padding(horizontal = 8.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Box(Modifier.size(6.dp).alpha(pulse).background(Tc.Ok, CircleShape))
        TcText("已配置", 10f, color = Tc.Ok, weight = FontWeight.Medium)
    }
}

@Composable
private fun Pill(text: String, ink: Color, wash: Color, line: Color, icon: String? = null) {
    Row(
        Modifier.clip(CircleShape).background(wash).border(1.dp, line, CircleShape).padding(horizontal = 10.dp, vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        icon?.let { TcPathIcon(it, 11.dp, ink) }
        TcText(text, Tc.FsSub, color = ink, weight = FontWeight.Medium)
    }
}

@Composable
private fun EmptyVault(onAdd: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 64.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        KeyBadge(64.dp, 26.dp)
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            TcText("还没有保存任何密钥", Tc.FsBody, weight = FontWeight.SemiBold, align = TextAlign.Center)
            TcText("添加后，LoveHouse 可以安全地替你管理 API 凭证。", Tc.FsSub, color = Tc.Ink2, align = TextAlign.Center)
        }
        AccentButton("添加第一条密钥", icon = true, onClick = onAdd)
    }
}

// ---------------- 底部弹层 ----------------

@Composable
private fun VaultSheetFrame(content: @Composable ColumnScope.() -> Unit) {
    val maxHeight = (LocalConfiguration.current.screenHeightDp * .9f).dp
    val shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
    Column(
        Modifier
            .widthIn(max = 448.dp)
            .fillMaxWidth()
            .heightIn(max = maxHeight)
            .shadow(16.dp, shape, ambientColor = Tc.PopShadow, spotColor = Tc.PopShadow)
            .clip(shape)
            .background(Tc.GlassStrong)
            .border(1.dp, Tc.Edge, shape)
            .clickable(remember { MutableInteractionSource() }, null) {}
            .imePadding()
            .navigationBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        Box(Modifier.align(Alignment.CenterHorizontally).padding(bottom = 20.dp).size(40.dp, 4.dp).background(Handle, CircleShape))
        content()
    }
}

@Composable
private fun SheetHeader(onClose: () -> Unit, title: @Composable () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(bottom = 20.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f)) { title() }
        Box(
            Modifier.size(36.dp).clip(CircleShape).clickable(onClickLabel = "关闭", onClick = onClose),
            contentAlignment = Alignment.Center,
        ) { TcPathIcon(VaultIcon.Close, 18.dp, Tc.Ink2) }
    }
}

@Composable
private fun AddKeySheet(onClose: () -> Unit, onToast: (String) -> Unit, onSave: (VaultKey) -> Unit) {
    var name by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(VaultKeyType.ApiKey) }
    var raw by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    val clipboard = LocalClipboardManager.current
    VaultSheetFrame {
        SheetHeader(onClose) {
            Column {
                TcText("添加密钥", 18f, weight = FontWeight.SemiBold, lineHeight = 1.4f)
                TcText("凭证将仅在保存时完整处理，保存后仅能查看到脱敏版本", Tc.FsCap, color = Tc.Ink2)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            VaultLabel("名称", required = true) { TcInput(name, { name = it }, placeholder = "例如：ElevenLabs / OpenAI") }
            VaultLabel("凭证类型") {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    VaultKeyType.entries.forEach { option ->
                        TypeOption(option.label, option == type, Modifier.weight(1f)) { type = option }
                    }
                }
            }
            VaultLabel(
                "完整密钥",
                required = true,
                action = "粘贴剪贴板",
                onAction = {
                    clipboard.getText()?.text?.takeIf(String::isNotBlank)?.let { raw = it.trim(); onToast("已完成剪贴板粘贴") }
                        ?: onToast("剪贴板里没有可用的文本")
                },
            ) { SecretField(raw, { raw = it }, "粘贴或输入完整 sk-...") }
            VaultLabel("可选备注") { TcInput(notes, { notes = it }, placeholder = "填写用途、配额额度或对应项目说明...", multiline = true) }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                GhostButton("取消", Modifier.weight(1f), onClose)
                AccentButton("保存密钥", Modifier.weight(2f)) {
                    if (name.isBlank() || raw.isBlank()) {
                        onToast("请填写完整必填字段")
                    } else {
                        val masked = maskVaultKey(raw)
                        raw = ""
                        onSave(
                            VaultKey(UUID.randomUUID().toString(), name.trim(), type, masked, notes.trim(), LocalDateTime.now().format(VaultTime)),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EditKeySheet(
    key: VaultKey,
    onClose: () -> Unit,
    onToast: (String) -> Unit,
    onDelete: () -> Unit,
    onSave: (VaultKey, Boolean) -> Unit,
) {
    var name by remember(key.id) { mutableStateOf(key.name) }
    var notes by remember(key.id) { mutableStateOf(key.notes) }
    var replaceOpen by remember(key.id) { mutableStateOf(false) }
    var replacement by remember(key.id) { mutableStateOf("") }
    val chevron by animateFloatAsState(if (replaceOpen) 180f else 0f, tween(200), label = "replace-chevron")
    val clipboard = LocalClipboardManager.current
    VaultSheetFrame {
        SheetHeader(onClose) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TcText(key.name, 18f, Modifier.weight(1f, fill = false), weight = FontWeight.SemiBold, lineHeight = 1.4f, maxLines = 1, overflow = TextOverflow.Ellipsis)
                TcText(
                    key.type.label, 10f,
                    Modifier.clip(RoundedCornerShape(6.dp)).background(Tc.Fill35).border(1.dp, Tc.Line, RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                    color = Tc.Ink2, weight = FontWeight.Medium,
                )
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            VaultLabel("名称") { TcInput(name, { name = it }) }
            VaultLabel("脱敏密钥", hint = "完整密钥无法二次查看") {
                Row(
                    Modifier.fillMaxWidth().tcInner(RoundedCornerShape(12.dp)).padding(horizontal = 14.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TcText(key.maskedKey, Tc.FsSub, Modifier.weight(1f), color = Tc.Ink2, family = Tc.Mono, maxLines = 1)
                    TcPathIcon(VaultIcon.Lock, 12.dp, Tc.Ink2)
                }
            }
            Column {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .drawBehind {
                            drawRoundRect(
                                color = Tc.Line.copy(alpha = .45f),
                                cornerRadius = CornerRadius(12.dp.toPx()),
                                style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))),
                            )
                        }
                        .clickable(role = Role.Button) { replaceOpen = !replaceOpen }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TcIconView(TcIcon.Refresh, 14.dp, Tc.Ink2)
                    TcText("替换为新密钥", Tc.FsSub, Modifier.weight(1f).padding(start = 8.dp), weight = FontWeight.Medium)
                    TcIconView(TcIcon.Chev, 14.dp, Tc.Ink2, Modifier.rotate(chevron))
                }
                if (replaceOpen) {
                    Column(
                        Modifier.padding(top = 12.dp).fillMaxWidth().tcInner(RoundedCornerShape(12.dp)).padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            TcText("输入新密钥", Tc.FsCap, weight = FontWeight.SemiBold)
                            TcLink("粘贴新值", size = Tc.FsCap, minHeight = 28.dp) {
                                clipboard.getText()?.text?.takeIf(String::isNotBlank)?.let { replacement = it.trim(); onToast("已完成剪贴板粘贴") }
                                    ?: onToast("剪贴板里没有可用的文本")
                            }
                        }
                        SecretField(replacement, { replacement = it }, "粘贴或输入全新密钥...")
                        TcText("替换保存后，原脱敏记录将更新为新凭证的摘要。", 10f, color = Tc.Ink2)
                    }
                }
            }
            VaultLabel("修改备注") { TcInput(notes, { notes = it }, placeholder = "备注...", multiline = true) }
            Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AccentButton("保存修改", Modifier.fillMaxWidth()) {
                    if (name.isBlank()) {
                        onToast("名称不能为空")
                    } else {
                        val replaced = replacement.isNotBlank()
                        val masked = if (replaced) maskVaultKey(replacement) else key.maskedKey
                        replacement = ""
                        onSave(
                            key.copy(name = name.trim(), notes = notes.trim(), maskedKey = masked, updatedAt = LocalDateTime.now().format(VaultTime)),
                            replaced,
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 40.dp).clip(RoundedCornerShape(12.dp)).background(DangerWash)
                        .clickable(role = Role.Button, onClick = onDelete),
                    horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TcIconView(TcIcon.Trash, 14.dp, Tc.Danger)
                    TcText("删除此密钥", Tc.FsSub, color = Tc.Danger, weight = FontWeight.SemiBold)
                }
            }
        }
    }
}

@Composable
private fun VaultLabel(
    label: String,
    required: Boolean = false,
    hint: String? = null,
    action: String? = null,
    onAction: () -> Unit = {},
    content: @Composable () -> Unit,
) {
    Column {
        Row(Modifier.fillMaxWidth().padding(bottom = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            TcText(label, Tc.FsSub, weight = FontWeight.SemiBold, color = Tc.Ink)
            if (required) TcText(" *", Tc.FsSub, color = Tc.Accent, weight = FontWeight.SemiBold)
            Box(Modifier.weight(1f))
            hint?.let { TcText(it, Tc.FsCap, color = Tc.Ink2) }
            if (action != null) {
                Row(
                    Modifier.clip(RoundedCornerShape(6.dp)).clickable(onClick = onAction).padding(horizontal = 2.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    TcPathIcon(VaultIcon.Clipboard, 12.dp, Tc.Accent)
                    TcText(action, Tc.FsCap, color = Tc.Accent, weight = FontWeight.Medium)
                }
            }
        }
        content()
    }
}

@Composable
private fun TypeOption(label: String, on: Boolean, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .clip(shape)
            .background(if (on) Tc.AccentSoft else Tc.Fill22)
            .border(1.dp, if (on) Tc.Accent else Tc.Line, shape)
            .clickable(role = Role.RadioButton, onClick = onClick)
            .padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(
            Modifier.size(16.dp).border(1.5.dp, if (on) Tc.Accent else Tc.Ink2.copy(alpha = .5f), CircleShape),
            contentAlignment = Alignment.Center,
        ) { if (on) Box(Modifier.size(8.dp).background(Tc.Accent, CircleShape)) }
        TcText(label, Tc.FsSub, color = if (on) Tc.Accent else Tc.Ink, weight = FontWeight.Medium)
    }
}

/** Secret input with a show/hide toggle. The value only lives in this sheet's memory. */
@Composable
private fun SecretField(value: String, onChange: (String) -> Unit, placeholder: String) {
    var visible by remember { mutableStateOf(false) }
    val source = remember { MutableInteractionSource() }
    val focused by source.collectIsFocusedAsState()
    val shape = RoundedCornerShape(Tc.RadiusCtl)
    BasicTextField(
        value = value,
        onValueChange = onChange,
        singleLine = true,
        interactionSource = source,
        visualTransformation = if (visible) VisualTransformation.None else PasswordVisualTransformation(),
        cursorBrush = SolidColor(Tc.Accent),
        textStyle = TextStyle(color = Tc.Ink, fontSize = Tc.FsSub.sp, fontFamily = Tc.Mono),
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 42.dp)
            .clip(shape)
            .background(Tc.Fill30)
            .border(1.dp, if (focused) Tc.Accent else Tc.Line, shape),
        decorationBox = { inner ->
            Row(Modifier.padding(start = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
                    if (value.isEmpty()) TcText(placeholder, Tc.FsSub, color = Tc.Ink2.copy(alpha = .6f), family = Tc.Mono, maxLines = 1)
                    inner()
                }
                Box(
                    Modifier.size(42.dp).clickable(onClickLabel = if (visible) "隐藏" else "显示") { visible = !visible },
                    contentAlignment = Alignment.Center,
                ) { TcPathIcon(if (visible) VaultIcon.Eye else VaultIcon.EyeOff, 16.dp, Tc.Ink2) }
            }
        },
    )
}

@Composable
private fun AccentButton(label: String, modifier: Modifier = Modifier, icon: Boolean = false, onClick: () -> Unit) {
    val shape = RoundedCornerShape(12.dp)
    Row(
        modifier
            .heightIn(min = 40.dp)
            .shadow(4.dp, shape, ambientColor = Tc.CardShadow, spotColor = Tc.CardShadow)
            .clip(shape)
            .background(Tc.Accent)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon) TcIconView(TcIcon.Plus, 14.dp, Color.White)
        TcText(label, Tc.FsSub, color = Color.White, weight = FontWeight.SemiBold)
    }
}

@Composable
private fun GhostButton(label: String, modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.heightIn(min = 40.dp).clip(RoundedCornerShape(12.dp)).background(Tc.Fill30).border(1.dp, Tc.Line, RoundedCornerShape(12.dp))
            .clickable(role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { TcText(label, Tc.FsSub, color = Tc.Ink2, weight = FontWeight.Medium) }
}

@Composable
private fun DeleteConfirm(onCancel: () -> Unit, onConfirm: () -> Unit) {
    Box(
        Modifier.fillMaxSize().background(Tc.Scrim).clickable(remember { MutableInteractionSource() }, null, onClick = onCancel).padding(24.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.widthIn(max = 360.dp).fillMaxWidth()
                .tcGlass(RoundedCornerShape(16.dp), fill = Tc.GlassStrong, elevation = 16.dp, shadowColor = Tc.PopShadow)
                .clickable(remember { MutableInteractionSource() }, null) {}
                .padding(20.dp),
        ) {
            Row(Modifier.padding(bottom = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Box(Modifier.size(36.dp).background(DangerWash, CircleShape), contentAlignment = Alignment.Center) {
                    TcPathIcon(VaultIcon.Alert, 18.dp, Tc.Danger)
                }
                Column {
                    TcText("确认删除密钥？", Tc.FsBody, weight = FontWeight.SemiBold)
                    TcText("操作不可撤销", Tc.FsCap, color = Tc.Ink2)
                }
            }
            TcText(
                "删除后，使用这条凭证的服务可能无法继续工作。", Tc.FsSub,
                Modifier.padding(bottom = 20.dp).fillMaxWidth().tcInner(RoundedCornerShape(12.dp)).padding(12.dp),
                color = Tc.Ink2,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                GhostButton("取消", Modifier.weight(1f), onCancel)
                Box(
                    Modifier.weight(1f).heightIn(min = 40.dp).clip(RoundedCornerShape(12.dp)).background(Tc.Danger)
                        .clickable(role = Role.Button, onClick = onConfirm),
                    contentAlignment = Alignment.Center,
                ) { TcText("确认删除", Tc.FsSub, color = Color.White, weight = FontWeight.SemiBold) }
            }
        }
    }
}

@Composable
private fun VaultToast(text: String?, seq: Int, modifier: Modifier) {
    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(seq) {
        if (text == null) return@LaunchedEffect
        visible = true
        delay(2500)
        visible = false
    }
    val lift = with(LocalDensity.current) { 8.dp.roundToPx() }
    AnimatedVisibility(
        visible = visible,
        modifier = modifier.padding(horizontal = 24.dp),
        enter = fadeIn(tween(300)) + slideInVertically(tween(300)) { -lift },
        exit = fadeOut(tween(300)) + slideOutVertically(tween(300)) { -lift },
    ) {
        Row(
            Modifier.clip(CircleShape).background(Tc.ToastBg).padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Box(Modifier.size(6.dp).background(Color(0xFF8FC2A8), CircleShape))
            TcText(text.orEmpty(), Tc.FsSub, color = Color.White)
        }
    }
}
