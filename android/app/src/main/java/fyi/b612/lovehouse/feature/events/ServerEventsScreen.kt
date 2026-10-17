package fyi.b612.lovehouse.feature.events

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fyi.b612.lovehouse.core.designsystem.LoveHouseGlass
import kotlinx.coroutines.launch
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import fyi.b612.lovehouse.feature.settings.Tc
import fyi.b612.lovehouse.feature.settings.TcText
import fyi.b612.lovehouse.feature.settings.tcGlass
import fyi.b612.lovehouse.feature.settings.tcInner

/** Desktop → 收件箱 (first-level page). Kept under the old name so the Event route and deep link stay stable. */
@Composable
fun ServerEventsScreen(
    repository: ServerEventRepository,
    conversationLabel: (String?) -> String?,
    onOpenEvent: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    InboxHome(repository = repository, conversationLabel = conversationLabel, onOpenEvent = onOpenEvent, modifier = modifier)
}

/** Inbox Event Detail (second-level page): one top-left back arrow that always returns to Inbox Home. */
@Composable
fun ServerEventDetailScreen(
    eventId: String,
    repository: ServerEventRepository,
    onOpenTarget: (ServerEvent) -> Boolean,
    onBack: () -> Unit,
    conversationLabel: (String?) -> String? = { null },
    targetUnavailable: Boolean = false,
    modifier: Modifier = Modifier,
) {
    var state by remember(eventId) { mutableStateOf<ServerEventLoadResult?>(null) }
    var feedback by remember(eventId) { mutableStateOf<String?>(null) }
    var deciding by remember(eventId) { mutableStateOf(false) }
    var toast by remember(eventId) { mutableStateOf<String?>(null) }
    var sheetOpen by remember(eventId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    suspend fun reload() { state = repository.get(eventId) }
    LaunchedEffect(eventId) { reload() }
    // Opening an information Event records the authoritative W4C READ; approvals keep their action state.
    val found = (state as? ServerEventLoadResult.Found)?.event
    LaunchedEffect(found?.id, found?.readState) {
        val event = found ?: return@LaunchedEffect
        if (event.kind == ServerEventKind.ReplyCompleted && event.readState == ServerEventReadState.UNREAD) {
            (repository.markRead(event.id) as? ServerEventReadResult.Recorded)?.let { state = ServerEventLoadResult.Found(it.event) }
        }
    }
    BackHandler(onBack = onBack)
    val decide: (ServerEvent, ServerEventDecision) -> Unit = { event, decision ->
        deciding = true
        feedback = null
        scope.launch {
            when (val result = repository.decide(event.id, decision)) {
                is ServerEventDecisionResult.Recorded -> {
                    state = ServerEventLoadResult.Found(result.event)
                    feedback = if (result.event.status == "approved") "已批准，决定已记录" else "已拒绝，决定已记录"
                }
                is ServerEventDecisionResult.ServerState -> {
                    state = ServerEventLoadResult.Found(result.event)
                    feedback = when (result.event.status) {
                        "expired" -> "该确认已过期，已刷新服务器状态"
                        "approved" -> "服务器已记录为批准"
                        "denied" -> "服务器已记录为拒绝"
                        else -> "服务器状态已刷新"
                    }
                }
                ServerEventDecisionResult.AuthenticationRequired -> state = ServerEventLoadResult.AuthenticationRequired
                ServerEventDecisionResult.NotFound -> state = ServerEventLoadResult.NotFound
                is ServerEventDecisionResult.Error -> feedback = result.message
            }
            deciding = false
        }
    }
    Box(modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 20.dp)) {
            IbTopBar(total = null, onBack = onBack, onAdd = { sheetOpen = true })
            Spacer(Modifier.height(8.dp))
            Column(
                Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 20.dp)
                    .tcGlass(),
            ) {
                when (val current = state) {
                    null -> IbEmpty("正在读取…", "正在向服务器确认这条消息的状态。")
                    ServerEventLoadResult.AuthenticationRequired -> IbEmpty("还没登录", "请先在设置里登录 LoveHouse App Account。")
                    ServerEventLoadResult.NotFound -> IbEmpty("找不到这条消息", "它不存在或已不可访问。")
                    is ServerEventLoadResult.Error -> IbEmpty("暂时读不到", current.message, action = "重试") { scope.launch { reload() } }
                    is ServerEventLoadResult.Found -> {
                        val event = current.event
                        EventDetailToolbar(
                            event = event,
                            deciding = deciding,
                            onDecide = { decision -> decide(event, decision) },
                            onJump = {
                                feedback = if (event.hasExactNavigationIdentity() && onOpenTarget(event)) null else "目标消息暂时无法定位。"
                            },
                            onUnavailable = { label -> toast = "$label 尚未接入" },
                        )
                        EventDetailBody(event, conversationLabel(event.threadId), targetUnavailable, feedback)
                    }
                }
            }
        }
        IbToast(toast) { toast = null }
        if (sheetOpen) InboxSourceSheet(initial = InboxSourceType.Gmail, onDismiss = { sheetOpen = false })
    }
}

