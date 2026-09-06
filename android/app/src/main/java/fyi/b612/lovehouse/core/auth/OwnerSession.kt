package fyi.b612.lovehouse.core.auth

import java.security.MessageDigest
import java.util.Base64
import kotlinx.coroutines.flow.StateFlow

enum class OwnerSessionStatus { Active, Missing, Expired, Rejected }
enum class OwnerSessionSource { Runtime, DebugBootstrap }

data class OwnerSessionSummary(
    val status: OwnerSessionStatus,
    val source: OwnerSessionSource? = null,
    val expiresAtEpochSeconds: Long? = null,
    val fingerprint: String? = null,
)

class OwnerBearerToken internal constructor(
    internal val value: String,
    val fingerprint: String,
)

enum class OwnerSessionFailure { Missing, Expired, Rejected, Invalid }

class OwnerSessionException(
    val failure: OwnerSessionFailure,
    message: String,
) : Exception(message)

interface OwnerSessionStore {
    val state: StateFlow<OwnerSessionSummary>
    fun currentBearer(): OwnerBearerToken
    fun saveAccessToken(token: String): OwnerSessionSummary
    fun reject(fingerprint: String)
    fun clear()
}

object MissingOwnerSessionStore : OwnerSessionStore {
    private val mutableState = kotlinx.coroutines.flow.MutableStateFlow(
        OwnerSessionSummary(OwnerSessionStatus.Missing),
    )
    override val state: StateFlow<OwnerSessionSummary> = mutableState

    override fun currentBearer(): OwnerBearerToken = throw OwnerSessionException(
        OwnerSessionFailure.Missing,
        ownerSessionMessage(OwnerSessionFailure.Missing),
    )

    override fun saveAccessToken(token: String): OwnerSessionSummary =
        throw UnsupportedOperationException("Owner Session store is unavailable")

    override fun reject(fingerprint: String) = Unit
    override fun clear() = Unit
}

internal data class ParsedOwnerAccessToken(
    val expiresAtEpochSeconds: Long,
    val fingerprint: String,
)

internal fun parseOwnerAccessToken(token: String): ParsedOwnerAccessToken {
    val normalized = token.trim()
    if (normalized.isEmpty() || normalized.startsWith("sb_secret_", ignoreCase = true)) {
        throw OwnerSessionException(OwnerSessionFailure.Invalid, "这不是可用的 Owner 登录会话")
    }
    val payload = normalized.split('.').getOrNull(1)?.let { encoded ->
        runCatching { String(Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8) }.getOrNull()
    } ?: throw OwnerSessionException(OwnerSessionFailure.Invalid, "Owner 登录会话格式无效")
    val expiresAt = Regex("\\\"exp\\\"\\s*:\\s*(\\d+)")
        .find(payload)?.groupValues?.getOrNull(1)?.toLongOrNull()
        ?: throw OwnerSessionException(OwnerSessionFailure.Invalid, "Owner 登录会话缺少过期时间")
    val role = Regex("\\\"role\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
        .find(payload)?.groupValues?.getOrNull(1)
    if (role != "authenticated") {
        throw OwnerSessionException(OwnerSessionFailure.Invalid, "只接受正式 Owner 登录会话")
    }
    return ParsedOwnerAccessToken(expiresAt, ownerTokenFingerprint(normalized))
}

internal fun ownerTokenFingerprint(token: String): String = MessageDigest.getInstance("SHA-256")
    .digest(token.toByteArray(Charsets.UTF_8))
    .take(6)
    .joinToString("") { byte -> "%02x".format(byte) }

internal fun isExpired(expiresAtEpochSeconds: Long, nowEpochSeconds: Long): Boolean =
    nowEpochSeconds >= expiresAtEpochSeconds

internal fun ownerSessionMessage(failure: OwnerSessionFailure): String = when (failure) {
    OwnerSessionFailure.Missing -> "缺少 Owner 登录会话，请重新登录 / 重新连接服务器"
    OwnerSessionFailure.Expired -> "Owner 登录已过期，请重新登录 / 重新连接服务器"
    OwnerSessionFailure.Rejected -> "Owner 登录已失效，请重新登录 / 重新连接服务器"
    OwnerSessionFailure.Invalid -> "Owner 登录会话无效，请重新登录 / 重新连接服务器"
}
