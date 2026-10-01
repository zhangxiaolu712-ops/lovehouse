package fyi.b612.lovehouse.feature.chat

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

enum class LocalChatRole { User, Assistant }

enum class LocalChatDeliveryStatus { Sending, Sent, Failed }

enum class LocalChatExecutionStatus { Running, Completed, Failed }

data class LocalChatExecution(
    val executionId: String,
    val localThreadId: String,
    val provider: String,
    val canonicalThreadId: String,
    val userMessageId: String,
    val assistantMessageId: String,
    val status: LocalChatExecutionStatus,
    val lastError: String? = null,
    val personaVersion: Int? = null,
    val reanchorIntent: Boolean = false,
    val createdAtEpochMillis: Long,
    val updatedAtEpochMillis: Long,
)

data class LocalChatMessage(
    val localMessageId: String,
    val threadId: String,
    val role: LocalChatRole,
    val sender: String,
    val content: String,
    val createdAtEpochMillis: Long,
    val receivedAtEpochMillis: Long? = null,
    val status: LocalChatDeliveryStatus,
    val runtime: String? = null,
    val adapterId: String? = null,
    val attachments: List<ChatAttachment> = emptyList(),
    val processEvents: List<ChatProcessEvent> = emptyList(),
)

interface LocalChatMessageRepository {
    fun messages(threadId: String): List<LocalChatMessage>
    fun upsert(message: LocalChatMessage)
    fun upsert(messages: List<LocalChatMessage>) = messages.forEach(::upsert)
    fun replaceProcessEvents(threadId: String, assistantMessageId: String, events: List<ChatProcessEvent>) = Unit
    fun localThreadIdForCanonicalThread(canonicalThreadId: String): String? = null
    fun pendingExecutions(): List<LocalChatExecution> = emptyList()
    fun upsertExecution(execution: LocalChatExecution) = Unit
}

object NoOpLocalChatMessageRepository : LocalChatMessageRepository {
    override fun messages(threadId: String): List<LocalChatMessage> = emptyList()
    override fun upsert(message: LocalChatMessage) = Unit
    override fun upsert(messages: List<LocalChatMessage>) = Unit
    override fun replaceProcessEvents(threadId: String, assistantMessageId: String, events: List<ChatProcessEvent>) = Unit
    override fun localThreadIdForCanonicalThread(canonicalThreadId: String): String? = null
    override fun pendingExecutions(): List<LocalChatExecution> = emptyList()
    override fun upsertExecution(execution: LocalChatExecution) = Unit
}

