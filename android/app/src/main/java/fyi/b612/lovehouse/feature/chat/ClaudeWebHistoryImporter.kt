package fyi.b612.lovehouse.feature.chat

import android.content.Context
import java.security.MessageDigest
import org.json.JSONObject

/** Imports the optional owner-private Web snapshot into the existing local history store. */
class ClaudeWebHistoryImporter(
    private val repository: LocalChatMessageRepository,
    private val payloadLoader: () -> String?,
) {
    constructor(context: Context, repository: LocalChatMessageRepository) : this(
        repository = repository,
        payloadLoader = {
            val appContext = context.applicationContext
            val resourceId = appContext.resources.getIdentifier(
                "claude_v1_web_migration",
                "raw",
                appContext.packageName,
            )
            resourceId.takeIf { it != 0 }?.let { id ->
                appContext.resources.openRawResource(id).bufferedReader(Charsets.UTF_8).use { it.readText() }
            }
        },
    )

    fun importIfPresent(): Int {
        val payload = payloadLoader()?.let(::JSONObject) ?: return 0
        require(payload.getString("thread_id") == ClaudeRuntime.threadId)
        require(payload.getString("window_id") == ClaudeRuntime.windowId)
        val source = payload.getString("source")
        val messages = payload.getJSONArray("messages")
        val existingIds = repository.messages(ClaudeRuntime.threadId)
            .mapTo(hashSetOf(), LocalChatMessage::localMessageId)
        val missing = buildList {
            for (index in 0 until messages.length()) {
                val item = messages.getJSONObject(index)
                val roleValue = item.getString("role")
                val content = item.getString("content")
                require(roleValue == "user" || roleValue == "assistant")
                val messageId = "migration-${sha256("$source\u0000$index\u0000$roleValue\u0000$content")}"
                if (messageId in existingIds) continue
                val role = if (roleValue == "user") LocalChatRole.User else LocalChatRole.Assistant
                add(
                    LocalChatMessage(
                        localMessageId = messageId,
                        threadId = ClaudeRuntime.threadId,
                        role = role,
                        sender = if (role == LocalChatRole.User) "owner" else ClaudeRuntime.personaId,
                        content = content,
                        createdAtEpochMillis = index.toLong() + 1L,
                        status = LocalChatDeliveryStatus.Sent,
                    ),
                )
            }
        }
        repository.upsert(missing)
        return missing.size
    }

    private fun sha256(value: String): String = MessageDigest.getInstance("SHA-256")
        .digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
