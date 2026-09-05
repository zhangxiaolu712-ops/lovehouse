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

class SQLiteLocalChatMessageRepository(context: Context) : LocalChatMessageRepository {
    private val database = ChatHistoryDatabase(context.applicationContext)

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
                add(
                    LocalChatMessage(
                        localMessageId = cursor.getString(cursor.getColumnIndexOrThrow("local_message_id")),
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
                    ),
                )
            }
        }
    }

    @Synchronized
    override fun upsert(message: LocalChatMessage) {
        require(message.content.isNotBlank()) { "Canonical chat content must not be blank" }
        write(database.writableDatabase, message)
    }

    @Synchronized
    override fun upsert(messages: List<LocalChatMessage>) {
        database.writableDatabase.beginTransaction()
        try {
            messages.forEach { message ->
                require(message.content.isNotBlank()) { "Canonical chat content must not be blank" }
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
    }

    private class ChatHistoryDatabase(context: Context) : SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL(
                """
                CREATE TABLE $TABLE_MESSAGES (
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
            db.execSQL("CREATE INDEX chat_messages_thread_time_idx ON $TABLE_MESSAGES(thread_id, created_at_epoch_ms)")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            error("A non-destructive chat history migration is required from version $oldVersion to $newVersion")
        }
    }

    private companion object {
        const val DATABASE_NAME = "lovehouse_chat.db"
        const val DATABASE_VERSION = 1
        const val TABLE_MESSAGES = "chat_messages"
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
