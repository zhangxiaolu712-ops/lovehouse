package fyi.b612.lovehouse.feature.settings

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class ApiServiceKind { Tool, Voice }

data class ApiServiceDescriptor(
    val serviceId: String,
    val serviceKind: ApiServiceKind,
    val adapterId: String,
    val displayName: String,
    val acceptedCredentialTypes: List<String>,
    val credentialRequired: Boolean,
    val configContractVersion: Int,
)

data class ApiBackendConnection(
    val id: String,
    val serviceId: String?,
    val serviceType: String?,
    val baseUrl: String?,
    val displayName: String,
    val note: String?,
    val credentialId: String?,
    val configJson: String,
    val enabled: Boolean,
    val status: String,
    val createdAt: String,
    val updatedAt: String,
)

enum class ApiCapabilitySource { Discovered, Custom }
enum class ApiCapabilityAvailability { Available, Missing }

data class ApiCapability(
    val id: String,
    val source: ApiCapabilitySource,
    val sourceKey: String,
    val displayName: String,
    val description: String?,
    val method: String,
    val path: String,
    val inputSchema: String?,
    val outputSchema: String?,
    val availability: ApiCapabilityAvailability,
    val selected: Boolean,
)

data class ApiCapabilityChanges(
    val added: List<String> = emptyList(),
    val changed: List<String> = emptyList(),
    val removed: List<String> = emptyList(),
)

data class ApiCapabilityCatalog(
    val connectionId: String,
    val revision: Int,
    val capabilities: List<ApiCapability>,
    val lastDiscoverySummary: String? = null,
)

data class ApiCapabilityPreview(
    val previewId: String,
    val revision: Int,
    val capabilities: List<ApiCapability>,
    val changes: ApiCapabilityChanges,
    val expiresAt: String,
)

sealed interface ApiDiscoverySource {
    data object Preset : ApiDiscoverySource
    data class Url(val url: String, val apiKeyHeaderName: String? = null) : ApiDiscoverySource
    data class JsonDocument(val fileName: String, val documentJson: String) : ApiDiscoverySource
}

data class ApiCustomCapabilityInput(
    val displayName: String,
    val description: String?,
    val method: String,
    val path: String,
    val inputSchemaJson: String? = null,
    val outputSchemaJson: String? = null,
    val selected: Boolean = false,
)

sealed interface ApiCredentialBinding {
    data object None : ApiCredentialBinding
    data class Existing(val credentialId: String) : ApiCredentialBinding

    class NewSecret(
        val displayName: String,
        val credentialType: String,
        val secret: String,
        val metadata: SecretCredentialMetadata,
    ) : ApiCredentialBinding {
        override fun toString(): String =
            "NewSecret(displayName=$displayName, credentialType=$credentialType, secret=<redacted>)"
    }
}

class ApiConnectionCreate(
    val serviceId: String? = null,
    val serviceType: String? = null,
    val baseUrl: String? = null,
    val displayName: String,
    val note: String?,
    val configJson: String = "{}",
    val enabled: Boolean = true,
    val credential: ApiCredentialBinding,
    val idempotencyKey: String = UUID.randomUUID().toString(),
) {
    override fun toString(): String =
        "ApiConnectionCreate(serviceId=$serviceId, serviceType=$serviceType, displayName=$displayName, credential=$credential)"
}

data class ApiConnectionUpdate(
    val displayName: String? = null,
    val serviceType: String? = null,
    val baseUrl: String? = null,
    val note: String? = null,
    val configJson: String? = null,
    val enabled: Boolean? = null,
    val credentialId: String? = null,
    val updateCredential: Boolean = false,
)