/** `.d-bar`: real actions on the left; actions the backend does not support yet stay greyed out. */
@Composable
private fun EventDetailToolbar(
    event: ServerEvent,
    deciding: Boolean,
    onDecide: (ServerEventDecision) -> Unit,
    onJump: () -> Unit,
    onUnavailable: (String) -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 56.dp).padding(horizontal = 10.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        if (event.kind == ServerEventKind.ConfirmationRequired) {
            if (event.canDecide) {
                IbIconButton(IbPath.Check, "批准", { if (!deciding) onDecide(ServerEventDecision.Approved) }, tint = Tc.Ok, available = !deciding)
                IbIconButton(IbPath.X, "拒绝", { if (!deciding) onDecide(ServerEventDecision.Denied) }, tint = Tc.Danger, available = !deciding)
            }
            IbIconButton(IbPath.Jump, "去对话", onJump, available = event.hasExactNavigationIdentity())
        } else {
            IbIconButton(IbPath.Jump, "去对话", onJump, tint = Tc.Accent, available = event.hasExactNavigationIdentity())
        }
        Box(Modifier.padding(horizontal = 6.dp).size(1.dp, 20.dp).background(Tc.Line))
        IbIconButton(IbPath.Unread, "标为未读", { onUnavailable("标为未读") }, available = false)
        IbIconButton(IbPath.Star, "加星标", { onUnavailable("星标") }, available = false)
        Spacer(Modifier.weight(1f))
        IbIconButton(IbPath.Archive, "归档", { onUnavailable("归档") }, available = false)
        IbIconButton(IbPath.Trash, "删除", { onUnavailable("删除") }, tint = Tc.Danger, available = false)
    }
    IbLine()
}

@Composable
private fun EventDetailBody(event: ServerEvent, conversation: String?, targetUnavailable: Boolean, feedback: String?) {
    val approval = event.kind == ServerEventKind.ConfirmationRequired
    val source = if (approval) InboxSource.Approval else InboxSource.Reply
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 18.dp, end = 18.dp, top = 18.dp, bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IbTile(source)
            TcText(if (event.kind == ServerEventKind.Unknown) event.kindLabel() else source.label, 12f, color = Tc.Ink2)
            TcText("·", 12f, color = Tc.Ink2)
            TcText(inboxAbsoluteTime(event.createdAt), 12f, color = Tc.Ink2)
        }
        TcText(if (approval) event.safeSummary else conversation ?: "对话", Tc.FsTitle, weight = FontWeight.Medium, lineHeight = 1.45f)
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (approval) {
                EventMeta("对话", listOfNotNull(conversation ?: "对话", inboxIdTail(event.messageId)).joinToString(" · "))
                EventMeta("工具", event.toolName ?: "未提供")
                EventMeta("工具行动", inboxIdTail(event.toolActionId) ?: "—")
                Row(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalAlignment = Alignment.CenterVertically) {
                    TcText("状态", 12f, color = Tc.Ink2, modifier = Modifier.width(56.dp))
                    inboxStatusLabel(event.status).let { (text, style) -> IbPill(text, style) }
                }
            } else {
                EventMeta("消息", inboxIdTail(event.messageId) ?: "—")
            }
        }
        if (!approval) TcText(event.safeSummary, 14f, lineHeight = 1.8f)
        if (event.kind == ServerEventKind.ConfirmationRequired) {
            TcText("你的决定将记录到服务器；这不代表原操作已经执行。", 11f, color = Tc.Ink2)
        }
        if (event.kind == ServerEventKind.Unknown) {
            TcText("这是一种较新的动态类型；当前版本仅安全显示服务器摘要。", 12f, color = Tc.Ink2)
        }
        if (targetUnavailable) {
            Box(Modifier.fillMaxWidth().tcInner().padding(12.dp)) {
                TcText("目标消息暂时无法定位。事件仍保留在这里，没有跳到其他内容。", 12f, color = Tc.Ink2)
            }
        }
        feedback?.let { TcText(it, 12f, color = Tc.Accent) }
    }
}

