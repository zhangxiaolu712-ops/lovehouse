package fyi.b612.lovehouse.feature.events

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import fyi.b612.lovehouse.feature.settings.Tc
import fyi.b612.lovehouse.feature.settings.TcInput
import fyi.b612.lovehouse.feature.settings.TcPathIcon
import fyi.b612.lovehouse.feature.settings.TcSwitch
import fyi.b612.lovehouse.feature.settings.TcText
import fyi.b612.lovehouse.feature.settings.tcGlass
import fyi.b612.lovehouse.feature.settings.tcInner
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

// Owner HTML「收件箱」→ native Compose. Tokens come from the shared Tc set (same values as the HTML :root);
// only the Inbox-specific category tones live here.

internal object Ib {
    val ChatInk = Color(0xFF4F7F78)
    val ChatSoft = Color(0x1F4F7F78)
    val ApprInk = Color(0xFF8A6A2E)
    val ApprSoft = Color(0x24B08A46)
    val MailInk = Color(0xFFA55F52)
    val MailSoft = Color(0x1FA55F52)
    val SvcInk = Color(0xFF6E6296)
    val SvcSoft = Color(0x1F7868A0)
    val OkSoft = Color(0x1A4F7F68)
    val DangerSoft = Color(0x1A9A3B32)
}

/** The HTML's own 24×24 line icons, circles/rects rewritten as path data. */
internal object IbPath {
    const val Inbox = "M3 13h5l2 3h4l2-3h5M5 5h14l2 8v6H3v-6z"
    const val Bubble = "M21 12a8 8 0 0 1-11.6 7.1L4 20l1-4.6A8 8 0 1 1 21 12z"
    const val Shield = "M12 3 5 6v5c0 4.5 3 8.3 7 10 4-1.7 7-5.5 7-10V6z"
    const val Branch = "M8 5a2 2 0 1 1-4 0a2 2 0 1 1 4 0M8 19a2 2 0 1 1-4 0a2 2 0 1 1 4 0M20 8a2 2 0 1 1-4 0a2 2 0 1 1 4 0M6 7v10M18 10c0 4-6 3-12 7"
    const val Db = "M19 5a7 3 0 1 1-14 0a7 3 0 1 1 14 0M5 5v14c0 1.7 3.1 3 7 3s7-1.3 7-3V5M5 12c0 1.7 3.1 3 7 3s7-1.3 7-3"
    const val Server = "M5 4h14a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V6a2 2 0 0 1 2-2zM5 13h14a2 2 0 0 1 2 2v3a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-3a2 2 0 0 1 2-2zM7 7.5h.01M7 16.5h.01"
    const val Back = "M19 12H5M11 18l-6-6 6-6"
    const val Jump = "M7 17 17 7M8 7h9v9"
    const val Check = "m5 12 5 5 9-10"
    const val X = "M6 6l12 12M18 6 6 18"
    const val Plus = "M12 5v14M5 12h14"
    const val Gear = "M15 12a3 3 0 1 1-6 0a3 3 0 1 1 6 0M12 2v3M12 19v3M2 12h3M19 12h3M4.9 4.9l2.1 2.1M17 17l2.1 2.1M4.9 19.1 7 17M17 7l2.1-2.1"
    const val Unread = "M20 12a8 8 0 1 1-16 0a8 8 0 1 1 16 0M14.5 12a2.5 2.5 0 1 1-5 0a2.5 2.5 0 1 1 5 0"
    const val Star = "m12 3 2.8 5.7 6.2.9-4.5 4.4 1.1 6.2L12 17.3 6.4 20.2l1.1-6.2L3 9.6l6.2-.9z"
    const val Archive = "M4.5 4h15a1.5 1.5 0 0 1 1.5 1.5v2a1.5 1.5 0 0 1-1.5 1.5h-15A1.5 1.5 0 0 1 3 7.5v-2A1.5 1.5 0 0 1 4.5 4zM5 9v9a2 2 0 0 0 2 2h10a2 2 0 0 0 2-2V9M10 13h4"
    const val Trash = "M4 7h16M10 11v6M14 11v6M6 7l1 12a2 2 0 0 0 2 2h6a2 2 0 0 0 2-2l1-12M9 7V4h6v3"
    const val Search = "M17.5 11a6.5 6.5 0 1 1-13 0a6.5 6.5 0 1 1 13 0m2.5 9-4.2-4.2"
}

internal enum class IbTone(val ink: Color, val soft: Color) {
    All(Tc.Accent, Tc.AccentSoft),
    Chat(Ib.ChatInk, Ib.ChatSoft),
    Appr(Ib.ApprInk, Ib.ApprSoft),
    Mail(Ib.MailInk, Ib.MailSoft),
    Svc(Ib.SvcInk, Ib.SvcSoft),
}

/** Sources from the HTML. Only All / reply / approval carry real data; the rest are reserved slots. */
internal enum class InboxSource(
    val label: String,
    val tone: IbTone,
    val icon: String? = null,
    val mono: String? = null,
    val kind: InboxKind? = null,
) {
    All("全部", IbTone.All, icon = IbPath.Inbox, kind = InboxKind.All),
    Reply("回复完成", IbTone.Chat, icon = IbPath.Bubble, kind = InboxKind.ReplyCompleted),
    Approval("权限审批", IbTone.Appr, icon = IbPath.Shield, kind = InboxKind.ConfirmationRequired),
    Gmail("Gmail", IbTone.Mail, mono = "G"),
    Qq("QQ 邮箱", IbTone.Mail, mono = "Q"),
    Netease("网易邮箱", IbTone.Mail, mono = "网"),
    Github("GitHub", IbTone.Svc, icon = IbPath.Branch),
    Db("数据库", IbTone.Svc, icon = IbPath.Db),
    Vultr("Vultr", IbTone.Svc, icon = IbPath.Server),
    ;

    val connected: Boolean get() = kind != null
}

