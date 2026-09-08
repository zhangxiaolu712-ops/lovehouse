package fyi.b612.lovehouse

import android.content.ContentValues
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import fyi.b612.lovehouse.feature.chat.ChatAttachmentLifecycle
import fyi.b612.lovehouse.feature.chat.ChatMediaAttachment
import fyi.b612.lovehouse.feature.chat.LocalChatDeliveryStatus
import fyi.b612.lovehouse.feature.chat.LocalChatMessage
import fyi.b612.lovehouse.feature.chat.LocalChatRole
import fyi.b612.lovehouse.feature.chat.SQLiteLocalChatMessageRepository
import org.junit.Assert.assertEquals
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
        repository.close()

        val reopened = SQLiteLocalChatMessageRepository(context, databaseName)
        val messages = reopened.messages("thread")
        assertEquals(listOf("old-text", "new-media"), messages.map { it.localMessageId })
        assertEquals(1, messages.last().attachments.size)
        assertTrue(messages.last().content.isEmpty())
        reopened.close()
        context.deleteDatabase(databaseName)
    }
}