class SQLiteLocalChatMessageRepository(
    context: Context,
    databaseName: String = DATABASE_NAME,
    private val nowEpochMillis: () -> Long = System::currentTimeMillis,
) : LocalChatMessageRepository, ToolDetailCacheRepository {
    private val database = ChatHistoryDatabase(context.applicationContext, databaseName)

    init {
        cleanupExpired(nowEpochMillis())
    }

    internal fun close() = database.close()

    @Synchronized
    override fun messages(threadId: String): List<LocalChatMessage> {
        val processEventsByMessage = readProcessEvents(database.readableDatabase, threadId)
        return buildList {
            database.readableDatabase.query(
                TABLE_MESSAGES,
                MESSAGE_COLUMNS,
                "thread_id = ?",
                arrayOf(threadId),
                null,
                null,
                "created_at_epoch_ms ASC, rowid ASC",
            ).use { cursor ->
                while (cursor.moveToNext()) {
                    val messageId = cursor.getString(cursor.getColumnIndexOrThrow("local_message_id"))
                    add(
                        LocalChatMessage(
                            localMessageId = messageId,
                            threadId = cursor.getString(cursor.getColumnIndexOrThrow("thread_id")),
                            role = LocalChatRole.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("role"))),
                            sender = cursor.getString(cursor.getColumnIndexOrThrow("sender")),
                            content = cursor.getString(cursor.getColumnIndexOrThrow("content")),
                            createdAtEpochMillis = cursor.getLong(cursor.getColumnIndexOrThrow("created_at_epoch_ms")),
                            receivedAtEpochMillis = cursor.getColumnIndexOrThrow("received_at_epoch_ms").let { index ->
                                if (cursor.isNull(index)) null else cursor.getLong(index)
                            },
                            status = LocalChatDeliveryStatus.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("status"))),
                            runtime = cursor.getColumnIndexOrThrow("runtime").let { index ->
                                if (cursor.isNull(index)) null else cursor.getString(index)
                            },
                            adapterId = cursor.getColumnIndexOrThrow("adapter_id").let { index ->
                                if (cursor.isNull(index)) null else cursor.getString(index)
                            },
                            attachments = readAttachments(database.readableDatabase, messageId),
                            processEvents = processEventsByMessage[messageId].orEmpty(),
                        ),
                    )
                }
            }
        }
    }

    @Synchronized
    override fun replaceProcessEvents(
        threadId: String,
        assistantMessageId: String,
        events: List<ChatProcessEvent>,
    ) {
        database.writableDatabase.beginTransaction()
        try {
            replaceProcessEvents(database.writableDatabase, threadId, assistantMessageId, events)
            database.writableDatabase.setTransactionSuccessful()
        } finally {
            database.writableDatabase.endTransaction()
        }
    }

    @Synchronized
    override fun upsert(message: LocalChatMessage) {
        require(message.content.isNotBlank() || message.attachments.isNotEmpty()) { "Canonical chat message must have content or attachments" }
        database.writableDatabase.beginTransaction()
        try {
            write(database.writableDatabase, message)
            database.writableDatabase.setTransactionSuccessful()
        } finally {
            database.writableDatabase.endTransaction()
        }
    }

    @Synchronized
    override fun upsert(messages: List<LocalChatMessage>) {
        database.writableDatabase.beginTransaction()
        try {
            messages.forEach { message ->
                require(message.content.isNotBlank() || message.attachments.isNotEmpty()) { "Canonical chat message must have content or attachments" }
                write(database.writableDatabase, message)
            }
            database.writableDatabase.setTransactionSuccessful()
        } finally {
            database.writableDatabase.endTransaction()
        }
    }

    @Synchronized
    override fun localThreadIdForCanonicalThread(canonicalThreadId: String): String? =
        database.readableDatabase.query(
            TABLE_EXECUTIONS,
            arrayOf("local_thread_id"),
            "canonical_thread_id = ?",
            arrayOf(canonicalThreadId),
            null,
            null,
            "updated_at_epoch_ms DESC, rowid DESC",
            "1",
        ).use { cursor ->
            cursor.takeIf { it.moveToFirst() }?.getString(0)
        }

    @Synchronized
    override fun pendingExecutions(): List<LocalChatExecution> = buildList {
        database.readableDatabase.query(
            TABLE_EXECUTIONS,
            EXECUTION_COLUMNS,
            "status = ?",
            arrayOf(LocalChatExecutionStatus.Running.name),
            null,
            null,
            "created_at_epoch_ms ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                fun nullableString(name: String): String? = cursor.getColumnIndexOrThrow(name).let { index ->
                    if (cursor.isNull(index)) null else cursor.getString(index)
                }
                fun nullableInt(name: String): Int? = cursor.getColumnIndexOrThrow(name).let { index ->
                    if (cursor.isNull(index)) null else cursor.getInt(index)
                }
                add(LocalChatExecution(
                    executionId = cursor.getString(cursor.getColumnIndexOrThrow("execution_id")),
                    localThreadId = cursor.getString(cursor.getColumnIndexOrThrow("local_thread_id")),
                    provider = cursor.getString(cursor.getColumnIndexOrThrow("provider")),
                    canonicalThreadId = cursor.getString(cursor.getColumnIndexOrThrow("canonical_thread_id")),
                    userMessageId = cursor.getString(cursor.getColumnIndexOrThrow("user_message_id")),
                    assistantMessageId = cursor.getString(cursor.getColumnIndexOrThrow("assistant_message_id")),
                    status = LocalChatExecutionStatus.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("status"))),
                    lastError = nullableString("last_error"),
                    personaVersion = nullableInt("persona_version"),
                    reanchorIntent = cursor.getInt(cursor.getColumnIndexOrThrow("reanchor_intent")) == 1,
                    createdAtEpochMillis = cursor.getLong(cursor.getColumnIndexOrThrow("created_at_epoch_ms")),
                    updatedAtEpochMillis = cursor.getLong(cursor.getColumnIndexOrThrow("updated_at_epoch_ms")),
                ))
            }
        }
    }

    @Synchronized
    override fun upsertExecution(execution: LocalChatExecution) {
        val values = ContentValues().apply {
            put("execution_id", execution.executionId)
            put("local_thread_id", execution.localThreadId)
            put("provider", execution.provider)
            put("canonical_thread_id", execution.canonicalThreadId)
            put("user_message_id", execution.userMessageId)
            put("assistant_message_id", execution.assistantMessageId)
            put("status", execution.status.name)
            execution.lastError?.let { put("last_error", it) } ?: putNull("last_error")
            execution.personaVersion?.let { put("persona_version", it) } ?: putNull("persona_version")
            put("reanchor_intent", if (execution.reanchorIntent) 1 else 0)
            put("created_at_epoch_ms", execution.createdAtEpochMillis)
            put("updated_at_epoch_ms", execution.updatedAtEpochMillis)
        }
        database.writableDatabase.insertWithOnConflict(
            TABLE_EXECUTIONS,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        ).also { rowId -> check(rowId != -1L) { "Could not persist local chat execution" } }
    }

    @Synchronized
    override fun upsert(
        threadId: String,
        assistantMessageId: String,
        eventId: String,
        detail: ToolDetailEnvelope,
    ): CachedToolDetail? {
        if (threadId.isBlank() || assistantMessageId.isBlank() || !hasValidToolDetailIdentity(eventId, detail)) return null
        val now = nowEpochMillis()
        val db = database.writableDatabase
        db.beginTransaction()
        return try {
            cleanupExpired(db, now)
            val existing = readToolDetail(db, assistantMessageId, eventId, now)
            if (existing != null && existing.threadId != threadId) return null
            val merged = if (existing == null) detail else mergeToolDetails(existing.detail, detail) ?: return null
            if (!hasValidToolDetailIdentity(eventId, merged)) return null
            val payload = serializeToolDetailEnvelope(merged)
            if (payload.toByteArray(Charsets.UTF_8).size > MAX_TOOL_DETAIL_BYTES) return null
            val createdAt = existing?.createdAtEpochMillis ?: now
            val expiresAt = existing?.expiresAtEpochMillis ?: toolDetailExpiresAt(createdAt)
            val values = ContentValues().apply {
                put("thread_id", threadId)
                put("assistant_message_id", assistantMessageId)
                put("event_id", eventId)
                put("call_id", merged.callId)
                put("detail_kind", merged.detailKind())
                put("schema_version", merged.schemaVersion)
                put("safe_payload", payload)
                put("created_at_epoch_ms", createdAt)
                put("expires_at_epoch_ms", expiresAt)
                put("truncated", if (merged.truncated) 1 else 0)
            }
            db.insertWithOnConflict(TABLE_TOOL_DETAILS, null, values, SQLiteDatabase.CONFLICT_REPLACE)
                .also { rowId -> check(rowId != -1L) { "Could not persist safe tool detail" } }
            db.setTransactionSuccessful()
            CachedToolDetail(threadId, assistantMessageId, eventId, merged, createdAt, expiresAt)
        } finally {
            db.endTransaction()
        }
    }

    @Synchronized
    override fun detail(
        assistantMessageId: String,
        eventId: String,
    ): CachedToolDetail? {
        val now = nowEpochMillis()
        val db = database.writableDatabase
        cleanupExpired(db, now)
        return readToolDetail(db, assistantMessageId, eventId, now)
    }

    @Synchronized
    override fun deleteForAssistant(assistantMessageId: String): Int =
        database.writableDatabase.delete(
            TABLE_TOOL_DETAILS,
            "assistant_message_id = ?",
            arrayOf(assistantMessageId),
        )

    @Synchronized
    override fun deleteForThread(threadId: String): Int =
        database.writableDatabase.delete(TABLE_TOOL_DETAILS, "thread_id = ?", arrayOf(threadId))

    @Synchronized
    override fun cleanupExpired(nowEpochMillis: Long): Int =
        cleanupExpired(database.writableDatabase, nowEpochMillis)

    private fun cleanupExpired(db: SQLiteDatabase, nowEpochMillis: Long): Int =
        db.delete(TABLE_TOOL_DETAILS, "expires_at_epoch_ms <= ?", arrayOf(nowEpochMillis.toString()))

    private fun readToolDetail(
        db: SQLiteDatabase,
        assistantMessageId: String,
        eventId: String,
        nowEpochMillis: Long,
    ): CachedToolDetail? = db.query(
        TABLE_TOOL_DETAILS,
        TOOL_DETAIL_COLUMNS,
        "assistant_message_id = ? AND event_id = ? AND expires_at_epoch_ms > ?",
        arrayOf(assistantMessageId, eventId, nowEpochMillis.toString()),
        null,
        null,
        null,
        "1",
    ).use { cursor ->
        if (!cursor.moveToFirst()) return@use null
        val callId = cursor.getString(cursor.getColumnIndexOrThrow("call_id"))
        val payload = cursor.getString(cursor.getColumnIndexOrThrow("safe_payload"))
        val parsed = parseStoredToolDetailEnvelope(payload, callId) ?: run {
            db.delete(
                TABLE_TOOL_DETAILS,
                "assistant_message_id = ? AND event_id = ?",
                arrayOf(assistantMessageId, eventId),
            )
            return@use null
        }
        if (!hasValidToolDetailIdentity(eventId, parsed)
            || parsed.detailKind() != cursor.getString(cursor.getColumnIndexOrThrow("detail_kind"))
            || parsed.schemaVersion != cursor.getInt(cursor.getColumnIndexOrThrow("schema_version"))
        ) {
            db.delete(
                TABLE_TOOL_DETAILS,
                "assistant_message_id = ? AND event_id = ?",
                arrayOf(assistantMessageId, eventId),
            )
            return@use null
        }
        CachedToolDetail(
            threadId = cursor.getString(cursor.getColumnIndexOrThrow("thread_id")),
            assistantMessageId = cursor.getString(cursor.getColumnIndexOrThrow("assistant_message_id")),
            eventId = cursor.getString(cursor.getColumnIndexOrThrow("event_id")),
            detail = parsed,
            createdAtEpochMillis = cursor.getLong(cursor.getColumnIndexOrThrow("created_at_epoch_ms")),
            expiresAtEpochMillis = cursor.getLong(cursor.getColumnIndexOrThrow("expires_at_epoch_ms")),
        )
    }

    private fun ToolDetailEnvelope.detailKind(): String = when (this) {
        is ToolDetailEnvelope.GenericTool -> "generic_tool"
        is ToolDetailEnvelope.Command -> "command"
    }

    private fun write(db: SQLiteDatabase, message: LocalChatMessage) {
        val values = ContentValues().apply {
            put("local_message_id", message.localMessageId)
            put("thread_id", message.threadId)
            put("role", message.role.name)
            put("sender", message.sender)
            put("content", message.content)
            put("created_at_epoch_ms", message.createdAtEpochMillis)
            message.receivedAtEpochMillis?.let { put("received_at_epoch_ms", it) } ?: putNull("received_at_epoch_ms")
            put("status", message.status.name)
            message.runtime?.let { put("runtime", it) } ?: putNull("runtime")
            message.adapterId?.let { put("adapter_id", it) } ?: putNull("adapter_id")
        }
        db.insertWithOnConflict(
            TABLE_MESSAGES,
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE,
        ).also { rowId -> check(rowId != -1L) { "Could not persist local chat message" } }
        db.delete(TABLE_ATTACHMENTS, "local_message_id = ?", arrayOf(message.localMessageId))
        message.attachments.forEachIndexed { index, attachment -> writeAttachment(db, message.localMessageId, index, attachment) }
        if (message.processEvents.isNotEmpty()) {
            replaceProcessEvents(db, message.threadId, message.localMessageId, message.processEvents)
        }
    }

    private fun replaceProcessEvents(
        db: SQLiteDatabase,
        threadId: String,
        assistantMessageId: String,
        events: List<ChatProcessEvent>,
    ) {
        db.delete(TABLE_PROCESS_EVENTS, "thread_id = ? AND assistant_message_id = ?", arrayOf(threadId, assistantMessageId))
        events.forEachIndexed { position, event ->
            db.insertOrThrow(TABLE_PROCESS_EVENTS, null, ContentValues().apply {
                put("thread_id", threadId)
                put("assistant_message_id", assistantMessageId)
                put("event_id", event.id)
                put("position", position)
                put("kind", event.kind.name)
                put("title", event.title)
                put("status", event.status.name)
                event.detail?.let { put("detail", it) } ?: putNull("detail")
            })
        }
    }

    private fun readProcessEvents(db: SQLiteDatabase, threadId: String): Map<String, List<ChatProcessEvent>> {
        val events = linkedMapOf<String, MutableList<ChatProcessEvent>>()
        db.query(
            TABLE_PROCESS_EVENTS,
            PROCESS_EVENT_COLUMNS,
            "thread_id = ?",
            arrayOf(threadId),
            null,
            null,
            "assistant_message_id ASC, position ASC",
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val kind = runCatching {
                    ChatProcessKind.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("kind")))
                }.getOrNull() ?: continue
                val status = runCatching {
                    ChatProcessStatus.valueOf(cursor.getString(cursor.getColumnIndexOrThrow("status")))
                }.getOrNull() ?: continue
                val assistantMessageId = cursor.getString(cursor.getColumnIndexOrThrow("assistant_message_id"))
                val detailIndex = cursor.getColumnIndexOrThrow("detail")
                events.getOrPut(assistantMessageId, ::mutableListOf).add(ChatProcessEvent(
                    id = cursor.getString(cursor.getColumnIndexOrThrow("event_id")),
                    kind = kind,
                    title = cursor.getString(cursor.getColumnIndexOrThrow("title")),
                    status = status,
                    detail = if (cursor.isNull(detailIndex)) null else cursor.getString(detailIndex),
                ))
            }
        }
        return events
    }

    private fun writeAttachment(db: SQLiteDatabase, messageId: String, position: Int, attachment: ChatAttachment) {
        val values = ContentValues().apply {
            put("local_attachment_id", attachment.attachmentId)
            put("local_message_id", messageId)
            put("position", position)
            put("type", attachment.type)
            put("lifecycle", attachment.lifecycle.name)
            put("availability", attachment.availability.name)
            put("created_at_epoch_ms", attachment.createdAtEpochMillis)
            when (attachment) {
                is ChatMediaAttachment -> {
                    put("media_asset_id", attachment.mediaAssetId)
                    put("storage_ref", attachment.storageRef)
                    put("mime_type", attachment.mimeType)
                    put("size_bytes", attachment.sizeBytes)
                    put("name", attachment.name)
                    attachment.width?.let { put("width", it) }
                    attachment.height?.let { put("height", it) }
                    attachment.localCachePath?.let { put("local_cache_path", it) }
                    attachment.remoteExpiresAtEpochMillis?.let { put("remote_expires_at_epoch_ms", it) }
                }
                is ChatLocationAttachment -> {
                    put("latitude", attachment.latitude)
                    put("longitude", attachment.longitude)
                    attachment.accuracyMeters?.let { put("accuracy", it) }
                    put("captured_at_epoch_ms", attachment.capturedAtEpochMillis)
                    attachment.address?.let { put("address", it) }
                }
            }
        }
        db.insertOrThrow(TABLE_ATTACHMENTS, null, values)
    }

    private fun readAttachments(db: SQLiteDatabase, messageId: String): List<ChatAttachment> = buildList {
        db.query(TABLE_ATTACHMENTS, null, "local_message_id = ?", arrayOf(messageId), null, null, "position ASC").use { cursor ->
            while (cursor.moveToNext()) {
                fun string(name: String): String? = cursor.getColumnIndexOrThrow(name).let { if (cursor.isNull(it)) null else cursor.getString(it) }
                fun long(name: String): Long? = cursor.getColumnIndexOrThrow(name).let { if (cursor.isNull(it)) null else cursor.getLong(it) }
                fun int(name: String): Int? = cursor.getColumnIndexOrThrow(name).let { if (cursor.isNull(it)) null else cursor.getInt(it) }
                fun double(name: String): Double? = cursor.getColumnIndexOrThrow(name).let { if (cursor.isNull(it)) null else cursor.getDouble(it) }
                val attachmentId = string("local_attachment_id")!!
                val lifecycle = ChatAttachmentLifecycle.valueOf(string("lifecycle")!!)
                val availability = ChatAttachmentAvailability.valueOf(string("availability")!!)
                val createdAt = long("created_at_epoch_ms")!!
                when (string("type")) {
                    "photo", "file", "audio" -> ChatMediaAttachment(
                        type = string("type")!!,
                        mediaAssetId = string("media_asset_id"),
                        storageRef = string("storage_ref"),
                        mimeType = string("mime_type")!!,
                        sizeBytes = long("size_bytes")!!,
                        name = string("name")!!,
                        width = int("width"),
                        height = int("height"),
                        localCachePath = string("local_cache_path"),
                        attachmentId = attachmentId,
                        lifecycle = lifecycle,
                        availability = availability,
                        createdAtEpochMillis = createdAt,
                        remoteExpiresAtEpochMillis = long("remote_expires_at_epoch_ms"),
                    ).let { add(it.copy(availability = it.resolvedAvailability())) }
                    "location" -> add(ChatLocationAttachment(
                        latitude = double("latitude")!!,
                        longitude = double("longitude")!!,
                        accuracyMeters = double("accuracy")?.toFloat(),
                        capturedAtEpochMillis = long("captured_at_epoch_ms")!!,
                        address = string("address"),
                        attachmentId = attachmentId,
                        lifecycle = lifecycle,
                        availability = availability,
                        createdAtEpochMillis = createdAt,
                    ))
                }
            }
        }
    }

    private class ChatHistoryDatabase(context: Context, databaseName: String) : SQLiteOpenHelper(context, databaseName, null, DATABASE_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            createMessagesTable(db)
            createAttachmentsTable(db)
            createExecutionsTable(db)
            createProcessEventsTable(db)
            createToolDetailsTable(db)
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            var migratedVersion = oldVersion
            if (migratedVersion == 1 && newVersion >= 2) {
                db.execSQL("ALTER TABLE $TABLE_MESSAGES RENAME TO chat_messages_v1")
                db.execSQL("DROP INDEX IF EXISTS chat_messages_thread_time_idx")
                createMessagesTable(db)
                db.execSQL("INSERT INTO $TABLE_MESSAGES SELECT * FROM chat_messages_v1")
                db.execSQL("DROP TABLE chat_messages_v1")
                createAttachmentsTable(db)
                migratedVersion = 3
            } else if (migratedVersion == 2 && newVersion >= 3) {
                migratePreviewAttachments(db)
                migratedVersion = 3
            }
            if (migratedVersion == 3 && newVersion >= 4) {
                createExecutionsTable(db)
                migratedVersion = 4
            }
            if (migratedVersion == 4 && newVersion >= 5) {
                createProcessEventsTable(db)
                migratedVersion = 5
            }
            if (migratedVersion == 5 && newVersion >= 6) {
                createToolDetailsTable(db)
                migratedVersion = 6
            }
            if (migratedVersion != newVersion) {
                error("A non-destructive chat history migration is required from version $oldVersion to $newVersion")
            }
        }

        private fun createMessagesTable(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE_MESSAGES (
                    local_message_id TEXT PRIMARY KEY NOT NULL,
                    thread_id TEXT NOT NULL,
                    role TEXT NOT NULL CHECK(role IN ('User', 'Assistant')),
                    sender TEXT NOT NULL,
                    content TEXT NOT NULL,
                    created_at_epoch_ms INTEGER NOT NULL,
                    received_at_epoch_ms INTEGER,
                    status TEXT NOT NULL CHECK(status IN ('Sending', 'Sent', 'Failed')),
                    runtime TEXT,
                    adapter_id TEXT
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX chat_messages_thread_time_idx ON $TABLE_MESSAGES(thread_id, created_at_epoch_ms)")
        }

        private fun createAttachmentsTable(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_ATTACHMENTS (
                    local_attachment_id TEXT PRIMARY KEY NOT NULL,
                    local_message_id TEXT NOT NULL,
                    position INTEGER NOT NULL,
                    type TEXT NOT NULL CHECK(type IN ('photo', 'file', 'location', 'audio')),
                    lifecycle TEXT NOT NULL CHECK(lifecycle IN ('LOCAL', 'EPHEMERAL', 'DURABLE')),
                    availability TEXT NOT NULL CHECK(availability IN ('AVAILABLE', 'UPLOADING', 'FAILED', 'EXPIRED', 'LOCAL_MISSING', 'UNAVAILABLE')),
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
            db.execSQL("CREATE INDEX IF NOT EXISTS chat_attachments_message_idx ON $TABLE_ATTACHMENTS(local_message_id, position)")
        }

        private fun createExecutionsTable(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_EXECUTIONS (
                    execution_id TEXT PRIMARY KEY NOT NULL,
                    local_thread_id TEXT NOT NULL,
                    provider TEXT NOT NULL,
                    canonical_thread_id TEXT NOT NULL,
                    user_message_id TEXT NOT NULL,
                    assistant_message_id TEXT NOT NULL,
                    status TEXT NOT NULL CHECK(status IN ('Running', 'Completed', 'Failed')),
                    last_error TEXT,
                    persona_version INTEGER,
                    reanchor_intent INTEGER NOT NULL DEFAULT 0,
                    created_at_epoch_ms INTEGER NOT NULL,
                    updated_at_epoch_ms INTEGER NOT NULL
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS chat_executions_status_idx ON $TABLE_EXECUTIONS(status, updated_at_epoch_ms)")
        }

        private fun createProcessEventsTable(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_PROCESS_EVENTS (
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
            db.execSQL("CREATE INDEX IF NOT EXISTS chat_process_events_thread_idx ON $TABLE_PROCESS_EVENTS(thread_id, assistant_message_id, position)")
        }

        private fun createToolDetailsTable(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE IF NOT EXISTS $TABLE_TOOL_DETAILS (
                    thread_id TEXT NOT NULL,
                    assistant_message_id TEXT NOT NULL,
                    event_id TEXT NOT NULL,
                    call_id TEXT NOT NULL,
                    detail_kind TEXT NOT NULL CHECK(detail_kind IN ('generic_tool', 'command')),
                    schema_version INTEGER NOT NULL,
                    safe_payload TEXT NOT NULL,
                    created_at_epoch_ms INTEGER NOT NULL,
                    expires_at_epoch_ms INTEGER NOT NULL,
                    truncated INTEGER NOT NULL CHECK(truncated IN (0, 1)),
                    PRIMARY KEY (assistant_message_id, event_id)
                )
                """.trimIndent(),
            )
            db.execSQL("CREATE INDEX IF NOT EXISTS chat_tool_details_thread_idx ON $TABLE_TOOL_DETAILS(thread_id, assistant_message_id)")
            db.execSQL("CREATE INDEX IF NOT EXISTS chat_tool_details_expiry_idx ON $TABLE_TOOL_DETAILS(expires_at_epoch_ms)")
        }

        private fun migratePreviewAttachments(db: SQLiteDatabase) {
            db.execSQL("ALTER TABLE $TABLE_ATTACHMENTS RENAME TO chat_message_attachments_preview_v2")
            db.execSQL("DROP INDEX IF EXISTS chat_attachments_message_idx")
            createAttachmentsTable(db)
            db.execSQL(
                """
                INSERT INTO $TABLE_ATTACHMENTS (
                    local_attachment_id, local_message_id, position, type, lifecycle, availability,
                    created_at_epoch_ms, media_asset_id, storage_ref, mime_type, size_bytes, name,
                    width, height, local_cache_path, latitude, longitude, accuracy,
                    captured_at_epoch_ms, address
                )
                SELECT
                    local_attachment_id, local_message_id, position, type,
                    CASE WHEN type IN ('photo', 'file') THEN 'EPHEMERAL' ELSE 'LOCAL' END,
                    'AVAILABLE',
                    COALESCE(captured_at_epoch_ms, 0),
                    media_asset_id, storage_ref, mime_type, size_bytes, name,
                    width, height, local_cache_path, latitude, longitude, accuracy,
                    captured_at_epoch_ms, address
                FROM chat_message_attachments_preview_v2
                """.trimIndent(),
            )
            db.execSQL("DROP TABLE chat_message_attachments_preview_v2")
        }
    }

    private companion object {
        const val DATABASE_NAME = "lovehouse_chat.db"
        const val DATABASE_VERSION = 6
        const val TABLE_MESSAGES = "chat_messages"
        const val TABLE_ATTACHMENTS = "chat_message_attachments"
        const val TABLE_EXECUTIONS = "chat_executions"
        const val TABLE_PROCESS_EVENTS = "chat_process_events"
        const val TABLE_TOOL_DETAILS = "chat_tool_details"
        const val MAX_TOOL_DETAIL_BYTES = 256 * 1024
        val MESSAGE_COLUMNS = arrayOf(
            "local_message_id",
            "thread_id",
            "role",
            "sender",
            "content",
            "created_at_epoch_ms",
            "received_at_epoch_ms",
            "status",
            "runtime",
            "adapter_id",
        )
        val EXECUTION_COLUMNS = arrayOf(
            "execution_id", "local_thread_id", "provider", "canonical_thread_id",
            "user_message_id", "assistant_message_id", "status", "last_error",
            "persona_version", "reanchor_intent", "created_at_epoch_ms", "updated_at_epoch_ms",
        )
        val PROCESS_EVENT_COLUMNS = arrayOf(
            "assistant_message_id", "event_id", "position", "kind", "title", "status", "detail",
        )
        val TOOL_DETAIL_COLUMNS = arrayOf(
            "thread_id", "assistant_message_id", "event_id", "call_id", "detail_kind",
            "schema_version", "safe_payload", "created_at_epoch_ms", "expires_at_epoch_ms", "truncated",
        )
    }
}
