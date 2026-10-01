package fyi.b612.lovehouse

import fyi.b612.lovehouse.feature.chat.CachedToolDetail
import fyi.b612.lovehouse.feature.chat.ChatProcessEvent
import fyi.b612.lovehouse.feature.chat.ChatProcessKind
import fyi.b612.lovehouse.feature.chat.ChatProcessStatus
import fyi.b612.lovehouse.feature.chat.ChatRuntimeConfig
import fyi.b612.lovehouse.feature.chat.ChatSessionStore
import fyi.b612.lovehouse.feature.chat.CodexChatClient
import fyi.b612.lovehouse.feature.chat.CodexChatResult
import fyi.b612.lovehouse.feature.chat.CodexRuntimeEvidence
import fyi.b612.lovehouse.feature.chat.LocalChatMessage
import fyi.b612.lovehouse.feature.chat.LocalChatMessageRepository
import fyi.b612.lovehouse.feature.chat.ToolDetailCacheRepository
import fyi.b612.lovehouse.feature.chat.ToolDetailEnvelope
import fyi.b612.lovehouse.feature.chat.ToolDetailValue
import fyi.b612.lovehouse.feature.chat.TOOL_DETAIL_CACHE_TTL_MILLIS
import fyi.b612.lovehouse.feature.chat.buildChatPayload
import fyi.b612.lovehouse.feature.chat.ClaudeRuntime
import fyi.b612.lovehouse.feature.chat.hasValidToolDetailIdentity
import fyi.b612.lovehouse.feature.chat.mergeToolDetails
import fyi.b612.lovehouse.feature.chat.parseStoredToolDetailEnvelope
import fyi.b612.lovehouse.feature.chat.serializeToolDetailEnvelope
import fyi.b612.lovehouse.feature.chat.toolDetailExpiresAt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolDetailCacheContractTest {
    @Test
    fun `generic call and result merge without resetting creation identity`() {
        val call = genericDetail(arguments = ToolDetailValue.Text("safe argument"))
        val result = genericDetail(
            createdAt = "2026-10-01T01:00:00.000Z",
            result = ToolDetailValue.Text("safe result"),
            originalLength = 20,
        )

        val merged = mergeToolDetails(call, result) as ToolDetailEnvelope.GenericTool

        assertEquals(call.createdAt, merged.createdAt)
        assertEquals(ToolDetailValue.Text("safe argument"), merged.arguments)
        assertEquals(ToolDetailValue.Text("safe result"), merged.result)
        assertEquals(20, merged.originalLength)
    }

    @Test
    fun `command update retains command and first creation identity`() {
        val call = commandDetail(command = "pwd")
        val result = commandDetail(
            createdAt = "2026-10-01T01:00:00.000Z",
            output = "/tmp",
            exitCode = 0,
            status = "completed",
        )

        val merged = mergeToolDetails(call, result) as ToolDetailEnvelope.Command

        assertEquals("pwd", merged.command)
        assertEquals("/tmp", merged.output)
        assertEquals(call.createdAt, merged.createdAt)
    }

    @Test
    fun `cache ttl is fixed at first creation plus 72 hours`() {
        val createdAt = 1_000L

        assertEquals(72L * 60L * 60L * 1000L, TOOL_DETAIL_CACHE_TTL_MILLIS)
        assertEquals(createdAt + TOOL_DETAIL_CACHE_TTL_MILLIS, toolDetailExpiresAt(createdAt))
    }

    @Test
    fun `typed safe payload round trips truncated and original length`() {
        val original = commandDetail(output = "bounded", truncated = true, originalLength = 99)

        val restored = parseStoredToolDetailEnvelope(serializeToolDetailEnvelope(original), original.callId)

        assertEquals(original, restored)
    }

    @Test
    fun `stored detail remains isolated from next provider payload`() {
        val marker = "COLD_RESTORED_DETAIL_MUST_STAY_LOCAL"
        val stored = serializeToolDetailEnvelope(commandDetail(output = marker))
        val restored = parseStoredToolDetailEnvelope(stored, CALL_ID)
        val nextPayload = buildChatPayload(ClaudeRuntime, "next turn", emptySet())

        assertEquals(marker, (restored as ToolDetailEnvelope.Command).output)
        assertFalse(nextPayload.contains(marker))
        assertFalse(nextPayload.contains("tool_detail"))
    }

    @Test
    fun `invalid event identity and different detail kinds cannot merge`() {
        assertFalse(hasValidToolDetailIdentity("tool-call:other", commandDetail()))
        assertNull(mergeToolDetails(commandDetail(), genericDetail()))
    }

    @Test
    fun `validated runtime detail is cached by assistant and call identity`() = runBlocking {
        val cache = RecordingToolDetailCache()
        val messages = RecordingMessages()
        val client = processClient(
            ChatProcessEvent(
                id = "tool-call:$CALL_ID",
                kind = ChatProcessKind.ToolCall,
                title = "safe tool",
                status = ChatProcessStatus.Running,
                toolDetail = genericDetail(arguments = ToolDetailValue.Text("argument")),
            ),
            ChatProcessEvent(
                id = "tool-call:$CALL_ID",
                kind = ChatProcessKind.ToolResult,
                title = "safe tool",
                status = ChatProcessStatus.Succeeded,
                toolDetail = genericDetail(result = ToolDetailValue.Text("result")),
            ),
        )
        val store = ChatSessionStore(client, messages, toolDetailCache = cache)

        assertTrue(store.sendCodexMessage("agent-codex", "run") {}.isSuccess)

        val cached = cache.rows.single()
        assertTrue(cached.assistantMessageId.startsWith("assistant:"))
        assertEquals("tool-call:$CALL_ID", cached.eventId)
        assertEquals(ToolDetailValue.Text("argument"), (cached.detail as ToolDetailEnvelope.GenericTool).arguments)
        assertEquals(ToolDetailValue.Text("result"), cached.detail.result)
        assertEquals(cached.detail, store.toolDetail(cached.assistantMessageId, cached.eventId))
    }

    @Test
    fun `cache failure never removes lightweight timeline`() = runBlocking {
        val messages = RecordingMessages()
        val failingCache = object : ToolDetailCacheRepository by RecordingToolDetailCache() {
            override fun upsert(
                threadId: String,
                assistantMessageId: String,
                eventId: String,
                detail: ToolDetailEnvelope,
            ): CachedToolDetail? = error("cache unavailable")
        }
        val event = ChatProcessEvent(
            id = "tool-call:$CALL_ID",
            kind = ChatProcessKind.ToolCall,
            title = "safe tool",
            status = ChatProcessStatus.Running,
            toolDetail = commandDetail(command = "pwd"),
        )
        val store = ChatSessionStore(processClient(event), messages, toolDetailCache = failingCache)

        assertTrue(store.sendCodexMessage("agent-codex", "run") {}.isSuccess)

        val assistant = messages.rows.values.single { it.localMessageId.startsWith("assistant:") }
        assertEquals("tool-call:$CALL_ID", assistant.processEvents.single().id)
        assertNotNull(assistant.processEvents.single().toolDetail)
    }

    private class RecordingMessages : LocalChatMessageRepository {
        val rows = linkedMapOf<String, LocalChatMessage>()
        private val timelines = linkedMapOf<String, List<ChatProcessEvent>>()

        override fun messages(threadId: String): List<LocalChatMessage> = rows.values
            .filter { it.threadId == threadId }
            .map { it.copy(processEvents = timelines[it.localMessageId] ?: it.processEvents) }

        override fun upsert(message: LocalChatMessage) {
            rows[message.localMessageId] = message
            if (message.processEvents.isNotEmpty()) timelines[message.localMessageId] = message.processEvents
        }

        override fun replaceProcessEvents(
            threadId: String,
            assistantMessageId: String,
            events: List<ChatProcessEvent>,
        ) {
            timelines[assistantMessageId] = events
        }
    }

    private class RecordingToolDetailCache : ToolDetailCacheRepository {
        val rows = mutableListOf<CachedToolDetail>()

        override fun upsert(
            threadId: String,
            assistantMessageId: String,
            eventId: String,
            detail: ToolDetailEnvelope,
        ): CachedToolDetail? {
            if (!hasValidToolDetailIdentity(eventId, detail)) return null
            val existing = rows.firstOrNull { it.assistantMessageId == assistantMessageId && it.eventId == eventId }
            val merged = existing?.let { mergeToolDetails(it.detail, detail) ?: return null } ?: detail
            val row = CachedToolDetail(
                threadId,
                assistantMessageId,
                eventId,
                merged,
                existing?.createdAtEpochMillis ?: 1L,
                existing?.expiresAtEpochMillis ?: toolDetailExpiresAt(1L),
            )
            rows.removeAll { it.assistantMessageId == assistantMessageId && it.eventId == eventId }
            rows += row
            return row
        }

        override fun detail(assistantMessageId: String, eventId: String): CachedToolDetail? =
            rows.firstOrNull { it.assistantMessageId == assistantMessageId && it.eventId == eventId }

        override fun deleteForAssistant(assistantMessageId: String): Int = 0
        override fun deleteForThread(threadId: String): Int = 0
        override fun cleanupExpired(nowEpochMillis: Long): Int = 0
    }

    private fun processClient(vararg events: ChatProcessEvent): CodexChatClient = object : CodexChatClient {
        override suspend fun streamMessage(
            threadId: String,
            message: String,
            requestedToolIds: Set<String>,
            attachments: List<fyi.b612.lovehouse.feature.chat.ChatAttachment>,
            onText: (String) -> Unit,
        ): CodexChatResult = error("runtime path expected")

        override suspend fun streamRuntimeMessageWithProcess(
            config: ChatRuntimeConfig,
            message: String,
            requestedToolIds: Set<String>,
            attachments: List<fyi.b612.lovehouse.feature.chat.ChatAttachment>,
            onText: (String) -> Unit,
            onProcess: (ChatProcessEvent) -> Unit,
        ): CodexChatResult {
            events.forEach(onProcess)
            onText("done")
            return CodexChatResult("done", CodexRuntimeEvidence("codex_cli", "codex-cli-v1", config.threadId))
        }
    }

    private fun genericDetail(
        createdAt: String = "2026-10-01T00:00:00.000Z",
        arguments: ToolDetailValue? = null,
        result: ToolDetailValue? = null,
        originalLength: Int = 10,
    ) = ToolDetailEnvelope.GenericTool(1, CALL_ID, createdAt, false, originalLength, arguments, result, false)

    private fun commandDetail(
        createdAt: String = "2026-10-01T00:00:00.000Z",
        command: String? = null,
        output: String? = null,
        exitCode: Int? = null,
        status: String? = null,
        truncated: Boolean = false,
        originalLength: Int = 10,
    ) = ToolDetailEnvelope.Command(1, CALL_ID, createdAt, truncated, originalLength, command, output, exitCode, status)

    private companion object {
        const val CALL_ID = "call-1"
    }
}