internal fun inboxSectionLabel(kind: InboxKind, section: InboxSection): String = when (kind) {
    InboxKind.All -> if (section == InboxSection.Attention) "未读 · 待处理" else "已读 · 历史"
    InboxKind.ReplyCompleted -> if (section == InboxSection.Attention) "未读" else "已读"
    InboxKind.ConfirmationRequired -> if (section == InboxSection.Attention) "待处理" else "已处理"
}

/** Bulk READ is only offered where eligible information events exist; approvals are PROTECTED. */
internal fun inboxBulkReadEnabled(kind: InboxKind?, counts: InboxCounts?): Boolean = when (kind) {
    InboxKind.All, InboxKind.ReplyCompleted -> (counts?.replyUnread ?: 0) > 0
    InboxKind.ConfirmationRequired, null -> false
}

/** Pull size from the「再拉 N 条」box: blank or invalid falls back to 30, anything above the server max is clamped. */
internal fun inboxPullSize(raw: String): Pair<Int, Boolean> {
    val parsed = raw.trim().toIntOrNull()
    if (parsed == null || parsed < 1) return INBOX_DEFAULT_LIMIT to false
    return if (parsed > INBOX_MAX_LIMIT) INBOX_MAX_LIMIT to true else parsed to false
}

/** Short tail of a canonical ID, e.g. "#7d16" — for telling rows apart, not a sequence number. */
internal fun inboxIdTail(id: String?): String? = id?.filter(Char::isLetterOrDigit)?.takeLast(4)?.takeIf { it.isNotEmpty() }?.let { "#$it" }

internal fun inboxStatusLabel(status: String): Pair<String, IbPillStyle> = when (status) {
    "pending" -> "待处理" to IbPillStyle.Pending
    "approved" -> "已批准" to IbPillStyle.Ok
    "denied" -> "已拒绝" to IbPillStyle.Bad
    "expired" -> "已过期" to IbPillStyle.Idle
    "consumed" -> "已处理" to IbPillStyle.Idle
    else -> status to IbPillStyle.Idle
}

internal enum class IbPillStyle { Pending, Ok, Bad, Idle }

private val RelativeDate = DateTimeFormatter.ofPattern("M/d")
private val AbsoluteDate = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

internal fun inboxRelativeTime(createdAt: String, nowMillis: Long = System.currentTimeMillis()): String {
    val at = runCatching { Instant.parse(createdAt) }.getOrNull() ?: return createdAt
    val minutes = (nowMillis - at.toEpochMilli()) / 60_000
    return when {
        minutes < 1 -> "刚刚"
        minutes < 60 -> "$minutes 分钟前"
        minutes < 24 * 60 -> "${minutes / 60} 小时前"
        minutes < 48 * 60 -> "昨天"
        else -> RelativeDate.format(at.atZone(ZoneId.systemDefault()))
    }
}

internal fun inboxAbsoluteTime(createdAt: String): String =
    runCatching { AbsoluteDate.format(Instant.parse(createdAt).atZone(ZoneId.systemDefault())) }.getOrDefault(createdAt)

// ---------------------------------------------------------------------------------------------
// Small pieces shared by the list and the detail page
// ---------------------------------------------------------------------------------------------

@Composable
internal fun IbTile(source: InboxSource, size: Dp = 24.dp, radius: Dp = 999.dp, onAccent: Boolean = false) {
    val ink = if (onAccent) Color.White else source.tone.ink
    val fill = if (onAccent) Color.White.copy(alpha = .22f) else source.tone.soft
    Box(Modifier.size(size).clip(RoundedCornerShape(radius)).background(fill), contentAlignment = Alignment.Center) {
        val icon = source.icon
        if (icon != null) {
            TcPathIcon(icon, if (size > 30.dp) 18.dp else 14.dp, ink)
        } else {
            TcText(source.mono.orEmpty(), if (size > 30.dp) 14f else 11f, color = ink, weight = FontWeight.SemiBold, lineHeight = 1f)
        }
    }
}

@Composable
internal fun IbPill(text: String, style: IbPillStyle) {
    val (fill, ink) = when (style) {
        IbPillStyle.Pending -> Ib.ApprSoft to Ib.ApprInk
        IbPillStyle.Ok -> Ib.OkSoft to Tc.Ok
        IbPillStyle.Bad -> Ib.DangerSoft to Tc.Danger
        IbPillStyle.Idle -> Color.Transparent to Tc.Idle
    }
    val shape = RoundedCornerShape(999.dp)
    Box(
        Modifier
            .clip(shape)
            .background(fill)
            .then(if (style == IbPillStyle.Idle) Modifier.border(1.dp, Tc.Idle, shape) else Modifier)
            .padding(horizontal = 8.dp),
    ) { TcText(text, 10f, color = ink, weight = FontWeight.Medium, lineHeight = 1.8f) }
}