@Composable
private fun EventMeta(label: String, value: String) {
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        TcText(label, 12f, color = Tc.Ink2, modifier = Modifier.width(56.dp))
        TcText(value, 12f, family = if (value.startsWith("#")) Tc.Mono else Tc.Serif)
    }
}

internal sealed interface NotificationEventTargetResolution {
    data class Chat(
        val canonicalThreadId: String,
        val localThreadId: String,
        val messageId: String,
        val toolActionId: String?,
        val sourceEventId: String,
    ) : NotificationEventTargetResolution

    data class Confirmation(val eventId: String) : NotificationEventTargetResolution

    data object Fallback : NotificationEventTargetResolution
}

internal suspend fun resolveNotificationEventTarget(
    eventId: String,
    repository: ServerEventRepository,
    resolveLocalThreadId: (String) -> String?,
    rehydrateThread: suspend (localThreadId: String, canonicalThreadId: String) -> Boolean,
    containsTarget: (localThreadId: String, messageId: String, toolActionId: String?) -> Boolean,
): NotificationEventTargetResolution {
    val event = (repository.get(eventId) as? ServerEventLoadResult.Found)?.event
        ?: return NotificationEventTargetResolution.Fallback
    val fallback = when (event.kind) {
        ServerEventKind.ConfirmationRequired -> NotificationEventTargetResolution.Confirmation(event.id)
        ServerEventKind.ReplyCompleted -> NotificationEventTargetResolution.Fallback
        ServerEventKind.Unknown -> return NotificationEventTargetResolution.Fallback
    }
    val threadId = event.threadId
    val messageId = event.messageId
    val toolActionId = event.toolActionId
    if (threadId == null || messageId == null ||
        (event.kind == ServerEventKind.ConfirmationRequired &&
            (toolActionId == null || event.relationStatus != "linked"))
    ) {
        return fallback
    }
    val localThreadId = resolveLocalThreadId(threadId)
        ?: return fallback
    if (!rehydrateThread(localThreadId, threadId)) {
        return fallback
    }
    if (!containsTarget(localThreadId, messageId, toolActionId)) {
        return fallback
    }
    return NotificationEventTargetResolution.Chat(
        canonicalThreadId = threadId,
        localThreadId = localThreadId,
        messageId = messageId,
        toolActionId = toolActionId,
        sourceEventId = event.id,
    )
}

@Composable
internal fun NotificationEventTargetResolver(
    eventId: String,
    repository: ServerEventRepository,
    resolveLocalThreadId: (String) -> String?,
    rehydrateThread: suspend (localThreadId: String, canonicalThreadId: String) -> Boolean,
    containsTarget: (localThreadId: String, messageId: String, toolActionId: String?) -> Boolean,
    onOpenChat: (NotificationEventTargetResolution.Chat) -> Unit,
    onOpenConfirmation: (String) -> Unit,
    onFallback: () -> Unit,
    modifier: Modifier = Modifier,
) {
    LaunchedEffect(eventId, repository) {
        when (val target = resolveNotificationEventTarget(
            eventId = eventId,
            repository = repository,
            resolveLocalThreadId = resolveLocalThreadId,
            rehydrateThread = rehydrateThread,
            containsTarget = containsTarget,
        )) {
            is NotificationEventTargetResolution.Chat -> onOpenChat(target)
            is NotificationEventTargetResolution.Confirmation -> onOpenConfirmation(target.eventId)
            NotificationEventTargetResolution.Fallback -> onFallback()
        }
    }
    Box(modifier.fillMaxSize())
}

internal fun ServerEvent.hasExactNavigationIdentity(): Boolean = when (kind) {
    ServerEventKind.ReplyCompleted -> threadId != null && messageId != null
    ServerEventKind.ConfirmationRequired ->
        relationStatus == "linked" && threadId != null && messageId != null && toolActionId != null
    ServerEventKind.Unknown -> false
}

