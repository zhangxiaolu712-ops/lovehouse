package fyi.b612.lovehouse.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SecretVaultPageTest {
    @Test
    fun `configured credentials always use a fixed non-derived mask`() {
        assertEquals("••••••••••••••", FIXED_SECRET_MASK)
    }

    @Test
    fun `known and future credential types have safe labels`() {
        assertEquals("API Key", "api_key".vaultTypeLabel())
        assertEquals("Bearer Token", "bearer".vaultTypeLabel())
        assertEquals("Future type", "future_type".vaultTypeLabel())
    }

    @Test
    fun `backend timestamp is formatted without changing credential state`() {
        assertEquals(16, formatVaultTimestamp("2026-10-04T02:00:00Z").length)
        assertEquals("unknown", formatVaultTimestamp("unknown"))
    }
}
