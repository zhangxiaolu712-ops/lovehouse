package fyi.b612.lovehouse.feature.chat

import android.app.Activity
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.awaitLongPressOrCancellation
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsDraggedAsState
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.DropdownMenu
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
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
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import androidx.core.content.ContextCompat
import fyi.b612.lovehouse.R
import fyi.b612.lovehouse.core.designsystem.LoveHouseGlass
import fyi.b612.lovehouse.core.designsystem.APPEARANCE_WALLPAPER_KEY
import fyi.b612.lovehouse.core.designsystem.APPEARANCE_CUSTOM_WALLPAPER_KEY
import fyi.b612.lovehouse.core.designsystem.LoveHouseIcon
import fyi.b612.lovehouse.core.designsystem.LoveHouseIconGallery
import fyi.b612.lovehouse.core.designsystem.LoveHouseIconOpticalSize
import fyi.b612.lovehouse.core.designsystem.LoveHouseIconView
import fyi.b612.lovehouse.core.storage.LocalStorage
import fyi.b612.lovehouse.feature.nativelab.LocationSmokeTest
import fyi.b612.lovehouse.feature.nativelab.readSelectedResource
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.absoluteValue

private val PersonaInk = Color(0xFF3E4847)
private val PersonaMuted = Color(0xFF7F8B88)
private val PersonaAccent = Color(0xFF718E87)

private object ChatRhythm {
    val SameSenderSpacing = 8.dp
    val GroupSpacing = 24.dp
}

internal enum class ChatBackdrop(
    val key: String,
    val title: String,
    val topTint: Color,
    val bottomTint: Color,
) {
    Green("green", "庭院绿", Color(0xFFB8CBC0), Color(0xFFADC5B5)),
    Rose("rose", "雾粉", Color(0xFFE3BEC9), Color(0xFFD9AFBD)),
    Lavender("lavender", "雾蓝紫", Color(0xFFB9C5DF), Color(0xFFAEB9D7)),
    Warm("warm", "暖米", Color(0xFFE4D8C0), Color(0xFFD6C8AC)),
    Night("night", "夜色", Color(0xFF536174), Color(0xFF26374B)),
}

internal data class ChatVisualContext(
    val wallpaper: ChatBackdrop,
    val chatOverrideKey: String?,
    val customWallpaper: ImageBitmap? = null,
    val customTint: Color? = null,
) {
    val topTint: Color = customTint ?: wallpaper.topTint
    val bottomTint: Color = customTint?.copy(
        red = (customTint.red * .90f).coerceIn(0f, 1f),
        green = (customTint.green * .90f).coerceIn(0f, 1f),
        blue = (customTint.blue * .90f).coerceIn(0f, 1f),
    ) ?: wallpaper.bottomTint
    private val paleTint = Color(
        red = topTint.red * .58f + .42f,
        green = topTint.green * .58f + .42f,
        blue = topTint.blue * .58f + .42f,
    )
    private val darkWallpaper = topTint.red * .2126f + topTint.green * .7152f + topTint.blue * .0722f < .46f
    val panelGlass: Color = paleTint.copy(alpha = .48f)
    val popupGlass: Color = paleTint.copy(alpha = .64f)
    val popupBorder: Color = if (darkWallpaper) Color.White.copy(alpha = .24f) else Color.Black.copy(alpha = .14f)
    val brightEdge: Color = Color.White.copy(alpha = .42f)
    val outsideScrim: Color = bottomTint.copy(alpha = .025f)
}

private data class LocalChatWallpaper(val image: ImageBitmap, val tint: Color)

private fun chatWallpaperPathKey(threadId: String) = "chat_wallpaper_local_path_v1_$threadId"

private suspend fun cacheChatWallpaper(context: Context, threadId: String, source: Uri): String? = withContext(Dispatchers.IO) {
    runCatching {
        val directory = File(context.filesDir, "chat-wallpapers").apply { mkdirs() }
        val target = File(directory, "${threadId.hashCode().toUInt().toString(16)}.image")
        context.contentResolver.openInputStream(source)?.use { input -> target.outputStream().use(input::copyTo) }
            ?: return@runCatching null
        target.absolutePath
    }.getOrNull()
}

private suspend fun loadChatWallpaper(path: String?): LocalChatWallpaper? = withContext(Dispatchers.IO) {
    if (path.isNullOrBlank()) return@withContext null
    runCatching {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        var sample = 1
        while (bounds.outWidth / sample > 1440 || bounds.outHeight / sample > 1440) sample *= 2
        val bitmap = BitmapFactory.decodeFile(path, BitmapFactory.Options().apply { inSampleSize = sample }) ?: return@runCatching null
        var red = 0L
        var green = 0L
        var blue = 0L
        var count = 0L
        val stride = (maxOf(bitmap.width, bitmap.height) / 48).coerceAtLeast(1)
        for (y in 0 until bitmap.height step stride) for (x in 0 until bitmap.width step stride) {
            val pixel = bitmap.getPixel(x, y)
            red += android.graphics.Color.red(pixel)
            green += android.graphics.Color.green(pixel)
            blue += android.graphics.Color.blue(pixel)
            count++
        }
        val hsv = FloatArray(3)
        android.graphics.Color.RGBToHSV((red / count).toInt(), (green / count).toInt(), (blue / count).toInt(), hsv)
        hsv[1] = hsv[1].coerceIn(.38f, .72f)
        hsv[2] = hsv[2].coerceIn(.72f, .90f)
        LocalChatWallpaper(bitmap.asImageBitmap(), Color(android.graphics.Color.HSVToColor(hsv)))
    }.getOrNull()
}

internal fun resolveChatWallpaperKey(chatOverride: String?, globalWallpaper: String?): String = when (val key = chatOverride ?: globalWallpaper ?: "house") {
    "house", "green" -> "green"
    "rose", "lavender", "warm", "night" -> key
    else -> "green"
}

internal fun resolveChatWallpaperPath(
    threadWallpaperPath: String?,
    chatOverride: String?,
    globalWallpaper: String?,
    globalWallpaperPath: String?,
): String? = threadWallpaperPath ?: globalWallpaperPath?.takeIf {
    chatOverride == null && globalWallpaper == "custom"
}

private fun chatBackdropFor(key: String): ChatBackdrop =
    ChatBackdrop.entries.firstOrNull { it.key == key } ?: ChatBackdrop.Green

private enum class PersonaPanel { Detail, Search, DateJump, Bookshelf, Appearance, Status, IconGallery, MemberPicker, ForwardTarget, WorkflowForward, ForwardBundle, AvatarPicker }
private enum class BubbleStyle(val title: String, val subtitle: String) {
    None("无气泡", "文字直接浮在壁纸上"),
    Soft("轻气泡", "参考图式柔软浅气泡"),
    Glass("玻璃", "双方都使用雾面玻璃"),
    Paper("纸片", "偏纸张/便签质感"),
}

