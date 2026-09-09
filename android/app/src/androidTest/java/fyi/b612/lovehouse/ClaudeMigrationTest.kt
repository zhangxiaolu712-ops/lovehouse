package fyi.b612.lovehouse

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import fyi.b612.lovehouse.feature.chat.ClaudeRuntime
import fyi.b612.lovehouse.feature.chat.ClaudeWebHistoryImporter
import fyi.b612.lovehouse.feature.chat.SQLiteLocalChatMessageRepository
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ClaudeMigrationTest {
    @Test
    fun importsTwelveBodiesExactlyOnceIntoExistingHistoryDatabase() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val databaseName = "claude-migration-test.db"
        context.deleteDatabase(databaseName)
        val messages = JSONArray().apply {
            repeat(12) { index ->
                put(
                    JSONObject()
                        .put("role", if (index % 2 == 0) "user" else "assistant")
                        .put("content", "migration fixture $index"),
                )
            }
        }
        val payload = JSONObject()
            .put("source", "idempotence-test")
            .put("thread_id", ClaudeRuntime.threadId)
            .put("window_id", ClaudeRuntime.windowId)
            .put("messages", messages)
            .toString()
        val repository = SQLiteLocalChatMessageRepository(context, databaseName)
        try {
            val importer = ClaudeWebHistoryImporter(repository) { payload }
            assertEquals(12, importer.importIfPresent())
            assertEquals(0, importer.importIfPresent())
            assertEquals(12, repository.messages(ClaudeRuntime.threadId).size)
        } finally {
            repository.close()
            context.deleteDatabase(databaseName)
        }
    }
}