interface ApiConnectionRepository {
    suspend fun services(): List<ApiServiceDescriptor>
    suspend fun connections(): List<ApiBackendConnection>
    suspend fun connection(connectionId: String): ApiBackendConnection
    suspend fun create(input: ApiConnectionCreate): ApiBackendConnection
    suspend fun update(connectionId: String, input: ApiConnectionUpdate): ApiBackendConnection
    suspend fun delete(connectionId: String): List<ApiBackendConnection>
    suspend fun capabilityCatalog(connectionId: String): ApiCapabilityCatalog
    suspend fun previewCapabilities(connectionId: String, source: ApiDiscoverySource): ApiCapabilityPreview
    suspend fun commitCapabilities(
        connectionId: String,
        previewId: String,
        selectedCapabilityIds: Set<String>,
    ): ApiCapabilityCatalog
    suspend fun selectCapabilities(connectionId: String, selectedCapabilityIds: Set<String>): ApiCapabilityCatalog
    suspend fun createCustomCapability(connectionId: String, input: ApiCustomCapabilityInput): ApiCapabilityCatalog
    suspend fun updateCustomCapability(
        connectionId: String,
        capabilityId: String,
        input: ApiCustomCapabilityInput,
    ): ApiCapabilityCatalog
    suspend fun deleteCustomCapability(connectionId: String, capabilityId: String): ApiCapabilityCatalog
}

class AppBackendApiConnectionRepository private constructor(
    private val api: ApiConnectionApi,
) : ApiConnectionRepository {
    constructor(baseUrl: String, sessionCookie: () -> String?) : this(HttpApiConnectionApi(baseUrl, sessionCookie))
    internal constructor(api: ApiConnectionApi, testOnly: Unit = Unit) : this(api)

    override suspend fun services(): List<ApiServiceDescriptor> =
        api.request("GET", "/api/api-services").optJSONArray("services").toApiServices()

    override suspend fun connections(): List<ApiBackendConnection> =
        api.request("GET", "/api/api-connections").optJSONArray("connections").toApiConnections()

    override suspend fun connection(connectionId: String): ApiBackendConnection =
        api.request("GET", "/api/api-connections/${encodeApiPath(connectionId)}").requireApiConnection()

    override suspend fun create(input: ApiConnectionCreate): ApiBackendConnection {
        val created = api.request(
            method = "POST",
            path = "/api/api-connections",
            body = createApiConnectionBody(input).toString(),
            headers = mapOf("Idempotency-Key" to input.idempotencyKey),
        ).requireApiConnection()
        return connection(created.id)
    }

    override suspend fun update(connectionId: String, input: ApiConnectionUpdate): ApiBackendConnection {
        api.request(
            method = "PUT",
            path = "/api/api-connections/${encodeApiPath(connectionId)}",
            body = updateApiConnectionBody(input).toString(),
        ).requireApiConnection()
        return connection(connectionId)
    }

    override suspend fun delete(connectionId: String): List<ApiBackendConnection> {
        val result = api.request("DELETE", "/api/api-connections/${encodeApiPath(connectionId)}")
        if (result.optString("status") != "deleted") {
            throw ApiConnectionException("API 连接删除尚未完成，请刷新后重试")
        }
        return connections()
    }

    override suspend fun capabilityCatalog(connectionId: String): ApiCapabilityCatalog =
        api.request("GET", "/api/api-connections/${encodeApiPath(connectionId)}/capabilities")
            .requireApiCapabilityCatalog()

    override suspend fun previewCapabilities(
        connectionId: String,
        source: ApiDiscoverySource,
    ): ApiCapabilityPreview = api.request(
        method = "POST",
        path = "/api/api-connections/${encodeApiPath(connectionId)}/capability-discovery/preview",
        body = JSONObject().put("source", source.toApiJson()).toString(),
    ).requireApiCapabilityPreview()

    override suspend fun commitCapabilities(
        connectionId: String,
        previewId: String,
        selectedCapabilityIds: Set<String>,
    ): ApiCapabilityCatalog = api.request(
        method = "POST",
        path = "/api/api-connections/${encodeApiPath(connectionId)}/capability-discovery/commit",
        body = JSONObject().put("preview_id", previewId)
            .put("selected_capability_ids", JSONArray(selectedCapabilityIds.toList())).toString(),
    ).requireApiCapabilityCatalog()

    override suspend fun selectCapabilities(
        connectionId: String,
        selectedCapabilityIds: Set<String>,
    ): ApiCapabilityCatalog = api.request(
        method = "PUT",
        path = "/api/api-connections/${encodeApiPath(connectionId)}/capability-selections",
        body = JSONObject().put("selected_capability_ids", JSONArray(selectedCapabilityIds.toList())).toString(),
    ).requireApiCapabilityCatalog()

    override suspend fun createCustomCapability(
        connectionId: String,
        input: ApiCustomCapabilityInput,
    ): ApiCapabilityCatalog = api.request(
        method = "POST",
        path = "/api/api-connections/${encodeApiPath(connectionId)}/capabilities/custom",
        body = input.toApiJson().toString(),
    ).requireApiCapabilityCatalog()

    override suspend fun updateCustomCapability(
        connectionId: String,
        capabilityId: String,
        input: ApiCustomCapabilityInput,
    ): ApiCapabilityCatalog = api.request(
        method = "PUT",
        path = "/api/api-connections/${encodeApiPath(connectionId)}/capabilities/custom/${encodeApiPath(capabilityId)}",
        body = input.toApiJson().toString(),
    ).requireApiCapabilityCatalog()

    override suspend fun deleteCustomCapability(connectionId: String, capabilityId: String): ApiCapabilityCatalog =
        api.request(
            method = "DELETE",
            path = "/api/api-connections/${encodeApiPath(connectionId)}/capabilities/custom/${encodeApiPath(capabilityId)}",
        ).requireApiCapabilityCatalog()
}

