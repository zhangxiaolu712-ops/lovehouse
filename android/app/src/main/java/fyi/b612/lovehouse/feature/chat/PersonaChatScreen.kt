package fyi.b612.lovehouse.feature.chat

import android.app.Activity
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
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
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
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
import fyi.b612.lovehouse.core.designsystem.LocalLoveHouseAppearance
import fyi.b612.lovehouse.core.designsystem.LoveHouseAppearance
import fyi.b612.lovehouse.core.designsystem.LoveHouseWallpaperLayer
import fyi.b612.lovehouse.core.capability.CapabilityAvailability
import fyi.b612.lovehouse.core.capability.LoveHouseCapabilityId
import fyi.b612.lovehouse.core.capability.LoveHouseCapabilityRegistry
import fyi.b612.lovehouse.core.capability.LoveHouseCapabilityState
import fyi.b612.lovehouse.core.capability.ProviderConsumption
import fyi.b612.lovehouse.core.storage.LocalStorage
import fyi.b612.lovehouse.core.capability.OneShotLocationProvider
import fyi.b612.lovehouse.feature.settings.ToolAvailability
import fyi.b612.lovehouse.feature.settings.ToolCapability
import fyi.b612.lovehouse.feature.settings.CapabilityRegistry
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.absoluteValue
import kotlin.math.roundToInt

private val PersonaInk = Color(0xFF3E4847)
private val PersonaMuted = Color(0xFF7F8B88)
private val PersonaAccent = Color(0xFF718E87)

internal const val CHAT_TEXT_SIZE_KEY = "chat_appearance_text_size_sp_v1"
internal const val CHAT_BUBBLE_SCALE_KEY = "chat_appearance_bubble_scale_v1"

internal data class ChatAppearanceSettings(
    val textSizeSp: Float = 14f,
    val bubbleScale: Float = .90f,
)

internal fun resolveChatAppearance(textSize: String?, bubbleScale: String?) = ChatAppearanceSettings(
    textSizeSp = textSize?.toFloatOrNull()?.coerceIn(12f, 18f) ?: 14f,
    bubbleScale = bubbleScale?.toFloatOrNull()?.coerceIn(.80f, 1.10f) ?: .90f,
)

private object ChatRhythm {
    val SameSenderSpacing = 8.dp
    val GroupSpacing = 24.dp
    val SegmentSpacing = 5.dp
}

internal fun chatMessageSpacing(sameSenderGroup: Boolean): Dp =
    if (sameSenderGroup) ChatRhythm.SameSenderSpacing else ChatRhythm.GroupSpacing

internal fun resolveRequestedToolIds(
    message: String,
    selectedToolIds: Set<String>,
    enabledCapabilities: List<ToolCapability>,
): Set<String> {
    val enabledIds = enabledCapabilities.mapTo(linkedSetOf()) { it.toolId }
    val requested = selectedToolIds.intersect(enabledIds).toMutableSet()
    val mentionedLabels = Regex("@([^\\s@，。！？,.!?]+)")
        .findAll(message)
        .map { it.groupValues[1] }
        .toList()
    enabledCapabilities.groupBy { it.group }.values.forEach { group ->
        val label = group.first().groupLabel
        if (mentionedLabels.any { it.equals(label, ignoreCase = true) }) {
            group.mapTo(requested) { it.toolId }
        }
    }
    return requested.toSortedSet()
}

internal fun composerUnavailableActions(
    runtime: ChatRuntimeConfig,
    baseCapabilities: LoveHouseCapabilityState? = null,
): Map<String, String> = buildMap {
    val attachmentActions = mapOf(
        "相机" to LoveHouseCapabilityId.AttachmentCamera,
        "照片" to LoveHouseCapabilityId.AttachmentPhoto,
        "文件" to LoveHouseCapabilityId.AttachmentFile,
        "定位" to LoveHouseCapabilityId.AttachmentLocation,
    )
    attachmentActions.forEach { (action, capabilityId) ->
        val capability = baseCapabilities?.capability(capabilityId)
        val providerSupport = capability?.providerConsumption?.get(runtime.personaId)
        if (!runtime.attachmentsEnabled || providerSupport == ProviderConsumption.Unsupported) {
            put(action, "${runtime.personaId.replaceFirstChar(Char::uppercase)} Runtime 当前未启用附件")
        }
    }
    if (!runtime.toolCenterEnabled) {
        put("工具", "${runtime.personaId.replaceFirstChar(Char::uppercase)} Runtime 当前未启用 Tool Center")
    }
}