internal enum class IbButtonStyle { Plain, Primary, Ok, Danger }

@Composable
internal fun IbButton(
    label: String,
    onClick: () -> Unit,
    style: IbButtonStyle = IbButtonStyle.Plain,
    small: Boolean = false,
    enabled: Boolean = true,
    icon: String? = null,
) {
    val (fill, ink, edge) = when (style) {
        IbButtonStyle.Plain -> Triple(Tc.Fill30, Tc.Ink2, Tc.Line)
        IbButtonStyle.Primary -> Triple(Tc.Accent, Color.White, Tc.Accent)
        IbButtonStyle.Ok -> Triple(Ib.OkSoft, Tc.Ok, Color.Transparent)
        IbButtonStyle.Danger -> Triple(Ib.DangerSoft, Tc.Danger, Color.Transparent)
    }
    val shape = RoundedCornerShape(Tc.RadiusCtl)
    Row(
        Modifier
            .alpha(if (enabled) 1f else .45f)
            .heightIn(min = if (small) 32.dp else 40.dp)
            .clip(shape)
            .background(fill)
            .border(1.dp, edge, shape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = if (small) 12.dp else 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally),
    ) {
        if (icon != null) TcPathIcon(icon, 16.dp, ink)
        TcText(label, if (small) 12f else 14f, color = ink, lineHeight = 1.2f, maxLines = 1)
    }
}

/** `.ibtn`: square icon button. A disabled one stays visible (Owner's choice) and explains itself on tap. */
@Composable
internal fun IbIconButton(
    icon: String,
    label: String,
    onClick: () -> Unit,
    tint: Color = Tc.Ink2,
    pressed: Boolean = false,
    available: Boolean = true,
) {
    val shape = RoundedCornerShape(Tc.RadiusCtl)
    Box(
        Modifier
            .size(40.dp)
            .alpha(if (available) 1f else .45f)
            .clip(shape)
            .background(if (pressed) Tc.Fill30 else Color.Transparent)
            .border(1.dp, if (pressed) Tc.Line else Color.Transparent, shape)
            .clickable(onClickLabel = label, role = Role.Button, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { TcPathIcon(icon, 20.dp, if (pressed) Tc.Accent else tint) }
}

/** Top bar: Inbox Home has no back arrow (first-level page); the detail page has exactly one. */
@Composable
internal fun IbTopBar(total: Int?, onBack: (() -> Unit)?, onAdd: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(56.dp).padding(bottom = 0.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            Box(
                Modifier.size(Tc.Tap).clip(CircleShape).clickable(onClickLabel = "返回", role = Role.Button, onClick = onBack),
                contentAlignment = Alignment.Center,
            ) { TcPathIcon(IbPath.Back, 22.dp, Tc.Ink) }
        } else {
            Spacer(Modifier.width(Tc.Tap))
        }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                TcText("收件箱", Tc.FsTitle, weight = FontWeight.Medium, lineHeight = 1.25f)
                if (total != null && total > 0) {
                    TcText("$total", 12f, color = Tc.Accent, weight = FontWeight.Medium, lineHeight = 1.6f)
                }
            }
            TcText("INBOX · MESSAGE HUB", 10f, color = Tc.Accent, weight = FontWeight.SemiBold, letterSpacing = .26f, family = Tc.Latin, lineHeight = 1.25f)
        }
        Box(
            Modifier.size(Tc.Tap).clip(CircleShape).clickable(onClickLabel = "添加接入", role = Role.Button, onClick = onAdd),
            contentAlignment = Alignment.Center,
        ) { TcPathIcon(IbPath.Plus, 22.dp, Tc.Ink) }
    }
}

