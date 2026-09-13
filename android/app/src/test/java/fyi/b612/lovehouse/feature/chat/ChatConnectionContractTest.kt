package fyi.b612.lovehouse.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ChatConnectionContractTest {
    @Test
    fun `connection preserves configured chat URL and appends status exactly once`() {
        val chatUrl = "https://chat.b612.fyi/v1/chat/codex"

        assertEquals(chatUrl, normalizeChatConnectionEndpointInput("$chatUrl\nModel：gpt-5.6-sol"))
        assertEquals("https://chat.b612.fyi/v1/chat/codex/status", codexStatusEndpoint(chatUrl))
        assertEquals("$chatUrl/status", codexStatusEndpoint("$chatUrl/status"))
        assertEquals("$chatUrl/status", codexStatusEndpoint("$chatUrl/status/"))
        assertFalse(codexStatusEndpoint(chatUrl).contains("model"))
    }
}
