package fyi.b612.lovehouse.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class SecretVaultPageTest {
    @Test
    fun `mask keeps only a short prefix and suffix`() {
        val raw = "sk-proj-abcdefghijklmnop7mXa"
        val masked = maskVaultKey(raw)
        assertEquals("sk-••••••7mXa", masked)
        assertFalse(masked.contains("abcdefghijklmnop"))
    }

    @Test
    fun `short and empty keys are still masked`() {
        assertEquals("ab••••gh", maskVaultKey("abcdefgh"))
        assertEquals("sk-••••••••", maskVaultKey("   "))
    }

    @Test
    fun `preview entries never hold a full key`() {
        VaultSamples.keys.forEach { assertEquals(true, it.maskedKey.contains("••••")) }
    }
}
