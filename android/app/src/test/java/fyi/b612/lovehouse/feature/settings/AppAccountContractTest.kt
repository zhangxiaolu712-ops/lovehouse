package fyi.b612.lovehouse.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppAccountContractTest {
    @Test
    fun `account endpoints are scoped to app backend auth`() {
        val base = "https://app.b612.fyi/"

        assertEquals("https://app.b612.fyi/api/auth/register", appAuthEndpoint(base, "register"))
        assertEquals("https://app.b612.fyi/api/auth/login", appAuthEndpoint(base, "/login"))
        assertEquals("https://app.b612.fyi/api/auth/session", appAuthEndpoint(base, "session"))
        assertEquals("https://app.b612.fyi/api/auth/logout", appAuthEndpoint(base, "logout"))
    }

    @Test
    fun `secure session retains cookie pairs without cookie attributes`() {
        val headers = mapOf<String?, List<String>>(
            "Content-Type" to listOf("application/json"),
            "Set-Cookie" to listOf(
                "lovehouse_session=opaque-session; Path=/; Secure; HttpOnly; SameSite=Lax",
                "lovehouse_refresh=opaque-refresh; Path=/api/auth; Secure; HttpOnly",
            ),
        )

        assertEquals(
            "lovehouse_session=opaque-session; lovehouse_refresh=opaque-refresh",
            cookieHeaderFromSetCookie(headers),
        )
    }

    @Test
    fun `missing set cookie cannot masquerade as signed in session`() {
        assertNull(cookieHeaderFromSetCookie(mapOf("Content-Type" to listOf("application/json"))))
    }

    @Test
    fun `email validation rejects incomplete identities`() {
        assertTrue(isValidAppAccountEmail("owner@example.com"))
        assertFalse(isValidAppAccountEmail("owner"))
        assertFalse(isValidAppAccountEmail("owner@"))
    }
}
