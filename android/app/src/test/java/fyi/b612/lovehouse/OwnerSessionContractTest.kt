package fyi.b612.lovehouse

import fyi.b612.lovehouse.core.auth.OwnerSessionException
import fyi.b612.lovehouse.core.auth.OwnerSessionFailure
import fyi.b612.lovehouse.core.auth.isExpired
import fyi.b612.lovehouse.core.auth.parseOwnerAccessToken
import java.util.Base64
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OwnerSessionContractTest {
    @Test
    fun `authenticated access token exposes only safe session metadata`() {
        val parsed = parseOwnerAccessToken(jwt(role = "authenticated", expiresAt = 200))

        assertEquals(200, parsed.expiresAtEpochSeconds)
        assertEquals(12, parsed.fingerprint.length)
        assertFalse(isExpired(parsed.expiresAtEpochSeconds, nowEpochSeconds = 199))
        assertTrue(isExpired(parsed.expiresAtEpochSeconds, nowEpochSeconds = 200))
    }

    @Test
    fun `service role and secret keys are rejected before persistence`() {
        assertInvalid(jwt(role = "service_role", expiresAt = 200))
        assertInvalid("sb_secret_not_allowed")
    }

    private fun assertInvalid(value: String) {
        val error = runCatching { parseOwnerAccessToken(value) }.exceptionOrNull()
        assertTrue(error is OwnerSessionException)
        assertEquals(OwnerSessionFailure.Invalid, (error as OwnerSessionException).failure)
    }

    private fun jwt(role: String, expiresAt: Long): String {
        val payload = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("{\"role\":\"$role\",\"exp\":$expiresAt}".toByteArray())
        return "header.$payload.signature"
    }
}
