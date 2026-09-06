package fyi.b612.lovehouse.core.auth

import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** Temporary Owner Auth adapter. Callers depend only on [OwnerSessionRefresher]. */
class SupabaseOwnerSessionRefresher(
    baseUrl: String,
    private val publishableKey: String,
) : OwnerSessionRefresher {
    private val refreshEndpoint = baseUrl.trim().trimEnd('/').let { root ->
        if (root.isBlank() || !root.startsWith("https://")) null
        else "$root/auth/v1/token?grant_type=refresh_token"
    }

    init {
        validatePublishableKey(publishableKey)
    }

    override suspend fun refresh(refreshToken: String): OwnerSessionInput = withContext(Dispatchers.IO) {
        validateRefreshToken(refreshToken)
        val endpoint = refreshEndpoint ?: throw OwnerSessionException(
            OwnerSessionFailure.RefreshUnavailable,
            ownerSessionMessage(OwnerSessionFailure.RefreshUnavailable),
        )
        if (publishableKey.isBlank()) {
            throw OwnerSessionException(
                OwnerSessionFailure.RefreshUnavailable,
                ownerSessionMessage(OwnerSessionFailure.RefreshUnavailable),
            )
        }
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "application/json")
            setRequestProperty("apikey", publishableKey)
        }
        try {
            val requestBody = JSONObject().put("refresh_token", refreshToken).toString()
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(requestBody) }
            val status = connection.responseCode
            val response = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (status !in 200..299) {
                throw OwnerSessionRefreshException(
                    terminal = status == 400 || status == 401 || status == 403,
                    message = if (status == 400 || status == 401 || status == 403) {
                        "Owner refresh session 已失效"
                    } else {
                        "Owner Session 续期服务暂不可用（HTTP $status）"
                    },
                )
            }
            val json = runCatching { JSONObject(response) }.getOrElse {
                throw OwnerSessionRefreshException(false, "Owner Session 续期响应无效", it)
            }
            OwnerSessionInput(
                accessToken = json.optString("access_token"),
                refreshToken = json.optString("refresh_token"),
                expiresAtEpochSeconds = json.optLong("expires_at").takeIf { it > 0 },
                expiresInSeconds = json.optLong("expires_in").takeIf { it > 0 },
                tokenType = json.optString("token_type").takeIf(String::isNotBlank),
            )
        } catch (error: OwnerSessionException) {
            throw error
        } catch (error: OwnerSessionRefreshException) {
            throw error
        } catch (error: Exception) {
            throw OwnerSessionRefreshException(false, "Owner Session 续期连接失败", error)
        } finally {
            connection.disconnect()
        }
    }
}

internal fun validatePublishableKey(key: String) {
    val normalized = key.trim()
    if (normalized.isEmpty()) return
    if (normalized.startsWith("sb_secret_", ignoreCase = true)) {
        throw IllegalArgumentException("Android 禁止使用 sb_secret")
    }
    val jwtPayload = normalized.split('.').getOrNull(1)?.let { encoded ->
        runCatching {
            String(java.util.Base64.getUrlDecoder().decode(encoded), Charsets.UTF_8)
        }.getOrNull()
    }
    if (jwtPayload != null && Regex("\\\"role\\\"\\s*:\\s*\\\"service_role\\\"").containsMatchIn(jwtPayload)) {
        throw IllegalArgumentException("Android 禁止使用 service_role")
    }
}