@Composable
internal fun BoxScope.IbToast(message: String?, onShown: () -> Unit) {
    LaunchedEffect(message) {
        if (message != null) {
            delay(2_200)
            onShown()
        }
    }
    if (message != null) {
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .navigationBarsPadding()
                .padding(bottom = 24.dp, start = 20.dp, end = 20.dp)
                .clip(RoundedCornerShape(999.dp))
                .background(Tc.ToastBg)
                .padding(horizontal = 16.dp, vertical = 9.dp),
        ) { TcText(message, 12f, color = Color.White, lineHeight = 1.4f, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
}

@Composable
internal fun IbEmpty(title: String, body: String, modifier: Modifier = Modifier, action: String? = null, onAction: (() -> Unit)? = null) {
    Column(
        modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        TcText(title, Tc.FsTitle, weight = FontWeight.Medium, align = TextAlign.Center)
        TcText(body, 12f, color = Tc.Ink2, align = TextAlign.Center, modifier = Modifier.widthIn(max = 260.dp))
        if (action != null && onAction != null) {
            Spacer(Modifier.height(6.dp))
            IbButton(action, onAction, small = true)
        }
    }
}

@Composable
internal fun IbLine() {
    Box(Modifier.fillMaxWidth().height(1.dp).background(Tc.Line))
}

// ---------------------------------------------------------------------------------------------
// Inbox Home
// ---------------------------------------------------------------------------------------------

private class InboxSectionState(
    val events: List<ServerEvent> = emptyList(),
    val nextCursor: String? = null,
    val loading: Boolean = false,
)

private sealed interface InboxLoad {
    data object Loading : InboxLoad
    data object Ready : InboxLoad
    data object AuthenticationRequired : InboxLoad
    data class Error(val message: String) : InboxLoad
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun InboxHome(
    repository: ServerEventRepository,
    conversationLabel: (String?) -> String?,
    onOpenEvent: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var sourceName by rememberSaveable { mutableStateOf(InboxSource.All.name) }
    val source = InboxSource.valueOf(sourceName)
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var queryDraft by rememberSaveable { mutableStateOf("") }
    var query by rememberSaveable { mutableStateOf("") }
    var counts by remember { mutableStateOf<InboxCounts?>(null) }
    var attention by remember { mutableStateOf(InboxSectionState()) }
    var history by remember { mutableStateOf(InboxSectionState()) }
    var load by remember { mutableStateOf<InboxLoad>(InboxLoad.Loading) }
    var toast by remember { mutableStateOf<String?>(null) }
    var sheetType by remember { mutableStateOf<InboxSourceType?>(null) }
    var reloadTick by remember { mutableIntStateOf(0) }
    var busy by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Coming back from the detail page (or the app returning to the foreground) re-reads the authoritative state.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        var first = true
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (first) first = false else reloadTick += 1
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    suspend fun refreshCounts() {
        when (val result = repository.inboxCounts()) {
            is InboxCountsResult.Ready -> counts = result.counts
            InboxCountsResult.AuthenticationRequired -> load = InboxLoad.AuthenticationRequired
            is InboxCountsResult.Error -> Unit
        }
    }

    suspend fun loadSection(section: InboxSection, limit: Int, append: Boolean): Boolean {
        val kind = source.kind ?: return false
        val current = if (section == InboxSection.Attention) attention else history
        val cursor = if (append) (current.nextCursor ?: return false) else null
        when (val result = repository.inbox(section, kind, query.takeIf(String::isNotBlank), limit, cursor)) {
            is ServerEventPageResult.Ready -> {
                val merged = if (append) (current.events + result.page.events).distinctBy(ServerEvent::id) else result.page.events
                val next = InboxSectionState(merged, result.page.nextCursor)
                if (section == InboxSection.Attention) attention = next else history = next
                return true
            }
            ServerEventPageResult.AuthenticationRequired -> load = InboxLoad.AuthenticationRequired
            is ServerEventPageResult.Error -> load = InboxLoad.Error(result.message)
        }
        return false
    }

    LaunchedEffect(sourceName, query, reloadTick) {
        attention = InboxSectionState()
        history = InboxSectionState()
        if (!source.connected) {
            load = InboxLoad.Ready
            refreshCounts()
            return@LaunchedEffect
        }
        load = InboxLoad.Loading
        refreshCounts()
        if (load is InboxLoad.AuthenticationRequired) return@LaunchedEffect
        val ok = loadSection(InboxSection.Attention, INBOX_DEFAULT_LIMIT, append = false) &&
            loadSection(InboxSection.History, INBOX_DEFAULT_LIMIT, append = false)
        if (ok) load = InboxLoad.Ready
    }

    val reloadAll: () -> Unit = { reloadTick += 1 }

    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp)) {
            IbTopBar(
                total = counts?.count(InboxKind.All, InboxSection.Attention),
                onBack = null,
                onAdd = { sheetType = InboxSourceType.forSource(source) },
            )
            Spacer(Modifier.height(8.dp))
            IbChips(source, counts) { picked ->
                if (picked != source) {
                    sourceName = picked.name
                }
            }
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 20.dp)
                    .tcGlass(),
            ) {
                IbStreamHead(
                    source = source,
                    searchOpen = searchOpen,
                    bulkEnabled = !busy && inboxBulkReadEnabled(source.kind, counts),
                    onToggleSearch = {
                        searchOpen = !searchOpen
                        if (!searchOpen && query.isNotEmpty()) {
                            query = ""
                            queryDraft = ""
                        }
                    },
                    onBulkRead = {
                        val kind = source.kind ?: return@IbStreamHead
                        busy = true
                        scope.launch {
                            toast = when (val result = repository.bulkRead(kind)) {
                                is InboxBulkReadResult.Recorded -> {
                                    val kept = counts?.approvalPending ?: 0
                                    "已读 ${result.markedReadCount} 条" + if (kind == InboxKind.All && kept > 0) "，$kept 条审批还等你处理" else ""
                                }
                                InboxBulkReadResult.Protected -> "审批消息需要你决定，不能一键已读"
                                InboxBulkReadResult.AuthenticationRequired -> "请先登录 LoveHouse App Account"
                                is InboxBulkReadResult.Error -> result.message
                            }
                            busy = false
                            reloadAll()
                        }
                    },
                )
                if (searchOpen) {
                    IbSearchBar(
                        value = queryDraft,
                        onValueChange = {
                            queryDraft = it.take(INBOX_MAX_QUERY_LENGTH)
                            if (queryDraft.isBlank() && query.isNotEmpty()) query = ""
                        },
                        onSubmit = { query = queryDraft.trim() },
                    )
                }
                LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
                    when {
                        !source.connected -> item {
                            IbEmpty("${source.label} 尚未接入", "后端链路还没接，这里不会收到消息，也不会显示示例数据。")
                        }
                        load is InboxLoad.AuthenticationRequired -> item {
                            IbEmpty("还没登录", "请先在设置里登录 LoveHouse App Account。")
                        }
                        load is InboxLoad.Error -> item {
                            IbEmpty("暂时读不到", (load as InboxLoad.Error).message, action = "重试", onAction = reloadAll)
                        }
                        load is InboxLoad.Loading && attention.events.isEmpty() && history.events.isEmpty() -> item {
                            IbEmpty("正在读取…", "从服务器拉取最近 $INBOX_DEFAULT_LIMIT 条。")
                        }
                        else -> {
                            if (query.isNotEmpty()) item {
                                TcText(
                                    "在服务器上按关键词查事件摘要，每个分区先回 $INBOX_DEFAULT_LIMIT 条。",
                                    11f,
                                    color = Tc.Ink2,
                                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 8.dp),
                                )
                            }
                            val kind = source.kind ?: InboxKind.All
                            listOf(InboxSection.Attention to attention, InboxSection.History to history).forEach { (section, state) ->
                                if (state.events.isEmpty()) return@forEach
                                val label = inboxSectionLabel(kind, section).let { if (query.isNotEmpty()) "「$query」· $it" else it }
                                val total = if (query.isEmpty()) counts?.count(kind, section) else null
                                inboxSection(
                                    key = section.wireValue,
                                    label = label,
                                    countLabel = (total ?: state.events.size).toString(),
                                    state = state,
                                    total = total,
                                    conversationLabel = conversationLabel,
                                    onOpen = onOpenEvent,
                                    onDecide = { event, decision ->
                                        scope.launch {
                                            toast = when (val result = repository.decide(event.id, decision)) {
                                                is ServerEventDecisionResult.Recorded ->
                                                    if (result.event.status == "approved") "已批准，决定已记录" else "已拒绝，决定已记录"
                                                is ServerEventDecisionResult.ServerState -> "服务器状态已刷新：${inboxStatusLabel(result.event.status).first}"
                                                ServerEventDecisionResult.AuthenticationRequired -> "请先登录 LoveHouse App Account"
                                                ServerEventDecisionResult.NotFound -> "该审批已不可用"
                                                is ServerEventDecisionResult.Error -> result.message
                                            }
                                            reloadAll()
                                        }
                                    },
                                    onPull = { raw ->
                                        val (size, clamped) = inboxPullSize(raw)
                                        scope.launch {
                                            if (loadSection(section, size, append = true)) {
                                                toast = if (clamped) "一次最多拉 $INBOX_MAX_LIMIT 条，已按 $INBOX_MAX_LIMIT 条拉取" else "已再拉 $size 条"
                                            }
                                        }
                                    },
                                )
                            }
                            if (attention.events.isEmpty() && history.events.isEmpty()) item {
                                if (query.isNotEmpty()) {
                                    IbEmpty("没搜到", "换个词试试，比如对话里的关键词或工具名。")
                                } else {
                                    IbEmpty("这里空空的", "暂时没有新消息。")
                                }
                            }
                        }
                    }
                }
            }
        }
        IbToast(toast) { toast = null }
        sheetType?.let { type ->
            InboxSourceSheet(initial = type, onDismiss = { sheetType = null })
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
private fun LazyListScope.inboxSection(
    key: String,
    label: String,
    countLabel: String,
    state: InboxSectionState,
    total: Int?,
    conversationLabel: (String?) -> String?,
    onOpen: (String) -> Unit,
    onDecide: (ServerEvent, ServerEventDecision) -> Unit,
    onPull: (String) -> Unit,
) {
    stickyHeader(key = "sec-$key") {
        Row(
            Modifier.fillMaxWidth().background(Tc.GlassStrong).padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TcText(label, 11f, color = Tc.Ink2, weight = FontWeight.Medium, letterSpacing = .2f, lineHeight = 1.4f)
            TcText(countLabel, 11f, color = Tc.Ink2, weight = FontWeight.Medium, lineHeight = 1.4f)
        }
    }
    items(state.events, key = { "$key-${it.id}" }) { event ->
        InboxRow(
            event = event,
            first = event.id == state.events.firstOrNull()?.id,
            conversationLabel = conversationLabel,
            onOpen = { onOpen(event.id) },
            onDecide = { decision -> onDecide(event, decision) },
        )
    }
    item(key = "more-$key") {
        IbMore(shown = state.events.size, total = total, hasMore = state.nextCursor != null, onPull = onPull)
    }
}

@Composable
private fun IbChips(selected: InboxSource, counts: InboxCounts?, onSelect: (InboxSource) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(bottom = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        InboxSource.entries.forEach { source ->
            val on = source == selected
            val count = source.kind?.let { counts?.count(it, InboxSection.Attention) } ?: 0
            val shape = RoundedCornerShape(999.dp)
            Row(
                Modifier
                    .heightIn(min = 32.dp)
                    .clip(shape)
                    .background(if (on) Tc.Accent else Tc.Fill22)
                    .border(1.dp, if (on) Tc.Accent else Tc.Line, shape)
                    .clickable(role = Role.Tab) { onSelect(source) }
                    .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                IbTile(source, onAccent = on)
                TcText(source.label, 12f, color = if (on) Color.White else Tc.Ink, lineHeight = 1.2f, maxLines = 1)
                if (count > 0) TcText("$count", 11f, color = if (on) Color.White else Tc.Accent, weight = FontWeight.SemiBold, lineHeight = 1.2f)
            }
        }
    }
}

@Composable
private fun IbStreamHead(
    source: InboxSource,
    searchOpen: Boolean,
    bulkEnabled: Boolean,
    onToggleSearch: () -> Unit,
    onBulkRead: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(start = 16.dp, end = 10.dp, top = 10.dp, bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        IbTile(source)
        TcText(source.label, 14f, weight = FontWeight.Medium, lineHeight = 1.3f, maxLines = 1, overflow = TextOverflow.Ellipsis)
        if (!source.connected) IbPill("尚未接入", IbPillStyle.Idle)
        Spacer(Modifier.weight(1f))
        if (source.connected) {
            IbIconButton(IbPath.Search, "搜索", onToggleSearch, pressed = searchOpen)
            // Hidden where nothing is eligible (approvals are PROTECTED); the server enforces it as well.
            IbButton("全部已读", onBulkRead, small = true, enabled = bulkEnabled)
        }
    }
    IbLine()
}

@Composable
private fun IbSearchBar(value: String, onValueChange: (String) -> Unit, onSubmit: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.weight(1f)) {
            IbTextField(value, onValueChange, "搜对话关键词、工具名…", onDone = onSubmit)
        }
        IbButton("搜索", onSubmit, style = IbButtonStyle.Primary, small = true)
    }
    IbLine()
}

