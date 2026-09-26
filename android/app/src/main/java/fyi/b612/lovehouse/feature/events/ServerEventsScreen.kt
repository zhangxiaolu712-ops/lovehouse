package fyi.b612.lovehouse.feature.events

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fyi.b612.lovehouse.core.designsystem.LoveHouseGlass
import fyi.b612.lovehouse.core.designsystem.LoveHouseIcon
import fyi.b612.lovehouse.core.designsystem.LoveHouseIconView
import kotlinx.coroutines.launch

@Composable
fun ServerEventsScreen(
    repository: ServerEventRepository,
    onOpenEvent: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by repository.feed.collectAsState()
    val scope = rememberCoroutineScope()
    LaunchedEffect(repository) { repository.refresh() }
    BackHandler(onBack = onBack)
    Column(modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        EventTopBar("动态", onBack, onRefresh = { scope.launch { repository.refresh() } })
        when (val current = state) {
            ServerEventFeedState.Initial, ServerEventFeedState.Loading -> EventCenteredMessage("正在读取服务器动态…")
            ServerEventFeedState.AuthenticationRequired -> EventCenteredMessage("请先在设置中登录 LoveHouse App Account")
            is ServerEventFeedState.Error -> EventCenteredMessage(current.message, "重试") { scope.launch { repository.refresh() } }
            is ServerEventFeedState.Ready -> if (current.events.isEmpty()) {
                EventCenteredMessage("当前没有待处理或进行中的动态")
            } else {
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(14.dp, 8.dp, 14.dp, 28.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    items(current.events, key = ServerEvent::id) { event ->
                        ServerEventCard(event, onClick = { onOpenEvent(event.id) })
                    }
                }
            }
        }
    }
}

