package fyi.b612.lovehouse.feature.settings

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

internal const val FIXED_SECRET_MASK = "••••••••••••••"

data class SecretCredentialMetadata(
    val serviceName: String? = null,
    val usernameHint: String? = null,
    val note: String? = null,
)

data class SecretCredential(
    val id: String,
    val displayName: String,
    val credentialType: String,
    val metadata: SecretCredentialMetadata,
    val configured: Boolean,
    val createdAt: String,
    val updatedAt: String,
)

class SecretCredentialCreate(
    val displayName: String,
    val credentialType: String,
    val secret: String,
    val metadata: SecretCredentialMetadata,
) {
    override fun toString(): String =
        "SecretCredentialCreate(displayName=$displayName, credentialType=$credentialType, secret=<redacted>)"
}

class SecretCredentialUpdate(
    val displayName: String? = null,
    val credentialType: String? = null,
    val replacementSecret: String? = null,
    val metadata: SecretCredentialMetadata? = null,
) {
    override fun toString(): String =
        "SecretCredentialUpdate(displayName=$displayName, credentialType=$credentialType, replacementSecret=<redacted>)"
}

interface SecretVaultRepository {
    suspend fun list(): List<SecretCredential>
    suspend fun get(credentialId: String): SecretCredential
    suspend fun create(input: SecretCredentialCreate): SecretCredential
    suspend fun update(credentialId: String, input: SecretCredentialUpdate): SecretCredential
    suspend fun delete(credentialId: String)
}

class AppBackendSecretVaultRepository private constructor(
    private val api: SecretVaultApi,
) : SecretVaultRepository {
    constructor(baseUrl: String, sessionCookie: () -> String?) : this(HttpSecretVaultApi(baseUrl, sessionCookie))
    internal constructor(api: SecretVaultApi, testOnly: Unit = Unit) : this(api)

    override suspend fun list(): List<SecretCredential> =
        api.request("GET", "/api/credentials").optJSONArray("credentials").toSecretCredentials()

    override suspend fun get(credentialId: String): SecretCredential =
        api.request("GET", "/api/credentials/${encodeVaultPath(credentialId)}")
            .requireCredential()

    override suspend fun create(input: SecretCredentialCreate): SecretCredential =
        api.request("POST", "/api/credentials", createSecretCredentialBody(input).toString())
            .requireCredential()

    override suspend fun update(credentialId: String, input: SecretCredentialUpdate): SecretCredential =
        api.request(
            "PUT",
            "/api/credentials/${encodeVaultPath(credentialId)}",
            updateSecretCredentialBody(input).toString(),
        ).requireCredential()

    override suspend fun delete(credentialId: String) {
        val result = api.request("DELETE", "/api/credentials/${encodeVaultPath(credentialId)}")
        if (result.optString("status") != "deleted") throw SecretVaultException("密钥删除尚未完成，请刷新后重试")
    }
}

internal interface SecretVaultApi {
    suspend fun request(method: String, path: String, body: String? = null): JSONObject
}

private class HttpSecretVaultApi(
    private val baseUrl: String,
    private val sessionCookie: () -> String?,
) : SecretVaultApi {
    override suspend fun request(method: String, path: String, body: String?): JSONObject = withContext(Dispatchers.IO) {
        val cookie = sessionCookie() ?: throw SecretVaultException("请先登录 LoveHouse App Account")
        val connection = (URL("${baseUrl.trimEnd('/')}$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cookie", cookie)
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val payload = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (status !in 200..299) {
                val code = payload.optJSONObject("error")?.optString("code").orEmpty()
                throw SecretVaultException(secretVaultErrorText(status, code))
            }
            payload
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
    }
}

internal class SecretVaultException(message: String) : Exception(message)

internal fun createSecretCredentialBody(input: SecretCredentialCreate): JSONObject = JSONObject()
    .put("display_name", input.displayName.trim())
    .put("credential_type", input.credentialType)
    .put("secret", input.secret)
    .put("metadata", input.metadata.toJson())

internal fun updateSecretCredentialBody(input: SecretCredentialUpdate): JSONObject = JSONObject().apply {
    input.displayName?.let { put("display_name", it.trim()) }
    input.credentialType?.let { put("credential_type", it) }
    input.replacementSecret?.let { put("secret", it) }
    input.metadata?.let { put("metadata", it.toJson()) }
}

private fun SecretCredentialMetadata.toJson(): JSONObject = JSONObject().apply {
    serviceName?.trim()?.takeIf(String::isNotEmpty)?.let { put("service_name", it) }
    usernameHint?.trim()?.takeIf(String::isNotEmpty)?.let { put("username_hint", it) }
    note?.trim()?.takeIf(String::isNotEmpty)?.let { put("note", it) }
}

internal fun JSONObject.toSecretCredential(): SecretCredential {
    val metadata = optJSONObject("metadata") ?: JSONObject()
    return SecretCredential(
        id = getString("credential_id"),
        displayName = getString("display_name"),
        credentialType = getString("credential_type"),
        metadata = SecretCredentialMetadata(
            serviceName = metadata.nullableString("service_name"),
            usernameHint = metadata.nullableString("username_hint"),
            note = metadata.nullableString("note"),
        ),
        configured = optString("credential_status") == "configured",
        createdAt = getString("created_at"),
        updatedAt = getString("updated_at"),
    )
}

private fun JSONObject.requireCredential(): SecretCredential =
    optJSONObject("credential")?.toSecretCredential()
        ?: throw SecretVaultException("App Backend 未返回密钥状态，请刷新后重试")

private fun JSONArray?.toSecretCredentials(): List<SecretCredential> = this?.let { values ->
    buildList {
        for (index in 0 until values.length()) values.optJSONObject(index)?.let { add(it.toSecretCredential()) }
    }
}.orEmpty()

private fun JSONObject.nullableString(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)

private fun encodeVaultPath(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

private fun secretVaultErrorText(status: Int, code: String): String = when {
    status == HttpURLConnection.HTTP_UNAUTHORIZED || code == "APP_AUTH_REQUIRED" -> "请先登录 LoveHouse App Account"
    status == HttpURLConnection.HTTP_NOT_FOUND || code == "CREDENTIAL_NOT_FOUND" -> "这条密钥已不存在，请刷新列表"
    status == HttpURLConnection.HTTP_BAD_REQUEST || code == "INVALID_CREDENTIAL" -> "密钥信息不完整或格式不正确"
    status == HttpURLConnection.HTTP_UNAVAILABLE || code == "CREDENTIAL_VAULT_UNAVAILABLE" -> "密码库暂时不可用，请稍后重试"
    else -> "密码库请求失败（HTTP $status）"
}
