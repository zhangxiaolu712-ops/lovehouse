package fyi.b612.lovehouse.feature.settings

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

data class GenericApiProvider(
    val id: String,
    val displayName: String,
    val defaultBaseUrl: String,
    val discoveryKind: String,
)

data class GenericApiModel(
    val id: String,
    val displayName: String,
)

data class GenericApiConnection(
    val id: String,
    val providerId: String,
    val displayName: String,
    val baseUrl: String,
    val discoveryKind: String,
    val credentialId: String,
    val selectedModels: List<GenericApiModel>,
    val createdAt: String,
    val updatedAt: String,
)

data class GenericApiDraft(
    val providerId: String,
    val displayName: String,
    val baseUrl: String,
    val discoveryKind: String,
    val credentialId: String,
    val selectedModels: List<GenericApiModel> = emptyList(),
)

data class GenericApiTestResult(
    val status: String,
    val code: String?,
    val models: List<GenericApiModel>,
)

interface GenericApiRepository {
    suspend fun providers(): List<GenericApiProvider>
    suspend fun connections(): List<GenericApiConnection>
    suspend fun connection(connectionId: String): GenericApiConnection
    suspend fun test(draft: GenericApiDraft): GenericApiTestResult
    suspend fun create(draft: GenericApiDraft): GenericApiConnection
    suspend fun update(connectionId: String, draft: GenericApiDraft): GenericApiConnection
    suspend fun delete(connectionId: String)
}

class AppBackendGenericApiRepository private constructor(
    private val api: GenericApiApi,
) : GenericApiRepository {
    constructor(baseUrl: String, sessionCookie: () -> String?) : this(HttpGenericApiApi(baseUrl, sessionCookie))
    internal constructor(api: GenericApiApi, testOnly: Unit = Unit) : this(api)

    override suspend fun providers(): List<GenericApiProvider> =
        api.request("GET", "/api/generic-api/providers").optJSONArray("providers").toProviders()

    override suspend fun connections(): List<GenericApiConnection> =
        api.request("GET", "/api/generic-api/connections").optJSONArray("connections").toConnections()

    override suspend fun connection(connectionId: String): GenericApiConnection =
        api.request("GET", "/api/generic-api/connections/${encodePath(connectionId)}").requireConnection()

    override suspend fun test(draft: GenericApiDraft): GenericApiTestResult =
        api.request("POST", "/api/generic-api/test", draft.toJson(includeDisplayName = false).toString())
            .requireTestResult()

    override suspend fun create(draft: GenericApiDraft): GenericApiConnection =
        api.request("POST", "/api/generic-api/connections", draft.toJson().toString()).requireConnection()

    override suspend fun update(connectionId: String, draft: GenericApiDraft): GenericApiConnection =
        api.request("PUT", "/api/generic-api/connections/${encodePath(connectionId)}", draft.toJson().toString())
            .requireConnection()

    override suspend fun delete(connectionId: String) {
        val result = api.request("DELETE", "/api/generic-api/connections/${encodePath(connectionId)}")
        if (result.optString("status") != "deleted") throw GenericApiException("连接删除尚未完成，请刷新后重试")
    }
}

internal interface GenericApiApi {
    suspend fun request(method: String, path: String, body: String? = null): JSONObject
}

private class HttpGenericApiApi(
    private val baseUrl: String,
    private val sessionCookie: () -> String?,
) : GenericApiApi {
    override suspend fun request(method: String, path: String, body: String?): JSONObject = withContext(Dispatchers.IO) {
        val cookie = sessionCookie() ?: throw GenericApiException("请先登录 LoveHouse App Account")
        val connection = (URL("${baseUrl.trimEnd('/')}$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
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
                throw GenericApiException(genericApiErrorText(status, code))
            }
            payload
        } finally {
            connection.disconnect()
        }
    }
}

internal class GenericApiException(message: String) : Exception(message)

internal fun GenericApiDraft.toJson(includeDisplayName: Boolean = true): JSONObject = JSONObject()
    .put("provider_id", providerId)
    .put("base_url", baseUrl)
    .put("discovery_kind", discoveryKind)
    .put("credential_id", credentialId)
    .apply {
        if (includeDisplayName) {
            put("display_name", displayName)
            put("selected_models", JSONArray().apply {
                selectedModels.forEach { model ->
                    put(JSONObject().put("model_id", model.id).put("display_name", model.displayName))
                }
            })
        }
    }

