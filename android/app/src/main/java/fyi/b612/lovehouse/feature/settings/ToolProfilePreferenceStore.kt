package fyi.b612.lovehouse.feature.settings

import android.content.Context

interface ToolProfilePreferenceStore {
    fun profile(personaId: String, threadId: String): LocalToolProfile
    fun setPreferred(personaId: String, threadId: String, toolId: String, enabled: Boolean)
}

class AndroidToolProfilePreferenceStore(context: Context) : ToolProfilePreferenceStore {
    private val preferences = context.getSharedPreferences("lovehouse_tool_profiles_v1", Context.MODE_PRIVATE)

    override fun profile(personaId: String, threadId: String): LocalToolProfile = LocalToolProfile(
        personaId = personaId,
        threadId = threadId,
        preferredToolIds = preferences.getStringSet(key(personaId, threadId), emptySet()).orEmpty().toSet(),
    )

    override fun setPreferred(personaId: String, threadId: String, toolId: String, enabled: Boolean) {
        val updated = profile(personaId, threadId).preferredToolIds.toMutableSet().apply {
            if (enabled) add(toolId) else remove(toolId)
        }
        preferences.edit().putStringSet(key(personaId, threadId), updated).apply()
    }

    private fun key(personaId: String, threadId: String) = "$personaId::$threadId"
}