@Composable
fun ChatConfirmationCard(
    event: ServerEvent,
    repository: ServerEventRepository,
    modifier: Modifier = Modifier,
    navigationFocused: Boolean = false,
) {
    var current by remember(event.id) { mutableStateOf(event) }
    var feedback by remember(event.id) { mutableStateOf<String?>(null) }
    var deciding by remember(event.id) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    EventGlassPanel(
        modifier,
        border = if (navigationFocused) BorderStroke(1.dp, NavigationTerracotta.copy(alpha = .55f)) else null,
    ) {
        if (navigationFocused) NavigationFocusTag(current)
        Text(current.safeSummary, color = LoveHouseGlass.Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
        current.expiresAt?.let { Text("有效期至 $it", color = LoveHouseGlass.MutedInk, fontSize = 9.sp) }
        if (current.canDecide) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                OutlinedButton(
                    onClick = {
                        deciding = true
                        scope.launch {
                            feedback = applyChatConfirmationDecision(repository, current.id, ServerEventDecision.Denied) {
                                current = it
                            }
                            deciding = false
                        }
                    },
                    enabled = !deciding,
                    modifier = Modifier.weight(1f),
                ) { Text("拒绝") }
                Button(
                    onClick = {
                        deciding = true
                        scope.launch {
                            feedback = applyChatConfirmationDecision(repository, current.id, ServerEventDecision.Approved) {
                                current = it
                            }
                            deciding = false
                        }
                    },
                    enabled = !deciding,
                    modifier = Modifier.weight(1f),
                ) { Text("允许") }
            }
        } else {
            Text(current.statusLabel(), color = LoveHouseGlass.MutedInk, fontSize = 10.sp)
        }
        feedback?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 9.sp) }
        Text("服务器决定为最终依据；本卡片不直接执行工具。", color = LoveHouseGlass.MutedInk, fontSize = 9.sp)
    }
}

private val NavigationTerracotta = Color(0xFFC96442)
private val NavigationDecidedGreen = Color(0xFF2F6A55)

/** Small "where this came from" line shown only while the card is a navigation focus hint. */
@Composable
private fun NavigationFocusTag(event: ServerEvent) {
    val dot = when (event.status) {
        "approved" -> NavigationDecidedGreen
        "denied", "expired" -> NavigationTerracotta
        else -> NavigationTerracotta.copy(alpha = .55f)
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(6.dp).background(dot, CircleShape))
        Text(
            "定位自通知 · ${event.statusLabel()}",
            color = NavigationTerracotta,
            fontSize = 9.sp,
            fontWeight = FontWeight.Medium,
            letterSpacing = .4.sp,
        )
    }
}

internal suspend fun applyChatConfirmationDecision(
    repository: ServerEventRepository,
    eventId: String,
    decision: ServerEventDecision,
    update: (ServerEvent) -> Unit = {},
): String = when (val result = repository.decide(eventId, decision)) {
    is ServerEventDecisionResult.Recorded -> {
        update(result.event)
        if (result.event.status == "approved") "已允许，决定已记录" else "已拒绝，决定已记录"
    }
    is ServerEventDecisionResult.ServerState -> {
        update(result.event)
        when (result.event.status) {
            "expired" -> "该确认已过期"
            "approved" -> "服务器已记录为允许"
            "denied" -> "服务器已记录为拒绝"
            else -> "服务器状态已刷新"
        }
    }
    ServerEventDecisionResult.AuthenticationRequired -> "请先登录 LoveHouse App Account"
    ServerEventDecisionResult.NotFound -> "该确认已不可用"
    is ServerEventDecisionResult.Error -> result.message
}

@Composable
private fun EventGlassPanel(
    modifier: Modifier = Modifier,
    border: BorderStroke? = null,
    content: @Composable () -> Unit,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(17.dp),
        color = LoveHouseGlass.Background,
        border = border ?: BorderStroke(1.dp, LoveHouseGlass.Border),
    ) {
        Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) { content() }
    }
}

internal fun ServerEvent.kindLabel(): String = when (kind) {
    ServerEventKind.ReplyCompleted -> "回复已完成"
    ServerEventKind.ConfirmationRequired -> "等待确认"
    ServerEventKind.Unknown -> "动态"
}

internal fun ServerEvent.statusLabel(): String = when (status) {
    "pending" -> "待决定"
    "active" -> "进行中"
    "approved" -> "已允许"
    "denied" -> "已拒绝"
    "expired" -> "已过期"
    "consumed" -> "已处理"
    else -> status
}
