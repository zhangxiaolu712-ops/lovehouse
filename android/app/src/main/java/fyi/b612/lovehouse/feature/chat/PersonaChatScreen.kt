package fyi.b612.lovehouse.feature.chat

import android.app.Activity
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import fyi.b612.lovehouse.R
import fyi.b612.lovehouse.core.designsystem.LoveHouseGlass
import fyi.b612.lovehouse.core.designsystem.LoveHouseIcon
import fyi.b612.lovehouse.core.designsystem.LoveHouseIconGallery
import fyi.b612.lovehouse.core.designsystem.LoveHouseIconOpticalSize
import fyi.b612.lovehouse.core.designsystem.LoveHouseIconView
import kotlinx.coroutines.launch
import kotlin.math.absoluteValue

private val PersonaInk = Color(0xFF3E4847)
private val PersonaMuted = Color(0xFF7F8B88)
private val PersonaAccent = Color(0xFF718E87)

private enum class ChatBackdrop(
    val title: String,
    val topTint: Color,
    val bottomTint: Color,
) {
    Green("庭院绿", Color(0xFFB8CBC0), Color(0xFFADC5B5)),
    Rose("雾粉", Color(0xFFE3BEC9), Color(0xFFD9AFBD)),
    Lavender("雾蓝紫", Color(0xFFB9C5DF), Color(0xFFAEB9D7)),
}

private enum class PersonaPanel { Detail, Search, DateJump, Bookshelf, Appearance, Status, IconGallery, MemberPicker, ForwardTarget, WorkflowForward, ForwardBundle, AvatarPicker }
private enum class BubbleStyle(val title: String, val subtitle: String) {
    None("无气泡", "文字直接浮在壁纸上"),
    Soft("轻气泡", "参考图式柔软浅气泡"),
    Glass("玻璃", "双方都使用雾面玻璃"),
    Paper("纸片", "偏纸张/便签质感"),
}

@Composable
fun ChatShellScreen(threadId: String, store: ChatSessionStore, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val thread = store.thread(threadId) ?: return
    val messages = store.messages(threadId)
    var panel by remember { mutableStateOf<PersonaPanel?>(null) }
    var bubbleStyle by remember { mutableStateOf(BubbleStyle.Soft) }
    var backdrop by remember(threadId) { mutableStateOf(ChatBackdrop.entries.firstOrNull { it.name.lowercase() == store.background(threadId) } ?: ChatBackdrop.Green) }
    var selectedModel by remember { mutableStateOf(if (threadId == "agent-codex") "Codex · 现有 Runtime" else "GPT-5.6 Sol") }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var selectedMessages by remember { mutableStateOf<Set<String>>(emptySet()) }
    var forwardingIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var openedBundle by remember { mutableStateOf<ChatMessageUi?>(null) }
    var actionNotice by remember { mutableStateOf<String?>(null) }
    var openWorkflowTaskId by remember { mutableStateOf<String?>(null) }
    var forwardingTaskId by remember { mutableStateOf<String?>(null) }
    val listState = rememberLazyListState()
    val chatScope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    ChatNavigationBarTint(backdrop)

    BackHandler(panel != null || selectedMessages.isNotEmpty()) {
        panel = when (panel) {
            PersonaPanel.MemberPicker, PersonaPanel.AvatarPicker, PersonaPanel.Search,
            PersonaPanel.DateJump, PersonaPanel.Bookshelf, PersonaPanel.Appearance,
            PersonaPanel.Status, PersonaPanel.IconGallery -> PersonaPanel.Detail
            else -> null
        }
        if (panel == null && selectedMessages.isNotEmpty()) selectedMessages = emptySet()
    }
    Box(modifier.fillMaxSize()) {
        ChatBackdropLayer(backdrop)
        ChatAtmosphere(backdrop)
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            PersonaTopBar(thread, onBack = onBack, onMore = {
                if (thread.kind == ChatThreadKind.TemporaryTask) openWorkflowTaskId = thread.taskId ?: "mock-running-001" else panel = PersonaPanel.Detail
            })
            LazyColumn(
                modifier = Modifier.weight(1f).fillMaxWidth(),
                state = listState,
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(11.dp),
            ) {
                item { DateDivider("2026.08.18") }
                items(messages, key = { it.messageId }) { message ->
                    MessageBubble(
                        message = message,
                        task = message.taskId?.let(store::task),
                        style = bubbleStyle,
                        selected = message.messageId in selectedMessages,
                        selectionMode = selectedMessages.isNotEmpty(),
                        onToggleSelection = { selectedMessages = if (message.messageId in selectedMessages) selectedMessages - message.messageId else selectedMessages + message.messageId },
                        onForward = { forwardingIds = setOf(message.messageId); panel = PersonaPanel.ForwardTarget },
                        onOpenBundle = { openedBundle = message; panel = PersonaPanel.ForwardBundle },
                        onOpenWorkflow = { message.taskId?.let { openWorkflowTaskId = it } },
                        onAction = { action ->
                            actionNotice = when (action) {
                                "复制" -> { clipboard.setText(AnnotatedString(message.body)); "已复制" }
                                "重试" -> "已加入本地重试队列"
                                "朗读" -> "朗读状态已切换"
                                "翻译" -> "已生成本地 Mock 译文"
                                else -> "已打开消息操作"
                            }
                        },
                    )
                }
            }
            actionNotice?.let { Text(it, Modifier.align(Alignment.CenterHorizontally).padding(vertical = 2.dp), color = PersonaMuted, fontSize = 8.sp) }
            if (selectedMessages.isNotEmpty()) MultiSelectBar(selectedMessages.size, onCancel = { selectedMessages = emptySet() }) {
                forwardingIds = selectedMessages; panel = PersonaPanel.ForwardTarget
            } else PersonaComposer(
                value = input, model = selectedModel, onValueChange = { input = it },
                onModelSelected = { if (threadId != "agent-codex") selectedModel = it },
                modelSelectionEnabled = threadId != "agent-codex",
                onSend = {
                    if (sending || input.isBlank()) return@PersonaComposer
                    if (threadId != "agent-codex") {
                        store.sendMessage(threadId, input)
                        input = ""
                    } else {
                        val pending = input
                        sending = true
                        actionNotice = "正在连接 Codex…"
                        chatScope.launch {
                            val result = store.sendCodexMessage(threadId, pending) { }
                            sending = false
                            result.onSuccess { reply ->
                                input = ""
                                actionNotice = "Codex · ${reply.evidence.adapterId} · 同一 Thread"
                            }.onFailure { error ->
                                actionNotice = error.message ?: "发送失败"
                            }
                        }
                    }
                }, onToolAction = { actionNotice = it },
            )
        }
        if (panel != null) {
            when (panel) {
                PersonaPanel.ForwardTarget -> ForwardTargetSheet(store, threadId, forwardingIds.size > 1, backdrop, onClose = { panel = null }, onConfirm = { target -> store.forward(threadId, forwardingIds, target, forwardingIds.size > 1); selectedMessages = emptySet(); panel = null; actionNotice = "已转发到 ${store.thread(target)?.title}" })
                PersonaPanel.WorkflowForward -> ForwardTargetSheet(store, threadId, false, backdrop, title = "转发 Workflow", onClose = { panel = null; forwardingTaskId = null }, onConfirm = { target -> forwardingTaskId?.let { store.forwardWorkflow(it, target) }; panel = null; forwardingTaskId = null; actionNotice = "Workflow 已转发到 ${store.thread(target)?.title}" })
                PersonaPanel.MemberPicker -> MemberPickerSheet(store, threadId, backdrop, onClose = { panel = PersonaPanel.Detail }) { store.addMember(threadId, it); panel = PersonaPanel.Detail }
                PersonaPanel.ForwardBundle -> ForwardBundleSheet(openedBundle, backdrop, onClose = { panel = null })
                PersonaPanel.AvatarPicker -> AvatarPickerSheet(backdrop, onClose = { panel = PersonaPanel.Detail }) { store.updateAvatar(threadId, it); panel = PersonaPanel.Detail }
                else -> PersonaSheet(panel!!, thread, store, selectedModel, bubbleStyle, backdrop, onClose = { panel = if (panel == PersonaPanel.Detail) null else PersonaPanel.Detail }, onNavigate = { panel = it }, onModel = { selectedModel = it }, onBubble = { bubbleStyle = it; panel = PersonaPanel.Detail }, onBackdrop = { backdrop = it; store.setBackground(threadId, it.name.lowercase()); panel = PersonaPanel.Detail })
            }
        }
        openWorkflowTaskId?.let { taskId ->
            store.task(taskId)?.let { task ->
                TaskWorkflowOverlay(
                    task = task,
                    backgroundKey = backdrop.name.lowercase(),
                    onClose = { openWorkflowTaskId = null },
                    onForward = { forwardingTaskId = taskId; openWorkflowTaskId = null; panel = PersonaPanel.WorkflowForward },
                    onAdvance = { store.advanceTask(taskId) },
                    onDecision = { eventId, approved -> store.decideApproval(taskId, eventId, approved) },
                    onJump = { eventId ->
                        val messageIndex = messages.indexOfFirst { it.workflowEventId == eventId }
                        openWorkflowTaskId = null
                        if (messageIndex >= 0) chatScope.launch { listState.animateScrollToItem(messageIndex + 1) }
                        else actionNotice = "当前窗口正文中没有这个节点的日志"
                    },
                    onWindowAction = { action ->
                        when (action) {
                            "保留窗口" -> store.retain(threadId)
                            "转为长期" -> store.convertToLongTerm(threadId)
                            "归档" -> store.archive(threadId)
                        }
                        actionNotice = "已执行：$action"
                    },
                )
            }
        }
    }
}

