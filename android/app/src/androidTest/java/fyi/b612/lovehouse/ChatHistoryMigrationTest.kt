package fyi.b612.lovehouse

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fyi.b612.lovehouse.feature.chat.ChatAttachmentLifecycle
import fyi.b612.lovehouse.feature.chat.ChatMediaAttachment
import fyi.b612.lovehouse.feature.chat.ChatProcessEvent
import fyi.b612.lovehouse.feature.chat.ChatProcessKind
import fyi.b612.lovehouse.feature.chat.ChatProcessStatus
import fyi.b612.lovehouse.feature.chat.LocalChatDeliveryStatus
import fyi.b612.lovehouse.feature.chat.LocalChatExecution
import fyi.b612.lovehouse.feature.chat.LocalChatExecutionStatus
import fyi.b612.lovehouse.feature.chat.LocalChatMessage
import fyi.b612.lovehouse.feature.chat.LocalChatRole
import fyi.b612.lovehouse.feature.chat.SQLiteLocalChatMessageRepository
import fyi.b612.lovehouse.feature.chat.TOOL_DETAIL_CACHE_TTL_MILLIS
import fyi.b612.lovehouse.feature.chat.ToolDetailEnvelope
import fyi.b612.lovehouse.feature.chat.ToolDetailValue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ChatHistoryMigrationTest {
    @Test
    fun v1TextHistoryMigratesWithoutLossAndAttachmentMessagesReopen() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "chat-history-migration-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        val v1 = object : SQLiteOpenHelper(context, databaseName, null, 1) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE chat_messages (
                        local_message_id TEXT PRIMARY KEY NOT NULL,
                        thread_id TEXT NOT NULL,
                        role TEXT NOT NULL CHECK(role IN ('User', 'Assistant')),
                        sender TEXT NOT NULL,
                        content TEXT NOT NULL CHECK(length(trim(content)) > 0),
                        created_at_epoch_ms INTEGER NOT NULL,
                        received_at_epoch_ms INTEGER,
                        status TEXT NOT NULL CHECK(status IN ('Sending', 'Sent', 'Failed')),
                        runtime TEXT,
                        adapter_id TEXT
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX chat_messages_thread_time_idx ON chat_messages(thread_id, created_at_epoch_ms)")
            }
            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        v1.writableDatabase.insertOrThrow("chat_messages", null, ContentValues().apply {
            put("local_message_id", "old-text")
            put("thread_id", "thread")
            put("role", "User")
            put("sender", "owner")
            put("content", "旧纯文字仍在")
            put("created_at_epoch_ms", 1L)
            put("status", "Sent")
        })
        v1.close()

        val repository = SQLiteLocalChatMessageRepository(context, databaseName)
        assertEquals("旧纯文字仍在", repository.messages("thread").single().content)
        repository.upsert(
            LocalChatMessage(
                localMessageId = "new-media",
                threadId = "thread",
                role = LocalChatRole.User,
                sender = "owner",
                content = "",
                createdAtEpochMillis = 2L,
                status = LocalChatDeliveryStatus.Sent,
                attachments = listOf(
                    ChatMediaAttachment(
                        type = "photo",
                        mediaAssetId = "11111111-1111-4111-8111-111111111111",
                        storageRef = "media/owner/photo.jpg",
                        mimeType = "image/jpeg",
                        sizeBytes = 10,
                        name = "photo.jpg",
                        lifecycle = ChatAttachmentLifecycle.EPHEMERAL,
                    ),
                ),
            ),
        )
        repository.upsertExecution(LocalChatExecution(
            executionId = "11111111-1111-4111-8111-111111111111",
            localThreadId = "thread",
            provider = "codex",
            canonicalThreadId = "canonical-thread",
            userMessageId = "new-media",
            assistantMessageId = "assistant:11111111-1111-4111-8111-111111111111",
            status = LocalChatExecutionStatus.Running,
            createdAtEpochMillis = 2L,
            updatedAtEpochMillis = 2L,
        ))
        repository.close()

        val reopened = SQLiteLocalChatMessageRepository(context, databaseName)
        val messages = reopened.messages("thread")
        assertEquals(listOf("old-text", "new-media"), messages.map { it.localMessageId })
        assertEquals(1, messages.last().attachments.size)
        assertTrue(messages.last().content.isEmpty())
        assertEquals("11111111-1111-4111-8111-111111111111", reopened.pendingExecutions().single().executionId)
        assertEquals("thread", reopened.localThreadIdForCanonicalThread("canonical-thread"))
        assertEquals(null, reopened.localThreadIdForCanonicalThread("missing-canonical-thread"))
        assertTrue(reopened.messages("thread").all { it.processEvents.isEmpty() })
        reopened.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun processTimelinePersistsPerAssistantAcrossColdReopenWithoutDuplication() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "chat-process-timeline-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        val firstAssistantId = "assistant:execution-one"
        val secondAssistantId = "assistant:execution-two"
        val firstTimeline = listOf(
            ChatProcessEvent("thinking", ChatProcessKind.Thinking, "Thinking", ChatProcessStatus.Running, "让我仔细想想"),
            ChatProcessEvent("reasoning", ChatProcessKind.ReasoningStatus, "思考状态", ChatProcessStatus.Running, "正在核对"),
            ChatProcessEvent("tool-call:pending", ChatProcessKind.ToolCall, "读取 LivingRoom", ChatProcessStatus.Running, "准备读取"),
            ChatProcessEvent("tool-call:one", ChatProcessKind.ToolResult, "读取 LivingRoom", ChatProcessStatus.Succeeded, "读取完成"),
            ChatProcessEvent("workflow", ChatProcessKind.WorkflowStatus, "执行状态", ChatProcessStatus.Running, "整理回答"),
        )
        val secondTimeline = listOf(
            ChatProcessEvent("tool-call:two", ChatProcessKind.ToolError, "读取 LivingRoom", ChatProcessStatus.Failed, "已拒绝"),
        )
        val repository = SQLiteLocalChatMessageRepository(context, databaseName)
        repository.replaceProcessEvents("thread", firstAssistantId, firstTimeline)
        repository.replaceProcessEvents("thread", secondAssistantId, secondTimeline)
        repository.upsert(listOf(
            LocalChatMessage(firstAssistantId, "thread", LocalChatRole.Assistant, "claude", "first answer", 1L, status = LocalChatDeliveryStatus.Sent),
            LocalChatMessage(secondAssistantId, "thread", LocalChatRole.Assistant, "codex", "second answer", 2L, status = LocalChatDeliveryStatus.Sent),
        ))
        repository.close()

        val reopened = SQLiteLocalChatMessageRepository(context, databaseName)
        val messages = reopened.messages("thread")
        assertEquals(listOf(firstAssistantId, secondAssistantId), messages.map(LocalChatMessage::localMessageId))
        assertEquals(firstTimeline, messages[0].processEvents)
        assertEquals(secondTimeline, messages[1].processEvents)
        assertEquals(1, messages[0].processEvents.count { it.id == "thinking" })
        reopened.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun v5MigratesToV6WithoutChangingMessagesOrProcessTimeline() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "chat-tool-detail-v5-migration-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        val v5 = object : SQLiteOpenHelper(context, databaseName, null, 5) {
            override fun onCreate(db: SQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE chat_messages (
                        local_message_id TEXT PRIMARY KEY NOT NULL,
                        thread_id TEXT NOT NULL,
                        role TEXT NOT NULL,
                        sender TEXT NOT NULL,
                        content TEXT NOT NULL,
                        created_at_epoch_ms INTEGER NOT NULL,
                        received_at_epoch_ms INTEGER,
                        status TEXT NOT NULL,
                        runtime TEXT,
                        adapter_id TEXT
                    )
                    """.trimIndent(),
                )
                db.execSQL("CREATE INDEX chat_messages_thread_time_idx ON chat_messages(thread_id, created_at_epoch_ms)")
                db.execSQL(
                    """
                    CREATE TABLE chat_message_attachments (
                        local_attachment_id TEXT PRIMARY KEY NOT NULL,
                        local_message_id TEXT NOT NULL,
                        position INTEGER NOT NULL,
                        type TEXT NOT NULL,
                        lifecycle TEXT NOT NULL,
                        availability TEXT NOT NULL,
                        created_at_epoch_ms INTEGER NOT NULL,
                        media_asset_id TEXT,
                        storage_ref TEXT,
                        mime_type TEXT,
                        size_bytes INTEGER,
                        name TEXT,
                        width INTEGER,
                        height INTEGER,
                        local_cache_path TEXT,
                        remote_expires_at_epoch_ms INTEGER,
                        latitude REAL,
                        longitude REAL,
                        accuracy REAL,
                        captured_at_epoch_ms INTEGER,
                        address TEXT
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE chat_executions (
                        execution_id TEXT PRIMARY KEY NOT NULL,
                        local_thread_id TEXT NOT NULL,
                        provider TEXT NOT NULL,
                        canonical_thread_id TEXT NOT NULL,
                        user_message_id TEXT NOT NULL,
                        assistant_message_id TEXT NOT NULL,
                        status TEXT NOT NULL,
                        last_error TEXT,
                        persona_version INTEGER,
                        reanchor_intent INTEGER NOT NULL DEFAULT 0,
                        created_at_epoch_ms INTEGER NOT NULL,
                        updated_at_epoch_ms INTEGER NOT NULL
                    )
                    """.trimIndent(),
                )
                db.execSQL(
                    """
                    CREATE TABLE chat_process_events (
                        assistant_message_id TEXT NOT NULL,
                        event_id TEXT NOT NULL,
                        thread_id TEXT NOT NULL,
                        position INTEGER NOT NULL,
                        kind TEXT NOT NULL,
                        title TEXT NOT NULL,
                        status TEXT NOT NULL,
                        detail TEXT,
                        PRIMARY KEY (assistant_message_id, event_id)
                    )
                    """.trimIndent(),
                )
            }

            override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) = Unit
        }
        v5.writableDatabase.insertOrThrow("chat_messages", null, ContentValues().apply {
            put("local_message_id", "assistant:old")
            put("thread_id", "thread")
            put("role", "Assistant")
            put("sender", "codex")
            put("content", "old answer")
            put("created_at_epoch_ms", 1L)
            put("status", "Sent")
        })
        v5.writableDatabase.insertOrThrow("chat_process_events", null, ContentValues().apply {
            put("assistant_message_id", "assistant:old")
            put("event_id", "tool-call:old")
            put("thread_id", "thread")
            put("position", 0)
            put("kind", "ToolResult")
            put("title", "old tool")
            put("status", "Succeeded")
            put("detail", "old lightweight detail")
        })
        v5.close()

        val repository = SQLiteLocalChatMessageRepository(context, databaseName, nowEpochMillis = { 1_000L })
        val message = repository.messages("thread").single()
        assertEquals("old answer", message.content)
        assertEquals("tool-call:old", message.processEvents.single().id)
        assertEquals("old lightweight detail", message.processEvents.single().detail)
        val cached = repository.upsert(
            "thread",
            "assistant:old",
            "tool-call:old",
            commandDetail("old", output = "safe output"),
        )
        assertEquals("safe output", (cached?.detail as ToolDetailEnvelope.Command).output)
        val version = context.getDatabasePath(databaseName).let { path ->
            SQLiteDatabase.openDatabase(path.path, null, SQLiteDatabase.OPEN_READONLY).use { it.version }
        }
        assertEquals(6, version)
        repository.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun toolDetailCacheMergesLifecycleSurvivesColdReopenAndExpiresWithoutTimelineLoss() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "chat-tool-detail-cache-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        var clock = 10_000L
        val assistantId = "assistant:execution-one"
        val eventId = "tool-call:call-one"
        val repository = SQLiteLocalChatMessageRepository(context, databaseName, nowEpochMillis = { clock })
        repository.upsert(LocalChatMessage(
            assistantId,
            "thread",
            LocalChatRole.Assistant,
            "codex",
            "answer",
            1L,
            status = LocalChatDeliveryStatus.Sent,
            processEvents = listOf(ChatProcessEvent(eventId, ChatProcessKind.ToolResult, "tool", ChatProcessStatus.Succeeded)),
        ))
        val initial = repository.upsert(
            "thread",
            assistantId,
            eventId,
            genericDetail("call-one", arguments = ToolDetailValue.Text("safe argument"), truncated = true),
        )!!
        clock += 60_000L
        val completed = repository.upsert(
            "thread",
            assistantId,
            eventId,
            genericDetail("call-one", result = ToolDetailValue.Text("safe result"), originalLength = 99),
        )!!
        assertEquals(initial.createdAtEpochMillis, completed.createdAtEpochMillis)
        assertEquals(initial.expiresAtEpochMillis, completed.expiresAtEpochMillis)
        assertEquals(ToolDetailValue.Text("safe argument"), (completed.detail as ToolDetailEnvelope.GenericTool).arguments)
        assertEquals(ToolDetailValue.Text("safe result"), completed.detail.result)
        assertTrue(completed.detail.truncated)
        assertEquals(99, completed.detail.originalLength)
        repository.close()

        clock = initial.createdAtEpochMillis + TOOL_DETAIL_CACHE_TTL_MILLIS - 60_000L
        val reopened = SQLiteLocalChatMessageRepository(context, databaseName, nowEpochMillis = { clock })
        val restored = reopened.detail(assistantId, eventId)
        assertEquals(completed.detail, restored?.detail)
        assertEquals(initial.createdAtEpochMillis, restored?.createdAtEpochMillis)
        assertEquals(initial.expiresAtEpochMillis, restored?.expiresAtEpochMillis)
        assertEquals(1, reopened.messages("thread").single().processEvents.size)
        reopened.close()

        clock = initial.createdAtEpochMillis + TOOL_DETAIL_CACHE_TTL_MILLIS
        val expired = SQLiteLocalChatMessageRepository(context, databaseName, nowEpochMillis = { clock })
        assertNull(expired.detail(assistantId, eventId))
        assertEquals(eventId, expired.messages("thread").single().processEvents.single().id)
        expired.close()
        context.deleteDatabase(databaseName)
    }

    @Test
    fun toolDetailCacheIsolatesCallIdsAndSupportsScopedDeletionWithoutArchiveSideEffects() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val databaseName = "chat-tool-detail-scope-${System.nanoTime()}.db"
        context.deleteDatabase(databaseName)
        val repository = SQLiteLocalChatMessageRepository(context, databaseName, nowEpochMillis = { 1_000L })

        repository.upsert("thread-a", "assistant:a", "tool-call:a", commandDetail("a", output = "first"))
        repository.upsert("thread-a", "assistant:a", "tool-call:b", commandDetail("b", output = "second"))
        repository.upsert("thread-b", "assistant:b", "tool-call:c", commandDetail("c", output = "third"))
        assertNull(repository.upsert("thread-a", "assistant:a", "tool-call:wrong", commandDetail("a")))
        assertEquals("first", (repository.detail("assistant:a", "tool-call:a")?.detail as ToolDetailEnvelope.Command).output)
        assertEquals("second", (repository.detail("assistant:a", "tool-call:b")?.detail as ToolDetailEnvelope.Command).output)

        assertEquals(2, repository.deleteForAssistant("assistant:a"))
        assertNull(repository.detail("assistant:a", "tool-call:a"))
        assertEquals(1, repository.deleteForThread("thread-b"))
        assertNull(repository.detail("assistant:b", "tool-call:c"))
        repository.close()
        context.deleteDatabase(databaseName)
    }

    private fun commandDetail(callId: String, output: String? = null) = ToolDetailEnvelope.Command(
        schemaVersion = 1,
        callId = callId,
        createdAt = "2026-10-01T00:00:00.000Z",
        truncated = false,
        originalLength = output?.length ?: 0,
        command = "read-only",
        output = output,
        exitCode = output?.let { 0 },
        status = output?.let { "completed" } ?: "in_progress",
    )

    private fun genericDetail(
        callId: String,
        arguments: ToolDetailValue? = null,
        result: ToolDetailValue? = null,
        truncated: Boolean = false,
        originalLength: Int = 10,
    ) = ToolDetailEnvelope.GenericTool(
        schemaVersion = 1,
        callId = callId,
        createdAt = "2026-10-01T00:00:00.000Z",
        truncated = truncated,
        originalLength = originalLength,
        arguments = arguments,
        result = result,
        isError = false,
    )
}