private fun mediaProgressNotice(progress: MediaAttachmentProgress): String? {
    val prefix = if (progress.stage == MediaAttachmentStage.AllAttachmentsReady) {
        "全部 ${progress.total} 个附件"
    } else {
        "附件 ${progress.index}/${progress.total}"
    }
    return when (progress.stage) {
        MediaAttachmentStage.Selected -> "$prefix 已选择"
        MediaAttachmentStage.LocalMetadataReady -> "$prefix 本地信息已就绪"
        MediaAttachmentStage.TemporaryReferenceStarted -> "$prefix 正在建立临时媒体引用…"
        MediaAttachmentStage.PresignStarted -> "$prefix 正在申请上传许可…"
        MediaAttachmentStage.PresignCompleted -> "$prefix 上传许可已取得"
        MediaAttachmentStage.TemporaryReferenceCompleted -> "$prefix 临时媒体引用已建立"
        MediaAttachmentStage.PutStarted -> "$prefix 正在上传…"
        MediaAttachmentStage.PutCompleted -> "$prefix 上传完成"
        MediaAttachmentStage.AttachmentReady -> "$prefix 已就绪"
        MediaAttachmentStage.Failed -> "$prefix 失败"
        MediaAttachmentStage.AllAttachmentsReady -> "$prefix 已就绪"
        MediaAttachmentStage.Finished -> null
    }
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
    val globalAppearance: LoveHouseAppearance = LoveHouseAppearance(),
    val followsGlobalWallpaper: Boolean = false,
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
    val panelGlass: Color = paleTint.copy(alpha = .42f)
    val popupGlass: Color = paleTint.copy(alpha = .56f)
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

private enum class PersonaPanel { Detail, Search, DateJump, Bookshelf, Appearance, Status, Models, IconGallery, MemberPicker, ForwardTarget, WorkflowForward, ForwardBundle, AvatarPicker }
private enum class BubbleStyle(val title: String, val subtitle: String) {
    None("无气泡", "文字直接浮在壁纸上"),
    Soft("轻气泡", "参考图式柔软浅气泡"),
    Glass("玻璃", "双方都使用雾面玻璃"),
    Paper("纸片", "偏纸张/便签质感"),
}

@Composable
fun ChatShellScreen(
    threadId: String,
    store: ChatSessionStore,
    localStorage: LocalStorage,
    capabilityRegistry: CapabilityRegistry,
    baseCapabilities: LoveHouseCapabilityRegistry,
    mediaAttachments: MediaAttachmentClient,
    chatConnections: ChatConnectionStore,
    chatConnectionProbe: ChatConnectionProbe,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val thread = store.thread(threadId) ?: return
    val messages = store.messages(threadId)
    val context = LocalContext.current
    val wallpaperScope = rememberCoroutineScope()
    var panel by remember { mutableStateOf<PersonaPanel?>(null) }
    var bubbleStyle by remember { mutableStateOf(BubbleStyle.Soft) }
    val globalWallpaper by localStorage.observeString(APPEARANCE_WALLPAPER_KEY).collectAsState(initial = null)
    val chatTextSize by localStorage.observeString(CHAT_TEXT_SIZE_KEY).collectAsState(initial = null)
    val chatBubbleScale by localStorage.observeString(CHAT_BUBBLE_SCALE_KEY).collectAsState(initial = null)
    val chatAppearance = resolveChatAppearance(chatTextSize, chatBubbleScale)
    val globalAppearance = LocalLoveHouseAppearance.current
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
        globalAppearance = globalAppearance,
        followsGlobalWallpaper = chatWallpaperOverride == null && threadWallpaperPath == null,
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
    val isCodexRuntime = threadId == "agent-codex"
    val isClaudeRuntime = threadId == ClaudeRuntime.threadId
    val isRuntimeThread = isCodexRuntime || isClaudeRuntime
    val baseCapabilityState by baseCapabilities.state.collectAsState()
    val unavailableComposerActions = composerUnavailableActions(
        if (isClaudeRuntime) ClaudeRuntime else CodexRuntime,
        baseCapabilityState,
    )
    val selectedModel = when {
        isCodexRuntime -> actualRuntimeLabel
        isClaudeRuntime -> "${ClaudeRuntime.expectedRuntime} · ${ClaudeRuntime.expectedAdapterId}"
        else -> "Runtime 尚未接入"
    }
    var input by remember { mutableStateOf("") }
    var pendingAttachments by remember(threadId) { mutableStateOf<List<ChatAttachment>>(emptyList()) }
    var uploadingAttachments by remember(threadId) { mutableStateOf(false) }
    var requestedToolIds by remember(threadId) { mutableStateOf<Set<String>>(emptySet()) }
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
    val capabilityState by capabilityRegistry.state.collectAsState()
    val eligibleTools = if (isCodexRuntime) capabilityState.enabledCapabilities.distinctBy { it.group } else emptyList()
    val locationReader = remember(context.applicationContext) { OneShotLocationProvider(context.applicationContext) }
    DisposableEffect(locationReader) { onDispose { locationReader.cancel() } }
    val captureLocation: () -> Unit = {
        actionNotice = "正在获取一次当前位置…"
        locationReader.request { result ->
            result.snapshot?.let { snapshot ->
                pendingAttachments = pendingAttachments.filterNot { it is ChatLocationAttachment } +
                    ChatLocationAttachment(
                        latitude = snapshot.latitude,
                        longitude = snapshot.longitude,
                        accuracyMeters = snapshot.accuracyMeters,
                        capturedAtEpochMillis = snapshot.capturedAtEpochMillis,
                    )
            }
            actionNotice = result.message.takeIf { result.snapshot == null }
        }
    }
    val attachmentScope = rememberCoroutineScope()
    val attachmentPhoto = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) actionNotice = "没有选择照片" else attachmentScope.launch {
            uploadingAttachments = true
            actionNotice = "正在保存 ${uris.size} 张照片到本地草稿…"
            uris.forEach { runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
            runCatching { mediaAttachments.importLocal(uris, "photo") }
                .onSuccess { imported ->
                    pendingAttachments = pendingAttachments + imported
                    actionNotice = "${imported.size} 张照片已保存为本地草稿"
                }
                .onFailure { actionNotice = it.message ?: "照片导入失败" }
            uploadingAttachments = false
        }
    }
    val attachmentFile = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) actionNotice = "没有选择文件" else attachmentScope.launch {
            uploadingAttachments = true
            actionNotice = "正在保存 ${uris.size} 个文件到本地草稿…"
            uris.forEach { runCatching { context.contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
            runCatching { mediaAttachments.importLocal(uris, "file") }
                .onSuccess { imported ->
                    pendingAttachments = pendingAttachments + imported
                    actionNotice = "${imported.size} 个文件已保存为本地草稿"
                }
                .onFailure { actionNotice = it.message ?: "文件导入失败" }
            uploadingAttachments = false
        }
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        baseCapabilities.refresh()
        if (grants.values.any { it }) captureLocation()
        else actionNotice = "定位权限未授予"
    }
    val attachmentAction: (String) -> Unit = { action ->
        when (action) {
            "照片" -> attachmentPhoto.launch(arrayOf("image/*"))
            "文件" -> attachmentFile.launch(arrayOf("*/*"))
            "相机" -> actionNotice = "相机原图 transport 本刀尚未接通，请先使用“照片”多选上传"
            "定位" -> if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            ) {
                captureLocation()
            } else locationPermission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
            "表情面板已切换" -> actionNotice = "表情面板尚未接入"
            "语音原音频不可用" -> actionNotice = "当前系统 STT 不提供可复用录音文件，且 Chat 音频附件 transport 尚未接通"
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
        if (sending || uploadingAttachments || (outgoing.isBlank() && pendingAttachments.isEmpty())) return@submit
        val toolsForTurn = if (isCodexRuntime) {
            resolveRequestedToolIds(
                message = outgoing,
                selectedToolIds = requestedToolIds,
                enabledCapabilities = capabilityState.enabledCapabilities,
            )
        } else {
            emptySet()
        }
        val localAttachmentsForTurn = pendingAttachments
        requestedToolIds = emptySet()
        pendingAttachments = emptyList()
        input = ""
        if (!isRuntimeThread) {
            if (localAttachmentsForTurn.isNotEmpty()) {
                pendingAttachments = localAttachmentsForTurn
                input = outgoing
                actionNotice = "当前窗口尚未接入真实附件 transport，未发送"
                return@submit
            }
            store.sendMessage(threadId, outgoing)
        } else {
            if (isClaudeRuntime && localAttachmentsForTurn.isNotEmpty()) {
                pendingAttachments = localAttachmentsForTurn
                input = outgoing
                actionNotice = "Claude Runtime 当前未启用附件"
                return@submit
            }
            sending = true
            chatScope.launch {
                try {
                    val attachmentsForTurn = if (isCodexRuntime) {
                        try {
                            mediaAttachments.makeEphemeral(localAttachmentsForTurn) { progress ->
                                withContext(Dispatchers.Main.immediate) {
                                    mediaProgressNotice(progress)?.let { actionNotice = it }
                                }
                            }
                        } catch (error: Exception) {
                            pendingAttachments = localAttachmentsForTurn
                            requestedToolIds = toolsForTurn
                            actionNotice = error.message ?: "媒体 transport 尚不可用"
                            return@launch
                        }
                    } else {
                        emptyList()
                    }
                    actionNotice = "正在连接 ${if (isClaudeRuntime) "Claude" else "Codex"}…"
                    Log.i(
                        "LoveHouseMedia",
                        "canonical_turn_send attachments=${attachmentsForTurn.size} tools=${toolsForTurn.size}",
                    )
                    val result = if (isClaudeRuntime) {
                        store.sendClaudeMessage(outgoing) { }
                    } else {
                        store.sendCodexMessage(threadId, outgoing, toolsForTurn, attachmentsForTurn) { }
                    }
                    result.onSuccess { response ->
                        actionNotice = response.evidence.toolCalls.lastOrNull()?.let { call ->
                            "${response.evidence.requestedToolIds.sorted().joinToString()} · MCP ${call.name} · ${call.status}"
                        }
                    }.onFailure { error ->
                        actionNotice = error.message ?: "发送失败"
                    }
                } finally {
                    sending = false
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
            PersonaPanel.Status, PersonaPanel.Models, PersonaPanel.IconGallery -> PersonaPanel.Detail
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
                            else -> chatMessageSpacing(!endsGroup)
                        }
                        MessageBubble(
                            message = message,
                            startsGroup = startsGroup,
                            spacingAfter = spacingAfter,
                            task = message.taskId?.let(store::task),
                            style = bubbleStyle,
                            appearance = chatAppearance,
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
                attachments = pendingAttachments,
                visualContext = visualContext,
                onValueChange = { input = it },
                onRemoveAttachment = { target -> pendingAttachments = pendingAttachments - target },
                onSend = { submitMessage(input) },
                onToolAction = { action ->
                    val unavailableReason = unavailableComposerActions[action]
                    if (unavailableReason != null) actionNotice = unavailableReason else attachmentAction(action)
                },
                    eligibleTools = eligibleTools,
                    sttAvailability = baseCapabilityState.capability(LoveHouseCapabilityId.VoiceStt)?.availability
                        ?: CapabilityAvailability.Unavailable,
                    sttUnavailableReason = baseCapabilityState.capability(LoveHouseCapabilityId.VoiceStt)?.unavailableReason,
                    onCapabilityRefresh = baseCapabilities::refresh,
                unavailableActions = unavailableComposerActions,
                onToolMention = { tool ->
                    val mention = "@${tool.groupLabel}"
                    if (!input.contains(mention)) input = listOf(mention, input).filter(String::isNotBlank).joinToString(" ")
                    val selectedForGroup = capabilityState.enabledCapabilities
                        .filter { it.group == tool.group }
                        .mapTo(linkedSetOf()) { it.toolId }
                    val merged = requestedToolIds + selectedForGroup
                    requestedToolIds = merged
                    actionNotice = "本轮请求 ${merged.sorted().joinToString()}；Bridge 仍会重新校验权限与作用域"
                },
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
                    appearance = chatAppearance,
                    chatConnections = chatConnections,
                    chatConnectionProbe = chatConnectionProbe,
                    onClose = { panel = if (panel == PersonaPanel.Detail) null else PersonaPanel.Detail },
                    onNavigate = { panel = it },
                    onBubble = { bubbleStyle = it; panel = PersonaPanel.Detail },
                    onTextSize = { value -> wallpaperScope.launch { localStorage.writeString(CHAT_TEXT_SIZE_KEY, value.toString()) } },
                    onBubbleScale = { value -> wallpaperScope.launch { localStorage.writeString(CHAT_BUBBLE_SCALE_KEY, value.toString()) } },
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
    if (visualContext.followsGlobalWallpaper) {
        LoveHouseWallpaperLayer(visualContext.globalAppearance)
        return
    }
    val backdrop = visualContext.wallpaper
    visualContext.customWallpaper?.let { image ->
        Image(image, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    } ?: Image(
        painterResource(if (backdrop == ChatBackdrop.Lavender) R.drawable.wallpaper_chat_lavender else R.drawable.wallpaper_default_green),
        contentDescription = null,
        modifier = Modifier.fillMaxSize(),
        contentScale = ContentScale.Crop,
    )
    val tintAlpha = if (visualContext.customWallpaper != null) .06f else .08f
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
    // Keep chat brightness aligned with ChatList/Persona list. Readability comes
    // from the bounded glass surfaces, not a page-wide dark or white scrim.
    Box(Modifier.fillMaxSize())
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

@Composable private fun ProcessTimeline(events: List<ChatProcessEvent>) {
    var expanded by remember { mutableStateOf(false) }
    val thinking = events.any { it.kind == ChatProcessKind.Thinking || it.kind == ChatProcessKind.ReasoningStatus }
    val running = events.any { it.status == ChatProcessStatus.Running }
    val title = when {
        thinking -> "思考过程"
        events.any { it.kind == ChatProcessKind.ToolCall || it.kind == ChatProcessKind.ToolResult || it.kind == ChatProcessKind.ToolError } -> "工具调用"
        else -> "执行过程"
    }
    Column(
        Modifier.padding(top = 5.dp, bottom = 4.dp).animateContentSize(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Row(
            Modifier.clip(RoundedCornerShape(8.dp)).clickable { expanded = !expanded }.padding(vertical = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LoveHouseIconView(
                if (thinking) LoveHouseIcon.Thinking else LoveHouseIcon.Wrench,
                null,
                Modifier.size(if (thinking) 16.dp else 14.dp),
                PersonaMuted,
                LoveHouseIconOpticalSize.Compact,
            )
            Text(title, Modifier.padding(start = 7.dp), color = PersonaInk.copy(alpha = .78f), fontSize = 10.sp)
            if (running) BreathingDots()
            LoveHouseIconView(
                if (expanded) LoveHouseIcon.Collapse else LoveHouseIcon.Expand,
                null,
                Modifier.padding(start = 5.dp).size(11.dp),
                PersonaMuted,
                LoveHouseIconOpticalSize.Compact,
            )
        }
        if (expanded) events.forEach { event ->
            Row(Modifier.padding(start = 2.dp), verticalAlignment = Alignment.Top) {
                LoveHouseIconView(
                    if (event.kind == ChatProcessKind.Thinking || event.kind == ChatProcessKind.ReasoningStatus) LoveHouseIcon.Thinking else LoveHouseIcon.Wrench,
                    null,
                    Modifier.padding(top = 1.dp).size(13.dp),
                    if (event.status == ChatProcessStatus.Failed) MaterialTheme.colorScheme.error.copy(alpha = .74f) else PersonaMuted,
                    LoveHouseIconOpticalSize.Compact,
                )
                Column(Modifier.padding(start = 7.dp).widthIn(max = 260.dp)) {
                    Text(event.title, color = PersonaInk.copy(alpha = .76f), fontSize = 9.sp)
                    event.detail?.let { detail -> Text(detail, Modifier.padding(top = 2.dp), color = PersonaMuted, fontSize = 8.5.sp, lineHeight = 12.sp) }
                }
            }
        }
    }
}

@Composable
private fun BreathingDots() {
    val transition = rememberInfiniteTransition(label = "thinking-dots")
    val alpha by transition.animateFloat(
        initialValue = .28f,
        targetValue = .82f,
        animationSpec = infiniteRepeatable(tween(760), RepeatMode.Reverse),
        label = "thinking-dots-alpha",
    )
    Text(" ···", color = PersonaMuted.copy(alpha = alpha), fontSize = 9.sp)
}

@Composable private fun MessageBubble(
    message: ChatMessageUi,
    startsGroup: Boolean,
    spacingAfter: Dp,
    task: RemoteAgentTask?,
    style: BubbleStyle,
    appearance: ChatAppearanceSettings,
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
            BubbleStyle.Soft -> if (message.mine) Color(0xFFE7F0EC).copy(alpha = .68f) else Color.White.copy(alpha = .58f)
            BubbleStyle.Glass -> Color.White.copy(alpha = .48f)
            BubbleStyle.Paper -> Color(0xFFF6F0E2).copy(alpha = .92f)
        }
        if (!message.mine) { if (startsGroup) MessageAvatar(message) else Spacer(Modifier.size(30.dp)) }
        Column(
            modifier = Modifier.fillMaxWidth((.78f * appearance.bubbleScale).coerceIn(.62f, .86f)).padding(horizontal = 7.dp).animateContentSize(),
            horizontalAlignment = if (message.mine) Alignment.End else Alignment.Start,
        ) {
            if (startsGroup) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
                if (message.mine) Text(message.time, color = PersonaMuted, fontSize = 7.5.sp)
                Text(message.author, color = if (message.mine) PersonaMuted else PersonaAccent, fontSize = 8.5.sp, fontWeight = FontWeight.Medium)
                if (!message.mine) Text(message.time, color = PersonaMuted, fontSize = 7.5.sp)
            }
            if (!message.mine && message.processEvents.isNotEmpty()) {
                ProcessTimeline(message.processEvents)
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
            val bubbleBorder = when {
                selected -> BorderStroke(1.5.dp, PersonaAccent)
                style != BubbleStyle.None -> BorderStroke(.8.dp, Color.White.copy(alpha = .62f))
                else -> null
            }
            if (message.kind == ChatMessageKind.Text) {
                Column(
                    Modifier.padding(top = if (startsGroup) 1.dp else 0.dp),
                    verticalArrangement = Arrangement.spacedBy(ChatRhythm.SegmentSpacing),
                    horizontalAlignment = if (message.mine) Alignment.End else Alignment.Start,
                ) {
                    if (message.attachments.isNotEmpty()) {
                        MessageAttachmentSegments(message, color, bubbleBorder, bubbleModifier, appearance.bubbleScale)
                    }
                    message.body.takeIf(String::isNotBlank)?.naturalMessageSegments()?.forEach { segment ->
                        AnimatedVisibility(visible = true, enter = fadeIn(tween(140))) {
                            Surface(
                                modifier = bubbleModifier.animateContentSize(),
                                shape = RoundedCornerShape(18.dp * appearance.bubbleScale),
                                color = color,
                                border = bubbleBorder,
                            ) {
                                Text(
                                    segment,
                                    Modifier.padding(
                                        horizontal = 16.dp * appearance.bubbleScale,
                                        vertical = 12.dp * appearance.bubbleScale,
                                    ),
                                    color = PersonaInk,
                                    fontSize = appearance.textSizeSp.sp,
                                    lineHeight = (appearance.textSizeSp * 1.5f).sp,
                                )
                            }
                        }
                    }
                    message.deliveryError?.let { error ->
                        Text(
                            error,
                            Modifier.padding(top = 3.dp, start = 2.dp, end = 2.dp),
                            color = MaterialTheme.colorScheme.error.copy(alpha = .82f),
                            fontSize = 8.sp,
                            lineHeight = 11.sp,
                        )
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
    attachments: List<ChatAttachment>,
    visualContext: ChatVisualContext,
    onValueChange: (String) -> Unit,
    onRemoveAttachment: (ChatAttachment) -> Unit,
    onSend: () -> Unit,
    onToolAction: (String) -> Unit,
    eligibleTools: List<ToolCapability>,
    sttAvailability: CapabilityAvailability,
    sttUnavailableReason: String?,
    onCapabilityRefresh: () -> Unit,
    unavailableActions: Map<String, String>,
    onToolMention: (ToolCapability) -> Unit,
    onHeightChanged: () -> Unit,
) {
    val context = LocalContext.current
    val density = LocalDensity.current
    val imeVisible = WindowInsets.ime.getBottom(density) > 0
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    var showAttachments by remember { mutableStateOf(false) }
    var attachmentToolMode by remember { mutableStateOf(false) }
    var inputFocused by remember { mutableStateOf(false) }
    var composerExpanded by remember { mutableStateOf(false) }
    var imeShownDuringCurrentFocus by remember { mutableStateOf(false) }
    var voiceState by remember { mutableStateOf(ChatVoiceInputState()) }
    var voiceMode by remember { mutableStateOf(ChatVoiceComposerMode.Text) }
    val voiceAvailable = sttAvailability == CapabilityAvailability.Available
    val voiceController = remember(context, voiceAvailable) {
        if (voiceAvailable) ChatVoiceInputController(context) { voiceState = it } else null
    }
    DisposableEffect(voiceController) { onDispose { voiceController?.destroy() } }
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        onCapabilityRefresh()
        if (!granted) voiceState = ChatVoiceInputState(error = "需要麦克风权限才能语音输入")
    }
    val startVoiceInput: () -> Unit = {
        when {
            ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED -> {
                voiceState = ChatVoiceInputState(error = "授权后请再次按住说话")
                microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
            }
            !voiceAvailable -> voiceState = ChatVoiceInputState(error = sttUnavailableReason ?: STT_UNAVAILABLE_MESSAGE)
            else -> {
                voiceMode = transitionVoiceComposer(voiceMode, ChatVoiceComposerAction.Press)
                voiceController?.start()
            }
        }
        Unit
    }
    LaunchedEffect(voiceState.finished, voiceState.transcript) {
        voiceState.composerTranscriptOrNull()?.let { transcript ->
            onValueChange(transcript)
            voiceMode = transitionVoiceComposer(
                voiceMode,
                ChatVoiceComposerAction.ReleaseWithTranscript,
                hasTranscript = true,
            )
            voiceController?.dismiss()
        }
    }
    LaunchedEffect(value) {
        if (value.isBlank() && voiceMode == ChatVoiceComposerMode.Review) {
            voiceMode = transitionVoiceComposer(voiceMode, ChatVoiceComposerAction.SendOrClear)
        }
    }
    LaunchedEffect(voiceState.listening, voiceState.processing) {
        if (voiceState.listening || voiceState.processing) {
            focusManager.clearFocus(force = true)
            keyboardController?.hide()
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
        modifier = Modifier.fillMaxWidth().onSizeChanged { onHeightChanged() }.padding(
            start = 12.dp,
            end = 12.dp,
            top = 2.dp,
            bottom = if (imeVisible) 2.dp else 14.dp,
        ),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            attachments.forEach { attachment ->
                ChatAttachmentDraft(attachment, visualContext) { onRemoveAttachment(attachment) }
            }
            if (voiceMode == ChatVoiceComposerMode.Review) {
                VoiceDraftReview(
                    transcript = value,
                    onDismiss = {
                        voiceMode = transitionVoiceComposer(voiceMode, ChatVoiceComposerAction.Cancel)
                        onValueChange("")
                    },
                )
            } else if (voiceState.listening || voiceState.processing || voiceState.transcript.isNotBlank() || voiceState.error != null) {
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
                                    voiceState.finished && voiceState.transcript.isNotBlank() -> "语音转写"
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
                        voiceState.error?.let { error ->
                            Text(error, Modifier.padding(top = 3.dp), color = PersonaMuted, fontSize = 8.5.sp)
                        }
                        if (voiceState.listening) {
                            Text("松开结束语音输入", Modifier.padding(top = 3.dp), color = PersonaMuted, fontSize = 8.5.sp)
                        }
                    }
                }
            }
            AnimatedVisibility(
                visible = showAttachments,
                enter = expandVertically(expandFrom = Alignment.Bottom) + fadeIn(),
                exit = shrinkVertically(shrinkTowards = Alignment.Bottom) + fadeOut(),
            ) {
                AttachmentBottomSheet(
                    visualContext = visualContext,
                    toolMode = attachmentToolMode,
                    onToolModeChange = { attachmentToolMode = it },
                    onDismiss = { showAttachments = false; attachmentToolMode = false },
                    onToolAction = onToolAction,
                    eligibleTools = eligibleTools,
                    unavailableActions = unavailableActions,
                    onToolMention = onToolMention,
                )
            }
            Surface(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(17.dp),
                color = Color.White.copy(alpha = .30f),
                border = BorderStroke(.6.dp, Color.White.copy(alpha = .46f)),
            ) {
                val composerTextStyle = androidx.compose.ui.text.TextStyle(color = PersonaInk, fontSize = 12.sp, lineHeight = 17.sp)
                ComposerContentLayout(
                    modifier = Modifier.padding(horizontal = 5.dp),
                    expanded = composerExpanded && voiceMode != ChatVoiceComposerMode.VoiceReady && voiceMode != ChatVoiceComposerMode.Listening,
                    attachment = {
                        ComposerAttachmentButton(
                            expanded = showAttachments,
                            onExpandedChange = { showAttachments = it; if (!it) attachmentToolMode = false },
                        )
                    },
                    textField = {
                        if (voiceMode == ChatVoiceComposerMode.VoiceReady || voiceMode == ChatVoiceComposerMode.Listening) {
                            PushToTalkArea(
                                active = voiceState.listening || voiceState.processing,
                                onPressStart = startVoiceInput,
                                onPressEnd = { voiceController?.stop(); Unit },
                            )
                        } else BasicTextField(
                            value = value,
                            onValueChange = onValueChange,
                            modifier = Modifier.fillMaxWidth().heightIn(min = 36.dp, max = 96.dp)
                                .onFocusChanged { focusState ->
                                    if (focusState.isFocused && !inputFocused) composerExpanded = true
                                    if (!focusState.isFocused) composerExpanded = false
                                    inputFocused = focusState.isFocused
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
                    send = {
                        if (voiceMode == ChatVoiceComposerMode.VoiceReady || voiceMode == ChatVoiceComposerMode.Listening) {
                            ChatIconButton(LoveHouseIcon.Keyboard, "返回文字输入", touchSize = 40.dp) {
                                voiceController?.dismiss()
                                voiceState = ChatVoiceInputState()
                                voiceMode = transitionVoiceComposer(voiceMode, ChatVoiceComposerAction.Cancel)
                            }
                        } else if (value.isBlank() && attachments.isEmpty()) {
                            ChatIconButton(LoveHouseIcon.Mic, "进入语音输入", touchSize = 40.dp) {
                                focusManager.clearFocus(force = true)
                                keyboardController?.hide()
                                voiceMode = transitionVoiceComposer(voiceMode, ChatVoiceComposerAction.EnterVoice)
                            }
                        } else {
                            PawSendButton(enabled = true, onClick = onSend)
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun ComposerContentLayout(
    modifier: Modifier = Modifier,
    expanded: Boolean,
    attachment: @Composable () -> Unit,
    textField: @Composable () -> Unit,
    emoji: @Composable () -> Unit,
    send: @Composable () -> Unit,
) {
    Layout(
        modifier = modifier.fillMaxWidth(),
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
    onExpandedChange: (Boolean) -> Unit,
) {
    ChatIconButton(LoveHouseIcon.Plus, "添加附件", touchSize = 34.dp) { onExpandedChange(!expanded) }
}

@Composable
private fun AttachmentBottomSheet(
    visualContext: ChatVisualContext,
    toolMode: Boolean,
    onToolModeChange: (Boolean) -> Unit,
    onDismiss: () -> Unit,
    onToolAction: (String) -> Unit,
    eligibleTools: List<ToolCapability>,
    unavailableActions: Map<String, String>,
    onToolMention: (ToolCapability) -> Unit,
) {
    val rows = if (toolMode) eligibleTools.map { LoveHouseIcon.Wrench to "@${it.groupLabel}" } else listOf(
        LoveHouseIcon.Camera to "相机", LoveHouseIcon.Photo to "照片", LoveHouseIcon.File to "文件",
        LoveHouseIcon.Location to "定位", LoveHouseIcon.Wrench to "工具", LoveHouseIcon.More to "其他",
    )
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(topStart = 19.dp, topEnd = 19.dp, bottomStart = 13.dp, bottomEnd = 13.dp),
        color = visualContext.popupGlass,
        border = BorderStroke(.7.dp, visualContext.popupBorder),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(if (toolMode) "选择工具" else "添加到消息", Modifier.weight(1f), color = PersonaInk, fontSize = 10.sp, fontWeight = FontWeight.Medium)
                ChatIconButton(LoveHouseIcon.Close, "关闭", iconSize = 14.dp, touchSize = 30.dp, onClick = onDismiss)
            }
            if (toolMode && rows.isEmpty()) Text("没有已启用且可用的工具", color = PersonaMuted, fontSize = 9.sp)
            rows.chunked(3).forEach { rowItems ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    rowItems.forEach { (icon, item) ->
                        val unavailable = item in unavailableActions
                        Row(
                            Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(Color.White.copy(alpha = .22f)).clickable {
                                when {
                                    unavailable -> { onDismiss(); onToolAction(item) }
                                    item == "工具" -> onToolModeChange(true)
                                    toolMode -> {
                                        eligibleTools.firstOrNull { "@${it.groupLabel}" == item }?.let(onToolMention)
                                        onDismiss()
                                    }
                                    else -> { onDismiss(); onToolAction(item) }
                                }
                            }.padding(horizontal = 8.dp, vertical = 9.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            LoveHouseIconView(icon, null, Modifier.size(17.dp), if (unavailable) PersonaMuted.copy(alpha = .5f) else PersonaMuted)
                            Text(if (unavailable) "$item · 暂不可用" else item, color = if (unavailable) PersonaMuted else PersonaInk, fontSize = 9.5.sp, maxLines = 1)
                        }
                    }
                    repeat(3 - rowItems.size) { Spacer(Modifier.weight(1f)) }
                }
            }
            if (toolMode) Text("返回附件", Modifier.clickable { onToolModeChange(false) }.padding(vertical = 4.dp), color = PersonaAccent, fontSize = 9.sp)
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
private fun PersonaSheet(
    panel: PersonaPanel,
    thread: ChatThreadSummary,
    store: ChatSessionStore,
    model: String,
    bubble: BubbleStyle,
    visualContext: ChatVisualContext,
    appearance: ChatAppearanceSettings,
    chatConnections: ChatConnectionStore,
    chatConnectionProbe: ChatConnectionProbe,
    onClose: () -> Unit,
    onNavigate: (PersonaPanel) -> Unit,
    onBubble: (BubbleStyle) -> Unit,
    onTextSize: (Float) -> Unit,
    onBubbleScale: (Float) -> Unit,
    onBackdrop: (ChatBackdrop?) -> Unit,
    onPickCustomWallpaper: () -> Unit,
) {
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
                    else -> DirectDetailPanel(thread, model, onClose, onNavigate)
                }
                PersonaPanel.Appearance -> AppearancePanel(
                    current = bubble,
                    visualContext = visualContext,
                    appearance = appearance,
                    onClose = onClose,
                    onBubble = onBubble,
                    onTextSize = onTextSize,
                    onBubbleScale = onBubbleScale,
                    onBackdrop = onBackdrop,
                    onPickCustomWallpaper = onPickCustomWallpaper,
                )
                PersonaPanel.Search -> SearchPanel(thread, store.messages(thread.threadId), onClose)
                PersonaPanel.DateJump -> DatePanel(onClose)
                PersonaPanel.Bookshelf -> BookshelfPanel(onClose)
                PersonaPanel.Status -> StatusPanel(onClose)
                PersonaPanel.Models -> ModelConnectionsPanel(chatConnections, chatConnectionProbe, onClose)
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

@Composable private fun DirectDetailPanel(thread: ChatThreadSummary, model: String, onClose: () -> Unit, onNavigate: (PersonaPanel) -> Unit) {
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
            if (thread.threadId == "agent-codex") {
                DetailRow("新增 / 管理模型", "已接入 / 添加模型  ›") { onNavigate(PersonaPanel.Models) }
            } else {
                DetailRow("新增 / 管理模型", "当前 Persona 暂未支持")
            }
            Text("运行模型 · 只改变底层引擎，不改变人格、Thread、Memory 和书架", Modifier.padding(horizontal = 19.dp, vertical = 14.dp), color = PersonaMuted, fontSize = 9.sp, lineHeight = 14.sp)
            Text("这里保存并测试连接信息；Claude / Codex 正式聊天统一使用当前 /api/v1/chat 解耦链。", Modifier.padding(horizontal = 19.dp), color = PersonaMuted, fontSize = 9.sp, lineHeight = 14.sp)
        }
    }
}

@Composable
private fun ModelConnectionsPanel(
    store: ChatConnectionStore,
    probe: ChatConnectionProbe,
    onClose: () -> Unit,
) {
    val connections by store.connections.collectAsState()
    val scope = rememberCoroutineScope()
    var adding by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var endpoint by remember { mutableStateOf("") }
    var credential by remember { mutableStateOf("") }
    var result by remember { mutableStateOf<ChatConnectionTestResult?>(null) }
    var busy by remember { mutableStateOf(false) }
    var notice by remember { mutableStateOf<String?>(null) }

    LazyColumn(contentPadding = PaddingValues(bottom = 28.dp)) {
        item { SheetHeader("接入模型", "已接入 / + 添加模型", onClose) }
        if (!adding) {
            item {
                Text("已接入", Modifier.padding(horizontal = 19.dp, vertical = 7.dp), color = PersonaInk, fontSize = 11.sp, fontWeight = FontWeight.Medium)
            }
            if (connections.isEmpty()) {
                item { Text("尚未添加 Chat Connection", Modifier.padding(horizontal = 19.dp, vertical = 8.dp), color = PersonaMuted, fontSize = 9.sp) }
            }
            items(connections, key = { it.id }) { connection ->
                Column(
                    Modifier.fillMaxWidth().clickable { store.select(connection.id) }
                        .padding(horizontal = 19.dp, vertical = 10.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(connection.name, color = PersonaInk, fontSize = 11.sp, fontWeight = FontWeight.Medium)
                            Text(connection.endpoint, color = PersonaMuted, fontSize = 8.5.sp)
                        }
                        Text(if (connection.selected) "✓ 当前" else "选择", color = if (connection.selected) PersonaAccent else PersonaMuted, fontSize = 9.sp)
                    }
                    Text(
                        connection.lastResult ?: when (connection.status) {
                            ChatConnectionStatus.Connected -> "连接已测试"
                            ChatConnectionStatus.Failed -> "连接测试失败"
                            ChatConnectionStatus.Untested -> "尚未测试"
                        },
                        Modifier.padding(top = 3.dp),
                        color = PersonaMuted,
                        fontSize = 8.sp,
                    )
                    Text(
                        "删除",
                        Modifier.align(Alignment.End).clip(RoundedCornerShape(9.dp)).clickable { store.delete(connection.id) }
                            .padding(horizontal = 8.dp, vertical = 5.dp),
                        color = MaterialTheme.colorScheme.error.copy(alpha = .78f),
                        fontSize = 8.5.sp,
                    )
                }
            }
            item {
                Text(
                    "+ 添加模型",
                    Modifier.fillMaxWidth().clickable { adding = true }.padding(horizontal = 19.dp, vertical = 13.dp),
                    color = PersonaAccent,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                )
            }
        } else {
            item {
                Column(Modifier.padding(horizontal = 19.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
                    ChatConnectionField("名称", name, { name = it }, "例如：Codex 私有连接")
                    ChatConnectionField("URL", endpoint, { endpoint = normalizeChatConnectionEndpointInput(it) }, "https://chat.b612.fyi/v1/chat/codex")
                    ChatConnectionField("Key", credential, { credential = it }, "安全加密保存", secret = true)
                    result?.let { Text(it.message, color = if (it.succeeded) PersonaAccent else MaterialTheme.colorScheme.error, fontSize = 9.sp) }
                    notice?.let { Text(it, color = MaterialTheme.colorScheme.error, fontSize = 9.sp) }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                        Text("取消", Modifier.clip(RoundedCornerShape(10.dp)).clickable { adding = false }.padding(horizontal = 12.dp, vertical = 8.dp), color = PersonaMuted, fontSize = 10.sp)
                        Text(
                            if (busy) "测试中…" else "测试连接",
                            Modifier.clip(RoundedCornerShape(10.dp)).clickable(enabled = !busy) {
                                val draft = ChatConnectionDraft(name = name, endpoint = endpoint, credential = credential)
                                busy = true
                                notice = null
                                scope.launch {
                                    result = probe.test(draft)
                                    busy = false
                                }
                            }.padding(horizontal = 12.dp, vertical = 8.dp),
                            color = PersonaAccent,
                            fontSize = 10.sp,
                        )
                        Text(
                            "保存",
                            Modifier.clip(RoundedCornerShape(10.dp)).background(PersonaAccent.copy(alpha = .16f)).clickable {
                                runCatching {
                                    store.save(ChatConnectionDraft(name = name, endpoint = endpoint, credential = credential), result)
                                }.onSuccess { adding = false }.onFailure { notice = it.message }
                            }.padding(horizontal = 13.dp, vertical = 8.dp),
                            color = PersonaInk,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }
                    Text("Key 仅保存在 Android Keystore 加密的本机连接存储中，不进入 Chat History。", color = PersonaMuted, fontSize = 8.sp, lineHeight = 12.sp)
                }
            }
        }
    }
}

@Composable
private fun ChatConnectionField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    secret: Boolean = false,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = PersonaInk, fontSize = 9.5.sp)
        Surface(shape = RoundedCornerShape(13.dp), color = Color.White.copy(alpha = .36f), border = BorderStroke(.6.dp, Color.White.copy(alpha = .58f))) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp),
                textStyle = androidx.compose.ui.text.TextStyle(color = PersonaInk, fontSize = 11.sp),
                visualTransformation = if (secret) androidx.compose.ui.text.input.PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
                singleLine = true,
                decorationBox = { inner ->
                    Box { if (value.isEmpty()) Text(placeholder, color = PersonaMuted.copy(alpha = .72f), fontSize = 10.sp); inner() }
                },
            )
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

@Composable private fun AppearancePanel(
    current: BubbleStyle,
    visualContext: ChatVisualContext,
    appearance: ChatAppearanceSettings,
    onClose: () -> Unit,
    onBubble: (BubbleStyle) -> Unit,
    onTextSize: (Float) -> Unit,
    onBubbleScale: (Float) -> Unit,
    onBackdrop: (ChatBackdrop?) -> Unit,
    onPickCustomWallpaper: () -> Unit,
) {
    var textSize by remember { mutableStateOf(appearance.textSizeSp) }
    var bubbleScale by remember { mutableStateOf(appearance.bubbleScale) }
    LaunchedEffect(appearance.textSizeSp) { textSize = appearance.textSizeSp }
    LaunchedEffect(appearance.bubbleScale) { bubbleScale = appearance.bubbleScale }
    Column(Modifier.navigationBarsPadding().padding(bottom = 18.dp)) {
        SheetHeader("聊天外观", onClose = onClose)
        Text("文字与气泡尺寸由 Claude / Codex 共用并即时保存；聊天背景仍按当前窗口生效。", Modifier.padding(horizontal = 19.dp, vertical = 5.dp), color = PersonaMuted, fontSize = 9.sp, lineHeight = 14.sp)
        AppearanceSlider(
            title = "文字大小",
            valueLabel = "${textSize.roundToInt()}sp",
            value = textSize,
            valueRange = 12f..18f,
            steps = 5,
        ) { value ->
            textSize = value
            onTextSize(value)
        }
        AppearanceSlider(
            title = "气泡大小",
            valueLabel = "${(bubbleScale * 100).roundToInt()}%",
            value = bubbleScale,
            valueRange = .80f..1.10f,
            steps = 5,
        ) { value ->
            bubbleScale = value
            onBubbleScale(value)
        }
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 19.dp, vertical = 5.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            Surface(
                modifier = Modifier.widthIn(max = 240.dp * bubbleScale).animateContentSize(),
                shape = RoundedCornerShape(18.dp * bubbleScale),
                color = Color(0xFFE7F0EC).copy(alpha = .68f),
                border = BorderStroke(.8.dp, Color.White.copy(alpha = .62f)),
            ) {
                Text(
                    "聊天外观即时预览",
                    Modifier.padding(horizontal = 16.dp * bubbleScale, vertical = 12.dp * bubbleScale),
                    color = PersonaInk,
                    fontSize = textSize.sp,
                    lineHeight = (textSize * 1.5f).sp,
                )
            }
        }
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

@Composable
private fun AppearanceSlider(
    title: String,
    valueLabel: String,
    value: Float,
    valueRange: ClosedFloatingPointRange<Float>,
    steps: Int,
    onValueChange: (Float) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 19.dp, vertical = 5.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), color = PersonaInk, fontSize = 11.sp)
            Text(valueLabel, color = PersonaAccent, fontSize = 9.sp, fontWeight = FontWeight.Medium)
        }
        Slider(
            value = value,
            onValueChange = onValueChange,
            valueRange = valueRange,
            steps = steps,
            modifier = Modifier.fillMaxWidth().height(30.dp),
        )
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

@Composable
private fun MessageAttachmentSegments(
    message: ChatMessageUi,
    color: Color,
    border: BorderStroke?,
    messageModifier: Modifier,
    bubbleScale: Float,
) {
    attachmentSegments(message.attachments).forEach { segment ->
        when (segment.kind) {
            ChatAttachmentSegmentKind.Photos -> PhotoAttachmentBubble(segment.attachments.filterIsInstance<ChatMediaAttachment>(), color, border, messageModifier, bubbleScale)
            ChatAttachmentSegmentKind.Files -> FileAttachmentBubble(message.messageId, segment.attachments.filterIsInstance<ChatMediaAttachment>(), color, border, messageModifier, bubbleScale)
            ChatAttachmentSegmentKind.Location -> LocationAttachmentBubble(segment.attachments.single() as ChatLocationAttachment, color, border, messageModifier, bubbleScale)
            ChatAttachmentSegmentKind.Audio -> AttachmentUnavailableBubble("语音附件 transport 尚未接通", color, border, bubbleScale)
        }
    }
}

@Composable
private fun PhotoAttachmentBubble(
    photos: List<ChatMediaAttachment>,
    color: Color,
    border: BorderStroke?,
    messageModifier: Modifier,
    bubbleScale: Float,
) {
    val pagerState = rememberPagerState(pageCount = { photos.size })
    Box(Modifier.width(232.dp * bubbleScale).height(164.dp * bubbleScale)) {
        if (photos.size > 1) {
            Surface(
                Modifier.fillMaxSize().padding(start = 14.dp, top = 8.dp),
                RoundedCornerShape(15.dp), color.copy(alpha = .56f), border = border,
            ) {}
            Surface(
                Modifier.fillMaxSize().padding(start = 7.dp, top = 4.dp, end = 7.dp, bottom = 4.dp),
                RoundedCornerShape(15.dp), color.copy(alpha = .72f), border = border,
            ) {}
        }
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.fillMaxSize().padding(end = if (photos.size > 1) 14.dp else 0.dp),
        ) { page ->
            val attachment = photos[page]
            val availability = attachment.resolvedAvailability()
            val image by produceState<ImageBitmap?>(null, attachment.localCachePath) {
                value = withContext(Dispatchers.IO) {
                    attachment.localCachePath?.takeIf { availability == ChatAttachmentAvailability.AVAILABLE }
                        ?.let(BitmapFactory::decodeFile)?.asImageBitmap()
                }
            }
            Surface(messageModifier.fillMaxSize(), RoundedCornerShape(15.dp), color, border = border) {
                if (image != null) Image(
                    bitmap = image!!,
                    contentDescription = attachment.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                ) else Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(attachmentAvailabilityLabel(availability, "照片 · ${attachment.name}"), color = PersonaMuted, fontSize = 9.sp, maxLines = 2)
                }
            }
        }
        if (photos.size > 1) Surface(
            modifier = Modifier.align(Alignment.TopStart).padding(6.dp),
            shape = CircleShape,
            color = Color.Black.copy(alpha = .38f),
        ) { Text("${pagerState.currentPage + 1}/${photos.size}", Modifier.padding(horizontal = 7.dp, vertical = 3.dp), color = Color.White, fontSize = 8.sp) }
    }
}

@Composable
private fun FileAttachmentBubble(
    messageId: String,
    files: List<ChatMediaAttachment>,
    color: Color,
    border: BorderStroke?,
    messageModifier: Modifier,
    bubbleScale: Float,
) {
    var expanded by remember(messageId) { mutableStateOf(false) }
    Surface(
        modifier = messageModifier.animateContentSize().clickable(enabled = files.size > 1) { expanded = !expanded },
        shape = RoundedCornerShape(15.dp * bubbleScale), color = color, border = border,
    ) {
        Column(
            Modifier.padding(horizontal = 11.dp * bubbleScale, vertical = 8.dp * bubbleScale),
            verticalArrangement = Arrangement.spacedBy(7.dp * bubbleScale),
        ) {
            (if (expanded) files else files.take(1)).forEachIndexed { index, file ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                    if (index == 0 && files.size > 1) Surface(shape = CircleShape, color = PersonaAccent.copy(alpha = .18f)) {
                        Text(files.size.toString(), Modifier.padding(horizontal = 6.dp, vertical = 3.dp), color = PersonaAccent, fontSize = 8.dp.value.sp)
                    }
                    LoveHouseIconView(LoveHouseIcon.File, null, Modifier.size(16.dp), PersonaAccent)
                    Column(Modifier.widthIn(max = 210.dp)) {
                        Text(file.name, color = PersonaInk, fontSize = 10.sp, maxLines = 1)
                        Text(
                            "${formatAttachmentSize(file.sizeBytes)} · ${attachmentAvailabilityLabel(file.resolvedAvailability(), file.lifecycle.name)}",
                            color = PersonaMuted,
                            fontSize = 8.sp,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AttachmentUnavailableBubble(label: String, color: Color, border: BorderStroke?, bubbleScale: Float) {
    Surface(shape = RoundedCornerShape(15.dp * bubbleScale), color = color, border = border) {
        Text(label, Modifier.padding(horizontal = 11.dp * bubbleScale, vertical = 8.dp * bubbleScale), color = PersonaMuted, fontSize = 9.sp)
    }
}

private fun attachmentAvailabilityLabel(
    availability: ChatAttachmentAvailability,
    availableLabel: String,
): String = when (availability) {
    ChatAttachmentAvailability.AVAILABLE -> availableLabel
    ChatAttachmentAvailability.UPLOADING -> "正在建立临时引用"
    ChatAttachmentAvailability.FAILED -> "发送失败"
    ChatAttachmentAvailability.EXPIRED -> "已过期"
    ChatAttachmentAvailability.LOCAL_MISSING -> "本地文件不可用"
    ChatAttachmentAvailability.UNAVAILABLE -> "暂不可用"
}

@Composable
private fun LocationAttachmentBubble(
    location: ChatLocationAttachment,
    color: Color,
    border: BorderStroke?,
    messageModifier: Modifier,
    bubbleScale: Float,
) {
    Surface(messageModifier, RoundedCornerShape(15.dp * bubbleScale), color, border = border) {
        Row(Modifier.padding(horizontal = 11.dp * bubbleScale, vertical = 8.dp * bubbleScale), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp * bubbleScale)) {
            LoveHouseIconView(LoveHouseIcon.Location, null, Modifier.size(17.dp), PersonaAccent)
            Column {
                Text(location.address ?: "位置快照", color = PersonaInk, fontSize = 10.sp)
                Text(location.displaySummary(), color = PersonaMuted, fontSize = 8.sp)
            }
        }
    }
}

private fun formatAttachmentSize(bytes: Long): String = when {
    bytes < 1_024 -> "$bytes B"
    bytes < 1_048_576 -> String.format(java.util.Locale.CHINA, "%.1f KB", bytes / 1_024.0)
    else -> String.format(java.util.Locale.CHINA, "%.1f MB", bytes / 1_048_576.0)
}

@Composable
private fun ChatAttachmentDraft(
    attachment: ChatAttachment,
    visualContext: ChatVisualContext,
    onRemove: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = visualContext.popupGlass,
        border = BorderStroke(.5.dp, visualContext.popupBorder),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            Modifier.padding(start = 11.dp, end = 5.dp, top = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            val icon = when (attachment) {
                is ChatLocationAttachment -> LoveHouseIcon.Location
                is ChatMediaAttachment -> if (attachment.type == "photo") LoveHouseIcon.Photo else LoveHouseIcon.File
            }
            LoveHouseIconView(icon, null, Modifier.size(16.dp), PersonaAccent)
            Column(Modifier.weight(1f)) {
                Text(
                    when (attachment.type) { "photo" -> "照片"; "file" -> "文件"; else -> "当前位置" },
                    color = PersonaInk, fontSize = 10.sp, fontWeight = FontWeight.Medium,
                )
                Text(
                    when (attachment) {
                        is ChatMediaAttachment -> attachmentAvailabilityLabel(attachment.resolvedAvailability(), attachment.displaySummary())
                        else -> attachment.displaySummary()
                    },
                    color = PersonaMuted,
                    fontSize = 8.5.sp,
                    maxLines = 1,
                )
            }
            ChatIconButton(LoveHouseIcon.Close, "移除附件", iconSize = 13.dp, touchSize = 30.dp, onClick = onRemove)
        }
    }
}

@Composable
private fun PushToTalkArea(
    active: Boolean,
    onPressStart: () -> Unit,
    onPressEnd: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .heightIn(min = 36.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.White.copy(alpha = if (active) .25f else .12f))
            .pointerInput(onPressStart, onPressEnd) {
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    down.consume()
                    onPressStart()
                    waitForUpOrCancellation()
                    onPressEnd()
                }
            }
            .semantics { contentDescription = "按住说话" },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (active) "松开结束" else "按住说话",
            color = if (active) PersonaAccent else PersonaInk.copy(alpha = .72f),
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun VoiceDraftReview(
    transcript: String,
    onDismiss: () -> Unit,
) {
    Surface(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        color = Color.White.copy(alpha = .22f),
        border = BorderStroke(.5.dp, Color.White.copy(alpha = .40f)),
        tonalElevation = 0.dp,
        shadowElevation = 0.dp,
    ) {
        Row(
            Modifier.padding(horizontal = 10.dp, vertical = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text("文字", color = PersonaAccent, fontSize = 9.sp, fontWeight = FontWeight.Medium)
            Text("语音 · 当前系统 STT 不提供原音频", Modifier.weight(1f), color = PersonaMuted, fontSize = 8.5.sp)
            ChatIconButton(LoveHouseIcon.Close, "取消语音草稿", iconSize = 13.dp, touchSize = 28.dp, onClick = onDismiss)
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