@Composable
fun ChatShellScreen(threadId: String, store: ChatSessionStore, localStorage: LocalStorage, onBack: () -> Unit, modifier: Modifier = Modifier) {
    val thread = store.thread(threadId) ?: return
    val messages = store.messages(threadId)
    val context = LocalContext.current
    val wallpaperScope = rememberCoroutineScope()
    var panel by remember { mutableStateOf<PersonaPanel?>(null) }
    var bubbleStyle by remember { mutableStateOf(BubbleStyle.Soft) }
    val globalWallpaper by localStorage.observeString(APPEARANCE_WALLPAPER_KEY).collectAsState(initial = null)
    val chatWallpaperOverride = store.backgroundOverride(threadId)
    val globalWallpaperPath by localStorage.observeString(APPEARANCE_CUSTOM_WALLPAPER_KEY).collectAsState(initial = null)
    val threadWallpaperPath by localStorage.observeString(chatWallpaperPathKey(threadId)).collectAsState(initial = null)
    val effectiveWallpaperPath = resolveChatWallpaperPath(
        threadWallpaperPath = threadWallpaperPath,
        chatOverride = chatWallpaperOverride,
        globalWallpaper = globalWallpaper,
        globalWallpaperPath = globalWallpaperPath,
    )
    val localWallpaper by produceState<LocalChatWallpaper?>(initialValue = null, effectiveWallpaperPath) {
        value = loadChatWallpaper(effectiveWallpaperPath)
    }
    val visualContext = ChatVisualContext(
        wallpaper = chatBackdropFor(resolveChatWallpaperKey(chatWallpaperOverride, globalWallpaper)),
        chatOverrideKey = chatWallpaperOverride,
        customWallpaper = localWallpaper?.image,
        customTint = localWallpaper?.tint,
    )
    val customWallpaperPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) wallpaperScope.launch {
            cacheChatWallpaper(context, threadId, uri)?.let { path ->
                store.clearBackground(threadId)
                localStorage.writeString(chatWallpaperPathKey(threadId), path)
            }
        }
    }
    val actualRuntimeLabel = messages.asReversed().firstNotNullOfOrNull { message ->
        message.runtime?.let { runtime -> listOfNotNull(runtime, message.adapterId).joinToString(" · ") }
    } ?: "Runtime 尚未返回"
    var selectedModel by remember(threadId) { mutableStateOf(if (threadId == "agent-codex") actualRuntimeLabel else "Runtime 尚未接入") }
    LaunchedEffect(actualRuntimeLabel, threadId) {
        if (threadId == "agent-codex") selectedModel = actualRuntimeLabel
    }
    var input by remember { mutableStateOf("") }
    var sending by remember { mutableStateOf(false) }
    var selectedMessages by remember { mutableStateOf<Set<String>>(emptySet()) }
    var forwardingIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var openedBundle by remember { mutableStateOf<ChatMessageUi?>(null) }
    var actionNotice by remember { mutableStateOf<String?>(null) }
    var expandedActionMessageId by remember(threadId) { mutableStateOf<String?>(null) }
    var followLatest by remember(threadId) { mutableStateOf(true) }
    var showJumpToLatest by remember(threadId) { mutableStateOf(false) }
    var openWorkflowTaskId by remember { mutableStateOf<String?>(null) }
    var forwardingTaskId by remember { mutableStateOf<String?>(null) }
    val locationReader = remember(context.applicationContext) { LocationSmokeTest(context.applicationContext) }
    DisposableEffect(locationReader) { onDispose { locationReader.cancel() } }
    val attachmentPhoto = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        actionNotice = uri?.let {
            runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            "照片已选择 · ${context.contentResolver.readSelectedResource(it).asDisplayText()} · 本地待发送"
        } ?: "没有选择照片"
    }
    val attachmentFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        actionNotice = uri?.let {
            runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            "文件已选择 · ${context.contentResolver.readSelectedResource(it).asDisplayText()} · 本地待发送"
        } ?: "没有选择文件"
    }
    val attachmentCamera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        actionNotice = bitmap?.let { "照片已拍摄 · ${it.width}×${it.height} · 本地待发送" } ?: "没有拍摄照片"
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) attachmentCamera.launch(null) else actionNotice = "相机权限未授予"
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        if (grants.values.any { it }) locationReader.request { actionNotice = it.message }
        else actionNotice = "定位权限未授予"
    }
    val attachmentAction: (String) -> Unit = { action ->
        when (action) {
            "照片" -> attachmentPhoto.launch(arrayOf("image/*"))
            "文件" -> attachmentFile.launch(arrayOf("*/*"))
            "相机" -> if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
                attachmentCamera.launch(null)
            } else cameraPermission.launch(Manifest.permission.CAMERA)
            "定位" -> if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            ) {
                locationReader.request { actionNotice = it.message }
            } else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
            "表情面板已切换" -> actionNotice = "表情面板尚未接入"
            else -> actionNotice = "其他附件类型尚未接入"
        }
    }
    val listState = rememberLazyListState()
    val isUserDragging by listState.interactionSource.collectIsDraggedAsState()
    val isAtBottom by remember { derivedStateOf { !listState.canScrollForward } }
    val latestMessageRevision = messages.lastOrNull()?.let { message ->
        Triple(message.messageId, message.body.length, message.deliveryStatus)
    }
    val chatScope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    ChatNavigationBarTint(visualContext)

    val submitMessage: (String) -> Unit = submit@{ outgoing ->
        if (sending || outgoing.isBlank()) return@submit
        input = ""
        if (threadId != "agent-codex") {
            store.sendMessage(threadId, outgoing)
        } else {
            sending = true
            actionNotice = "正在连接 Codex…"
            chatScope.launch {
                val result = store.sendCodexMessage(threadId, outgoing) { }
                sending = false
                result.onSuccess {
                    actionNotice = null
                }.onFailure { error ->
                    actionNotice = error.message ?: "发送失败"
                }
            }
        }
    }

    LaunchedEffect(isUserDragging, isAtBottom) {
        when {
            isAtBottom -> {
                followLatest = true
                showJumpToLatest = false
            }
            isUserDragging -> followLatest = false
        }
    }
    LaunchedEffect(messages.size, latestMessageRevision) {
        if (messages.isEmpty()) return@LaunchedEffect
        if (followLatest || isAtBottom) {
            listState.scrollToItem(messages.lastIndex)
            showJumpToLatest = false
        } else {
            showJumpToLatest = true
        }
    }

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
        ChatBackdropLayer(visualContext)
        ChatAtmosphere(visualContext)
        Column(Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding()) {
            PersonaTopBar(thread, onBack = onBack, onMore = {
                if (thread.kind == ChatThreadKind.TemporaryTask) openWorkflowTaskId = thread.taskId ?: "mock-running-001" else panel = PersonaPanel.Detail
            })
            Box(
                Modifier.weight(1f).fillMaxWidth().clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                ) { expandedActionMessageId = null },
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    state = listState,
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 7.dp),
                    verticalArrangement = Arrangement.spacedBy(0.dp),
                ) {
                    itemsIndexed(messages, key = { _, message -> message.messageId }) { index, message ->
                        val previous = messages.getOrNull(index - 1)
                        val next = messages.getOrNull(index + 1)
                        val startsGroup = previous == null || previous.author != message.author || previous.mine != message.mine
                        val endsGroup = next == null || next.author != message.author || next.mine != message.mine
                        val spacingAfter = when {
                            next == null -> 0.dp
                            endsGroup -> ChatRhythm.GroupSpacing
                            else -> ChatRhythm.SameSenderSpacing
                        }
                        MessageBubble(
                            message = message,
                            startsGroup = startsGroup,
                            spacingAfter = spacingAfter,
                            task = message.taskId?.let(store::task),
                            style = bubbleStyle,
                            visualContext = visualContext,
                            selected = message.messageId in selectedMessages,
                            selectionMode = selectedMessages.isNotEmpty(),
                            actionsExpanded = expandedActionMessageId == message.messageId,
                            onToggleActions = {
                                expandedActionMessageId = if (expandedActionMessageId == message.messageId) null else message.messageId
                            },
                            onToggleSelection = {
                                expandedActionMessageId = null
                                selectedMessages = if (message.messageId in selectedMessages) selectedMessages - message.messageId else selectedMessages + message.messageId
                            },
                            onForward = { forwardingIds = setOf(message.messageId); panel = PersonaPanel.ForwardTarget },
                            onOpenBundle = { openedBundle = message; panel = PersonaPanel.ForwardBundle },
                            onOpenWorkflow = { message.taskId?.let { openWorkflowTaskId = it } },
                            onAction = { action ->
                                actionNotice = when (action) {
                                    "复制" -> { clipboard.setText(AnnotatedString(message.body)); "已复制" }
                                    "重试" -> "重试尚未接入"
                                    "朗读" -> "朗读尚未接入"
                                    else -> "已打开消息操作"
                                }
                            },
                        )
                    }
                }
                if (showJumpToLatest && messages.isNotEmpty()) {
                    Surface(
                        modifier = Modifier.align(Alignment.BottomEnd).padding(end = 18.dp, bottom = 7.dp)
                            .clickable {
                                followLatest = true
                                showJumpToLatest = false
                                chatScope.launch { listState.animateScrollToItem(messages.lastIndex) }
                            },
                        shape = RoundedCornerShape(14.dp),
                        color = visualContext.bottomTint.copy(alpha = .78f),
                        border = BorderStroke(.6.dp, Color.White.copy(alpha = .58f)),
                    ) {
                        Text("↓  新消息", Modifier.padding(horizontal = 10.dp, vertical = 6.dp), color = PersonaInk, fontSize = 9.sp)
                    }
                }
            }
            actionNotice?.let { Text(it, Modifier.align(Alignment.CenterHorizontally).padding(vertical = 2.dp), color = PersonaMuted, fontSize = 8.sp) }
            if (selectedMessages.isNotEmpty()) MultiSelectBar(selectedMessages.size, onCancel = { selectedMessages = emptySet() }) {
                forwardingIds = selectedMessages; panel = PersonaPanel.ForwardTarget
            } else PersonaComposer(
                value = input,
                visualContext = visualContext,
                onValueChange = { input = it },
                onSend = { submitMessage(input) },
                onSendTranscript = submitMessage,
                onToolAction = attachmentAction,
                onHeightChanged = {
                    if (followLatest && messages.isNotEmpty()) chatScope.launch { listState.scrollToItem(messages.lastIndex) }
                },
            )
        }
        if (panel != null) {
            when (panel) {
                PersonaPanel.ForwardTarget -> ForwardTargetSheet(store, threadId, forwardingIds.size > 1, visualContext, onClose = { panel = null }, onConfirm = { target -> store.forward(threadId, forwardingIds, target, forwardingIds.size > 1); selectedMessages = emptySet(); panel = null; actionNotice = "已转发到 ${store.thread(target)?.title}" })
                PersonaPanel.WorkflowForward -> ForwardTargetSheet(store, threadId, false, visualContext, title = "转发 Workflow", onClose = { panel = null; forwardingTaskId = null }, onConfirm = { target -> forwardingTaskId?.let { store.forwardWorkflow(it, target) }; panel = null; forwardingTaskId = null; actionNotice = "Workflow 已转发到 ${store.thread(target)?.title}" })
                PersonaPanel.MemberPicker -> MemberPickerSheet(store, threadId, visualContext, onClose = { panel = PersonaPanel.Detail }) { store.addMember(threadId, it); panel = PersonaPanel.Detail }
                PersonaPanel.ForwardBundle -> ForwardBundleSheet(openedBundle, visualContext, onClose = { panel = null })
                PersonaPanel.AvatarPicker -> AvatarPickerSheet(visualContext, onClose = { panel = PersonaPanel.Detail }) { store.updateAvatar(threadId, it); panel = PersonaPanel.Detail }
                else -> PersonaSheet(
                    panel!!, thread, store, selectedModel, bubbleStyle, visualContext,
                    onClose = { panel = if (panel == PersonaPanel.Detail) null else PersonaPanel.Detail },
                    onNavigate = { panel = it },
                    onModel = { selectedModel = it },
                    onBubble = { bubbleStyle = it; panel = PersonaPanel.Detail },
                    onBackdrop = { choice ->
                        wallpaperScope.launch { localStorage.remove(chatWallpaperPathKey(threadId)) }
                        if (choice == null) store.clearBackground(threadId) else store.setBackground(threadId, choice.key)
                        panel = PersonaPanel.Detail
                    },
                    onPickCustomWallpaper = { customWallpaperPicker.launch(arrayOf("image/*")) },
                )
            }
        }
        openWorkflowTaskId?.let { taskId ->
            store.task(taskId)?.let { task ->
                TaskWorkflowOverlay(
                    task = task,
                    visualContext = visualContext,
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
private fun ChatNavigationBarTint(visualContext: ChatVisualContext) {
    val window = (LocalView.current.context as? Activity)?.window ?: return
    DisposableEffect(window, visualContext.bottomTint) {
        val previousColor = window.navigationBarColor
        val previousContrast = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced else true
        window.navigationBarColor = visualContext.bottomTint.toArgb()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = false
        onDispose {
            window.navigationBarColor = previousColor
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) window.isNavigationBarContrastEnforced = previousContrast
        }
    }
}

@Composable
internal fun ChatBackdropLayer(visualContext: ChatVisualContext) {
    val backdrop = visualContext.wallpaper
    visualContext.customWallpaper?.let { image ->
        Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    } ?: Image(
        painterResource(if (backdrop == ChatBackdrop.Lavender) R.drawable.wallpaper_chat_lavender else R.drawable.wallpaper_default_green),
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
    )
    val tintAlpha = if (visualContext.customWallpaper != null) .10f else when (backdrop) {
        ChatBackdrop.Green -> .06f
        ChatBackdrop.Lavender -> .12f
        ChatBackdrop.Rose -> .32f
        ChatBackdrop.Warm -> .24f
        ChatBackdrop.Night -> .42f
    }
    Box(
        Modifier.fillMaxSize().background(
            Brush.verticalGradient(
                listOf(visualContext.topTint.copy(alpha = tintAlpha), visualContext.bottomTint.copy(alpha = tintAlpha * .82f)),
            ),
        ),
    )
}

@Composable
internal fun ChatAtmosphere(visualContext: ChatVisualContext) {
    Box(Modifier.fillMaxSize()) {
        Box(
            Modifier.fillMaxWidth().fillMaxHeight(.22f).align(Alignment.TopCenter).background(
                Brush.verticalGradient(
                    0f to visualContext.topTint.copy(alpha = .72f),
                    .42f to visualContext.topTint.copy(alpha = .42f),
                    1f to Color.Transparent,
                ),
            ),
        )
        Box(
            Modifier.fillMaxWidth().fillMaxHeight(.28f).align(Alignment.BottomCenter).background(
                Brush.verticalGradient(
                    0f to Color.Transparent,
                    .62f to visualContext.bottomTint.copy(alpha = .42f),
                    1f to visualContext.bottomTint.copy(alpha = .74f),
                ),
            ),
        )
    }
}

@Composable
private fun ChatOverlayFrame(visualContext: ChatVisualContext, onClose: () -> Unit, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        ChatBackdropLayer(visualContext)
        ChatAtmosphere(visualContext)
        Box(Modifier.fillMaxSize().background(visualContext.outsideScrim).clickable(onClick = onClose))
        Surface(
            modifier = Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 22.dp),
            shape = RoundedCornerShape(26.dp),
            color = visualContext.panelGlass,
            border = BorderStroke(.8.dp, visualContext.brightEdge),
        ) { content() }
    }
}