@Composable
private fun IbTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    onDone: () -> Unit,
    modifier: Modifier = Modifier,
    numeric: Boolean = false,
    compact: Boolean = false,
) {
    val shape = RoundedCornerShape(Tc.RadiusCtl)
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        singleLine = true,
        cursorBrush = SolidColor(Tc.Accent),
        textStyle = TextStyle(
            color = Tc.Ink,
            fontSize = if (compact) 12.sp else 14.sp,
            fontFamily = Tc.Serif,
            textAlign = if (compact) TextAlign.Center else TextAlign.Start,
        ),
        keyboardOptions = KeyboardOptions(
            keyboardType = if (numeric) KeyboardType.Number else KeyboardType.Text,
            imeAction = if (numeric) ImeAction.Done else ImeAction.Search,
        ),
        keyboardActions = KeyboardActions(onDone = { onDone() }, onSearch = { onDone() }),
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = if (compact) 32.dp else 40.dp)
            .clip(shape)
            .background(Tc.Fill30)
            .border(1.dp, Tc.Line, shape)
            .padding(horizontal = if (compact) 8.dp else 12.dp, vertical = if (compact) 6.dp else 9.dp),
        decorationBox = { inner ->
            Box(contentAlignment = if (compact) Alignment.Center else Alignment.CenterStart) {
                if (value.isEmpty()) TcText(placeholder, if (compact) 12f else 14f, color = Tc.Ink2.copy(alpha = .6f), lineHeight = 1.3f)
                inner()
            }
        },
    )
}

