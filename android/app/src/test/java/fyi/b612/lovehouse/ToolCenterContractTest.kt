package fyi.b612.lovehouse

import fyi.b612.lovehouse.feature.chat.buildCodexChatPayload
import fyi.b612.lovehouse.feature.settings.BuiltInToolIds
import fyi.b612.lovehouse.feature.settings.LocalToolProfile
import fyi.b612.lovehouse.feature.settings.ToolProfilePreferenceStore
import fyi.b612.lovehouse.feature.settings.ownerTokenIsExpired
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ToolCenterContractTest {
    @Test
    fun `local profile is a preference and chat carries stable tool ids`() {
        val store = InMemoryToolProfileStore()
        store.setPreferred("codex", "thread-1", BuiltInToolIds.LivingRoomRead, true)
        store.setPreferred("codex", "thread-1", BuiltInToolIds.EngineeringReadCurrent, true)

        val profile = store.profile("codex", "thread-1")
        val payload = buildCodexChatPayload("thread-1", "读取一下", profile.preferredToolIds)

        assertEquals(2, profile.preferredToolIds.size)
        assertTrue(payload.contains("\"allowed_tool_ids\":[\"builtin.engineering.read_current\",\"builtin.livingroom.read\"]"))
        assertTrue(payload.contains("\"persona_id\":\"codex\""))
    }

    @Test
    fun `expired owner session is detected locally without exposing its value`() {
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{\"exp\":100}".toByteArray())
        val token = "header.$payload.signature"

        assertTrue(ownerTokenIsExpired(token, nowEpochSeconds = 101))
        assertEquals(false, ownerTokenIsExpired(token, nowEpochSeconds = 99))
    }
}

private class InMemoryToolProfileStore : ToolProfilePreferenceStore {
    private val values = mutableMapOf<String, Set<String>>()
    override fun profile(personaId: String, threadId: String) = LocalToolProfile(
        personaId, threadId, values["$personaId::$threadId"].orEmpty(),
    )

    override fun setPreferred(personaId: String, threadId: String, toolId: String, enabled: Boolean) {
        val key = "$personaId::$threadId"
        values[key] = values[key].orEmpty().toMutableSet().apply {
            if (enabled) add(toolId) else remove(toolId)
        }
    }
}
