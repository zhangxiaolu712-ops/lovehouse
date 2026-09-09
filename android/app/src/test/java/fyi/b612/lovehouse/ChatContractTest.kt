package fyi.b612.lovehouse

import fyi.b612.lovehouse.feature.chat.ChatListState
import fyi.b612.lovehouse.feature.chat.ChatRuntimeConfig
import fyi.b612.lovehouse.feature.chat.ChatMessageKind
import fyi.b612.lovehouse.feature.chat.ChatSessionStore
import fyi.b612.lovehouse.feature.chat.ClaudeRuntime
import fyi.b612.lovehouse.feature.chat.ChatThreadKind
import fyi.b612.lovehouse.feature.chat.MockChatRepository
import fyi.b612.lovehouse.feature.chat.LocalChatDeliveryStatus
import fyi.b612.lovehouse.feature.chat.LocalChatMessage
import fyi.b612.lovehouse.feature.chat.LocalChatMessageRepository
import fyi.b612.lovehouse.feature.chat.LocalChatRole
import fyi.b612.lovehouse.feature.chat.resolveChatWallpaperKey
import fyi.b612.lovehouse.feature.chat.resolveChatWallpaperPath
import fyi.b612.lovehouse.feature.chat.ChatVoiceInputState
import fyi.b612.lovehouse.feature.chat.composerTranscriptOrNull
import fyi.b612.lovehouse.feature.chat.ChatLocationAttachment
import fyi.b612.lovehouse.feature.chat.ChatMediaAttachment
import fyi.b612.lovehouse.feature.chat.buildCodexChatPayload
import fyi.b612.lovehouse.feature.chat.buildChatPayload
import fyi.b612.lovehouse.feature.chat.chatAttachmentsJson
import fyi.b612.lovehouse.feature.chat.attachmentSegments
import fyi.b612.lovehouse.feature.chat.ChatAttachmentSegmentKind
import fyi.b612.lovehouse.feature.chat.ChatAttachmentAvailability
import fyi.b612.lovehouse.feature.chat.ChatAttachmentLifecycle
import fyi.b612.lovehouse.feature.chat.ChatProcessEvent
import fyi.b612.lovehouse.feature.chat.ChatProcessKind
import fyi.b612.lovehouse.feature.chat.ChatProcessStatus
import fyi.b612.lovehouse.feature.chat.ChatVoiceComposerAction
import fyi.b612.lovehouse.feature.chat.ChatVoiceComposerMode
import fyi.b612.lovehouse.feature.chat.chatMessageSpacing
import fyi.b612.lovehouse.feature.chat.mergeProcessEvent
import fyi.b612.lovehouse.feature.chat.mergeThinkingText
import fyi.b612.lovehouse.feature.chat.resolveRequestedToolIds
import fyi.b612.lovehouse.feature.chat.transitionVoiceComposer
import fyi.b612.lovehouse.feature.chat.STT_UNAVAILABLE_MESSAGE
import fyi.b612.lovehouse.feature.settings.ToolAvailability
import fyi.b612.lovehouse.feature.settings.ToolCapability
import fyi.b612.lovehouse.feature.settings.ToolCapabilityKind
import fyi.b612.lovehouse.feature.settings.ToolRiskLevel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class ChatContractTest {
    private class DurableLocalMessages : LocalChatMessageRepository {
        private val rows = linkedMapOf<String, LocalChatMessage>()

        override fun messages(threadId: String): List<LocalChatMessage> =
            rows.values.filter { it.threadId == threadId }.sortedBy { it.createdAtEpochMillis }

        override fun upsert(message: LocalChatMessage) {
            rows[message.localMessageId] = message
        }
    }

    @Test
    fun `codex messages use one fixed LoveHouse thread and real runtime evidence`() = runBlocking {
        val observedThreads = mutableListOf<String>()
        val client = object : fyi.b612.lovehouse.feature.chat.CodexChatClient {
            override suspend fun streamMessage(threadId: String, message: String, requestedToolIds: Set<String>, attachments: List<fyi.b612.lovehouse.feature.chat.ChatAttachment>, onText: (String) -> Unit): fyi.b612.lovehouse.feature.chat.CodexChatResult {
                observedThreads += threadId
                onText("reply to $message")
                return fyi.b612.lovehouse.feature.chat.CodexChatResult(
                    "reply to $message",
                    fyi.b612.lovehouse.feature.chat.CodexRuntimeEvidence("codex_cli", "codex-cli-v1", threadId),
                )
            }
        }
        val store = ChatSessionStore(client)

        assertTrue(store.sendCodexMessage("agent-codex", "turn one") {}.isSuccess)
        assertTrue(store.sendCodexMessage("agent-codex", "turn two") {}.isSuccess)

        assertEquals(2, observedThreads.size)
        assertEquals(observedThreads.first(), observedThreads.last())
        assertTrue(store.messages("agent-codex").any { it.body == "reply to turn two" })
    }

    @Test
    fun `claude preserves web thread runtime boundary and does not persist process events`() = runBlocking {
        val repository = DurableLocalMessages()
        var observedConfig: ChatRuntimeConfig? = null
        val client = object : fyi.b612.lovehouse.feature.chat.CodexChatClient {
            override suspend fun streamMessage(
                threadId: String,
                message: String,
                requestedToolIds: Set<String>,
                attachments: List<fyi.b612.lovehouse.feature.chat.ChatAttachment>,
                onText: (String) -> Unit,
            ): fyi.b612.lovehouse.feature.chat.CodexChatResult = error("legacy Codex path must not handle Claude")

            override suspend fun streamRuntimeMessageWithProcess(
                config: ChatRuntimeConfig,
                message: String,
                requestedToolIds: Set<String>,
                attachments: List<fyi.b612.lovehouse.feature.chat.ChatAttachment>,
                onText: (String) -> Unit,
                onProcess: (ChatProcessEvent) -> Unit,
            ): fyi.b612.lovehouse.feature.chat.CodexChatResult {
                observedConfig = config
                onProcess(ChatProcessEvent("thinking", ChatProcessKind.Thinking, "Thinking", ChatProcessStatus.Running, "safe summary"))
                onText("Claude reply")
                return fyi.b612.lovehouse.feature.chat.CodexChatResult(
                    "Claude reply",
                    fyi.b612.lovehouse.feature.chat.CodexRuntimeEvidence("claude_cli", "claude-cli-v1", config.threadId),
                )
            }
        }
        val store = ChatSessionStore(client, repository)

        assertTrue(store.sendClaudeMessage("continue") {}.isSuccess)

        assertEquals(ClaudeRuntime, observedConfig)
        assertEquals(2, repository.messages(ClaudeRuntime.threadId).size)
        assertEquals("Claude reply", repository.messages(ClaudeRuntime.threadId).last().content)
        assertTrue(repository.messages(ClaudeRuntime.threadId).all { it.attachments.isEmpty() })
    }

    @Test
    fun `claude payload inherits fixed window and exposes neither tools nor attachments`() {
        val payload = buildChatPayload(ClaudeRuntime, "hello", emptySet(), emptyList())

        assertTrue(payload.contains("\"persona_id\":\"claude\""))
        assertTrue(payload.contains("\"thread_id\":\"${ClaudeRuntime.threadId}\""))
        assertTrue(payload.contains("\"window_id\":\"${ClaudeRuntime.windowId}\""))
        assertFalse(payload.contains("allowed_tool_ids"))
        assertFalse(payload.contains("attachments"))
    }

    @Test
    fun `real codex messages rehydrate as one canonical record per message`() = runBlocking {
        val repository = DurableLocalMessages()
        val observedThreads = mutableListOf<String>()
        var clock = 1_000L
        val client = object : fyi.b612.lovehouse.feature.chat.CodexChatClient {
            override suspend fun streamMessage(threadId: String, message: String, requestedToolIds: Set<String>, attachments: List<fyi.b612.lovehouse.feature.chat.ChatAttachment>, onText: (String) -> Unit): fyi.b612.lovehouse.feature.chat.CodexChatResult {
                observedThreads += threadId
                onText("first segment")
                onText("first segment\n\nfinal segment for $message")
                return fyi.b612.lovehouse.feature.chat.CodexChatResult(
                    "first segment\n\nfinal segment for $message",
                    fyi.b612.lovehouse.feature.chat.CodexRuntimeEvidence("codex_cli", "codex-cli-v1", threadId),
                )
            }
        }
        val firstStore = ChatSessionStore(client, repository, now = { clock++ })

        assertTrue(firstStore.sendCodexMessage("agent-codex", "turn one") {}.isSuccess)
        assertTrue(firstStore.sendCodexMessage("agent-codex", "turn two") {}.isSuccess)

        val persisted = repository.messages(observedThreads.singleDistinct())
        assertEquals(4, persisted.size)
        assertEquals(listOf(LocalChatRole.User, LocalChatRole.Assistant, LocalChatRole.User, LocalChatRole.Assistant), persisted.map { it.role })
        assertEquals(2, persisted.count { it.role == LocalChatRole.Assistant })
        assertTrue(persisted.all { it.status == LocalChatDeliveryStatus.Sent })
        assertTrue(persisted.filter { it.role == LocalChatRole.User }.all { it.runtime == null && it.adapterId == null })
        assertTrue(persisted.filter { it.role == LocalChatRole.Assistant }.all { it.runtime == "codex_cli" && it.adapterId == "codex-cli-v1" })

        val reopenedStore = ChatSessionStore(client, repository, now = { clock++ })
        assertEquals(4, reopenedStore.messages("agent-codex").size)
        assertEquals(2, reopenedStore.messages("agent-codex").count { it.body.contains("first segment") })
        assertTrue(reopenedStore.sendCodexMessage("agent-codex", "turn three") {}.isSuccess)
        assertEquals(6, repository.messages(observedThreads.singleDistinct()).size)
        assertEquals(1, observedThreads.distinct().size)
    }

    private fun <T> List<T>.singleDistinct(): T = distinct().single()

    @Test
    fun `voice transcript enters composer only after recognition finishes`() {
        assertNull(ChatVoiceInputState(listening = true, transcript = "正在识别").composerTranscriptOrNull())
        assertNull(ChatVoiceInputState(processing = true, transcript = "正在整理").composerTranscriptOrNull())
        assertEquals(
            "可以继续编辑的文字",
            ChatVoiceInputState(transcript = "  可以继续编辑的文字  ", finished = true).composerTranscriptOrNull(),
        )
    }

    @Test
    fun `location attachment transports real structured snapshot fields`() {
        val transport = chatAttachmentsJson(
            listOf(
                ChatLocationAttachment(
                    latitude = 31.230416,
                    longitude = 121.473701,
                    accuracyMeters = 12.5f,
                    capturedAtEpochMillis = 1_757_257_600_000L,
                ),
            ),
        )

        assertTrue(transport.contains("\"type\":\"location\""))
        assertTrue(transport.contains("\"latitude\":31.230416"))
        assertTrue(transport.contains("\"longitude\":121.473701"))
        assertTrue(transport.contains("\"accuracy\":12.5"))
        assertTrue(transport.contains("\"captured_at\":"))
        assertTrue(transport.startsWith("["))
    }

    @Test
    fun `location-only turn remains one canonical user message`() = runBlocking {
        val repository = DurableLocalMessages()
        var observedAttachments = emptyList<fyi.b612.lovehouse.feature.chat.ChatAttachment>()
        val client = object : fyi.b612.lovehouse.feature.chat.CodexChatClient {
            override suspend fun streamMessage(
                threadId: String,
                message: String,
                requestedToolIds: Set<String>,
                attachments: List<fyi.b612.lovehouse.feature.chat.ChatAttachment>,
                onText: (String) -> Unit,
            ): fyi.b612.lovehouse.feature.chat.CodexChatResult {
                observedAttachments = attachments
                onText("收到位置")
                return fyi.b612.lovehouse.feature.chat.CodexChatResult(
                    "收到位置",
                    fyi.b612.lovehouse.feature.chat.CodexRuntimeEvidence("codex_cli", "codex-cli-v1", threadId),
                )
            }
        }
        val store = ChatSessionStore(client, repository)
        val location = ChatLocationAttachment(31.2, 121.4, 8f, 1_757_257_600_000L)

        assertTrue(store.sendCodexMessage("agent-codex", "", attachments = listOf(location)) {}.isSuccess)

        assertEquals(listOf(location), observedAttachments)
        assertEquals(1, repository.messages(stableThread()).count { it.role == LocalChatRole.User })
        val persisted = repository.messages(stableThread()).single { it.role == LocalChatRole.User }
        assertEquals("", persisted.content)
        assertEquals(listOf(location), persisted.attachments)
    }

    @Test
    fun `attachment plus text persists and transports as one canonical user turn`() = runBlocking {
        val repository = DurableLocalMessages()
        var observedText = ""
        var observedAttachments = emptyList<fyi.b612.lovehouse.feature.chat.ChatAttachment>()
        val client = object : fyi.b612.lovehouse.feature.chat.CodexChatClient {
            override suspend fun streamMessage(
                threadId: String,
                message: String,
                requestedToolIds: Set<String>,
                attachments: List<fyi.b612.lovehouse.feature.chat.ChatAttachment>,
                onText: (String) -> Unit,
            ): fyi.b612.lovehouse.feature.chat.CodexChatResult {
                observedText = message
                observedAttachments = attachments
                onText("已读取")
                return fyi.b612.lovehouse.feature.chat.CodexChatResult(
                    "已读取",
                    fyi.b612.lovehouse.feature.chat.CodexRuntimeEvidence("codex_cli", "codex-cli-v1", threadId),
                )
            }
        }
        val attachment = ChatMediaAttachment("file", "88888888-8888-4888-8888-888888888888", "media/report.pdf", "application/pdf", 12, "report.pdf")
        val store = ChatSessionStore(client, repository)

        assertTrue(store.sendCodexMessage("agent-codex", "请读附件", attachments = listOf(attachment)) {}.isSuccess)

        val userRows = repository.messages(stableThread()).filter { it.role == LocalChatRole.User }
        assertEquals(1, userRows.size)
        assertEquals("请读附件", observedText)
        assertEquals(listOf(attachment), observedAttachments)
        assertEquals(listOf(attachment), userRows.single().attachments)
    }

    @Test
    fun `one payload keeps all media references and text in one turn`() {
        val attachments = listOf(
            ChatMediaAttachment("photo", "11111111-1111-4111-8111-111111111111", "media/owner/one.jpg", "image/jpeg", 3, "one.jpg"),
            ChatMediaAttachment("photo", "22222222-2222-4222-8222-222222222222", "media/owner/two.jpg", "image/jpeg", 4, "two.jpg"),
            ChatMediaAttachment("file", "33333333-3333-4333-8333-333333333333", "media/owner/notes.pdf", "application/pdf", 5, "notes.pdf"),
        )
        val payload = buildCodexChatPayload("thread", "看看全部附件", emptySet(), attachments)
        assertEquals(1, Regex("\\\"message\\\"\\s*:").findAll(payload).count())
        assertTrue(payload.contains("看看全部附件"))
        assertEquals(3, Regex("media_asset_id").findAll(payload).count())
    }

    @Test
    fun `one canonical turn maps multiple attachment types to visual segments without data loss`() {
        val attachments = listOf(
            ChatMediaAttachment("photo", "11111111-1111-4111-8111-111111111111", "media/one.jpg", "image/jpeg", 3, "one.jpg"),
            ChatMediaAttachment("photo", "22222222-2222-4222-8222-222222222222", "media/two.jpg", "image/jpeg", 4, "two.jpg"),
            ChatMediaAttachment("file", "33333333-3333-4333-8333-333333333333", "media/one.pdf", "application/pdf", 5, "one.pdf"),
            ChatMediaAttachment("file", "44444444-4444-4444-8444-444444444444", "media/two.pdf", "application/pdf", 6, "two.pdf"),
            ChatLocationAttachment(31.2, 121.4, 8f, 1_757_257_600_000L),
        )
        val segments = attachmentSegments(attachments)
        assertEquals(
            listOf(ChatAttachmentSegmentKind.Photos, ChatAttachmentSegmentKind.Files, ChatAttachmentSegmentKind.Location),
            segments.map { it.kind },
        )
        assertEquals(listOf(2, 2, 1), segments.map { it.attachments.size })
        assertEquals(attachments.toSet(), segments.flatMap { it.attachments }.toSet())
    }

    @Test
    fun `single and multi media groups retain every real attachment`() {
        val onePhoto = listOf(ChatMediaAttachment("photo", "11111111-1111-4111-8111-111111111111", "media/one.jpg", "image/jpeg", 3, "one.jpg"))
        val fourPhotos = (1..4).map { index ->
            ChatMediaAttachment("photo", "00000000-0000-4000-8000-00000000000$index", "media/$index.jpg", "image/jpeg", index.toLong(), "$index.jpg")
        }
        val oneFile = listOf(ChatMediaAttachment("file", "55555555-5555-4555-8555-555555555555", "media/one.pdf", "application/pdf", 5, "one.pdf"))
        val twoFiles = oneFile + ChatMediaAttachment("file", "66666666-6666-4666-8666-666666666666", "media/two.pdf", "application/pdf", 6, "two.pdf")
        assertEquals(1, attachmentSegments(onePhoto).single().attachments.size)
        assertEquals(4, attachmentSegments(fourPhotos).single().attachments.size)
        assertEquals(1, attachmentSegments(oneFile).single().attachments.size)
        assertEquals(2, attachmentSegments(twoFiles).single().attachments.size)
    }

    @Test
    fun `local media cannot masquerade as remotely transportable`() {
        val local = ChatMediaAttachment(
            type = "photo",
            mimeType = "image/jpeg",
            sizeBytes = 3,
            name = "local.jpg",
            localCachePath = "missing-local-file",
        )
        assertEquals(ChatAttachmentLifecycle.LOCAL, local.lifecycle)
        assertEquals(ChatAttachmentAvailability.LOCAL_MISSING, local.resolvedAvailability())
        assertTrue(runCatching { local.transportJson() }.isFailure)
    }

    @Test
    fun `expired and missing attachment availability remains truthful`() {
        val remote = ChatMediaAttachment(
            type = "file",
            mediaAssetId = "77777777-7777-4777-8777-777777777777",
            storageRef = "media/file.pdf",
            mimeType = "application/pdf",
            sizeBytes = 9,
            name = "file.pdf",
            remoteExpiresAtEpochMillis = 10,
        )
        assertEquals(ChatAttachmentAvailability.EXPIRED, remote.resolvedAvailability(nowEpochMillis = 11))
    }

    @Test
    fun `voice composer enters hold mode and only creates review with a real transcript`() {
        val ready = transitionVoiceComposer(ChatVoiceComposerMode.Text, ChatVoiceComposerAction.EnterVoice)
        val listening = transitionVoiceComposer(ready, ChatVoiceComposerAction.Press)
        val noResult = transitionVoiceComposer(listening, ChatVoiceComposerAction.ReleaseWithTranscript, hasTranscript = false)
        val review = transitionVoiceComposer(listening, ChatVoiceComposerAction.ReleaseWithTranscript, hasTranscript = true)
        assertEquals(ChatVoiceComposerMode.VoiceReady, ready)
        assertEquals(ChatVoiceComposerMode.Listening, listening)
        assertEquals(ChatVoiceComposerMode.VoiceReady, noResult)
        assertEquals(ChatVoiceComposerMode.Review, review)
        assertEquals(ChatVoiceComposerMode.Text, transitionVoiceComposer(review, ChatVoiceComposerAction.SendOrClear))
        assertEquals("当前设备没有可用的系统语音识别服务", STT_UNAVAILABLE_MESSAGE)
    }

    @Test
    fun `tool process preserves event order while result updates its original node`() {
        val started = ChatProcessEvent("tool:engineering", ChatProcessKind.ToolCall, "读取 Engineering", ChatProcessStatus.Running)
        val other = ChatProcessEvent("workflow", ChatProcessKind.WorkflowStatus, "执行状态", ChatProcessStatus.Running)
        val completed = started.copy(kind = ChatProcessKind.ToolResult, status = ChatProcessStatus.Succeeded, detail = "revision 12")
        val events = mergeProcessEvent(mergeProcessEvent(mergeProcessEvent(emptyList(), started), other), completed)
        assertEquals(listOf("tool:engineering", "workflow"), events.map { it.id })
        assertEquals(ChatProcessStatus.Succeeded, events.first().status)
        assertEquals("revision 12", events.first().detail)
    }

    @Test
    fun `thinking deltas accumulate into one process node while summary replaces the snapshot`() {
        var thinkingText = ""
        var events = emptyList<ChatProcessEvent>()

        fun accept(summary: String? = null, delta: String? = null) {
            mergeThinkingText(thinkingText, summary, delta)?.let { updated ->
                thinkingText = updated
                updated.takeIf(String::isNotBlank)?.let { detail ->
                    events = mergeProcessEvent(
                        events,
                        ChatProcessEvent("thinking", ChatProcessKind.Thinking, "Thinking", ChatProcessStatus.Running, detail),
                    )
                }
            }
        }

        accept(delta = "让我仔细")
        accept(delta = "想想")
        assertEquals(1, events.size)
        assertEquals("让我仔细想想", events.single().detail)

        accept(summary = "完整摘要")
        assertEquals(1, events.size)
        assertEquals("完整摘要", events.single().detail)
    }

    @Test
    fun `thinking accumulation starts empty for every new turn`() {
        val firstTurn = mergeThinkingText("", summary = null, delta = "第一轮")
        val secondTurn = mergeThinkingText("", summary = null, delta = "第二轮")

        assertEquals("第一轮", firstTurn)
        assertEquals("第二轮", secondTurn)
    }

    @Test
    fun `chat spacing tokens do not branch by assistant or owner role`() {
        assertEquals(8f, chatMessageSpacing(true).value)
        assertEquals(24f, chatMessageSpacing(false).value)
    }

    @Test
    fun `living room then engineering mentions merge every enabled tool id`() {
        assertEquals(
            setOf("builtin.engineering.open", "builtin.engineering.read_current", "builtin.livingroom.read"),
            resolveRequestedToolIds(
                "@LivingRoom @Engineering 请一起读取",
                emptySet(),
                enabledToolCapabilities(),
            ),
        )
    }

    @Test
    fun `engineering then living room mentions merge every enabled tool id`() {
        assertEquals(
            setOf("builtin.engineering.open", "builtin.engineering.read_current", "builtin.livingroom.read"),
            resolveRequestedToolIds(
                "@Engineering @LivingRoom 请一起读取",
                emptySet(),
                enabledToolCapabilities(),
            ),
        )
    }

    @Test
    fun `duplicate tool mentions and selected ids are de-duplicated`() {
        assertEquals(
            setOf("builtin.engineering.open", "builtin.engineering.read_current", "builtin.livingroom.read"),
            resolveRequestedToolIds(
                "@Engineering @Engineering @LivingRoom",
                setOf("builtin.engineering.read_current", "not-enabled"),
                enabledToolCapabilities(),
            ),
        )
    }

    @Test
    fun `chat wallpaper override wins before global and default`() {
        assertEquals("lavender", resolveChatWallpaperKey("lavender", "rose"))
        assertEquals("rose", resolveChatWallpaperKey(null, "rose"))
        assertEquals("green", resolveChatWallpaperKey(null, null))
        assertEquals("green", resolveChatWallpaperKey(null, "house"))
    }

    @Test
    fun `clearing chat wallpaper restores inherited resolution`() {
        val store = ChatSessionStore()

        store.setBackground("agent-codex", "lavender")
        assertEquals("lavender", resolveChatWallpaperKey(store.backgroundOverride("agent-codex"), "rose"))

        store.clearBackground("agent-codex")
        assertEquals("rose", resolveChatWallpaperKey(store.backgroundOverride("agent-codex"), "rose"))
    }

    @Test
    fun `streamed assistant text remains visible when tool completion later fails`() = runBlocking {
        val repository = DurableLocalMessages()
        val client = object : fyi.b612.lovehouse.feature.chat.CodexChatClient {
            override suspend fun streamMessage(threadId: String, message: String, requestedToolIds: Set<String>, attachments: List<fyi.b612.lovehouse.feature.chat.ChatAttachment>, onText: (String) -> Unit): fyi.b612.lovehouse.feature.chat.CodexChatResult {
                onText("已经生成且必须保留的正文")
                throw fyi.b612.lovehouse.feature.chat.CodexChatException("工具调用未完成")
            }
        }
        val store = ChatSessionStore(client, repository)

        assertTrue(store.sendCodexMessage("agent-codex", "读取工程", setOf("builtin.engineering.read_current")) {}.isFailure)

        val assistant = store.messages("agent-codex").single { it.author == "Codex" }
        assertEquals("已经生成且必须保留的正文", assistant.body)
        assertEquals(LocalChatDeliveryStatus.Failed, assistant.deliveryStatus)
        assertEquals("工具调用未完成", assistant.deliveryError)
        assertEquals("已经生成且必须保留的正文", repository.messages(stableThread()).single { it.role == LocalChatRole.Assistant }.content)
    }

    @Test
    fun `real tool error node remains separate from already generated assistant body`() = runBlocking {
        val repository = DurableLocalMessages()
        val client = object : fyi.b612.lovehouse.feature.chat.CodexChatClient {
            override suspend fun streamMessage(
                threadId: String,
                message: String,
                requestedToolIds: Set<String>,
                attachments: List<fyi.b612.lovehouse.feature.chat.ChatAttachment>,
                onText: (String) -> Unit,
            ): fyi.b612.lovehouse.feature.chat.CodexChatResult = error("process path expected")

            override suspend fun streamMessageWithProcess(
                threadId: String,
                message: String,
                requestedToolIds: Set<String>,
                attachments: List<fyi.b612.lovehouse.feature.chat.ChatAttachment>,
                onText: (String) -> Unit,
                onProcess: (ChatProcessEvent) -> Unit,
            ): fyi.b612.lovehouse.feature.chat.CodexChatResult {
                onProcess(ChatProcessEvent("tool:livingroom", ChatProcessKind.ToolCall, "读取 LivingRoom", ChatProcessStatus.Running))
                onText("这段已经生成的正文不能被吞掉")
                onProcess(ChatProcessEvent("tool:livingroom", ChatProcessKind.ToolError, "读取 LivingRoom", ChatProcessStatus.Failed, "权限拒绝"))
                throw fyi.b612.lovehouse.feature.chat.CodexChatException("工具调用失败")
            }
        }
        val store = ChatSessionStore(client, repository)

        assertTrue(store.sendCodexMessage("agent-codex", "读取客厅", setOf("builtin.livingroom.read")) {}.isFailure)

        val assistant = store.messages("agent-codex").single { it.author == "Codex" }
        assertEquals("这段已经生成的正文不能被吞掉", assistant.body)
        assertEquals(ChatProcessStatus.Failed, assistant.processEvents.single().status)
        assertEquals("权限拒绝", assistant.processEvents.single().detail)
    }

    private fun stableThread() = fyi.b612.lovehouse.feature.chat.stableCodexThreadId()

    private fun enabledToolCapabilities() = listOf(
        toolCapability("builtin.engineering.read_current", "engineering", "Engineering"),
        toolCapability("builtin.engineering.open", "engineering", "Engineering"),
        toolCapability("builtin.livingroom.read", "livingroom", "LivingRoom"),
    )

    private fun toolCapability(toolId: String, group: String, groupLabel: String) = ToolCapability(
        toolId = toolId,
        group = group,
        groupLabel = groupLabel,
        displayName = toolId,
        summary = "",
        availability = ToolAvailability.Available,
        detail = "",
        riskLevel = ToolRiskLevel.Low,
        capabilityKind = ToolCapabilityKind.Read,
        requiresApproval = false,
        scope = listOf("owner"),
    )

    @Test
    fun `global custom wallpaper path is inherited only without a chat override`() {
        assertEquals(
            "/local/global.jpg",
            resolveChatWallpaperPath(null, null, "custom", "/local/global.jpg"),
        )
        assertNull(resolveChatWallpaperPath(null, "rose", "custom", "/local/global.jpg"))
        assertEquals(
            "/local/thread.jpg",
            resolveChatWallpaperPath("/local/thread.jpg", null, "custom", "/local/global.jpg"),
        )
    }

    @Test
    fun `chat list carries every planned conversation kind`() {
        val threads = MockChatRepository.mockThreads

        assertEquals(ChatThreadKind.entries.toSet(), threads.map { it.kind }.toSet())
        assertTrue(threads.first { it.kind == ChatThreadKind.LivingRoom }.pinned)
        assertNotNull(threads.first { it.kind == ChatThreadKind.TemporaryTask }.taskId)
        assertTrue(threads.first { it.kind == ChatThreadKind.TemporaryTask }.expiresAtLabel?.isNotBlank() == true)
    }

    @Test
    fun `mock repository exposes chat through shared list state`() {
        val state = MockChatRepository().listState.value

        assertTrue(state is ChatListState.Content)
        assertTrue((state as ChatListState.Content).threads.isNotEmpty())
    }

    @Test
    fun `window creation preserves persona and chooses only thread lifetime`() {
        val store = ChatSessionStore()
        val persona = store.personas.first()

        val long = store.createThread(persona, temporary = false)
        val temporary = store.createThread(persona, temporary = true)

        assertEquals(ChatThreadKind.Direct, long.kind)
        assertEquals(ChatThreadKind.TemporaryTask, temporary.kind)
        assertEquals(persona.avatar, long.avatarGlyph)
        assertTrue(temporary.title.startsWith(persona.name))
    }

    @Test
    fun `living room member is added only once`() {
        val store = ChatSessionStore()
        val persona = store.personas.first { candidate -> candidate.personaId == "gemini" }

        store.addMember("living-room", persona)
        store.addMember("living-room", persona)

        assertEquals(1, store.members("living-room").count { it.memberId == persona.personaId })
    }

    @Test
    fun `merged forward creates an openable chat record card`() {
        val store = ChatSessionStore()
        val sourceIds = store.messages("persona-gpt").take(2).map { it.messageId }.toSet()

        store.forward("persona-gpt", sourceIds, "living-room", merged = true)

        val forwarded = store.messages("living-room").last()
        assertEquals(ChatMessageKind.ForwardBundle, forwarded.kind)
        assertEquals(2, forwarded.forwarded.size)
    }

    @Test
    fun `workflow advance completes current node and activates the next node`() {
        val store = ChatSessionStore()
        val before = store.task("mock-running-001")!!
        val currentIndex = before.workflow.indexOfFirst { it.status.name == "Current" }

        store.advanceTask(before.taskId)

        val after = store.task(before.taskId)!!
        assertEquals("Completed", after.workflow[currentIndex].status.name)
        assertEquals("Current", after.workflow[currentIndex + 1].status.name)
        assertTrue(store.messages("task-remote-ui").any { it.workflowEventId == after.workflow[currentIndex + 1].id })
    }

    @Test
    fun `workflow forward shares one compact task card without copying logs`() {
        val store = ChatSessionStore()
        val before = store.messages("living-room").size

        store.forwardWorkflow("mock-running-001", "living-room")

        assertEquals(before + 1, store.messages("living-room").size)
        assertEquals(ChatMessageKind.Workflow, store.messages("living-room").last().kind)
        assertTrue(store.messages("living-room").last().forwarded.isEmpty())
    }
}