internal interface ApiConnectionApi {
    suspend fun request(
        method: String,
        path: String,
        body: String? = null,
        headers: Map<String, String> = emptyMap(),
    ): JSONObject
}

private class HttpApiConnectionApi(
    private val baseUrl: String,
    private val sessionCookie: () -> String?,
) : ApiConnectionApi {
    override suspend fun request(
        method: String,
        path: String,
        body: String?,
        headers: Map<String, String>,
    ): JSONObject = withContext(Dispatchers.IO) {
        val cookie = sessionCookie() ?: throw ApiConnectionException("请先登录 LoveHouse App Account")
        val connection = (URL("${baseUrl.trimEnd('/')}$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cookie", cookie)
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
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
                throw ApiConnectionException(apiConnectionErrorText(status, code))
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

internal class ApiConnectionException(message: String) : Exception(message)

internal fun createApiConnectionBody(input: ApiConnectionCreate): JSONObject = JSONObject()
    .put("display_name", input.displayName.trim())
    .put("note", input.note?.trim()?.takeIf(String::isNotEmpty) ?: JSONObject.NULL)
    .put("config", JSONObject(input.configJson))
    .put("enabled", input.enabled)
    .apply {
        input.serviceId?.let { put("service_id", it) }
        input.serviceType?.let { put("service_type", it.trim()) }
        input.baseUrl?.let { put("base_url", it.trim()) }
        when (val binding = input.credential) {
            ApiCredentialBinding.None -> Unit
            is ApiCredentialBinding.Existing ->
                put("credential", JSONObject().put("credential_id", binding.credentialId))
            is ApiCredentialBinding.NewSecret -> put(
                "credential",
                JSONObject().put(
                    "create",
                    JSONObject()
                        .put("display_name", binding.displayName.trim())
                        .put("credential_type", binding.credentialType)
                        .put("secret", binding.secret)
                        .put("metadata", binding.metadata.toApiJson()),
                ),
            )
        }
    }

internal fun updateApiConnectionBody(input: ApiConnectionUpdate): JSONObject = JSONObject().apply {
    input.displayName?.let { put("display_name", it.trim()) }
    input.serviceType?.let { put("service_type", it.trim()) }
    input.baseUrl?.let { put("base_url", it.trim()) }
    input.note?.let { put("note", it.trim().takeIf(String::isNotEmpty) ?: JSONObject.NULL) }
    input.configJson?.let { put("config", JSONObject(it)) }
    input.enabled?.let { put("enabled", it) }
    if (input.updateCredential) put("credential_id", input.credentialId ?: JSONObject.NULL)
}

internal fun JSONObject.toApiService(): ApiServiceDescriptor = ApiServiceDescriptor(
    serviceId = getString("service_id"),
    serviceKind = when (getString("service_kind")) {
        "VOICE" -> ApiServiceKind.Voice
        else -> ApiServiceKind.Tool
    },
    adapterId = getString("adapter_id"),
    displayName = getString("display_name"),
    acceptedCredentialTypes = optJSONArray("accepted_credential_types").toStringValues(),
    credentialRequired = optBoolean("credential_required", true),
    configContractVersion = optInt("config_contract_version", 1),
)

internal fun JSONObject.toApiConnection(): ApiBackendConnection = ApiBackendConnection(
    id = getString("connection_id"),
    serviceId = nullableApiString("service_id"),
    serviceType = nullableApiString("service_type"),
    baseUrl = nullableApiString("base_url"),
    displayName = getString("display_name"),
    note = nullableApiString("note"),
    credentialId = nullableApiString("credential_id"),
    configJson = (optJSONObject("config") ?: JSONObject()).toString(),
    enabled = optBoolean("enabled"),
    status = getString("status"),
    createdAt = getString("created_at"),
    updatedAt = getString("updated_at"),
)

private fun ApiDiscoverySource.toApiJson(): JSONObject = when (this) {
    ApiDiscoverySource.Preset -> JSONObject().put("type", "preset")
    is ApiDiscoverySource.Url -> JSONObject().put("type", "url").put("url", url).apply {
        apiKeyHeaderName?.takeIf(String::isNotBlank)?.let { put("api_key_header_name", it) }
    }
    is ApiDiscoverySource.JsonDocument -> JSONObject().put("type", "document")
        .put("file_name", fileName).put("document", JSONObject(documentJson))
}

private fun ApiCustomCapabilityInput.toApiJson(): JSONObject = JSONObject()
    .put("display_name", displayName)
    .put("description", description ?: JSONObject.NULL)
    .put("http_method", method)
    .put("relative_path", path)
    .put("selected", selected)
    .apply {
        inputSchemaJson?.takeIf(String::isNotBlank)?.let { put("input_schema", JSONObject(it)) }
        outputSchemaJson?.takeIf(String::isNotBlank)?.let { put("output_schema", JSONObject(it)) }
    }

private fun JSONObject.toApiCapability(): ApiCapability = ApiCapability(
    id = getString("capability_id"),
    source = if (getString("source_kind") == "CUSTOM") ApiCapabilitySource.Custom else ApiCapabilitySource.Discovered,
    sourceKey = getString("source_key"),
    displayName = getString("display_name"),
    description = nullableApiString("description"),
    method = getString("http_method"),
    path = getString("relative_path"),
    inputSchema = optJSONObject("input_schema")?.toString(2),
    outputSchema = optJSONObject("output_schema")?.toString(2),
    availability = if (getString("availability") == "MISSING") {
        ApiCapabilityAvailability.Missing
    } else {
        ApiCapabilityAvailability.Available
    },
    selected = optBoolean("selected", false),
)

private fun JSONObject.toApiCapabilityCatalog(): ApiCapabilityCatalog {
    val values = optJSONArray("capabilities")
    val capabilities = buildList {
        if (values != null) for (index in 0 until values.length()) {
            values.optJSONObject(index)?.let { add(it.toApiCapability()) }
        }
    }
    val discovery = optJSONObject("last_discovery")
    val changes = discovery?.optJSONObject("changes")
    val summary = discovery?.let {
        "第 ${it.optInt("revision")} 版 · +${changes?.optJSONArray("added")?.length() ?: 0} " +
            "~${changes?.optJSONArray("changed")?.length() ?: 0} -${changes?.optJSONArray("removed")?.length() ?: 0}"
    }
    return ApiCapabilityCatalog(
        connectionId = getString("connection_id"),
        revision = optInt("revision"),
        capabilities = capabilities,
        lastDiscoverySummary = summary,
    )
}

private fun JSONObject.toApiCapabilityChanges(): ApiCapabilityChanges = ApiCapabilityChanges(
    added = optJSONArray("added").toStringValues(),
    changed = optJSONArray("changed").toStringValues(),
    removed = optJSONArray("removed").toStringValues(),
)

private fun JSONObject.requireApiCapabilityCatalog(): ApiCapabilityCatalog =
    optJSONObject("catalog")?.toApiCapabilityCatalog()
        ?: throw ApiConnectionException("App Backend 未返回能力目录，请刷新后重试")

private fun JSONObject.requireApiCapabilityPreview(): ApiCapabilityPreview {
    val preview = optJSONObject("preview")
        ?: throw ApiConnectionException("App Backend 未返回能力预览，请重试")
    val values = preview.optJSONArray("capabilities")
    return ApiCapabilityPreview(
        previewId = preview.getString("preview_id"),
        revision = preview.optInt("revision"),
        capabilities = buildList {
            if (values != null) for (index in 0 until values.length()) {
                values.optJSONObject(index)?.let { add(it.toApiCapability()) }
            }
        },
        changes = preview.optJSONObject("changes")?.toApiCapabilityChanges() ?: ApiCapabilityChanges(),
        expiresAt = preview.getString("expires_at"),
    )
}

private fun JSONObject.requireApiConnection(): ApiBackendConnection =
    optJSONObject("connection")?.toApiConnection()
        ?: throw ApiConnectionException("App Backend 未返回 API 连接状态，请刷新后重试")

private fun JSONArray?.toApiServices(): List<ApiServiceDescriptor> = this?.let { values ->
    buildList { for (index in 0 until values.length()) values.optJSONObject(index)?.let { add(it.toApiService()) } }
}.orEmpty()

private fun JSONArray?.toApiConnections(): List<ApiBackendConnection> = this?.let { values ->
    buildList { for (index in 0 until values.length()) values.optJSONObject(index)?.let { add(it.toApiConnection()) } }
}.orEmpty()

private fun JSONArray?.toStringValues(): List<String> = this?.let { values ->
    buildList { for (index in 0 until values.length()) values.optString(index).takeIf(String::isNotBlank)?.let(::add) }
}.orEmpty()

private fun JSONObject.nullableApiString(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)

private fun SecretCredentialMetadata.toApiJson(): JSONObject = JSONObject().apply {
    serviceName?.trim()?.takeIf(String::isNotEmpty)?.let { put("service_name", it) }
    usernameHint?.trim()?.takeIf(String::isNotEmpty)?.let { put("username_hint", it) }
    note?.trim()?.takeIf(String::isNotEmpty)?.let { put("note", it) }
}

private fun encodeApiPath(value: String): String =
    URLEncoder.encode(value, Charsets.UTF_8.name()).replace("+", "%20")

private fun apiConnectionErrorText(status: Int, code: String): String = when {
    status == HttpURLConnection.HTTP_UNAUTHORIZED || code == "APP_AUTH_REQUIRED" ->
        "请先登录 LoveHouse App Account"
    code == "API_CAPABILITY_NOT_FOUND" -> "这个能力已不存在，请刷新目录"
    code == "API_DISCOVERY_SOURCE_REQUIRED" -> "请选择官方描述 URL 或 JSON 文件"
    code == "API_DISCOVERY_SOURCE_UNAVAILABLE" -> "这个服务尚未提供官方能力描述，请填写描述 URL 或手动添加"
    code == "API_DISCOVERY_PREVIEW_EXPIRED" -> "能力预览已过期，请重新发现"
    code == "API_DISCOVERY_FETCH_FAILED" -> "官方能力描述读取失败，原目录未改变"
    code.startsWith("OPENAPI_") -> "无法读取这个 OpenAPI 3.0/3.1 JSON 描述"
    code == "API_DISCOVERY_AUTH_UNSUPPORTED" -> "当前凭证类型暂不支持读取受保护描述"
    code == "INVALID_API_DISCOVERY_HEADER" -> "API Key Header 名称不可用"
    status == HttpURLConnection.HTTP_NOT_FOUND || code == "API_CONNECTION_NOT_FOUND" ->
        "这个 API 连接已不存在，请刷新列表"
    code == "API_SERVICE_NOT_FOUND" -> "这个 API 服务当前不可用，请刷新服务目录"
    code == "API_CREDENTIAL_NOT_FOUND" -> "所选密码库凭证已不存在，请重新选择"
    code == "API_CREDENTIAL_TYPE_UNSUPPORTED" -> "所选凭证类型不适用于这个 API 服务"
    code == "API_CREDENTIAL_REQUIRED" -> "这个 API 服务需要凭证"
    code == "IDEMPOTENCY_KEY_REUSED" -> "本次创建请求已变化，请返回后重新添加"
    status == HttpURLConnection.HTTP_BAD_REQUEST || code == "INVALID_API_CONNECTION" ->
        "API 连接信息不完整或格式不正确"
    status == HttpURLConnection.HTTP_UNAVAILABLE || code == "API_CONNECTION_UNAVAILABLE" ->
        "API 连接服务暂时不可用，请稍后重试"
    else -> "API 连接请求失败（HTTP $status）"
}