@Composable
fun ServerEventDetailScreen(
    eventId: String,
    repository: ServerEventRepository,
    onOpenThread: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var state by remember(eventId) { mutableStateOf<ServerEventLoadResult?>(null) }
    var feedback by remember(eventId) { mutableStateOf<String?>(null) }
    var deciding by remember(eventId) { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    suspend fun reload() { state = repository.get(eventId) }
    LaunchedEffect(eventId) { reload() }
    BackHandler(onBack = onBack)
    Column(modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding()) {
        EventTopBar("动态详情", onBack, onRefresh = { scope.launch { reload() } })
        when (val current = state) {
            null -> EventCenteredMessage("正在读取服务器状态…")
            ServerEventLoadResult.AuthenticationRequired -> EventCenteredMessage("请先在设置中登录 LoveHouse App Account")
            ServerEventLoadResult.NotFound -> EventCenteredMessage("这条动态不存在或已不可访问")
            is ServerEventLoadResult.Error -> EventCenteredMessage(current.message, "重试") { scope.launch { reload() } }
            is ServerEventLoadResult.Found -> {
                val event = current.event
                LazyColumn(
                    Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(14.dp, 8.dp, 14.dp, 28.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    item { ServerEventCard(event) }
                    if (event.kind == ServerEventKind.ReplyCompleted && event.threadId != null) {
                        item {
                            Button(onClick = { onOpenThread(event.threadId) }, modifier = Modifier.fillMaxWidth()) {
                                Text("进入对应聊天")
                            }
                        }
                    }
                    if (event.kind == ServerEventKind.ConfirmationRequired) {
                        item {
                            EventDecisionPanel(
                                event = event,
                                enabled = event.canDecide && !deciding,
                                feedback = feedback,
                                onDecision = { decision ->
                                    deciding = true
                                    feedback = null
                                    scope.launch {
                                        when (val result = repository.decide(event.id, decision)) {
                                            is ServerEventDecisionResult.Recorded -> {
                                                state = ServerEventLoadResult.Found(result.event)
                                                feedback = if (result.event.status == "approved") "已允许，决定已记录" else "已拒绝，决定已记录"
                                            }
                                            is ServerEventDecisionResult.ServerState -> {
                                                state = ServerEventLoadResult.Found(result.event)
                                                feedback = when (result.event.status) {
                                                    "expired" -> "该确认已过期，已刷新服务器状态"
                                                    "approved" -> "服务器已记录为允许"
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
                                },
                            )
                        }
                    }
                    if (event.kind == ServerEventKind.Unknown) {
                        item { EventInfoPanel("这是一种较新的动态类型；当前版本仅安全显示服务器摘要。") }
                    }
                }
            }
        }
    }
}

@Composable
private fun EventDecisionPanel(
    event: ServerEvent,
    enabled: Boolean,
    feedback: String?,
    onDecision: (ServerEventDecision) -> Unit,
) {
    EventGlassPanel {
        Text("需要你的决定", color = LoveHouseGlass.Ink, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        event.expiresAt?.let { Text("有效期至 $it", color = LoveHouseGlass.MutedInk, fontSize = 9.sp) }
        if (event.canDecide) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Button(
                    onClick = { onDecision(ServerEventDecision.Approved) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                ) { Text("允许") }
                OutlinedButton(
                    onClick = { onDecision(ServerEventDecision.Denied) },
                    enabled = enabled,
                    modifier = Modifier.weight(1f),
                ) { Text("拒绝") }
            }
            Text("你的决定将记录到服务器；这不代表原操作已经执行。", color = LoveHouseGlass.MutedInk, fontSize = 9.sp)
        } else {
            Text(event.statusLabel(), color = LoveHouseGlass.MutedInk, fontSize = 11.sp)
            Text("服务器状态为最终依据；不会在本机重新批准或覆盖。", color = LoveHouseGlass.MutedInk, fontSize = 9.sp)
        }
        feedback?.let { Text(it, color = MaterialTheme.colorScheme.primary, fontSize = 10.sp) }
    }
}

@Composable
private fun ServerEventCard(event: ServerEvent, onClick: (() -> Unit)? = null) {
    EventGlassPanel(modifier = if (onClick == null) Modifier else Modifier.clickable(onClick = onClick)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            LoveHouseIconView(
                if (event.kind == ServerEventKind.ConfirmationRequired) LoveHouseIcon.Settings else LoveHouseIcon.Chat,
                null,
                Modifier.size(18.dp),
                LoveHouseGlass.MutedInk,
            )
            Column(Modifier.weight(1f).padding(start = 9.dp)) {
                Text(event.kindLabel(), color = LoveHouseGlass.Ink, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(event.safeSummary, color = LoveHouseGlass.Ink, fontSize = 11.sp, maxLines = 4, overflow = TextOverflow.Ellipsis)
            }
            Text(event.statusLabel(), color = MaterialTheme.colorScheme.primary, fontSize = 9.sp)
        }
        Text("${event.source} · ${event.createdAt}", color = LoveHouseGlass.MutedInk, fontSize = 8.sp)
    }
}

@Composable
private fun EventTopBar(title: String, onBack: () -> Unit, onRefresh: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        EventIconButton(LoveHouseIcon.Back, "返回", onBack)
        Text(title, Modifier.weight(1f), color = LoveHouseGlass.Ink, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        EventIconButton(LoveHouseIcon.Regenerate, "刷新", onRefresh)
    }
}

@Composable
private fun EventIconButton(icon: LoveHouseIcon, label: String, onClick: () -> Unit) {
    Box(Modifier.size(40.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        LoveHouseIconView(icon, label, Modifier.size(18.dp), LoveHouseGlass.Ink)
    }
}

@Composable
private fun EventCenteredMessage(message: String, action: String? = null, onAction: (() -> Unit)? = null) {
    Box(Modifier.fillMaxSize().padding(24.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(message, color = LoveHouseGlass.MutedInk, fontSize = 11.sp)
            if (action != null && onAction != null) OutlinedButton(onClick = onAction) { Text(action) }
        }
    }
}

@Composable
private fun EventInfoPanel(message: String) {
    EventGlassPanel { Text(message, color = LoveHouseGlass.MutedInk, fontSize = 10.sp) }
}

@Composable
private fun EventGlassPanel(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(17.dp),
        color = LoveHouseGlass.Background,
        border = BorderStroke(1.dp, LoveHouseGlass.Border),
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