internal fun JSONObject.toGenericApiProvider(): GenericApiProvider = GenericApiProvider(
    id = getString("provider_id"),
    displayName = getString("display_name"),
    defaultBaseUrl = getString("default_base_url"),
    discoveryKind = getString("discovery_kind"),
)

internal fun JSONObject.toGenericApiConnection(): GenericApiConnection = GenericApiConnection(
    id = getString("connection_id"),
    providerId = getString("provider_id"),
    displayName = getString("display_name"),
    baseUrl = getString("base_url"),
    discoveryKind = getString("discovery_kind"),
    credentialId = getString("credential_id"),
    selectedModels = optJSONArray("selected_models").toModels(),
    createdAt = getString("created_at"),
    updatedAt = getString("updated_at"),
)

private fun JSONObject.requireConnection(): GenericApiConnection =
    optJSONObject("connection")?.toGenericApiConnection()
        ?: throw GenericApiException("App Backend 未返回 API 连接状态，请刷新后重试")

private fun JSONObject.requireTestResult(): GenericApiTestResult {
    val value = optJSONObject("test") ?: throw GenericApiException("App Backend 未返回测试结果，请重试")
    return GenericApiTestResult(
        status = value.getString("status"),
        code = value.nullableString("code"),
        models = value.optJSONArray("models").toModels(),
    )
}

private fun JSONArray?.toProviders(): List<GenericApiProvider> = this?.let { values ->
    buildList { for (index in 0 until values.length()) values.optJSONObject(index)?.let { add(it.toGenericApiProvider()) } }
}.orEmpty()

private fun JSONArray?.toConnections(): List<GenericApiConnection> = this?.let { values ->
    buildList { for (index in 0 until values.length()) values.optJSONObject(index)?.let { add(it.toGenericApiConnection()) } }
}.orEmpty()

private fun JSONArray?.toModels(): List<GenericApiModel> = this?.let { values ->
    buildList {
        for (index in 0 until values.length()) values.optJSONObject(index)?.let {
            add(GenericApiModel(it.getString("model_id"), it.getString("display_name")))
        }
    }
}.orEmpty()

private fun JSONObject.nullableString(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)

private fun encodePath(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

internal fun genericApiErrorText(status: Int, code: String): String = when {
    code == "GENERIC_API_INVALID_CREDENTIAL" -> "密钥无效，请检查后重试"
    code == "GENERIC_API_FORBIDDEN" -> "密钥没有访问这个服务的权限"
    code == "GENERIC_API_QUOTA_EXHAUSTED" -> "服务额度或余额不足，请检查账户"
    code == "GENERIC_API_RATE_LIMITED" -> "服务请求过于频繁，请稍后重试"
    code == "GENERIC_API_PROVIDER_UNAVAILABLE" -> "服务暂时不可用，请稍后重试"
    code == "GENERIC_API_TIMEOUT" -> "连接超时，请检查网络或 Base URL"
    code == "GENERIC_API_NETWORK_ERROR" -> "无法连接服务，请检查 Base URL 和网络"
    code == "GENERIC_API_DISCOVERY_UNSUPPORTED" -> "这家服务不支持自动获取模型，请手动填写模型 ID"
    code == "GENERIC_API_CREDENTIAL_NOT_FOUND" -> "密码库凭证已不存在，请重新选择"
    code == "GENERIC_API_CONNECTION_NOT_FOUND" -> "这个 API 连接已不存在，请刷新列表"
    code == "GENERIC_API_INVALID_RESPONSE" -> "服务返回了无法识别的模型列表"
    status == HttpURLConnection.HTTP_UNAUTHORIZED || code == "APP_AUTH_REQUIRED" -> "请先登录 LoveHouse App Account"
    status >= 500 -> "API 服务中心暂时不可用，请稍后重试"
    else -> "API 请求失败，请检查填写内容"
}

internal suspend fun resolveGenericApiCredential(
    vault: SecretVaultRepository,
    bindExisting: Boolean,
    selectedCredentialId: String?,
    currentCredentialId: String?,
    secret: String,
    credentialType: String,
    displayName: String,
): String {
    if (bindExisting) return selectedCredentialId ?: throw GenericApiException("请选择密码库凭证")
    if (secret.isBlank()) return currentCredentialId ?: throw GenericApiException("请输入 Key / Token")
    return currentCredentialId?.let {
        vault.update(it, SecretCredentialUpdate(credentialType = credentialType, replacementSecret = secret)).id
    } ?: vault.create(SecretCredentialCreate(
        displayName = "$displayName 密钥",
        credentialType = credentialType,
        secret = secret,
        metadata = SecretCredentialMetadata(serviceName = displayName),
    )).id
}
