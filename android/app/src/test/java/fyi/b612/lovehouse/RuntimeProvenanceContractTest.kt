package fyi.b612.lovehouse

import fyi.b612.lovehouse.feature.chat.ClaudeRuntime
import fyi.b612.lovehouse.feature.chat.PersonaRuntimeSnapshot
import fyi.b612.lovehouse.feature.chat.androidRuntimeProvenanceJson
import fyi.b612.lovehouse.feature.chat.buildChatPayload
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RuntimeProvenanceContractTest {
    @Test
    fun `persona provenance contains identity and booleans but never bodies or ticket`() {
        val snapshot = PersonaRuntimeSnapshot(
            personaId = "persona-a",
            personaVersion = 9,
            instructions = "PRIVATE PROMPT BODY",
            background = "PRIVATE BACKGROUND BODY",
            connectionIds = setOf("connection-a"),
            executionTicket = "secret-ticket",
            reanchorIntent = true,
        )
        val trace = androidRuntimeProvenanceJson("trace-a", ClaudeRuntime, snapshot)
        assertTrue(trace.contains("\"persona_id\":\"persona-a\""))
        assertTrue(trace.contains("\"persona_version\":9"))
        assertTrue(trace.contains("\"instructions_present\":true"))
        assertTrue(trace.contains("\"background_present\":true"))
        assertFalse(trace.contains("PRIVATE"))
        assertFalse(trace.contains("secret-ticket"))

        val payload = buildChatPayload(
            ClaudeRuntime, "hello", emptySet(), personaRuntime = snapshot, traceId = "trace-a",
        )
        assertTrue(payload.contains("\"trace_id\":\"trace-a\""))
    }
}
