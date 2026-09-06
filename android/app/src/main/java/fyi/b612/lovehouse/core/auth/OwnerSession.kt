package fyi.b612.lovehouse.core.auth

import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

enum class OwnerSessionStatus { Active, Missing, Expired, Rejected }
enum class OwnerSessionSource { Runtime, DebugBootstrap }

data class OwnerSessionSummary(
    val status: OwnerSessionStatus,
    val source: OwnerSessionSource? = null,
    val expiresAtEpochSeconds: Long? = null,
    val fingerprint: String? = null,
    val canRefresh: Boolean = false,
)

data class OwnerSessionInput(
    val accessToken: String,
    val refreshToken: String? = null,
    val expiresAtEpochSeconds: Long? = null,
    val expiresInSeconds: Long? = null,
    val tokenType: String? = null,
)

class OwnerBearerToken internal constructor(
    internal val value: String,
    val fingerprint: String,
)

enum class OwnerSessionFailure { Missing, Expired, Rejected, Invalid, RefreshUnavailable, RefreshFailed }

class OwnerSessionException(
    val failure: OwnerSessionFailure,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

interface OwnerSessionStore {
    val state: StateFlow<OwnerSessionSummary>
    suspend fun currentBearer(forceRefresh: Boolean = false): OwnerBearerToken
    fun saveSession(session: OwnerSessionInput): OwnerSessionSummary
    fun saveAccessToken(token: String): OwnerSessionSummary = saveSession(OwnerSessionInput(accessToken = token))
    fun reject(fingerprint: String)
    fun clear()
}

interface OwnerSessionRefresher {
    suspend fun refresh(refreshToken: String): OwnerSessionInput
}

class OwnerSessionRefreshException(
    val terminal: Boolean,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

object MissingOwnerSessionStore : OwnerSessionStore {
    private val mutableState = kotlinx.coroutines.flow.MutableStateFlow(
        OwnerSessionSummary(OwnerSessionStatus.Missing),
    )
    override val state: StateFlow<OwnerSessionSummary> = mutableState

    override suspend fun currentBearer(forceRefresh: Boolean): OwnerBearerToken = throw OwnerSessionException(
        OwnerSessionFailure.Missing,
        ownerSessionMessage(OwnerSessionFailure.Missing),
    )

    override fun saveSession(session: OwnerSessionInput): OwnerSessionSummary =
        throw UnsupportedOperationException("Owner Session store is unavailable")

    override fun reject(fingerprint: String) = Unit
    override fun clear() = Unit
}

internal data class ParsedOwnerAccessToken(
    val expiresAtEpochSeconds: Long,
    val fingerprint: String,
)

internal data class StoredOwnerSession(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtEpochSeconds: Long,
    val tokenType: String?,
    val fingerprint: String,
    val source: OwnerSessionSource,
)

internal fun parseOwnerAccessToken(token: String): ParsedOwnerAccessToken {
    val normalized = token.trim()
    if (normalized.isEmpty() || normalized.startsWith("sb_secret_", ignoreCase = true)) {
        throw OwnerSessionException(OwnerSessionFailure.Invalid, "这不是可用的 Owner 登录会话")
    }
    val payload = decodeJwtPayload(normalized)
        ?: throw OwnerSessionException(OwnerSessionFailure.Invalid, "Owner 登录会话格式无效")
    val expiresAt = Regex("\\\"exp\\\"\\s*:\\s*(\\d+)")
        .find(payload)?.groupValues?.getOrNull(1)?.toLongOrNull()
        ?: throw OwnerSessionException(OwnerSessionFailure.Invalid, "Owner 登录会话缺少过期时间")
    val role = jsonString(payload, "role")
    if (role != "authenticated") {
        throw OwnerSessionException(OwnerSessionFailure.Invalid, "只接受正式 Owner 登录会话")
    }
    return ParsedOwnerAccessToken(expiresAt, ownerTokenFingerprint(normalized))
}

internal fun parseOwnerSessionPayload(payload: String, nowEpochSeconds: Long): OwnerSessionInput {
    val accessToken = jsonString(payload, "access_token")
        ?: throw OwnerSessionException(OwnerSessionFailure.Invalid, "会话缺少 access_token")
    val refreshToken = jsonString(payload, "refresh_token")
    val expiresAt = jsonLong(payload, "expires_at")
    val expiresIn = jsonLong(payload, "expires_in")
    return OwnerSessionInput(
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAtEpochSeconds = expiresAt ?: expiresIn?.let { nowEpochSeconds + it },
        tokenType = jsonString(payload, "token_type"),
    )
}

internal fun normalizeOwnerSession(input: OwnerSessionInput, nowEpochSeconds: Long): StoredOwnerSession {
    val accessToken = input.accessToken.trim()
    val parsed = parseOwnerAccessToken(accessToken)
    val refreshToken = input.refreshToken?.trim()?.takeIf(String::isNotEmpty)?.also(::validateRefreshToken)
    val expiresAt = input.expiresAtEpochSeconds
        ?: input.expiresInSeconds?.let { nowEpochSeconds + it }
        ?: parsed.expiresAtEpochSeconds
    if (isExpired(expiresAt, nowEpochSeconds)) {
        throw OwnerSessionException(OwnerSessionFailure.Expired, ownerSessionMessage(OwnerSessionFailure.Expired))
    }
    return StoredOwnerSession(
        accessToken = accessToken,
        refreshToken = refreshToken,
        expiresAtEpochSeconds = minOf(expiresAt, parsed.expiresAtEpochSeconds),
        tokenType = input.tokenType?.trim()?.takeIf(String::isNotEmpty),
        fingerprint = parsed.fingerprint,
        source = OwnerSessionSource.Runtime,
    )
}

internal fun validateRefreshToken(token: String) {
    val normalized = token.trim()
    if (normalized.isEmpty() || normalized.startsWith("sb_secret_", ignoreCase = true)) {
        throw OwnerSessionException(OwnerSessionFailure.Invalid, "这不是可用的 Owner refresh session")
    }
    decodeJwtPayload(normalized)?.let { payload ->
        if (jsonString(payload, "role") == "service_role") {
            throw OwnerSessionException(OwnerSessionFailure.Invalid, "禁止使用 service_role")
        }
    }
}

internal fun ownerTokenFingerprint(token: String): String = MessageDigest.getInstance("SHA-256")
    .digest(token.toByteArray(Charsets.UTF_8))
    .take(6)
    .joinToString("") { byte -> "%02x".format(byte) }

internal fun isExpired(expiresAtEpochSeconds: Long, nowEpochSeconds: Long): Boolean =
    nowEpochSeconds >= expiresAtEpochSeconds

internal fun needsRefresh(expiresAtEpochSeconds: Long, nowEpochSeconds: Long): Boolean =
    expiresAtEpochSeconds - nowEpochSeconds <= REFRESH_WINDOW_SECONDS

internal class OwnerSessionRefreshCoordinator(
    private val nowEpochSeconds: () -> Long,
    private val load: () -> StoredOwnerSession?,
    private val save: (StoredOwnerSession) -> Unit,
    private val reject: (String) -> Unit,
    private val refresher: OwnerSessionRefresher,
) {
    private val refreshMutex = Mutex()

    suspend fun validSession(forceRefresh: Boolean = false): StoredOwnerSession = refreshMutex.withLock {
        val current = load() ?: throw OwnerSessionException(
            OwnerSessionFailure.Missing,
            ownerSessionMessage(OwnerSessionFailure.Missing),
        )
        val now = nowEpochSeconds()
        val expired = isExpired(current.expiresAtEpochSeconds, now)
        val shouldRefresh = forceRefresh || needsRefresh(current.expiresAtEpochSeconds, now)
        if (!shouldRefresh || current.refreshToken == null && !expired) return@withLock current
        val refreshToken = current.refreshToken ?: throw OwnerSessionException(
            OwnerSessionFailure.Expired,
            ownerSessionMessage(OwnerSessionFailure.Expired),
        )
        val refreshed = try {
            normalizeOwnerSession(refresher.refresh(refreshToken), nowEpochSeconds())
        } catch (error: OwnerSessionRefreshException) {
            if (error.terminal) {
                reject(current.fingerprint)
                throw OwnerSessionException(
                    OwnerSessionFailure.Rejected,
                    ownerSessionMessage(OwnerSessionFailure.Rejected),
                    error,
                )
            }
            throw OwnerSessionException(
                OwnerSessionFailure.RefreshFailed,
                ownerSessionMessage(OwnerSessionFailure.RefreshFailed),
                error,
            )
        }
        save(refreshed)
        refreshed
    }
}

internal fun ownerSessionMessage(failure: OwnerSessionFailure): String = when (failure) {
    OwnerSessionFailure.Missing -> "缺少 Owner 登录会话，请重新登录 / 重新连接服务器"
    OwnerSessionFailure.Expired -> "Owner 登录已过期，请重新登录 / 重新连接服务器"
    OwnerSessionFailure.Rejected -> "Owner 登录已失效，请重新登录 / 重新连接服务器"
    OwnerSessionFailure.Invalid -> "Owner 登录会话无效，请重新登录 / 重新连接服务器"
    OwnerSessionFailure.RefreshUnavailable -> "Owner 自动续期尚未配置，请重新登录 / 重新连接服务器"
    OwnerSessionFailure.RefreshFailed -> "Owner 自动续期失败，请检查网络后重试"
}

private fun decodeJwtPayload(token: String): String? = token.split('.').getOrNull(1)?.let { encoded ->
    runCatching { String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8) }.getOrNull()
}

private fun jsonString(json: String, key: String): String? =
    Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*\\\"([^\\\"]*)\\\"")
        .find(json)?.groupValues?.getOrNull(1)

private fun jsonLong(json: String, key: String): Long? =
    Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*(\\d+)")
        .find(json)?.groupValues?.getOrNull(1)?.toLongOrNull()

internal const val REFRESH_WINDOW_SECONDS = 90L
