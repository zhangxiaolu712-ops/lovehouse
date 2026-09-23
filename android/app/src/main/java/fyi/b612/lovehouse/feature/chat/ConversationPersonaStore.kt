package fyi.b612.lovehouse.feature.chat

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

interface ConversationPersonaStore {
    fun personaId(threadId: String): String?
    fun setPersonaId(threadId: String, personaId: String)
    fun savedThreads(): List<ChatThreadSummary> = emptyList()
    fun saveThread(thread: ChatThreadSummary) = Unit
    fun reanchorPending(threadId: String): Boolean = false
    fun setReanchorPending(threadId: String, pending: Boolean) = Unit
    fun materializedPersonaVersion(threadId: String): Int? = null
    fun setMaterializedPersonaVersion(threadId: String, version: Int) = Unit
}

class AndroidConversationPersonaStore(context: Context) : ConversationPersonaStore {
    private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

    override fun personaId(threadId: String): String? =
        preferences.getString(threadId, null)?.takeIf(String::isNotBlank)

    override fun setPersonaId(threadId: String, personaId: String) {
        require(threadId.isNotBlank()) { "threadId must not be blank" }
        require(personaId.isNotBlank()) { "personaId must not be blank" }
        preferences.edit().putString(threadId, personaId).apply()
    }

    override fun savedThreads(): List<ChatThreadSummary> = runCatching {
        val array = JSONArray(preferences.getString(THREADS_KEY, "[]"))
        buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.toThread()?.let(::add)
            }
        }
    }.getOrDefault(emptyList())

    override fun saveThread(thread: ChatThreadSummary) {
        val updated = (savedThreads().filterNot { it.threadId == thread.threadId } + thread)
        preferences.edit().putString(
            THREADS_KEY,
            JSONArray(updated.map(ChatThreadSummary::toJson)).toString(),
        ).apply()
    }

    override fun reanchorPending(threadId: String): Boolean =
        preferences.getBoolean("reanchor:$threadId", false)

    override fun setReanchorPending(threadId: String, pending: Boolean) {
        preferences.edit().putBoolean("reanchor:$threadId", pending).apply()
    }

    override fun materializedPersonaVersion(threadId: String): Int? =
        preferences.getInt("version:$threadId", -1).takeIf { it >= 0 }

    override fun setMaterializedPersonaVersion(threadId: String, version: Int) {
        preferences.edit().putInt("version:$threadId", version).apply()
    }

    private companion object {
        const val PREFERENCES_NAME = "lovehouse_conversation_personas_v1"
        const val THREADS_KEY = "saved_threads"
    }
}

internal class InMemoryConversationPersonaStore : ConversationPersonaStore {
    private val values = linkedMapOf<String, String>()
    private val threads = linkedMapOf<String, ChatThreadSummary>()
    private val pending = linkedMapOf<String, Boolean>()
    private val versions = linkedMapOf<String, Int>()

    override fun personaId(threadId: String): String? = values[threadId]

    override fun setPersonaId(threadId: String, personaId: String) {
        values[threadId] = personaId
    }

    override fun savedThreads(): List<ChatThreadSummary> = threads.values.toList()

    override fun saveThread(thread: ChatThreadSummary) {
        threads[thread.threadId] = thread
    }

    override fun reanchorPending(threadId: String): Boolean = pending[threadId] == true
    override fun setReanchorPending(threadId: String, pending: Boolean) { this.pending[threadId] = pending }
    override fun materializedPersonaVersion(threadId: String): Int? = versions[threadId]
    override fun setMaterializedPersonaVersion(threadId: String, version: Int) { versions[threadId] = version }
}

internal fun legacyPersonaIdForThread(threadId: String): String? = when (threadId) {
    ClaudeRuntime.threadId -> ClaudeRuntime.personaId
    "agent-codex", stableCodexThreadId() -> CodexRuntime.personaId
    "persona-gpt" -> "g"
    else -> null
}

private fun ChatThreadSummary.toJson() = JSONObject().apply {
    put("thread_id", threadId)
    put("kind", kind.name)
    put("title", title)
    put("preview", preview)
    put("updated_at", updatedAt)
    put("unread_count", unreadCount)
    put("pinned", pinned)
    put("presence", presence?.name ?: JSONObject.NULL)
    put("speaker_label", speakerLabel ?: JSONObject.NULL)
    put("expires_at_label", expiresAtLabel ?: JSONObject.NULL)
    put("task_id", taskId ?: JSONObject.NULL)
    put("avatar_glyph", avatarGlyph ?: JSONObject.NULL)
    put("persona_id", personaId ?: JSONObject.NULL)
}

private fun JSONObject.toThread(): ChatThreadSummary? {
    val id = optString("thread_id").takeIf(String::isNotBlank) ?: return null
    val kind = runCatching { ChatThreadKind.valueOf(optString("kind")) }.getOrNull() ?: return null
    return ChatThreadSummary(
        threadId = id,
        kind = kind,
        title = optString("title"),
        preview = optString("preview"),
        updatedAt = optString("updated_at"),
        unreadCount = optInt("unread_count"),
        pinned = optBoolean("pinned"),
        presence = nullableString("presence")?.let { runCatching { ChatPresence.valueOf(it) }.getOrNull() },
        speakerLabel = nullableString("speaker_label"),
        expiresAtLabel = nullableString("expires_at_label"),
        taskId = nullableString("task_id"),
        avatarGlyph = nullableString("avatar_glyph"),
        personaId = nullableString("persona_id"),
    )
}

private fun JSONObject.nullableString(key: String): String? =
    takeIf { has(key) && !isNull(key) }
        ?.optString(key)
        ?.takeIf(String::isNotBlank)
