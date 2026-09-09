package fyi.b612.lovehouse

import fyi.b612.lovehouse.feature.chat.AttachmentCapabilities
import fyi.b612.lovehouse.feature.chat.ChatAttachmentLifecycle
import fyi.b612.lovehouse.feature.chat.ChatAttachmentType
import fyi.b612.lovehouse.feature.chat.ChatLocationAttachment
import fyi.b612.lovehouse.feature.chat.ChatMediaAttachment
import fyi.b612.lovehouse.feature.chat.ChatProcessEvent
import fyi.b612.lovehouse.feature.chat.ChatProcessKind
import fyi.b612.lovehouse.feature.chat.ChatProcessStatus
import fyi.b612.lovehouse.feature.chat.ClaudeAttachmentCapabilities
import fyi.b612.lovehouse.feature.chat.ClaudeRuntime
import fyi.b612.lovehouse.feature.chat.CodexAttachmentCapabilities
import fyi.b612.lovehouse.feature.chat.CodexRuntime
import fyi.b612.lovehouse.feature.chat.MediaAttachmentException
import fyi.b612.lovehouse.feature.chat.MediaAttachmentProgress
import fyi.b612.lovehouse.feature.chat.MediaAttachmentStage
import fyi.b612.lovehouse.feature.chat.buildChatPayload
import fyi.b612.lovehouse.feature.chat.mergeProcessEvent
import fyi.b612.lovehouse.feature.chat.observeMediaUploadAttempt
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class AttachmentCapabilityCharacterizationTest {
    @Test
    fun `codex declares the current real attachment range`() {
        assertEquals(
            setOf(ChatAttachmentType.Photo, ChatAttachmentType.File, ChatAttachmentType.Location),
            CodexAttachmentCapabilities.acceptedTypes,
        )
        assertEquals(12, CodexAttachmentCapabilities.maxItems)
        assertTrue(CodexAttachmentCapabilities.supportsTextWithAttachments)
        assertEquals(
            setOf(ChatAttachmentLifecycle.LOCAL, ChatAttachmentLifecycle.EPHEMERAL),
            CodexAttachmentCapabilities.supportedLifecycles,
        )
        assertTrue(CodexRuntime.attachmentsEnabled)
    }

    @Test
    fun `claude remains explicitly unsupported`() {
        assertEquals(AttachmentCapabilities.Unsupported, ClaudeAttachmentCapabilities)
        assertFalse(ClaudeRuntime.attachmentsEnabled)
    }

    @Test
    fun `codex payload preserves single image file location and text`() {
        val image = remoteMedia("photo", "image/jpeg", "one.jpg", "one")
        val file = remoteMedia("file", "application/pdf", "report.pdf", "report")
        val location = ChatLocationAttachment(31.230416, 121.473701, 8f, 1_757_257_600_000L)

        assertPayload("看图片", listOf(image), expectedMedia = 1, expectedLocations = 0)
        assertPayload("读文件", listOf(file), expectedMedia = 1, expectedLocations = 0)
        assertPayload("我在这里", listOf(location), expectedMedia = 0, expectedLocations = 1)
    }

    @Test
    fun `codex payload preserves every item in multi image and mixed attachment turns`() {
        val attachments = listOf(
            remoteMedia("photo", "image/jpeg", "one.jpg", "one"),
            remoteMedia("photo", "image/png", "two.png", "two"),
            remoteMedia("file", "application/pdf", "report.pdf", "report"),
            ChatLocationAttachment(31.2, 121.4, 5f, 1_757_257_600_000L),
        )
        val payload = buildChatPayload(CodexRuntime, "同时读取", emptySet(), attachments)

        assertEquals(3, Regex("media_asset_id").findAll(payload).count())
        assertEquals(1, Regex("\\\"type\\\":\\\"location\\\"").findAll(payload).count())
        assertTrue(payload.contains("同时读取"))
        assertEquals(1, Regex("\\\"message\\\"\\s*:").findAll(payload).count())
    }

    @Test
    fun `attachment payload and tool request remain independent fields`() {
        val payload = buildChatPayload(
            CodexRuntime,
            "读取附件并查工程",
            setOf("builtin.engineering.read_current"),
            listOf(remoteMedia("file", "text/plain", "notes.txt", "notes")),
        )
        var events = emptyList<ChatProcessEvent>()
        events = mergeProcessEvent(events, ChatProcessEvent("tool:engineering", ChatProcessKind.ToolCall, "读取 Engineering", ChatProcessStatus.Running))
        events = mergeProcessEvent(events, ChatProcessEvent("tool:engineering", ChatProcessKind.ToolResult, "读取 Engineering", ChatProcessStatus.Succeeded))

        assertTrue(payload.contains("allowed_tool_ids"))
        assertTrue(payload.contains("attachments"))
        assertEquals(ChatProcessStatus.Succeeded, events.single().status)
    }

    @Test
    fun `upload failure records failure then always releases loading state`() = runBlocking {
        val progress = mutableListOf<MediaAttachmentProgress>()
        val failure = IllegalStateException("synthetic failure")

        val result = runCatching {
            observeMediaUploadAttempt("attachment", 1, 1, onProgress = progress::add) { stage ->
                stage(MediaAttachmentStage.PutStarted)
                throw failure
            }
        }

        assertSame(failure, result.exceptionOrNull())
        assertEquals(listOf(MediaAttachmentStage.PutStarted, MediaAttachmentStage.Failed, MediaAttachmentStage.Finished), progress.map { it.stage })
    }

    @Test
    fun `upload cancellation is rethrown and still releases loading state`() = runBlocking {
        val progress = mutableListOf<MediaAttachmentProgress>()
        val cancellation = CancellationException("synthetic cancellation")

        val result = runCatching {
            observeMediaUploadAttempt("attachment", 1, 1, onProgress = progress::add) { stage ->
                stage(MediaAttachmentStage.PresignStarted)
                throw cancellation
            }
        }

        assertSame(cancellation, result.exceptionOrNull())
        assertEquals(listOf(MediaAttachmentStage.PresignStarted, MediaAttachmentStage.Failed, MediaAttachmentStage.Finished), progress.map { it.stage })
    }

    @Test
    fun `upload timeout remains truthful and still releases loading state`() = runBlocking {
        val progress = mutableListOf<MediaAttachmentProgress>()

        val result = runCatching {
            observeMediaUploadAttempt("attachment", 2, 3, onProgress = progress::add) { stage ->
                stage(MediaAttachmentStage.TemporaryReferenceStarted)
                withTimeout(1) { delay(50) }
            }
        }

        assertTrue(result.exceptionOrNull() is MediaAttachmentException)
        assertTrue(result.exceptionOrNull()?.message.orEmpty().contains("附件草稿已保留"))
        assertEquals(listOf(MediaAttachmentStage.TemporaryReferenceStarted, MediaAttachmentStage.Failed, MediaAttachmentStage.Finished), progress.map { it.stage })
        assertEquals("timeout_after_TemporaryReferenceStarted", progress.first { it.stage == MediaAttachmentStage.Failed }.failure)
    }

    private fun assertPayload(text: String, attachments: List<fyi.b612.lovehouse.feature.chat.ChatAttachment>, expectedMedia: Int, expectedLocations: Int) {
        val payload = buildChatPayload(CodexRuntime, text, emptySet(), attachments)
        assertTrue(payload.contains(text))
        assertEquals(expectedMedia, Regex("media_asset_id").findAll(payload).count())
        assertEquals(expectedLocations, Regex("\\\"type\\\":\\\"location\\\"").findAll(payload).count())
    }

    private fun remoteMedia(type: String, mime: String, name: String, suffix: String) = ChatMediaAttachment(
        type = type,
        mediaAssetId = "11111111-1111-4111-8111-${suffix.padEnd(12, '1').take(12)}",
        storageRef = "media/owner/$name",
        mimeType = mime,
        sizeBytes = 12,
        name = name,
        lifecycle = ChatAttachmentLifecycle.EPHEMERAL,
    )
}
