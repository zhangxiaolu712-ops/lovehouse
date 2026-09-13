package fyi.b612.lovehouse.feature.chat

import org.junit.Assert.assertEquals
import org.junit.Test

class ChatAppearanceContractTest {
    @Test
    fun `appearance defaults are compact and shared`() {
        assertEquals(ChatAppearanceSettings(textSizeSp = 14f, bubbleScale = .90f), resolveChatAppearance(null, null))
    }

    @Test
    fun `appearance values are clamped to supported ranges`() {
        assertEquals(12f, resolveChatAppearance("8", ".5").textSizeSp)
        assertEquals(.80f, resolveChatAppearance("8", ".5").bubbleScale)
        assertEquals(18f, resolveChatAppearance("24", "2").textSizeSp)
        assertEquals(1.10f, resolveChatAppearance("24", "2").bubbleScale)
    }

    @Test
    fun `appearance accepts persisted values inside supported ranges`() {
        assertEquals(ChatAppearanceSettings(textSizeSp = 16f, bubbleScale = 1.05f), resolveChatAppearance("16", "1.05"))
    }
}