@Composable
private fun ForwardTargetSheet(
    store: ChatSessionStore,
    currentThreadId: String,
    merged: Boolean,
    visualContext: ChatVisualContext,
    title: String = if (merged) "合并转发" else "转发消息",
    onClose: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var selected by remember { mutableStateOf<String?>(null) }
    ChatOverlayFrame(visualContext, onClose) {
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
                Text("确认转发", Modifier.clip(RoundedCornerShape(12.dp)).background(if (selected == null) Color.White.copy(alpha = .35f) else PersonaAccent.copy(alpha = .18f)).clickable(enabled = selected != null) { selected?.let(onConfirm) }.padding(horizontal = 16.dp, vertical = 10.dp), color = if (selected == null) PersonaMuted else PersonaInk, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable
private fun MemberPickerSheet(store: ChatSessionStore, threadId: String, visualContext: ChatVisualContext, onClose: () -> Unit, onConfirm: (ChatPersona) -> Unit) {
    val existing = store.members(threadId).map { it.memberId }.toSet()
    var selected by remember { mutableStateOf<ChatPersona?>(null) }
    ChatOverlayFrame(visualContext, onClose) {
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
            Text("确认加入", Modifier.align(Alignment.End).padding(18.dp).clip(RoundedCornerShape(12.dp)).background(PersonaAccent.copy(alpha = if (selected == null) .06f else .18f)).clickable(enabled = selected != null) { selected?.let(onConfirm) }.padding(horizontal = 18.dp, vertical = 10.dp), color = if (selected == null) PersonaMuted else PersonaInk, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun ForwardBundleSheet(message: ChatMessageUi?, visualContext: ChatVisualContext, onClose: () -> Unit) {
    ChatOverlayFrame(visualContext, onClose) {
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
private fun AvatarPickerSheet(visualContext: ChatVisualContext, onClose: () -> Unit, onConfirm: (String) -> Unit) {
    var selected by remember { mutableStateOf("G") }
    ChatOverlayFrame(visualContext, onClose) {
        Column {
            SheetHeader("更换头像", "仅作用于当前 Persona 窗口。", onClose)
            Row(Modifier.fillMaxWidth().padding(20.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
                listOf("G", "花", "月", "星", "猫").forEach { glyph ->
                    Box(Modifier.size(46.dp).clip(RoundedCornerShape(15.dp)).background(if (selected == glyph) PersonaAccent.copy(alpha = .25f) else Color.White.copy(alpha = .45f)).clickable { selected = glyph }, contentAlignment = Alignment.Center) { Text(glyph, color = PersonaInk, fontWeight = FontWeight.Bold) }
                }
            }
            Text("确认", Modifier.align(Alignment.End).padding(18.dp).clip(RoundedCornerShape(12.dp)).background(PersonaAccent.copy(alpha = .18f)).clickable { onConfirm(selected) }.padding(horizontal = 18.dp, vertical = 10.dp), color = PersonaInk, fontSize = 11.sp, fontWeight = FontWeight.Medium)
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
    startsGroup: Boolean,
    spacingAfter: Dp,
    task: RemoteAgentTask?,
    style: BubbleStyle,
    visualContext: ChatVisualContext,
    selected: Boolean,
    selectionMode: Boolean,
    actionsExpanded: Boolean,
    onToggleActions: () -> Unit,
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
        if (!message.mine) { if (startsGroup) MessageAvatar(message) else Spacer(Modifier.size(30.dp)) }
        Column(
            modifier = Modifier.widthIn(max = 300.dp).padding(horizontal = 7.dp),
            horizontalAlignment = if (message.mine) Alignment.End else Alignment.Start,
        ) {
            if (startsGroup) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (message.mine) Text(message.time, color = PersonaMuted, fontSize = 7.5.sp)
                Text(message.author, color = if (message.mine) PersonaMuted else PersonaAccent, fontSize = 8.5.sp, fontWeight = FontWeight.Medium)
                if (!message.mine) Text(message.time, color = PersonaMuted, fontSize = 7.5.sp)
            }
            if (!message.mine && message.thoughtDuration != null && message.thoughtSummary != null) {
                ThoughtRow(message.thoughtDuration, message.thoughtSummary)
            }
            val bubbleModifier = Modifier.combinedClickable(
                onClick = {
                    if (selectionMode) onToggleSelection() else when (message.kind) {
                        ChatMessageKind.Task, ChatMessageKind.Workflow -> onOpenWorkflow()
                        ChatMessageKind.ForwardBundle -> onOpenBundle()
                        ChatMessageKind.Text -> onToggleActions()
                    }
                },
                onLongClick = onToggleSelection,
            )
            val bubbleBorder = when { selected -> BorderStroke(1.5.dp, PersonaAccent); style == BubbleStyle.Glass -> BorderStroke(1.dp, Color.White.copy(alpha = .72f)); else -> null }
            if (message.kind == ChatMessageKind.Text) {
                Column(
                    Modifier.padding(top = if (startsGroup) 1.dp else 0.dp),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                    horizontalAlignment = if (message.mine) Alignment.End else Alignment.Start,
                ) {
                    message.body.naturalMessageSegments().forEach { segment ->
                        Surface(modifier = bubbleModifier, shape = RoundedCornerShape(15.dp), color = color, border = bubbleBorder) {
                            Text(segment, Modifier.padding(horizontal = 10.dp, vertical = 4.dp), color = PersonaInk, fontSize = 11.5.sp, lineHeight = 17.sp)
                        }
                    }
                }
            } else Surface(
                modifier = Modifier.padding(top = if (startsGroup) 1.dp else 0.dp).then(bubbleModifier),
                shape = RoundedCornerShape(15.dp), color = color, border = bubbleBorder,
            ) {
                when (message.kind) {
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
                    ChatMessageKind.Text -> Unit
                }
            }
            if (actionsExpanded) MessageQuickActions(visualContext, onForward, onAction)
        }
        if (message.mine) { if (startsGroup) MessageAvatar(message) else Spacer(Modifier.size(30.dp)) }
    }
    if (spacingAfter > 0.dp) Spacer(Modifier.height(spacingAfter))
}

internal fun String.naturalMessageSegments(): List<String> =
    split(Regex("\\n\\s*\\n+")).map(String::trim).filter(String::isNotEmpty).ifEmpty { listOf(this) }

@Composable private fun MessageAvatar(message: ChatMessageUi) {
    Box(Modifier.size(30.dp).background(if (message.mine) Color(0xFFE8DDD4) else Color(0xFFD8E7E1), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) {
        Text(message.avatar, color = PersonaAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable private fun MessageQuickActions(visualContext: ChatVisualContext, onForward: () -> Unit, onAction: (String) -> Unit) {
    var showMore by remember { mutableStateOf(false) }
    Row(Modifier.padding(top = 0.dp), horizontalArrangement = Arrangement.spacedBy(0.dp)) {
        ChatIconButton(LoveHouseIcon.Copy, "复制", iconSize = 15.dp, touchSize = 30.dp, opticalSize = LoveHouseIconOpticalSize.Compact) { onAction("复制") }
        ChatIconButton(LoveHouseIcon.Forward, "转发", iconSize = 15.dp, touchSize = 30.dp, opticalSize = LoveHouseIconOpticalSize.Compact, onClick = onForward)
        Box {
            ChatIconButton(LoveHouseIcon.More, "更多", iconSize = 15.dp, touchSize = 30.dp, opticalSize = LoveHouseIconOpticalSize.Compact) { showMore = true }
            DropdownMenu(
                expanded = showMore,
                onDismissRequest = { showMore = false },
                shape = RoundedCornerShape(17.dp),
                containerColor = visualContext.popupGlass,
                tonalElevation = 0.dp,
                shadowElevation = 0.dp,
                border = BorderStroke(.5.dp, visualContext.popupBorder),
            ) {
                Row(Modifier.padding(horizontal = 4.dp, vertical = 1.dp), verticalAlignment = Alignment.CenterVertically) {
                    LoveHouseIconView(LoveHouseIcon.Collapse, null, Modifier.padding(horizontal = 4.dp).size(11.dp), PersonaAccent, LoveHouseIconOpticalSize.Compact)
                    listOf("朗读", "引用", "重试", "详情").forEach { action ->
                        Text(
                            action,
                            Modifier.clip(RoundedCornerShape(10.dp)).clickable { showMore = false; onAction(action) }
                                .padding(horizontal = 8.dp, vertical = 6.dp),
                            color = PersonaInk,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Normal,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PersonaComposer(
    value: String,
    visualContext: ChatVisualContext,
    onValueChange: (String) -> Unit,
    onSend: () -> Unit,
    onSendTranscript: (String) -> Unit,
    onToolAction: (String) -> Unit,
    onHeightChanged: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    var showAttachments by remember { mutableStateOf(false) }
    var inputFocused by remember { mutableStateOf(false) }
    var composerExpanded by remember { mutableStateOf(false) }
    var imeShownDuringCurrentFocus by remember { mutableStateOf(false) }
    var voiceState by remember { mutableStateOf(ChatVoiceInputState()) }
    val voiceAvailable = remember(context) { android.speech.SpeechRecognizer.isRecognitionAvailable(context) }
    val voiceController = remember(context, voiceAvailable) {
        if (voiceAvailable) ChatVoiceInputController(context) { voiceState = it } else null
    }
    DisposableEffect(voiceController) { onDispose { voiceController?.destroy() } }
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) voiceState = ChatVoiceInputState(error = "需要麦克风权限才能语音输入")
    }
    val startVoiceInput = {
        when {
            !voiceAvailable -> voiceState = ChatVoiceInputState(error = "当前设备没有可用的系统语音识别服务")
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> {
                voiceState = ChatVoiceInputState(error = "授权后请再次长按输入区")
                microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            }
            else -> voiceController?.start()
        }
    }
    LaunchedEffect(inputFocused, imeVisible) {
        when {
            !inputFocused -> {
                composerExpanded = false
                imeShownDuringCurrentFocus = false
            }
            imeVisible -> {
                composerExpanded = true
                imeShownDuringCurrentFocus = true
            }
            imeShownDuringCurrentFocus -> {
                composerExpanded = false
                imeShownDuringCurrentFocus = false
            }
        }
    }
    Box(
        modifier = Modifier.fillMaxWidth().onSizeChanged { onHeightChanged() }.padding(horizontal = 12.dp, vertical = 2.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (voiceState.listening || voiceState.processing || voiceState.transcript.isNotBlank() || voiceState.error != null) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(15.dp),
                    color = visualContext.popupGlass,
                    border = BorderStroke(.5.dp, visualContext.popupBorder),
                    tonalElevation = 0.dp,
                    shadowElevation = 0.dp,
                ) {
                    Column(Modifier.padding(horizontal = 11.dp, vertical = 8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                when {
                                    voiceState.transcript.isNotBlank() -> voiceState.transcript
                                    voiceState.listening -> "正在聆听…"
                                    voiceState.processing -> "正在整理转写…"
                                    else -> voiceState.error.orEmpty()
                                },
                                Modifier.weight(1f),
                                color = PersonaInk,
                                fontSize = 10.5.sp,
                                lineHeight = 15.sp,
                            )
                            ChatIconButton(LoveHouseIcon.Collapse, "收起语音输入", iconSize = 13.dp, touchSize = 30.dp) {
                                voiceController?.dismiss()
                                voiceState = ChatVoiceInputState()
                            }
                        }
                        voiceState.error?.takeIf { voiceState.transcript.isNotBlank() }?.let { error ->
                            Text(error, Modifier.padding(top = 3.dp), color = PersonaMuted, fontSize = 8.5.sp)
                        }
                        if (voiceState.finished && voiceState.transcript.isNotBlank()) {
                            Row(Modifier.fillMaxWidth().padding(top = 5.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                                Text("发送语音 · 待接入", color = PersonaMuted, fontSize = 8.5.sp)
                                Text(
                                    "发送文字",
                                    Modifier.padding(start = 7.dp).clip(RoundedCornerShape(10.dp)).clickable {
                                        val transcript = voiceState.transcript
                                        voiceController?.dismiss()
                                        onSendTranscript(transcript)
                                    }.padding(horizontal = 10.dp, vertical = 6.dp),
                                    color = PersonaInk,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        } else if (voiceState.listening) {
                            Text("松开结束语音输入", Modifier.padding(top = 3.dp), color = PersonaMuted, fontSize = 8.5.sp)
                        }
                    }
                }
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(17.dp),
                color = Color.White.copy(alpha = .30f),
                border = BorderStroke(.6.dp, Color.White.copy(alpha = .46f)),
            ) {
                val composerTextStyle = androidx.compose.ui.text.TextStyle(color = PersonaInk, fontSize = 12.sp, lineHeight = 17.sp)
                ComposerContentLayout(
                    expanded = composerExpanded,
                    attachment = {
                        ComposerAttachmentButton(
                            expanded = showAttachments,
                            visualContext = visualContext,
                            onExpandedChange = { showAttachments = it },
                            onToolAction = onToolAction,
                        )
                    },
                    textField = {
                    BasicTextField(
                        value = value,
                        onValueChange = onValueChange,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp, max = 96.dp)
                                .onFocusChanged { focusState ->
                                    if (focusState.isFocused && !inputFocused) composerExpanded = true
                                    if (!focusState.isFocused) composerExpanded = false
                                    inputFocused = focusState.isFocused
                                }
                            .pointerInput(voiceController) {
                                awaitEachGesture {
                                    val down = awaitFirstDown(requireUnconsumed = false)
                                    val longPress = awaitLongPressOrCancellation(down.id)
                                    if (longPress != null) {
                                        longPress.consume()
                                        startVoiceInput()
                                        waitForUpOrCancellation()
                                        voiceController?.stop()
                                    }
                                }
                            }
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            textStyle = composerTextStyle,
                            singleLine = false,
                        decorationBox = { inner ->
                                Box(Modifier.fillMaxWidth()) {
                                    if (value.isEmpty()) Text("输入文字……", style = composerTextStyle.copy(color = PersonaMuted))
                                inner()
                            }
                        },
                        )
                    },
                    emoji = { ChatIconButton(LoveHouseIcon.Emoji, "表情", touchSize = 34.dp) { onToolAction("表情面板已切换") } },
                    send = { PawSendButton(enabled = value.isNotBlank(), onClick = onSend) },
                )
            }
        }
    }
}

@Composable
private fun ComposerContentLayout(
    expanded: Boolean,
    attachment: @Composable () -> Unit,
    textField: @Composable () -> Unit,
    emoji: @Composable () -> Unit,
    send: @Composable () -> Unit,
) {
    Layout(
        modifier = Modifier.fillMaxWidth(),
        content = { attachment(); textField(); emoji(); send() },
    ) { measurables, constraints ->
        val loose = constraints.copy(minWidth = 0, minHeight = 0)
        val attachmentPlaceable = measurables[0].measure(loose)
        val emojiPlaceable = measurables[2].measure(loose)
        val sendPlaceable = measurables[3].measure(loose)
        val controlHeight = maxOf(attachmentPlaceable.height, emojiPlaceable.height, sendPlaceable.height)
        val textWidth = if (expanded) constraints.maxWidth else {
            (constraints.maxWidth - attachmentPlaceable.width - emojiPlaceable.width - sendPlaceable.width).coerceAtLeast(0)
        }
        val textPlaceable = measurables[1].measure(
            loose.copy(minWidth = textWidth, maxWidth = textWidth),
        )
        val contentHeight = if (expanded) textPlaceable.height + controlHeight else maxOf(textPlaceable.height, controlHeight)
        layout(constraints.maxWidth, contentHeight.coerceIn(constraints.minHeight, constraints.maxHeight)) {
            if (expanded) {
                textPlaceable.placeRelative(0, 0)
                val controlsTop = textPlaceable.height
                attachmentPlaceable.placeRelative(0, controlsTop + (controlHeight - attachmentPlaceable.height) / 2)
                sendPlaceable.placeRelative(constraints.maxWidth - sendPlaceable.width, controlsTop + (controlHeight - sendPlaceable.height) / 2)
                emojiPlaceable.placeRelative(constraints.maxWidth - sendPlaceable.width - emojiPlaceable.width, controlsTop + (controlHeight - emojiPlaceable.height) / 2)
            } else {
                val controlsTop = contentHeight - controlHeight
                attachmentPlaceable.placeRelative(0, controlsTop + (controlHeight - attachmentPlaceable.height) / 2)
                textPlaceable.placeRelative(attachmentPlaceable.width, (contentHeight - textPlaceable.height) / 2)
                emojiPlaceable.placeRelative(attachmentPlaceable.width + textPlaceable.width, controlsTop + (controlHeight - emojiPlaceable.height) / 2)
                sendPlaceable.placeRelative(attachmentPlaceable.width + textPlaceable.width + emojiPlaceable.width, controlsTop + (controlHeight - sendPlaceable.height) / 2)
            }
        }
    }
}

@Composable
private fun ComposerAttachmentButton(
    expanded: Boolean,
    visualContext: ChatVisualContext,
    onExpandedChange: (Boolean) -> Unit,
    onToolAction: (String) -> Unit,
) {
    Box {
        ChatIconButton(LoveHouseIcon.Plus, "添加附件", touchSize = 34.dp) { onExpandedChange(true) }
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { onExpandedChange(false) },
            modifier = Modifier.widthIn(max = 124.dp),
            shape = RoundedCornerShape(14.dp),
            containerColor = visualContext.popupGlass,
            tonalElevation = 0.dp,
            shadowElevation = 0.dp,
            border = BorderStroke(.5.dp, visualContext.popupBorder),
        ) {
            listOf(
                LoveHouseIcon.Camera to "相机", LoveHouseIcon.Photo to "照片", LoveHouseIcon.File to "文件",
                LoveHouseIcon.Location to "定位", LoveHouseIcon.More to "其他",
            ).forEach { (icon, item) ->
                Row(
                    Modifier.fillMaxWidth().clickable { onExpandedChange(false); onToolAction(item) }
                        .padding(horizontal = 10.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(7.dp),
                ) {
                    LoveHouseIconView(icon, null, Modifier.size(16.dp), PersonaMuted)
                    Text(item, color = PersonaInk, fontSize = 10.sp)
                }
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
private fun PersonaSheet(panel: PersonaPanel, thread: ChatThreadSummary, store: ChatSessionStore, model: String, bubble: BubbleStyle, visualContext: ChatVisualContext, onClose: () -> Unit, onNavigate: (PersonaPanel) -> Unit, onModel: (String) -> Unit, onBubble: (BubbleStyle) -> Unit, onBackdrop: (ChatBackdrop?) -> Unit, onPickCustomWallpaper: () -> Unit) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
        ChatBackdropLayer(visualContext)
        ChatAtmosphere(visualContext)
        Box(Modifier.fillMaxSize().background(visualContext.outsideScrim).clickable(onClick = onClose))
        val shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
        Box(
            modifier = Modifier.fillMaxSize().padding(top = 42.dp).clip(shape)
                .clickable(enabled = false) {}.border(.8.dp, visualContext.brightEdge, shape),
        ) {
            Box(Modifier.fillMaxSize().background(visualContext.panelGlass))
            when (panel) {
                PersonaPanel.Detail -> when (thread.kind) {
                    ChatThreadKind.LivingRoom -> LivingRoomDetailPanel(thread, store, onClose, onNavigate)
                    ChatThreadKind.TemporaryTask -> Column { SheetHeader(thread.title, "Workflow 已作为临时任务主详情。", onClose) }
                    else -> DirectDetailPanel(thread, model, onClose, onNavigate, onModel)
                }
                PersonaPanel.Appearance -> AppearancePanel(bubble, visualContext, onClose, onBubble, onBackdrop, onPickCustomWallpaper)
                PersonaPanel.Search -> SearchPanel(thread, store.messages(thread.threadId), onClose)
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
            Text(title, color = PersonaInk, fontWeight = FontWeight.Medium, fontSize = 15.sp)
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
                Column(Modifier.weight(1f).padding(start = 11.dp)) { Text(thread.title, color = PersonaInk, fontWeight = FontWeight.Medium, fontSize = 15.sp); Text("人格窗口 · 身份与模型分离", color = PersonaMuted, fontSize = 9.sp) }
                ChatIconButton(LoveHouseIcon.Close, "关闭", touchSize = 34.dp, onClick = onClose)
            }
            DetailRow("当前运行", model)
            DetailRow("Persona", thread.title)
            DetailRow("Memory", "状态尚未接入")
            DetailRow("长期 Thread", thread.threadId)
            DetailRow("更换头像", "自定义  ›") { onNavigate(PersonaPanel.AvatarPicker) }
            DetailRow("查找聊天", "›") { onNavigate(PersonaPanel.Search) }
            DetailRow("按日期跳转", "›") { onNavigate(PersonaPanel.DateJump) }
            DetailRow("聊天书架", "›") { onNavigate(PersonaPanel.Bookshelf) }
            DetailRow("聊天外观", "气泡 · 壁纸 · 雾面  ›") { onNavigate(PersonaPanel.Appearance) }
            DetailRow("会话状态", "Usage · 状态 · 工作记忆  ›") { onNavigate(PersonaPanel.Status) }
            DetailRow("新增 / 管理模型", "尚未接入")
            Text("运行模型 · 只改变底层引擎，不改变人格、Thread、Memory 和书架", Modifier.padding(horizontal = 19.dp, vertical = 14.dp), color = PersonaMuted, fontSize = 9.sp, lineHeight = 14.sp)
            Text("当前 Android/Bridge 尚无正式模型切换 contract，因此这里不提供只改变选中态的假按钮。", Modifier.padding(horizontal = 19.dp), color = PersonaMuted, fontSize = 9.sp, lineHeight = 14.sp)
        }
    }
}

@Composable private fun LivingRoomDetailPanel(thread: ChatThreadSummary, store: ChatSessionStore, onClose: () -> Unit, onNavigate: (PersonaPanel) -> Unit) {
    LazyColumn(contentPadding = PaddingValues(bottom = 24.dp)) {
        item {
            SheetHeader("小客厅", "成员是这个空间的核心；工单仅作为次级索引。", onClose)
            DetailRow("空间", thread.threadId)
            Text("成员", Modifier.padding(horizontal = 19.dp, vertical = 8.dp), color = PersonaInk, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            store.members(thread.threadId).forEach { member ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 19.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(32.dp).background(Color(0xFFDAE8E2), RoundedCornerShape(10.dp)), contentAlignment = Alignment.Center) { Text(member.avatar, color = PersonaAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold) }
                    Column(Modifier.weight(1f).padding(start = 10.dp)) { Text(member.name, color = PersonaInk, fontSize = 11.sp); Text(member.status, color = PersonaMuted, fontSize = 8.sp) }
                }
            }
            DetailRow("添加成员", "选择 Persona / 成员  ›") { onNavigate(PersonaPanel.MemberPicker) }
            DetailRow("已签收工单", "本地演示 · 未接真实数据")
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

@Composable private fun AppearancePanel(current: BubbleStyle, visualContext: ChatVisualContext, onClose: () -> Unit, onBubble: (BubbleStyle) -> Unit, onBackdrop: (ChatBackdrop?) -> Unit, onPickCustomWallpaper: () -> Unit) {
    Column(Modifier.navigationBarsPadding().padding(bottom = 18.dp)) {
        SheetHeader("G老师 · 聊天外观", onClose = onClose)
        Text("聊天背景只作用于当前窗口；上下氛围层会跟随背景取色。", Modifier.padding(horizontal = 19.dp, vertical = 5.dp), color = PersonaMuted, fontSize = 9.sp, lineHeight = 14.sp)
        BubbleStyle.entries.forEach { choice ->
            Row(Modifier.fillMaxWidth().clickable { onBubble(choice) }.padding(horizontal = 19.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) { Text(choice.title, color = PersonaInk, fontSize = 11.sp); Text(choice.subtitle, color = PersonaMuted, fontSize = 8.sp) }
                Text(if (choice == current) "✓" else "○", color = if (choice == current) PersonaAccent else PersonaMuted)
            }
        }
        Text("聊天背景", Modifier.padding(start = 19.dp, top = 11.dp, bottom = 3.dp), color = PersonaInk, fontSize = 10.sp, fontWeight = FontWeight.Medium)
        Row(Modifier.fillMaxWidth().clickable(onClick = onPickCustomWallpaper).padding(horizontal = 19.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            visualContext.customWallpaper?.let { image ->
                Image(image, null, Modifier.size(28.dp).clip(RoundedCornerShape(9.dp)), contentScale = ContentScale.Crop)
            } ?: Box(Modifier.size(28.dp).background(visualContext.topTint, RoundedCornerShape(9.dp)), contentAlignment = Alignment.Center) {
                LoveHouseIconView(LoveHouseIcon.Photo, null, Modifier.size(15.dp), PersonaInk)
            }
            Text("从相册选择 · 本机测试", Modifier.weight(1f).padding(start = 10.dp), color = PersonaInk, fontSize = 11.sp)
            Text(if (visualContext.customWallpaper != null) "✓" else "›", color = if (visualContext.customWallpaper != null) PersonaAccent else PersonaMuted)
        }
        Row(Modifier.fillMaxWidth().clickable { onBackdrop(null) }.padding(horizontal = 19.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(28.dp).background(visualContext.wallpaper.topTint, RoundedCornerShape(9.dp)))
            Text("跟随全局", Modifier.weight(1f).padding(start = 10.dp), color = PersonaInk, fontSize = 11.sp)
            Text(if (visualContext.chatOverrideKey == null) "✓" else "○", color = if (visualContext.chatOverrideKey == null) PersonaAccent else PersonaMuted)
        }
        ChatBackdrop.entries.filter { it == ChatBackdrop.Green || it == ChatBackdrop.Rose || it == ChatBackdrop.Lavender }.forEach { choice ->
            Row(Modifier.fillMaxWidth().clickable { onBackdrop(choice) }.padding(horizontal = 19.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(28.dp).background(choice.topTint, RoundedCornerShape(9.dp)))
                Text(choice.title, Modifier.weight(1f).padding(start = 10.dp), color = PersonaInk, fontSize = 11.sp)
                Text(if (choice.key == visualContext.chatOverrideKey) "✓" else "○", color = if (choice.key == visualContext.chatOverrideKey) PersonaAccent else PersonaMuted)
            }
        }
        Text("气泡样式按联系人单独保存。选择后立即返回聊天预览。", Modifier.padding(horizontal = 19.dp, vertical = 8.dp), color = PersonaMuted, fontSize = 9.sp)
    }
}

@Composable private fun SearchPanel(thread: ChatThreadSummary, messages: List<ChatMessageUi>, onClose: () -> Unit) {
    var query by remember { mutableStateOf("") }
    val results = remember(query, messages.size) {
        if (query.isBlank()) emptyList() else messages.filter { it.body.contains(query, ignoreCase = true) }
    }
    Column(Modifier.navigationBarsPadding().padding(bottom = 22.dp)) {
        SheetHeader("查找 ${thread.title} 的聊天", "搜索当前设备已保存的这个 Thread 正文。", onClose)
        Surface(Modifier.fillMaxWidth().padding(horizontal = 18.dp), RoundedCornerShape(14.dp), Color.White.copy(alpha = .55f)) {
            BasicTextField(query, { query = it }, Modifier.padding(13.dp), textStyle = androidx.compose.ui.text.TextStyle(color = PersonaInk, fontSize = 12.sp), decorationBox = { inner -> Box { if (query.isEmpty()) Text("输入关键词…", color = PersonaMuted, fontSize = 12.sp); inner() } })
        }
        Text(if (query.isEmpty()) "输入关键词开始查找" else "找到 ${results.size} 条本机真实消息", Modifier.padding(19.dp), color = PersonaMuted, fontSize = 9.sp)
        results.take(8).forEach { message ->
            Text("${message.author} · ${message.time}\n${message.body}", Modifier.padding(horizontal = 19.dp, vertical = 6.dp), color = PersonaInk, fontSize = 9.sp, maxLines = 3)
        }
    }
}

@Composable private fun DatePanel(onClose: () -> Unit) {
    Column(Modifier.navigationBarsPadding().padding(bottom = 22.dp)) {
        SheetHeader("按日期跳转", "日期索引尚未接入本地消息仓库。", onClose)
        EmptyStatusPage("待接入：当前不会生成虚假日期、卷册或跳转结果。")
    }
}

@Composable private fun BookshelfPanel(onClose: () -> Unit) {
    Column(Modifier.navigationBarsPadding().padding(bottom = 22.dp)) {
        SheetHeader("聊天书架", "卷册索引尚未接入。", onClose)
        EmptyStatusPage("待接入：本页不会伪造旧消息数量、日期范围或摘要。当前 Thread 的真实本地正文仍可在聊天页查看。")
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
                Text(titles[page], color = PersonaInk, fontSize = 15.sp, fontWeight = FontWeight.Medium)
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
        Text("Usage、上下文阈值、压缩次数与 Provider 用量尚无稳定读取 contract。", Modifier.padding(vertical = 12.dp), color = PersonaInk, fontSize = 10.sp, lineHeight = 16.sp)
        Text("当前不会显示假 token 数、假轮次、假健康状态或假授权状态。Runtime / adapter 仅在真实 SSE 返回并写入本地消息后显示。", color = PersonaMuted, fontSize = 9.sp, lineHeight = 15.sp)
    }
}

@Composable private fun EmptyStatusPage(text: String) { Text(text, Modifier.padding(horizontal = 19.dp, vertical = 12.dp), color = PersonaMuted, fontSize = 10.sp, lineHeight = 17.sp) }

@Composable private fun IconGalleryPanel(onClose: () -> Unit) {
    Column(Modifier.fillMaxWidth().navigationBarsPadding()) {
        SheetHeader("LoveHouse Icon Gallery", "统一 24dp 画布；消息级图标使用 18dp compact optical size。", onClose)
        LoveHouseIconGallery(Modifier.fillMaxWidth())
    }
}