@Composable
private fun ChatNavigationBarTint(backdrop: ChatBackdrop) {
    val window = (LocalView.current.context as? Activity)?.window ?: return
    DisposableEffect(window, backdrop) {
        val previousColor = window.navigationBarColor
        val previousContrast = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced else true
        window.navigationBarColor = backdrop.bottomTint.toArgb()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
        onDispose {
            window.navigationBarColor = previousColor
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = previousContrast
        }
    }
}

@Composable
private fun ChatBackdropLayer(backdrop: ChatBackdrop) {
    when (backdrop) {
        ChatBackdrop.Green -> Image(
            painterResource(R.drawable.wallpaper_default_green),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
        ChatBackdrop.Rose -> Box(
            Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFFF2D9DF), Color(0xFFD9B8C4), Color(0xFFF4E9E4)))),
        )
        ChatBackdrop.Lavender -> Image(
            painterResource(R.drawable.wallpaper_chat_lavender),
            contentDescription = null,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
        )
    }
}

@Composable
private fun ChatAtmosphere(backdrop: ChatBackdrop) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxWidth().fillMaxHeight(.22f).align(Alignment.TopCenter).background(
                Brush.verticalGradient(
                    0f to backdrop.topTint.copy(alpha = .92f),
                    .42f to backdrop.topTint.copy(alpha = .58f),
                    1f to Color.Transparent,
                ),
            ),
        )
        Box(
            Modifier.fillMaxWidth().fillMaxHeight(.28f).align(Alignment.BottomCenter).background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    .62f to backdrop.bottomTint.copy(alpha = .56f),
                    1f to backdrop.bottomTint.copy(alpha = .94f),
                ),
            ),
        )
    }
}

@Composable
private fun ChatOverlayFrame(backdrop: ChatBackdrop, onClose: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        ChatBackdropLayer(backdrop)
        ChatAtmosphere(backdrop)
        Box(Modifier.fillMaxSize().background(Color(0x2426322F)).clickable(onClick = onClose))
        Surface(
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 22.dp),
            shape = RoundedCornerShape(26.dp),
            color = Color(0xDDF5F3EB),
            border = BorderStroke(1.dp, Color.White.copy(alpha = .82f)),
        ) { content() }
    }
}

