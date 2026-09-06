package fyi.b612.lovehouse

import fyi.b612.lovehouse.core.auth.OwnerSessionException
import fyi.b612.lovehouse.core.auth.OwnerSessionFailure
import fyi.b612.lovehouse.core.auth.OwnerSessionInput
import fyi.b612.lovehouse.core.auth.OwnerSessionRefreshCoordinator
import fyi.b612.lovehouse.core.auth.OwnerSessionRefreshException
import fyi.b612.lovehouse.core.auth.OwnerSessionRefresher
import fyi.b612.lovehouse.core.auth.OwnerSessionSource
import fyi.b612.lovehouse.core.auth.StoredOwnerSession
import fyi.b612.lovehouse.core.auth.isExpired
import fyi.b612.lovehouse.core.auth.needsRefresh
import fyi.b612.lovehouse.core.auth.parseOwnerAccessToken
import fyi.b612.lovehouse.core.auth.parseOwnerSessionPayload
import fyi.b612.lovehouse.core.auth.validatePublishableKey
import fyi.b612.lovehouse.core.auth.validateRefreshToken
import java.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
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
        assertTrue(runCatching { validateRefreshToken("sb_secret_not_allowed") }.isFailure)
        assertTrue(runCatching { validatePublishableKey("sb_secret_not_allowed") }.isFailure)
        assertTrue(runCatching { validatePublishableKey(jwt(role = "service_role", expiresAt = 200)) }.isFailure)
    }

    @Test
    fun `full session payload keeps rotation fields without exposing them in summary`() {
        val access = jwt(role = "authenticated", expiresAt = 500)
        val parsed = parseOwnerSessionPayload(
            """{"access_token":"$access","refresh_token":"refresh-value","expires_at":500,"token_type":"bearer"}""",
            nowEpochSeconds = 100,
        )

        assertEquals(access, parsed.accessToken)
        assertEquals("refresh-value", parsed.refreshToken)
        assertEquals(500L, parsed.expiresAtEpochSeconds)
        assertTrue(needsRefresh(190, nowEpochSeconds = 100))
        assertFalse(needsRefresh(191, nowEpochSeconds = 100))
    }

    @Test
    fun `concurrent near-expiry requests share one rotated refresh`() = runBlocking {
        var stored: StoredOwnerSession? = storedSession(accessExpiresAt = 150, refreshToken = "first-refresh")
        var refreshCalls = 0
        val refreshStarted = CompletableDeferred<Unit>()
        val allowRefresh = CompletableDeferred<Unit>()
        val coordinator = OwnerSessionRefreshCoordinator(
            nowEpochSeconds = { 100 },
            load = { stored },
            save = { stored = it },
            reject = { stored = null },
            refresher = object : OwnerSessionRefresher {
                override suspend fun refresh(refreshToken: String): OwnerSessionInput {
                    refreshCalls += 1
                    assertEquals("first-refresh", refreshToken)
                    refreshStarted.complete(Unit)
                    allowRefresh.await()
                    return OwnerSessionInput(
                        accessToken = jwt(role = "authenticated", expiresAt = 1_000),
                        refreshToken = "rotated-refresh",
                    )
                }
            },
        )

        val chat = async { coordinator.validSession() }
        refreshStarted.await()
        val toolCenter = async { coordinator.validSession() }
        allowRefresh.complete(Unit)

        assertEquals("rotated-refresh", chat.await().refreshToken)
        assertEquals("rotated-refresh", toolCenter.await().refreshToken)
        assertEquals(1, refreshCalls)
    }

    @Test
    fun `revoked refresh moves session to rejected state`() = runBlocking {
        var stored: StoredOwnerSession? = storedSession(accessExpiresAt = 100, refreshToken = "revoked-refresh")
        var rejectedFingerprint: String? = null
        val coordinator = OwnerSessionRefreshCoordinator(
            nowEpochSeconds = { 100 },
            load = { stored },
            save = { stored = it },
            reject = { fingerprint -> rejectedFingerprint = fingerprint; stored = null },
            refresher = object : OwnerSessionRefresher {
                override suspend fun refresh(refreshToken: String): OwnerSessionInput {
                    throw OwnerSessionRefreshException(terminal = true, message = "revoked")
                }
            },
        )

        val error = runCatching { coordinator.validSession() }.exceptionOrNull()
        assertTrue(error is OwnerSessionException)
        assertEquals(OwnerSessionFailure.Rejected, (error as OwnerSessionException).failure)
        assertEquals(12, rejectedFingerprint?.length)
        assertEquals(null, stored)
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

    private fun storedSession(accessExpiresAt: Long, refreshToken: String): StoredOwnerSession {
        val token = jwt(role = "authenticated", expiresAt = accessExpiresAt)
        val parsed = parseOwnerAccessToken(token)
        return StoredOwnerSession(
            accessToken = token,
            refreshToken = refreshToken,
            expiresAtEpochSeconds = accessExpiresAt,
            tokenType = "bearer",
            fingerprint = parsed.fingerprint,
            source = OwnerSessionSource.Runtime,
        )
    }
}
