package fyi.b612.lovehouse.feature.chat

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

enum class LocalChatRole { User, Assistant }

enum class LocalChatDeliveryStatus { Sending, Sent, Failed }

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
)

interface LocalChatMessageRepository {
    fun messages(threadId: String): List<LocalChatMessage>
    fun upsert(message: LocalChatMessage)
    fun upsert(messages: List<LocalChatMessage>) = messages.forEach(::upsert)
}

object NoOpLocalChatMessageRepository : LocalChatMessageRepository {
    override fun messages(threadId: String): List<LocalChatMessage> = emptyList()
    override fun upsert(message: LocalChatMessage) = Unit
    override fun upsert(messages: List<LocalChatMessage>) = Unit
}

class SQLiteLocalChatMessageRepository(
    context: Context,
    databaseName: String = DATABASE_NAME,
) : LocalChatMessageRepository {
    private val database = ChatHistoryDatabase(context.applicationContext, databaseName)

    internal fun close() = database.close()

    @Synchronized
    override fun messages(threadId: String): List<LocalChatMessage> = buildList {
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
                    ),
                )
            }
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
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            if (oldVersion == 1 && newVersion >= 2) {
                db.execSQL("ALTER TABLE $TABLE_MESSAGES RENAME TO chat_messages_v1")
                db.execSQL("DROP INDEX IF EXISTS chat_messages_thread_time_idx")
                createMessagesTable(db)
                db.execSQL("INSERT INTO $TABLE_MESSAGES SELECT * FROM chat_messages_v1")
                db.execSQL("DROP TABLE chat_messages_v1")
                createAttachmentsTable(db)
                if (newVersion == 2) return
            } else if (oldVersion == 2 && newVersion >= 3) {
                migratePreviewAttachments(db)
                return
            }
            if (oldVersion != 1 || newVersion != 3) {
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
        const val DATABASE_VERSION = 3
        const val TABLE_MESSAGES = "chat_messages"
        const val TABLE_ATTACHMENTS = "chat_message_attachments"
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
    }
}