@Composable
private fun InboxRow(
    event: ServerEvent,
    first: Boolean,
    conversationLabel: (String?) -> String?,
    onOpen: () -> Unit,
    onDecide: (ServerEventDecision) -> Unit,
) {
    val approval = event.kind == ServerEventKind.ConfirmationRequired
    val source = if (approval) InboxSource.Approval else InboxSource.Reply
    // Approvals are grouped by action state; only information events show the unread treatment.
    val unread = !approval && event.readState == ServerEventReadState.UNREAD
    val who = conversationLabel(event.threadId) ?: "对话"
    Column(
        Modifier
            .fillMaxWidth()
            .background(if (unread) Tc.Fill18 else Color.Transparent)
            .drawBehind {
                if (!first) drawLine(Tc.Line, Offset(0f, 0f), Offset(size.width, 0f), 1.dp.toPx())
                if (unread) drawCircle(Tc.Accent, 3.dp.toPx(), Offset(12.dp.toPx(), 28.dp.toPx()))
            }
            .clickable(onClickLabel = "打开", onClick = onOpen)
            .padding(start = 22.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            IbTile(source, size = 36.dp, radius = Tc.RadiusCtl)
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    TcText(
                        who,
                        14f,
                        weight = if (unread) FontWeight.SemiBold else FontWeight.Medium,
                        lineHeight = 1.4f,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (approval) inboxStatusLabel(event.status).let { (text, style) -> IbPill(text, style) }
                }
                if (approval) {
                    TcText(event.safeSummary, 11f, color = Tc.Ink2, family = Tc.Mono, lineHeight = 1.5f, maxLines = 1, overflow = TextOverflow.Ellipsis)
                } else {
                    TcText("回复完成", 12f, lineHeight = 1.5f, maxLines = 1)
                    TcText(event.safeSummary, 12f, color = Tc.Ink2, lineHeight = 1.6f, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
                TcText(
                    inboxRelativeTime(event.createdAt),
                    11f,
                    color = if (unread) Tc.Accent else Tc.Ink2,
                    weight = if (unread) FontWeight.Medium else FontWeight.Normal,
                    lineHeight = 1.4f,
                    maxLines = 1,
                )
                inboxIdTail(event.messageId)?.let { TcText(it, 10f, color = Tc.Idle, family = Tc.Mono, lineHeight = 1.4f, maxLines = 1) }
            }
        }
        if (event.canDecide) {
            Row(Modifier.padding(start = 48.dp, top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                IbButton("批准", { onDecide(ServerEventDecision.Approved) }, style = IbButtonStyle.Ok, small = true, icon = IbPath.Check)
                IbButton("拒绝", { onDecide(ServerEventDecision.Denied) }, style = IbButtonStyle.Danger, small = true, icon = IbPath.X)
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IbMore(shown: Int, total: Int?, hasMore: Boolean, onPull: (String) -> Unit) {
    if (!hasMore) {
        if (shown > INBOX_DEFAULT_LIMIT) {
            Box(Modifier.fillMaxWidth().drawBehind { drawLine(Tc.Line, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }
                .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 14.dp), contentAlignment = Alignment.Center) {
                TcText("已经到底啦 · 共 $shown 条", 12f, color = Tc.Idle)
            }
        }
        return
    }
    var pull by rememberSaveable { mutableStateOf(INBOX_DEFAULT_LIMIT.toString()) }
    FlowRow(
        Modifier.fillMaxWidth().drawBehind { drawLine(Tc.Line, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }
            .padding(start = 16.dp, end = 16.dp, top = 10.dp, bottom = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        TcText(if (total != null) "已显示 $shown / $total" else "已显示 $shown", 12f, color = Tc.Ink2, modifier = Modifier.weight(1f))
        TcText("再拉", 12f, color = Tc.Ink2)
        IbTextField(
            value = pull,
            onValueChange = { next -> pull = next.filter(Char::isDigit).take(3) },
            placeholder = "$INBOX_DEFAULT_LIMIT",
            onDone = { onPull(pull) },
            modifier = Modifier.width(64.dp),
            numeric = true,
            compact = true,
        )
        TcText("条", 12f, color = Tc.Ink2)
        IbButton("拉取", { onPull(pull) }, small = true)
    }
}

// ---------------------------------------------------------------------------------------------
// 「+」接入配置：future-source UI only. Nothing is saved, tested or sent anywhere.
// ---------------------------------------------------------------------------------------------

internal enum class InboxSourceType(
    val label: String,
    val mail: Boolean,
    val addrLabel: String,
    val addrPlaceholder: String,
    val url: String = "",
    val imap: String = "",
    val smtp: String = "",
    val pwLabel: String,
    val hint: String,
    val source: InboxSource?,
) {
    Gmail("Gmail", true, "邮箱地址", "name@gmail.com", imap = "imap.gmail.com:993", smtp = "smtp.gmail.com:465",
        pwLabel = "应用专用密码", hint = "Google 账号开两步验证后，在「安全性 → 应用专用密码」里生成，不是登录密码。", source = InboxSource.Gmail),
    Qq("QQ 邮箱", true, "邮箱地址", "12345@qq.com", imap = "imap.qq.com:993", smtp = "smtp.qq.com:465",
        pwLabel = "授权码", hint = "QQ 邮箱「设置 → 账号」里开启 IMAP/SMTP 后生成授权码，不是 QQ 密码。", source = InboxSource.Qq),
    Netease("网易邮箱", true, "邮箱地址", "name@163.com", imap = "imap.163.com:993", smtp = "smtp.163.com:465",
        pwLabel = "授权码", hint = "网易邮箱「设置 → POP3/SMTP/IMAP」开启 IMAP 后获取授权码。", source = InboxSource.Netease),
    Github("GitHub", false, "API 地址", "https://api.github.com", url = "https://api.github.com",
        pwLabel = "Token", hint = "用 fine-grained token，只勾要收通知的仓库，只读权限就够。", source = InboxSource.Github),
    Db("数据库", false, "项目 URL", "https://xxxx.supabase.co",
        pwLabel = "API Key", hint = "在 Supabase 项目「Settings → API」里复制。", source = InboxSource.Db),
    Vultr("Vultr", false, "API 地址", "https://api.vultr.com/v2", url = "https://api.vultr.com/v2",
        pwLabel = "API Key", hint = "在 Vultr「Account → API」里启用并复制，建议限制访问 IP。", source = InboxSource.Vultr),
    Custom("自定义", false, "URL", "https://…",
        pwLabel = "API Key / 密码", hint = "自定义服务：填它推送或查询用的地址和密钥。", source = null),
    ;

    companion object {
        fun forSource(source: InboxSource): InboxSourceType = entries.firstOrNull { it.source == source } ?: Gmail
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun InboxSourceSheet(initial: InboxSourceType, onDismiss: () -> Unit) {
    var type by remember { mutableStateOf(initial) }
    var addr by remember(type) { mutableStateOf(type.url) }
    var imap by remember(type) { mutableStateOf(type.imap) }
    var smtp by remember(type) { mutableStateOf(type.smtp) }
    // Held only while the sheet is open; never persisted, logged or sent (no backend for sources yet).
    var secret by remember(type) { mutableStateOf("") }
    var showSecret by remember(type) { mutableStateOf(false) }
    var perm by remember(type) { mutableStateOf(true) }
    var reply by remember(type) { mutableStateOf(type.mail) }
    var forward by remember(type) { mutableStateOf(true) }
    var note by remember(type) { mutableStateOf("") }
    androidx.activity.compose.BackHandler(onBack = onDismiss)
    Box(
        Modifier
            .fillMaxSize()
            .background(Tc.Scrim)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.BottomCenter,
    ) {
        val sheetShape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp)
        Column(
            Modifier
                .widthIn(max = 440.dp)
                .fillMaxWidth()
                .heightIn(max = 640.dp)
                .clip(sheetShape)
                .background(Tc.GlassStrong)
                .border(1.dp, Tc.Edge, sheetShape)
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .navigationBarsPadding()
                .imePadding(),
        ) {
            Box(Modifier.padding(top = 10.dp, bottom = 2.dp).size(40.dp, 4.dp).clip(RoundedCornerShape(999.dp)).background(Tc.SwitchOff).align(Alignment.CenterHorizontally))
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Spacer(Modifier.width(Tc.Tap))
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    TcText("添加接入", Tc.FsTitle, weight = FontWeight.Medium, lineHeight = 1.25f)
                    TcText("INBOX · SOURCE", 10f, color = Tc.Accent, weight = FontWeight.SemiBold, letterSpacing = .26f, family = Tc.Latin, lineHeight = 1.25f)
                }
                Box(
                    Modifier.size(Tc.Tap).clip(CircleShape).clickable(onClickLabel = "关闭", role = Role.Button, onClick = onDismiss),
                    contentAlignment = Alignment.Center,
                ) { TcPathIcon(IbPath.X, 22.dp, Tc.Ink) }
            }
            Column(
                Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState()).padding(start = 22.dp, end = 22.dp, top = 6.dp, bottom = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                IbSheetSection("接入什么") {
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        InboxSourceType.entries.forEach { option ->
                            val on = option == type
                            val shape = RoundedCornerShape(999.dp)
                            Row(
                                Modifier
                                    .heightIn(min = 32.dp)
                                    .clip(shape)
                                    .background(if (on) Tc.Accent else Tc.Fill22)
                                    .border(1.dp, if (on) Tc.Accent else Tc.Line, shape)
                                    .clickable(role = Role.Tab) { type = option; note = "" }
                                    .padding(start = 4.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                val tileSource = option.source
                                if (tileSource != null) {
                                    IbTile(tileSource, onAccent = on)
                                } else {
                                    Box(
                                        Modifier.size(24.dp).clip(CircleShape).background(if (on) Color.White.copy(alpha = .22f) else Ib.SvcSoft),
                                        contentAlignment = Alignment.Center,
                                    ) { TcPathIcon(IbPath.Gear, 14.dp, if (on) Color.White else Ib.SvcInk) }
                                }
                                TcText(option.label, 12f, color = if (on) Color.White else Tc.Ink, lineHeight = 1.2f)
                            }
                        }
                    }
                }
                IbSheetSection("填写") {
                    IbField(type.addrLabel) { TcInput(addr, { addr = it }, placeholder = type.addrPlaceholder) }
                    if (type.mail) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Box(Modifier.weight(1f)) { IbField("收信 IMAP") { TcInput(imap, { imap = it }) } }
                            Box(Modifier.weight(1f)) { IbField("发信 SMTP") { TcInput(smtp, { smtp = it }) } }
                        }
                    }
                }
                IbSheetSection("密码") {
                    IbField(type.pwLabel) {
                        Box {
                            TcInput(secret, { secret = it }, placeholder = "粘贴${type.pwLabel}", secret = !showSecret)
                            Box(
                                Modifier.align(Alignment.CenterEnd).padding(end = 6.dp).clip(RoundedCornerShape(8.dp))
                                    .clickable { showSecret = !showSecret }.padding(horizontal = 8.dp, vertical = 4.dp),
                            ) { TcText(if (showSecret) "隐藏" else "显示", 12f, color = Tc.Accent, lineHeight = 1.3f) }
                        }
                    }
                    TcText(type.hint, 11f, color = Tc.Ink2)
                }
                IbSheetSection("开关") {
                    Column(Modifier.fillMaxWidth().tcInner()) {
                        IbToggleRow("权限提醒", "需要你批准的操作，推到收件箱提醒", perm, first = true) { perm = it }
                        IbToggleRow("回复", if (type.mail) "在收件箱里直接回复邮件" else "服务通知不能回复", reply && type.mail, enabled = type.mail) { reply = it }
                        IbToggleRow("转发", "可以把消息转发到 App 里的聊天", forward) { forward = it }
                    }
                }
            }
            IbLine()
            FlowRow(
                Modifier.fillMaxWidth().padding(start = 22.dp, end = 22.dp, top = 12.dp, bottom = 18.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp),
                itemVerticalAlignment = Alignment.CenterVertically,
            ) {
                IbButton("测试连接", { note = "尚未接入：后端链路还没接，无法测试" })
                TcText(note, 11f, color = Tc.Idle, modifier = Modifier.weight(1f))
                IbButton("保存", { note = "尚未接入：没有保存任何内容" }, style = IbButtonStyle.Primary)
            }
        }
    }
}

@Composable
private fun IbSheetSection(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        TcText(label, 12f, color = Tc.Ink2, weight = FontWeight.Medium)
        content()
    }
}

@Composable
private fun IbField(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        TcText(label, 12f, color = Tc.Ink2)
        content()
    }
}

@Composable
private fun IbToggleRow(
    title: String,
    description: String,
    checked: Boolean,
    first: Boolean = false,
    enabled: Boolean = true,
    onToggle: (Boolean) -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .alpha(if (enabled) 1f else .45f)
            .drawBehind { if (!first) drawLine(Tc.Line, Offset.Zero, Offset(size.width, 0f), 1.dp.toPx()) }
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Column(Modifier.weight(1f)) {
            TcText(title, 14f, weight = FontWeight.Medium, lineHeight = 1.4f)
            TcText(description, 11f, color = Tc.Ink2, lineHeight = 1.4f)
        }
        TcSwitch(checked, title, enabled = enabled, onToggle = onToggle)
    }
}