@Composable
private fun ForwardTargetSheet(
    store: ChatSessionStore,
    currentThreadId: String,
    merged: Boolean,
    backdrop: ChatBackdrop,
    title: String = if (merged) "合并转发" else "转发消息",
    onClose: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var selected by remember { mutableStateOf<String?>(null) }
    ChatOverlayFrame(backdrop, onClose) {
        Column {
            SheetHeader(title, "选择目标聊天，确认后才会写入。", onClose)
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(bottom = 8.dp)) {
                items(store.threads.filter { it.threadId != currentThreadId && it.kind != ChatThreadKind.Archive }, key = { it.threadId }) { target ->
                    Row(
                        Modifier.fillMaxWidth().clickable { selected = target.threadId }.padding(horizontal = 18.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(Modifier.size(36.dp).background(Color(0xFFDAE8E2), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                            Text(target.avatarGlyph ?: target.title.take(1), color = PersonaAccent, fontWeight = FontWeight.Bold)
                        }
                        Column(Modifier.weight(1f).padding(start = 10.dp)) {
                            Text(target.title, color = PersonaInk, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            Text(target.speakerLabel ?: target.kind.name, color = PersonaMuted, fontSize = 8.sp)
                        }
                        Text(if (selected == target.threadId) "✓" else "○", color = if (selected == target.threadId) PersonaAccent else PersonaMuted)
                    }
                }
            }
            Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                Text("取消", Modifier.clickable(onClick = onClose).padding(10.dp), color = PersonaMuted, fontSize = 11.sp)
                Text("确认转发", Modifier.clip(RoundedCornerShape(12.dp)).background(if (selected == null) Color.White.copy(alpha = .35f) else PersonaAccent.copy(alpha = .18f)).clickable(enabled = selected != null) { selected?.let(onConfirm) }.padding(horizontal = 16.dp, vertical = 10.dp), color = if (selected == null) PersonaMuted else PersonaInk, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
private fun MemberPickerSheet(store: ChatSessionStore, threadId: String, backdrop: ChatBackdrop, onClose: () -> Unit, onConfirm: (ChatPersona) -> Unit) {
    val existing = store.members(threadId).map { it.memberId }.toSet()
    var selected by remember { mutableStateOf<ChatPersona?>(null) }
    ChatOverlayFrame(backdrop, onClose) {
        Column {
            SheetHeader("添加成员", "选择 Persona / 成员；确认后立即加入小客厅。", onClose)
            LazyColumn(Modifier.weight(1f)) {
                items(store.personas.filter { it.personaId !in existing }, key = { it.personaId }) { persona ->
                    Row(Modifier.fillMaxWidth().clickable { selected = persona }.padding(horizontal = 18.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(36.dp).background(Color(0xFFDAE8E2), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) { Text(persona.avatar, color = PersonaAccent, fontWeight = FontWeight.Bold) }
                        Column(Modifier.weight(1f).padding(start = 10.dp)) { Text(persona.name, color = PersonaInk, fontSize = 11.sp); Text(persona.memoryLabel, color = PersonaMuted, fontSize = 8.sp) }
                        Text(if (selected == persona) "✓" else "○", color = if (selected == persona) PersonaAccent else PersonaMuted)
                    }
                }
            }
            Text("确认加入", Modifier.align(Alignment.End).padding(18.dp).clip(RoundedCornerShape(12.dp)).background(PersonaAccent.copy(alpha = if (selected == null) .06f else .18f)).clickable(enabled = selected != null) { selected?.let(onConfirm) }.padding(horizontal = 18.dp, vertical = 10.dp), color = if (selected == null) PersonaMuted else PersonaInk, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun ForwardBundleSheet(message: ChatMessageUi?, backdrop: ChatBackdrop, onClose: () -> Unit) {
    ChatOverlayFrame(backdrop, onClose) {
        Column {
            SheetHeader("聊天记录", "${message?.forwarded?.size ?: 0} 条合并消息", onClose)
            LazyColumn(Modifier.weight(1f), contentPadding = PaddingValues(horizontal = 18.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(message?.forwarded.orEmpty()) { forwarded ->
                    Column(Modifier.fillMaxWidth()) {
                        Text("${forwarded.author}  ${forwarded.time}", color = PersonaMuted, fontSize = 8.sp)
                        Text(forwarded.body, Modifier.padding(top = 3.dp), color = PersonaInk, fontSize = 11.sp, lineHeight = 16.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun AvatarPickerSheet(backdrop: ChatBackdrop, onClose: () -> Unit, onConfirm: (String) -> Unit) {
    var selected by remember { mutableStateOf("G") }
    ChatOverlayFrame(backdrop, onClose) {
        Column {
            SheetHeader("更换头像", "仅作用于当前 Persona 窗口。", onClose)
            Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf("G", "花", "月", "星", "猫").forEach { glyph ->
                    Box(Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background(if (selected == glyph) PersonaAccent.copy(alpha = .25f) else Color.White.copy(alpha = .45f)).clickable { selected = glyph }, contentAlignment = Alignment.Center) { Text(glyph, color = PersonaInk, fontWeight = FontWeight.Bold) }
                }
            }
            Text("确认", Modifier.align(Alignment.End).padding(18.dp).clip(RoundedCornerShape(12.dp)).background(PersonaAccent.copy(alpha = .18f)).clickable { onConfirm(selected) }.padding(horizontal = 18.dp, vertical = 10.dp), color = PersonaInk, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun PersonaTopBar(thread: ChatThreadSummary, onBack: () -> Unit, onMore: () -> Unit) {
    Box(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 7.dp, vertical = 5.dp), verticalAlignment = Alignment.CenterVertically) {
            ChatIconButton(LoveHouseIcon.Back, "返回", onClick = onBack)
            Box(Modifier.padding(start = 7.dp).size(34.dp).background(Color(0xFFDAE8E2), RoundedCornerShape(12.dp)), contentAlignment = Alignment.Center) {
                Text(thread.avatarGlyph ?: thread.title.take(1), color = Color(0xFF5F7B73), fontWeight = FontWeight.Bold, fontSize = 15.sp)
            }
            Column(Modifier.weight(1f).padding(start = 10.dp)) {
                Text(thread.title, color = PersonaInk, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                Text(when (thread.kind) { ChatThreadKind.LivingRoom -> "小客厅 · ${thread.speakerLabel.orEmpty()}"; ChatThreadKind.TemporaryTask -> "临时任务 · ${thread.expiresAtLabel.orEmpty()}"; else -> "人格窗口 · 长期线程" }, color = PersonaMuted, fontSize = 9.sp)
            }
            ChatIconButton(LoveHouseIcon.Call, "实时语音通话") {}
            ChatIconButton(LoveHouseIcon.More, "会话详情", onClick = onMore)
        }
    }
}

@Composable private fun DateDivider(label: String) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.weight(1f).height(1.dp).background(Color.White.copy(alpha = .7f)))
        Text(label, Modifier.padding(horizontal = 10.dp), color = PersonaMuted, fontSize = 9.sp)
        Box(Modifier.weight(1f).height(1.dp).background(Color.White.copy(alpha = .7f)))
    }
}

@Composable private fun ThoughtRow(duration: String, summary: String) {
    var expanded by remember { mutableStateOf(false) }
    Column(Modifier.clickable { expanded = !expanded }.padding(top = 3.dp, bottom = 1.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("思考过程 $duration", color = PersonaMuted, fontSize = 9.sp, fontWeight = FontWeight.Medium)
            LoveHouseIconView(
                if (expanded) LoveHouseIcon.Collapse else LoveHouseIcon.Expand,
                null,
                Modifier.padding(start = 4.dp).size(11.dp),
                PersonaMuted,
                LoveHouseIconOpticalSize.Compact,
            )
        }
        if (expanded) {
            Text(summary, Modifier.widthIn(max = 250.dp).padding(top = 3.dp), color = PersonaMuted, fontSize = 8.5.sp, lineHeight = 13.sp)
        }
    }
}

@Composable private fun MessageBubble(
    message: ChatMessageUi,
    task: RemoteAgentTask?,
    style: BubbleStyle,
    selected: Boolean,
    selectionMode: Boolean,
    onToggleSelection: () -> Unit,
    onForward: () -> Unit,
    onOpenBundle: () -> Unit,
    onOpenWorkflow: () -> Unit,
    onAction: (String) -> Unit,
) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (message.mine) Arrangement.End else Arrangement.Start, verticalAlignment = Alignment.Top) {
        val color = when (style) {
            BubbleStyle.None -> Color.Transparent
            BubbleStyle.Soft -> if (message.mine) Color(0xFFDDEAE5).copy(alpha = .82f) else Color(0xFFF8F5EF).copy(alpha = .76f)
            BubbleStyle.Glass -> Color.White.copy(alpha = .34f)
            BubbleStyle.Paper -> Color(0xFFF6F0E2).copy(alpha = .92f)
        }
        if (!message.mine) MessageAvatar(message)
        Column(
            modifier = Modifier.widthIn(max = 300.dp).padding(horizontal = 7.dp),
            horizontalAlignment = if (message.mine) Alignment.End else Alignment.Start,
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (message.mine) Text(message.time, color = PersonaMuted, fontSize = 8.sp)
                Text(message.author, color = if (message.mine) PersonaMuted else PersonaAccent, fontSize = 9.sp, fontWeight = FontWeight.Medium)
                if (!message.mine) Text(message.time, color = PersonaMuted, fontSize = 8.sp)
            }
            if (!message.mine && message.thoughtDuration != null && message.thoughtSummary != null) {
                ThoughtRow(message.thoughtDuration, message.thoughtSummary)
            }
            Surface(
                modifier = Modifier.padding(top = 3.dp).combinedClickable(
                    onClick = { if (selectionMode) onToggleSelection() else when (message.kind) { ChatMessageKind.Task, ChatMessageKind.Workflow -> onOpenWorkflow(); ChatMessageKind.ForwardBundle -> onOpenBundle(); else -> Unit } },
                    onLongClick = onToggleSelection,
                ),
                shape = RoundedCornerShape(16.dp), color = color,
                border = when { selected -> BorderStroke(1.5.dp, PersonaAccent); style == BubbleStyle.Glass -> BorderStroke(1.dp, Color.White.copy(alpha = .72f)); else -> null },
            ) {
                when (message.kind) {
                    ChatMessageKind.Text -> Text(message.body, Modifier.padding(horizontal = 11.dp, vertical = 8.dp), color = PersonaInk, fontSize = 12.sp, lineHeight = 18.sp)
                    ChatMessageKind.Task -> Column(Modifier.padding(horizontal = 11.dp, vertical = 8.dp)) {
                        Text("远程任务", color = PersonaAccent, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                        Text(message.body, color = PersonaInk, fontSize = 11.sp, lineHeight = 16.sp)
                        Text("查看 Workflow ›", Modifier.padding(top = 4.dp), color = PersonaMuted, fontSize = 8.sp)
                    }
                    ChatMessageKind.Workflow -> Column(Modifier.padding(horizontal = 11.dp, vertical = 8.dp)) {
                        Text("WORKFLOW", color = PersonaAccent, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                        Text(task?.title ?: message.body, color = PersonaInk, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                        val done = task?.workflow?.count { it.status == WorkflowEventStatus.Completed } ?: 0
                        Text("${task?.status?.label ?: "任务"} · $done/${task?.workflow?.size ?: 0} · 当前：${task?.latestMilestone ?: "等待同步"}", Modifier.padding(top = 3.dp), color = PersonaMuted, fontSize = 8.sp)
                        Text("点击查看任务地图 ›", Modifier.padding(top = 4.dp), color = PersonaAccent, fontSize = 8.sp)
                    }
                    ChatMessageKind.ForwardBundle -> Column(Modifier.padding(horizontal = 11.dp, vertical = 8.dp)) {
                        Text(message.body, color = PersonaInk, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        message.forwarded.take(2).forEach { Text("${it.author}：${it.body}", Modifier.padding(top = 3.dp), color = PersonaMuted, fontSize = 8.sp, maxLines = 1) }
                        Text("点击查看聊天记录", Modifier.padding(top = 4.dp), color = PersonaAccent, fontSize = 8.sp)
                    }
                }
            }
            MessageQuickActions(message.mine, onForward, onAction)
        }
        if (message.mine) MessageAvatar(message)
    }
}

@Composable private fun MessageAvatar(message: ChatMessageUi) {
    Box(Modifier.size(30.dp).background(if (message.mine) Color(0xFFE8DDD4) else Color(0xFFD8E7E1), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
        Text(message.avatar, color = PersonaAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable private fun MessageQuickActions(mine: Boolean, onForward: () -> Unit, onAction: (String) -> Unit) {
    Row(Modifier.padding(top = 1.dp), horizontalArrangement = Arrangement.spacedBy(1.dp)) {
        val actions = if (mine) {
            listOf(LoveHouseIcon.Copy to "复制", LoveHouseIcon.ReadAloud to "朗读", LoveHouseIcon.Forward to "转发", LoveHouseIcon.More to "更多")
        } else {
            listOf(LoveHouseIcon.Copy to "复制", LoveHouseIcon.Retry to "重试", LoveHouseIcon.ReadAloud to "朗读", LoveHouseIcon.Translate to "翻译", LoveHouseIcon.Forward to "转发")
        }
        actions.forEach { (icon, description) ->
            ChatIconButton(icon, description, iconSize = 18.dp, touchSize = 40.dp, opticalSize = LoveHouseIconOpticalSize.Compact) { if (description == "转发") onForward() else onAction(description) }
        }
    }
}

@Composable
private fun PersonaComposer(
    value: String, model: String, onValueChange: (String) -> Unit, onModelSelected: (String) -> Unit,
    onSend: () -> Unit, onToolAction: (String) -> Unit, modelSelectionEnabled: Boolean = true,
) {
    var showAttachments by remember { mutableStateOf(false) }
    var showModels by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 5.dp),
    ) {
        Column {
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(17.dp),
                color = Color.White.copy(alpha = .30f),
                border = BorderStroke(.6.dp, Color.White.copy(alpha = .46f)),
            ) {
                BasicTextField(
                    value = value, onValueChange = onValueChange,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 38.dp, max = 88.dp).padding(horizontal = 11.dp, vertical = 8.dp),
                    textStyle = androidx.compose.ui.text.TextStyle(color = PersonaInk, fontSize = 12.sp),
                    decorationBox = { inner -> Box { if (value.isEmpty()) Text("ring the chime...", color = PersonaMuted, fontSize = 12.sp); inner() } },
                )
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                Box {
                    ChatIconButton(LoveHouseIcon.Plus, "添加附件") { showAttachments = true }
                    DropdownMenu(expanded = showAttachments, onDismissRequest = { showAttachments = false }, modifier = Modifier.widthIn(min = 112.dp, max = 132.dp)) {
                        listOf(
                            LoveHouseIcon.Camera to "相机", LoveHouseIcon.Photo to "照片", LoveHouseIcon.File to "文件",
                            LoveHouseIcon.Location to "定位", LoveHouseIcon.More to "其他",
                        ).forEach { (icon, item) ->
                            DropdownMenuItem(
                                leadingIcon = { LoveHouseIconView(icon, null, Modifier.size(17.dp), PersonaMuted) },
                                text = { Text(item, color = PersonaInk, fontSize = 10.sp) },
                                onClick = { showAttachments = false; onToolAction("已选择$item（本地 Mock）") },
                                contentPadding = PaddingValues(horizontal = 10.dp),
                            )
                        }
                    }
                }
                ChatIconButton(LoveHouseIcon.Emoji, "表情") { onToolAction("表情面板已切换") }
                ChatIconButton(LoveHouseIcon.VoiceMessage, "录制语音消息") { onToolAction("语音消息录制状态已切换") }
                Box {
                    Row(
                        Modifier.clip(CircleShape).background(Color.White.copy(alpha = .24f)).clickable(enabled = modelSelectionEnabled) { showModels = true }.padding(horizontal = 10.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(model, color = PersonaInk, fontSize = 8.5.sp)
                        LoveHouseIconView(LoveHouseIcon.ModelSwitch, null, Modifier.padding(start = 4.dp).size(12.dp), PersonaMuted, LoveHouseIconOpticalSize.Compact)
                    }
                    DropdownMenu(expanded = showModels && modelSelectionEnabled, onDismissRequest = { showModels = false }, modifier = Modifier.widthIn(min = 140.dp, max = 180.dp)) {
                        listOf("GPT-5.6 Sol", "Claude", "Gemini").forEach { option ->
                            DropdownMenuItem(text = { Text(if (option == model) "✓  $option" else option, color = PersonaInk, fontSize = 10.sp) }, onClick = { onModelSelected(option); showModels = false })
                        }
                    }
                }
                Spacer(Modifier.weight(1f))
                PawSendButton(enabled = value.isNotBlank(), onClick = onSend)
            }
        }
    }
}

@Composable private fun MultiSelectBar(count: Int, onCancel: () -> Unit, onForward: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        ChatIconButton(LoveHouseIcon.Close, "取消多选", onClick = onCancel)
        Text("已选择 $count 条", Modifier.weight(1f).padding(start = 7.dp), color = PersonaInk, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Row(Modifier.clickable(onClick = onForward).padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            LoveHouseIconView(LoveHouseIcon.Forward, null, Modifier.size(17.dp), PersonaAccent)
            Text("合并转发", Modifier.padding(start = 5.dp), color = PersonaAccent, fontSize = 9.sp)
        }
    }
}

@Composable
private fun ChatIconButton(
    icon: LoveHouseIcon,
    contentDescription: String,
    iconSize: Dp = 17.dp,
    touchSize: Dp = 36.dp,
    opticalSize: LoveHouseIconOpticalSize = LoveHouseIconOpticalSize.Regular,
    onClick: () -> Unit,
) {
    Box(
        Modifier.size(touchSize).clip(CircleShape).clickable(onClick = onClick)
            .semantics { this.contentDescription = contentDescription },
        contentAlignment = Alignment.Center,
    ) {
        LoveHouseIconView(icon, null, Modifier.size(iconSize), PersonaInk.copy(alpha = .72f), opticalSize)
    }
}

@Composable
private fun PawSendButton(enabled: Boolean, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    Box(
        Modifier.size(40.dp).graphicsLayer {
            scaleX = if (pressed) .90f else 1f
            scaleY = if (pressed) .90f else 1f
        }.clip(CircleShape).clickable(enabled = enabled, interactionSource = interaction, indication = null, onClick = onClick)
            .semantics { contentDescription = "发送消息" },
        contentAlignment = Alignment.Center,
    ) {
        LoveHouseIconView(
            LoveHouseIcon.CatPawSend,
            null,
            Modifier.size(22.dp),
            if (enabled) PersonaAccent else PersonaMuted.copy(alpha = .26f),
        )
    }
}

@Composable
private fun PersonaSheet(panel: PersonaPanel, thread: ChatThreadSummary, store: ChatSessionStore, model: String, bubble: BubbleStyle, backdrop: ChatBackdrop, onClose: () -> Unit, onNavigate: (PersonaPanel) -> Unit, onModel: (String) -> Unit, onBubble: (BubbleStyle) -> Unit, onBackdrop: (ChatBackdrop) -> Unit) {
    Box(Modifier.fillMaxSize().background(Color(0x4B26322F)).clickable(onClick = onClose), contentAlignment = Alignment.BottomCenter) {
        val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        Box(
            modifier = Modifier.fillMaxSize().padding(top = 42.dp).clip(shape)
                .clickable(enabled = false) {}.border(1.dp, Color.White.copy(alpha = .85f), shape),
        ) {
            when (backdrop) {
                ChatBackdrop.Green -> Image(painterResource(R.drawable.wallpaper_default_green), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                ChatBackdrop.Rose -> Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFFF2D9DF), Color(0xFFD9B8C4), Color(0xFFF4E9E4)))))
                ChatBackdrop.Lavender -> Image(painterResource(R.drawable.wallpaper_chat_lavender), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            }
            Box(Modifier.fillMaxSize().background(Color(0xDDF5F3EB)))
            when (panel) {
                PersonaPanel.Detail -> when (thread.kind) {
                    ChatThreadKind.LivingRoom -> LivingRoomDetailPanel(thread, store, onClose, onNavigate)
                    ChatThreadKind.TemporaryTask -> Column { SheetHeader(thread.title, "Workflow 已作为临时任务主详情。", onClose) }
                    else -> DirectDetailPanel(thread, model, onClose, onNavigate, onModel)
                }
                PersonaPanel.Appearance -> AppearancePanel(bubble, backdrop, onClose, onBubble, onBackdrop)
                PersonaPanel.Search -> SearchPanel(onClose)
                PersonaPanel.DateJump -> DatePanel(onClose)
                PersonaPanel.Bookshelf -> BookshelfPanel(onClose)
                PersonaPanel.Status -> StatusPanel(onClose)
                PersonaPanel.IconGallery -> IconGalleryPanel(onClose)
                else -> Unit
            }
        }
    }
}

@Composable private fun SheetHeader(title: String, subtitle: String? = null, onClose: () -> Unit) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 15.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, color = PersonaInk, fontWeight = FontWeight.Bold, fontSize = 15.sp)
            subtitle?.let { Text(it, Modifier.padding(top = 3.dp), color = PersonaMuted, fontSize = 9.sp) }
        }
        ChatIconButton(LoveHouseIcon.Close, "关闭", touchSize = 34.dp, onClick = onClose)
    }
}

@Composable private fun DirectDetailPanel(thread: ChatThreadSummary, model: String, onClose: () -> Unit, onNavigate: (PersonaPanel) -> Unit, onModel: (String) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 15.dp, bottom = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(44.dp).background(Color(0xFFDAE8E2), RoundedCornerShape(15.dp)), contentAlignment = Alignment.Center) { Text(thread.avatarGlyph ?: thread.title.take(1), color = Color(0xFF5F7B73), fontWeight = FontWeight.Bold) }
                Column(Modifier.weight(1f).padding(start = 11.dp)) { Text(thread.title, color = PersonaInk, fontWeight = FontWeight.Bold, fontSize = 15.sp); Text("人格窗口 · 身份与模型分离", color = PersonaMuted, fontSize = 9.sp) }
                ChatIconButton(LoveHouseIcon.Close, "关闭", touchSize = 34.dp, onClick = onClose)
            }
            DetailRow("当前运行", "OpenAI · $model")
            DetailRow("Persona", thread.title)
            DetailRow("Memory", "独立专属 Memory")
            DetailRow("长期 Thread", thread.threadId)
            DetailRow("更换头像", "自定义  ›") { onNavigate(PersonaPanel.AvatarPicker) }
            DetailRow("查找聊天", "›") { onNavigate(PersonaPanel.Search) }
            DetailRow("按日期跳转", "›") { onNavigate(PersonaPanel.DateJump) }
            DetailRow("聊天书架", "›") { onNavigate(PersonaPanel.Bookshelf) }
            DetailRow("聊天外观", "气泡 · 壁纸 · 雾面  ›") { onNavigate(PersonaPanel.Appearance) }
            DetailRow("会话状态", "Usage · 状态 · 工作记忆  ›") { onNavigate(PersonaPanel.Status) }
            DetailRow("新增 / 管理模型", "当前 $model")
            Text("运行模型 · 只改变底层引擎，不改变人格、Thread、Memory 和书架", Modifier.padding(horizontal = 19.dp, vertical = 14.dp), color = PersonaMuted, fontSize = 9.sp, lineHeight = 14.sp)
            listOf("GPT-5.6 Sol" to "OpenAI", "Claude" to "Anthropic", "Gemini" to "Google", "自定义模型" to "自定义 API").forEach { option ->
                ModelRow(option.first, option.second, model == option.first) { onModel(option.first) }
            }
        }
    }
}

@Composable private fun LivingRoomDetailPanel(thread: ChatThreadSummary, store: ChatSessionStore, onClose: () -> Unit, onNavigate: (PersonaPanel) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            SheetHeader("小客厅", "成员是这个空间的核心；工单仅作为次级索引。", onClose)
            DetailRow("空间", thread.threadId)
            Text("成员", Modifier.padding(horizontal = 19.dp, vertical = 8.dp), color = PersonaInk, fontSize = 11.sp, fontWeight = FontWeight.Bold)
            store.members(thread.threadId).forEach { member ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 19.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(32.dp).background(Color(0xFFDAE8E2), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) { Text(member.avatar, color = PersonaAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                    Column(Modifier.weight(1f).padding(start = 10.dp)) { Text(member.name, color = PersonaInk, fontSize = 11.sp); Text(member.status, color = PersonaMuted, fontSize = 8.sp) }
                }
            }
            DetailRow("添加成员", "选择 Persona / 成员  ›") { onNavigate(PersonaPanel.MemberPicker) }
            DetailRow("已签收工单", "1 个 · 正文时间线查看")
            DetailRow("聊天背景", "独立窗口背景  ›") { onNavigate(PersonaPanel.Appearance) }
        }
    }
}

@Composable private fun DetailRow(title: String, value: String, onClick: (() -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().clickable(enabled = onClick != null) { onClick?.invoke() }.padding(horizontal = 19.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), color = PersonaInk, fontSize = 11.sp)
        Text(value, color = PersonaMuted, fontSize = 9.sp)
    }
}

@Composable private fun ModelRow(title: String, provider: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 19.dp, vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, color = PersonaInk, fontSize = 11.sp, fontWeight = FontWeight.Medium); Text(provider, color = PersonaMuted, fontSize = 8.sp) }
        Text(if (selected) "✓" else "○", color = if (selected) PersonaAccent else PersonaMuted, fontSize = 15.sp)
    }
}

@Composable private fun AppearancePanel(current: BubbleStyle, backdrop: ChatBackdrop, onClose: () -> Unit, onBubble: (BubbleStyle) -> Unit, onBackdrop: (ChatBackdrop) -> Unit) {
    Column(Modifier.navigationBarsPadding().padding(bottom = 18.dp)) {
        SheetHeader("G老师 · 聊天外观", onClose = onClose)
        Text("聊天背景只作用于当前窗口；上下氛围层会跟随背景取色。", Modifier.padding(horizontal = 19.dp, vertical = 5.dp), color = PersonaMuted, fontSize = 9.sp, lineHeight = 14.sp)
        BubbleStyle.entries.forEach { choice ->
            Row(Modifier.fillMaxWidth().clickable { onBubble(choice) }.padding(horizontal = 19.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(choice.title, color = PersonaInk, fontSize = 11.sp); Text(choice.subtitle, color = PersonaMuted, fontSize = 8.sp) }
                Text(if (choice == current) "✓" else "○", color = if (choice == current) PersonaAccent else PersonaMuted)
            }
        }
        Text("聊天背景", Modifier.padding(start = 19.dp, top = 11.dp, bottom = 3.dp), color = PersonaInk, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        ChatBackdrop.entries.forEach { choice ->
            Row(Modifier.fillMaxWidth().clickable { onBackdrop(choice) }.padding(horizontal = 19.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).background(choice.topTint, RoundedCornerShape(9.dp)))
                Text(choice.title, Modifier.weight(1f).padding(start = 10.dp), color = PersonaInk, fontSize = 11.sp)
                Text(if (choice == backdrop) "✓" else "○", color = if (choice == backdrop) PersonaAccent else PersonaMuted)
            }
        }
        Text("气泡样式按联系人单独保存。选择后立即返回聊天预览。", Modifier.padding(horizontal = 19.dp, vertical = 8.dp), color = PersonaMuted, fontSize = 9.sp)
    }
}

@Composable private fun SearchPanel(onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    Column(Modifier.navigationBarsPadding().padding(bottom = 22.dp)) {
        SheetHeader("查找 G老师 的聊天", "同时搜索当前 Thread 和已经封卷的聊天原文。", onClose)
        Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), RoundedCornerShape(14.dp), Color.White.copy(alpha = .55f)) {
            BasicTextField(query, { query = it }, Modifier.padding(13.dp), textStyle = androidx.compose.ui.text.TextStyle(color = PersonaInk, fontSize = 12.sp), decorationBox = { inner -> Box { if (query.isEmpty()) Text("输入关键词…", color = PersonaMuted, fontSize = 12.sp); inner() } })
        }
        Text(if (query.isEmpty()) "输入关键词开始查找" else "在当前 Thread 与聊天书架中查找“$query”", Modifier.padding(19.dp), color = PersonaMuted, fontSize = 9.sp)
    }
}

@Composable private fun DatePanel(onClose: () -> Unit) {
    Column(Modifier.navigationBarsPadding().padding(bottom = 22.dp)) {
        SheetHeader("按日期跳转", "当前聊天始终是一条 Thread；较早日期会跳到对应封存卷册。", onClose)
        ArchiveRow("2026.08.18 · 当前 Thread", "回到最近聊天")
        ArchiveRow("2026.08.12", "LoveHouse 聊天视觉讨论")
        ArchiveRow("2026.08.03", "人格窗口与 Memory")
    }
}

@Composable private fun BookshelfPanel(onClose: () -> Unit) {
    Column(Modifier.navigationBarsPadding().padding(bottom = 22.dp)) {
        SheetHeader("G老师 · 聊天书架", "旧消息保留原文；摘要只作为卷册索引。", onClose)
        ArchiveRow("2026.08.12 — 2026.08.17", "LoveHouse 聊天视觉 · 48 条原始消息")
        ArchiveRow("2026.08.03 — 2026.08.11", "人格窗口与 Memory · 76 条原始消息")
        Text("卷册中的旧消息仍可选择、引用和转发。", Modifier.padding(19.dp), color = PersonaMuted, fontSize = 9.sp)
    }
}

@Composable private fun ArchiveRow(title: String, subtitle: String) {
    Row(Modifier.fillMaxWidth().clickable {}.padding(horizontal = 19.dp, vertical = 11.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) { Text(title, color = PersonaInk, fontSize = 11.sp); Text(subtitle, color = PersonaMuted, fontSize = 8.sp) }
        Text("›", color = PersonaMuted, fontSize = 16.sp)
    }
}

@Composable private fun StatusPanel(onClose: () -> Unit) {
    val initial = 1500
    val pagerState = rememberPagerState(initialPage = initial, pageCount = { 3000 })
    val scope = rememberCoroutineScope()
    val page = ((pagerState.currentPage % 3) + 3) % 3
    val titles = listOf("Usage", "内在状态", "工作记忆")
    Column(Modifier.fillMaxWidth().navigationBarsPadding().padding(bottom = 14.dp)) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            ChatIconButton(LoveHouseIcon.Back, "上一张", touchSize = 34.dp) { scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } }
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Text(titles[page], color = PersonaInk, fontSize = 15.sp, fontWeight = FontWeight.Bold)
                Text("${page + 1} / 3", color = PersonaMuted, fontSize = 8.sp)
            }
            ChatIconButton(LoveHouseIcon.Close, "关闭", touchSize = 34.dp, onClick = onClose)
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxWidth().height(650.dp),
            contentPadding = PaddingValues(horizontal = 46.dp),
            pageSpacing = (-30).dp,
        ) { virtualPage ->
            val offset = ((pagerState.currentPage - virtualPage) + pagerState.currentPageOffsetFraction).absoluteValue.coerceIn(0f, 1f)
            val signedOffset = (pagerState.currentPage - virtualPage) + pagerState.currentPageOffsetFraction
            Surface(
                modifier = Modifier.fillMaxWidth().zIndex(1f - offset).graphicsLayer {
                    translationY = 58.dp.toPx() * offset * offset
                    rotationZ = -8f * signedOffset.coerceIn(-1f, 1f)
                    scaleX = 1f - .12f * offset
                    scaleY = 1f - .12f * offset
                    alpha = 1f - .26f * offset
                },
                shape = RoundedCornerShape(24.dp),
                color = LoveHouseGlass.StrongBackground,
                border = BorderStroke(1.dp, LoveHouseGlass.StrongBorder),
            ) {
                when (((virtualPage % 3) + 3) % 3) {
                    0 -> LazyColumn(contentPadding = PaddingValues(vertical = 16.dp)) { item { UsagePage() } }
                    1 -> EmptyStatusPage("这一页先只预留分页与容器。未来用于展示长期互动状态、变化轨迹、状态可视化与相关事件。当前母版不生成任何伪造的“内在状态”数值。")
                    else -> EmptyStatusPage("这一页先只预留分页与容器。未来接入当前项目、最近修改、pending work、接班包、错题集/踩坑记录等入口；这一刀不顺手实现数据系统。")
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 10.dp), horizontalArrangement = Arrangement.Center) {
            repeat(3) { index ->
                Box(Modifier.padding(horizontal = 4.dp).size(if (index == page) 7.dp else 5.dp).background(if (index == page) PersonaAccent else PersonaMuted.copy(alpha = .4f), CircleShape))
            }
        }
    }
}

@Composable private fun UsagePage() {
    Column(Modifier.padding(horizontal = 19.dp)) {
        Text("当前会话上下文 · LOVEHOUSE MANAGED WINDOW", color = PersonaMuted, fontSize = 8.sp)
        Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), verticalAlignment = Alignment.Bottom) { Text("72k", color = PersonaInk, fontSize = 28.sp, fontWeight = FontWeight.Bold); Text(" / 128k", color = PersonaMuted, fontSize = 11.sp) }
        Box(Modifier.fillMaxWidth().height(7.dp).background(Color.White.copy(alpha = .55f), CircleShape)) { Box(Modifier.fillMaxWidth(.5625f).height(7.dp).background(PersonaAccent, CircleShape)) }
        Text("距离准备整理 28k    ·    距离自动压缩 56k", Modifier.padding(vertical = 10.dp), color = PersonaMuted, fontSize = 9.sp)
        listOf(
            "管理阈值" to "100k 准备整理 · 128k 自动压缩", "压缩后继续携带" to "接班包 + 最近约 30 条原始聊天",
            "模型" to "GPT-5.6 Sol", "本窗口已聊" to "62 轮", "压缩累计" to "3 次 · 最近今天 14:04",
            "模型 / 聊天通道" to "正常", "记忆工具" to "正常", "登录 / 授权" to "有效",
        ).forEach { DetailRow(it.first, it.second) }
        Text("Provider / 额度", Modifier.padding(top = 14.dp, bottom = 5.dp), color = PersonaInk, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        DetailRow("渠道", "OpenAI · API / Subscription")
        DetailRow("Provider 真实 Context", "可读取时并列显示")
        DetailRow("订阅 / API 用量", "按渠道展示真实单位")
    }
}

@Composable private fun EmptyStatusPage(text: String) { Text(text, Modifier.padding(horizontal = 19.dp, vertical = 12.dp), color = PersonaMuted, fontSize = 10.sp, lineHeight = 17.sp) }

@Composable private fun IconGalleryPanel(onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
        SheetHeader("LoveHouse Icon Gallery", "统一 24dp 画布；消息级图标使用 18dp compact optical size。", onClose)
        LoveHouseIconGallery(Modifier.fillMaxWidth())
    }
}
