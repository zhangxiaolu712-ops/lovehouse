package fyi.b612.lovehouse.feature.settings

import java.net.HttpURLConnection
import java.net.URL
import java.io.IOException
import android.util.Log
import fyi.b612.lovehouse.feature.chat.personaRuntimeHttpFailure
import fyi.b612.lovehouse.feature.chat.personaRuntimeIoFailure
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

enum class McpBackendConnectionStatus {
    Connecting,
    AuthorizationRequired,
    Connected,
    Failed,
    Abandoned,
    AuthUpdating,
    Unknown,
}

enum class McpAuthType { None, Bearer, ApiKey, OAuth }

enum class McpCredentialStatus { Configured, Missing, ReauthorizationRequired }

/** Write-only credential input; it is never persisted and never printed. */
sealed interface McpCredentialInput {
    data object None : McpCredentialInput

    class Bearer(val token: String) : McpCredentialInput {
        override fun toString(): String = "Bearer(token=<redacted>)"
    }

    class ApiKey(val headerName: String, val value: String) : McpCredentialInput {
        override fun toString(): String = "ApiKey(headerName=$headerName, value=<redacted>)"
    }
}

/** An error whose message is Android-owned text that is safe to show. */
class McpSafeException(message: String) : IllegalStateException(message)

data class McpBackendConnection(
    val id: String,
    val serverUrl: String,
    val name: String?,
    val description: String?,
    val status: McpBackendConnectionStatus,
    val toolCount: Int,
    val errorMessage: String? = null,
    val toolServiceId: String? = null,
    val enabled: Boolean = true,
    val boundIdentityIds: List<String> = emptyList(),
    val tools: List<McpDiscoveredTool> = emptyList(),
    val displayName: String? = null,
    val note: String? = null,
    val authType: McpAuthType? = null,
    val credentialStatus: McpCredentialStatus? = null,
    val credentialUpdatedAt: String? = null,
    val apiKeyHeaderName: String? = null,
)

data class McpToolService(
    val id: String,
    val name: String?,
    val displayName: String?,
    val connectionCount: Int,
    val connectedConnectionCount: Int,
    val note: String? = null,
    val enabled: Boolean = true,
)

data class McpDiscoveredTool(
    val name: String,
    val description: String? = null,
    val toolId: String? = null,
    val inputSchema: String? = null,
)

enum class McpToolPolicyDecision { DENY, ASK, ALLOW }

enum class McpToolPolicyScopeType {
    PERSONA_CONNECTION_TOOL,
    PERSONA_CONNECTION,
    ACCOUNT_CONNECTION_TOOL,
    ACCOUNT_CONNECTION,
    ACCOUNT,
}

data class McpToolPolicyRecord(
    val id: String,
    val scopeType: McpToolPolicyScopeType,
    val decision: McpToolPolicyDecision,
    val policyRevision: Long,
    val personaId: String? = null,
    val connectionId: String? = null,
    val toolId: String? = null,
)

data class McpToolPolicyState(
    val policyRevision: Long,
    val policies: List<McpToolPolicyRecord>,
)

data class McpToolPolicyDraft(
    val scopeType: McpToolPolicyScopeType,
    val decision: McpToolPolicyDecision,
    val personaId: String? = null,
    val connectionId: String? = null,
    val toolId: String? = null,
) {
    companion object {
        fun accountConnectionTool(
            connectionId: String,
            toolId: String,
            decision: McpToolPolicyDecision,
        ) = McpToolPolicyDraft(
            scopeType = McpToolPolicyScopeType.ACCOUNT_CONNECTION_TOOL,
            decision = decision,
            connectionId = connectionId,
            toolId = toolId,
        )
    }
}

data class McpToolPolicyMutation(
    val policyRevision: Long,
    val policy: McpToolPolicyRecord,
)

data class McpToolServiceUpdate(val displayName: String?, val note: String?)

data class McpConnectionUpdate(
    val displayName: String? = null,
    val note: String? = null,
    val enabled: Boolean? = null,
    val updateMetadata: Boolean = false,
)

enum class McpToolServiceDeleteResult { Deleted, AlreadyAbsent, HasConnections }

data class McpEffectiveConnection(
    val connectionId: String,
    val toolServiceId: String,
    val name: String?,
    val toolCount: Int,
)

data class LegacyMcpConnection(
    val id: String,
    val name: String?,
    val claimMethod: String,
)

data class McpConnectionStart(
    val connectionId: String,
    val status: McpBackendConnectionStatus,
    val authorizationUrl: String? = null,
    val connection: McpBackendConnection? = null,
)

enum class McpConnectionDeleteResult {
    Deleted,
    AlreadyAbsent,
    Active,
}

internal enum class McpConnectionNextAction { OpenAuthorization, RefreshStatus, Wait }

internal fun McpConnectionStart.nextAction(): McpConnectionNextAction = when {
    status == McpBackendConnectionStatus.AuthorizationRequired && authorizationUrl != null ->
        McpConnectionNextAction.OpenAuthorization
    status == McpBackendConnectionStatus.Connected -> McpConnectionNextAction.RefreshStatus
    else -> McpConnectionNextAction.Wait
}

interface McpConnectionRepository {
    /** Null means this repository has no authoritative Persona runtime endpoint. */
    suspend fun effectiveConnections(personaId: String): List<McpEffectiveConnection>? = null
    suspend fun connections(): List<McpBackendConnection>
    suspend fun connection(id: String): McpBackendConnection
    suspend fun registry(): List<McpBackendConnection>
    suspend fun toolServices(): List<McpToolService> = emptyList()
    suspend fun serviceConnections(toolServiceId: String): List<McpBackendConnection> = emptyList()
    suspend fun connectionTools(connectionId: String): List<McpDiscoveredTool> = emptyList()
    suspend fun toolPolicies(): McpToolPolicyState = McpToolPolicyState(0, emptyList())
    suspend fun putToolPolicy(draft: McpToolPolicyDraft): McpToolPolicyMutation =
        error("Tool Policy 尚未接入")
    suspend fun deleteToolPolicy(policyId: String): McpToolPolicyMutation =
        error("Tool Policy 尚未接入")
    suspend fun updateToolService(toolServiceId: String, update: McpToolServiceUpdate): McpToolService =
        error("Tool Service 更新尚未接入")
    suspend fun deleteToolService(toolServiceId: String): McpToolServiceDeleteResult =
        error("Tool Service 删除尚未接入")
    suspend fun updateConnection(connectionId: String, update: McpConnectionUpdate): McpBackendConnection =
        error("MCP Connection 更新尚未接入")
    suspend fun connect(serverUrl: String): McpConnectionStart

    /** A null credential keeps the OAuth-capable create path; explicit None is a different request. */
    suspend fun connect(
        serverUrl: String,
        credential: McpCredentialInput?,
        toolServiceId: String? = null,
    ): McpConnectionStart =
        if (credential == null && toolServiceId == null) connect(serverUrl) else error("MCP 凭证尚未接入")
    suspend fun updateCredential(connectionId: String, credential: McpCredentialInput): McpBackendConnection =
        error("MCP 凭证尚未接入")
    suspend fun removeCredential(connectionId: String): McpBackendConnection = error("MCP 凭证尚未接入")
    suspend fun reauthorizeOAuth(connectionId: String): McpConnectionStart = error("MCP OAuth 重新授权尚未接入")
    suspend fun delete(connectionId: String): McpConnectionDeleteResult
    suspend fun bindIdentity(toolServiceId: String, identityId: String, connectionId: String)
    suspend fun unbindIdentity(toolServiceId: String, identityId: String)
    suspend fun legacyConnections(): List<LegacyMcpConnection> = emptyList()
    suspend fun beginLegacyClaim(connectionId: String): String = error("旧连接认领尚未接入")
}

internal fun appBackendMcpEndpoint(baseUrl: String, path: String): String =
    "${baseUrl.trimEnd('/')}/api/mcp/${path.trimStart('/')}"

internal fun mcpToolPolicyFields(draft: McpToolPolicyDraft): Map<String, Any> = buildMap {
    put("scope_type", draft.scopeType.name)
    put("policy", draft.decision.name)
    draft.personaId?.let { put("persona_id", it) }
    draft.connectionId?.let { put("connection_id", it) }
    draft.toolId?.let { put("tool_id", it) }
}

internal fun mcpToolServiceUpdateFields(update: McpToolServiceUpdate): Map<String, Any?> = mapOf(
    "display_name" to update.displayName,
    "note" to update.note,
)

internal fun mcpConnectionUpdateFields(update: McpConnectionUpdate): Map<String, Any?> = buildMap {
    if (update.updateMetadata) {
        put("display_name", update.displayName)
        put("note", update.note)
    }
    update.enabled?.let { put("enabled", it) }
}

internal fun opaqueMcpServerEndpoint(value: String): String? = value.takeIf(String::isNotBlank)

internal fun createMcpConnectionFields(serverUrl: String, toolServiceId: String? = null): Map<String, String> =
    buildMap {
        put("server_url", serverUrl)
        toolServiceId?.takeIf(String::isNotBlank)?.let { put("tool_service_id", it) }
    }

internal fun mcpCredentialFields(credential: McpCredentialInput): JSONObject = when (credential) {
    McpCredentialInput.None -> JSONObject().put("auth_type", "none")
    is McpCredentialInput.Bearer -> JSONObject().put("auth_type", "bearer").put("token", credential.token)
    is McpCredentialInput.ApiKey -> JSONObject()
        .put("auth_type", "api_key")
        .put("header_name", credential.headerName)
        .put("value", credential.value)
}

internal fun createMcpConnectionBody(
    serverUrl: String,
    credential: McpCredentialInput?,
    toolServiceId: String? = null,
): JSONObject =
    JSONObject(createMcpConnectionFields(serverUrl, toolServiceId)).apply {
        if (credential != null) put("credential", mcpCredentialFields(credential))
    }

/** Maps HTTP status + error.code to Android-owned text; backend error.message is never shown. */
internal fun mcpCredentialErrorText(status: Int, errorCode: String?): String = when {
    status == 401 -> "App 账号登录已失效，请重新登录后再试"
    status == 404 || errorCode == "MCP_CONNECTION_NOT_FOUND" -> "这个连接不存在或已被移除"
    errorCode == "INVALID_MCP_CREDENTIAL" -> "凭证格式不正确，请检查后再试"
    errorCode == "MCP_CREDENTIAL_UPDATE_FAILED" -> "凭证未通过远端 MCP 验证，未更新"
    errorCode == "MCP_CREDENTIAL_REMOVE_FAILED" -> "改为无鉴权后远端 MCP 未通过验证，凭证未移除"
    errorCode == "MCP_OAUTH_REAUTHORIZATION_FAILED" -> "无法发起 OAuth 授权，请稍后再试"
    errorCode == "MCP_CONNECTION_FAILED" -> "连接远端 MCP 失败，请检查地址和凭证"
    status == 403 -> "没有权限执行这个操作"
    else -> "操作失败（HTTP $status），请稍后再试"
}

internal fun Throwable.mcpSafeText(): String = (this as? McpSafeException)?.message ?: "操作失败，请稍后再试"

private val HeaderNameToken = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")

/** Local pre-check only; App Backend performs the authoritative validation. */
internal fun isPlausibleApiKeyHeaderName(value: String): Boolean = value.length <= 128 && HeaderNameToken.matches(value)

internal fun mcpConnectionEndpoint(baseUrl: String, connectionId: String): String {
    require(connectionId.isNotBlank()) { "缺少 MCP connection_id" }
    return appBackendMcpEndpoint(baseUrl, "connections/${encodePathSegment(connectionId)}")
}

internal fun mcpDeleteResult(status: Int, errorCode: String?): McpConnectionDeleteResult = when {
    status in 200..299 -> McpConnectionDeleteResult.Deleted
    status == 404 || errorCode == "MCP_CONNECTION_NOT_FOUND" -> McpConnectionDeleteResult.AlreadyAbsent
    status == 409 || errorCode == "CONNECTION_ACTIVE" -> McpConnectionDeleteResult.Active
    else -> error("无法删除 MCP connection（HTTP $status）")
}

internal fun isSafeMcpAuthorizationUrl(value: String): Boolean = runCatching {
    val url = URL(value)
    url.protocol == "https" && url.host.isNotBlank()
}.getOrDefault(false)

class AppBackendMcpConnectionRepository(
    private val baseUrl: String,
    private val sessionCookie: () -> String? = { null },
) : McpConnectionRepository {
    override suspend fun effectiveConnections(personaId: String): List<McpEffectiveConnection> {
        val endpoint = "${baseUrl.trimEnd('/')}/api/personas/${encodePathSegment(personaId)}/runtime"
        val payload = try {
            request("GET", endpoint)
        } catch (error: McpHttpException) {
            throw personaRuntimeHttpFailure(error.status, error.message.orEmpty())
        } catch (error: IOException) {
            throw personaRuntimeIoFailure(error)
        }
        val values = payload.optJSONArray("effective_connections") ?: return emptyList()
        return buildList {
            for (index in 0 until values.length()) values.optJSONObject(index)?.let { item ->
                val connectionId = item.optString("connection_id")
                val serviceId = item.optString("tool_service_id")
                if (connectionId.isNotBlank() && serviceId.isNotBlank()) {
                    add(McpEffectiveConnection(connectionId, serviceId,
                        item.optString("name").takeIf(String::isNotBlank), item.optInt("tool_count")))
                }
            }
        }
    }
    override suspend fun legacyConnections(): List<LegacyMcpConnection> {
        val array = request("GET", appBackendMcpEndpoint(baseUrl, "legacy-connections")).optJSONArray("connections")
            ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { item ->
                    item.optString("connection_id").takeIf(String::isNotBlank)?.let { id ->
                        add(LegacyMcpConnection(id, item.optString("name").takeIf(String::isNotBlank), item.optString("claim_method")))
                    }
                }
            }
        }
    }

    override suspend fun beginLegacyClaim(connectionId: String): String {
        val result = request("POST", appBackendMcpEndpoint(baseUrl, "connections/${encodePathSegment(connectionId)}/claim"))
        check(result.optString("status") == "authorization_required") { "该旧连接尚不能安全认领" }
        return result.optString("authorization_url").takeIf(::isSafeMcpAuthorizationUrl)
            ?: error("App Backend 未返回安全的授权地址")
    }
    override suspend fun connections(): List<McpBackendConnection> =
        request("GET", appBackendMcpEndpoint(baseUrl, "connections"))
            .optJSONArray("connections")
            .toConnections()

    override suspend fun connection(id: String): McpBackendConnection {
        val payload = request("GET", mcpConnectionEndpoint(baseUrl, id))
        return payload.optJSONObject("connection").orSelf(payload).toConnection()
    }

    override suspend fun registry(): List<McpBackendConnection> =
        request("GET", appBackendMcpEndpoint(baseUrl, "registry"))
            .optJSONArray("servers")
            .toConnections()

    override suspend fun toolServices(): List<McpToolService> {
        val values = request("GET", appBackendMcpEndpoint(baseUrl, "tool-services"))
            .optJSONArray("tool_services") ?: return emptyList()
        return buildList {
            for (index in 0 until values.length()) values.optJSONObject(index)?.let { service ->
                val id = service.firstString("id", "tool_service_id") ?: return@let
                add(McpToolService(
                    id = id,
                    name = service.firstString("name"),
                    displayName = service.firstString("display_name"),
                    connectionCount = service.firstInt("connections") ?: 0,
                    connectedConnectionCount = service.firstInt("connected_connections") ?: 0,
                    note = service.firstString("note"),
                    enabled = if (service.has("enabled")) service.optBoolean("enabled") else true,
                ))
            }
        }
    }

    override suspend fun serviceConnections(toolServiceId: String): List<McpBackendConnection> =
        request("GET", appBackendMcpEndpoint(baseUrl, "tool-services/${encodePathSegment(toolServiceId)}/connections"))
            .optJSONArray("connections")
            .toConnections()

    override suspend fun connectionTools(connectionId: String): List<McpDiscoveredTool> {
        val payload = request(
            "GET",
            appBackendMcpEndpoint(baseUrl, "connections/${encodePathSegment(connectionId)}/tools"),
        )
        check(payload.optString("status") == "ok") { "App Backend 未返回可用工具" }
        return payload.optJSONArray("tools").toAuthoritativeTools()
    }

    override suspend fun toolPolicies(): McpToolPolicyState =
        request("GET", appBackendMcpEndpoint(baseUrl, "tool-policies")).toToolPolicyState()

    override suspend fun putToolPolicy(draft: McpToolPolicyDraft): McpToolPolicyMutation {
        val payload = request(
            method = "PUT",
            endpoint = appBackendMcpEndpoint(baseUrl, "tool-policies"),
            body = JSONObject(mcpToolPolicyFields(draft)).toString(),
        )
        return McpToolPolicyMutation(payload.optLong("policy_revision"), payload.toToolPolicyRecord())
    }

    override suspend fun deleteToolPolicy(policyId: String): McpToolPolicyMutation {
        val payload = request(
            "DELETE",
            appBackendMcpEndpoint(baseUrl, "tool-policies/${encodePathSegment(policyId)}"),
        )
        val policy = payload.optJSONObject("policy") ?: error("App Backend 未返回已删除的 Tool Policy")
        return McpToolPolicyMutation(payload.optLong("policy_revision"), policy.toToolPolicyRecord())
    }

    override suspend fun updateToolService(
        toolServiceId: String,
        update: McpToolServiceUpdate,
    ): McpToolService = request(
        method = "PATCH",
        endpoint = appBackendMcpEndpoint(baseUrl, "tool-services/${encodePathSegment(toolServiceId)}"),
        body = JSONObject(mcpToolServiceUpdateFields(update)).toString(),
    ).toToolService()

    override suspend fun deleteToolService(toolServiceId: String): McpToolServiceDeleteResult = try {
        request("DELETE", appBackendMcpEndpoint(baseUrl, "tool-services/${encodePathSegment(toolServiceId)}"))
        McpToolServiceDeleteResult.Deleted
    } catch (error: McpHttpException) {
        when (error.errorCode) {
            "MCP_TOOL_SERVICE_NOT_FOUND" -> McpToolServiceDeleteResult.AlreadyAbsent
            "TOOL_SERVICE_HAS_CONNECTIONS" -> McpToolServiceDeleteResult.HasConnections
            else -> throw error
        }
    }

    override suspend fun updateConnection(
        connectionId: String,
        update: McpConnectionUpdate,
    ): McpBackendConnection = request(
        method = "PATCH",
        endpoint = mcpConnectionEndpoint(baseUrl, connectionId),
        body = JSONObject(mcpConnectionUpdateFields(update)).toString(),
    ).toConnection()

    override suspend fun connect(serverUrl: String): McpConnectionStart {
        val endpoint = opaqueMcpServerEndpoint(serverUrl) ?: error("请输入完整的 MCP Server URL")
        val payload = request(
            method = "POST",
            endpoint = appBackendMcpEndpoint(baseUrl, "connections"),
            body = JSONObject(createMcpConnectionFields(endpoint)).toString(),
        )
        val start = payload.toConnectionStart()
        Log.i(
            LOG_TAG,
            "create_connection connection_id=${start.connectionId} response_status=${start.status.name.lowercase()} " +
                "authorization_url_present=${!start.authorizationUrl.isNullOrBlank()}",
        )
        return start
    }

    override suspend fun connect(
        serverUrl: String,
        credential: McpCredentialInput?,
        toolServiceId: String?,
    ): McpConnectionStart {
        if (credential == null && toolServiceId == null) return connect(serverUrl)
        val endpoint = opaqueMcpServerEndpoint(serverUrl) ?: throw McpSafeException("请输入完整的 MCP Server URL")
        val requestBody = createMcpConnectionBody(endpoint, credential, toolServiceId).toString()
        val payload = if (credential == null) {
            request(
                method = "POST",
                endpoint = appBackendMcpEndpoint(baseUrl, "connections"),
                body = requestBody,
            )
        } else {
            credentialRequest(
                method = "POST",
                endpoint = appBackendMcpEndpoint(baseUrl, "connections"),
                body = requestBody,
            )
        }
        val start = payload.toConnectionStart()
        Log.i(
            LOG_TAG,
            "create_connection_with_credential response_status=${start.status.name.lowercase()} " +
                "existing_tool_service=${!toolServiceId.isNullOrBlank()}",
        )
        return start
    }

    override suspend fun updateCredential(
        connectionId: String,
        credential: McpCredentialInput,
    ): McpBackendConnection = credentialRequest(
        method = "PUT",
        endpoint = "${mcpConnectionEndpoint(baseUrl, connectionId)}/credential",
        body = mcpCredentialFields(credential).toString(),
    ).toUpdatedConnection()

    override suspend fun removeCredential(connectionId: String): McpBackendConnection = credentialRequest(
        method = "DELETE",
        endpoint = "${mcpConnectionEndpoint(baseUrl, connectionId)}/credential",
    ).toUpdatedConnection()

    override suspend fun reauthorizeOAuth(connectionId: String): McpConnectionStart {
        val start = credentialRequest(
            method = "POST",
            endpoint = "${mcpConnectionEndpoint(baseUrl, connectionId)}/oauth/reauthorize",
        ).toConnectionStart()
        if (start.status != McpBackendConnectionStatus.AuthorizationRequired || start.authorizationUrl.isNullOrBlank()) {
            throw McpSafeException("App Backend 未返回可用的 OAuth 授权地址")
        }
        return start
    }

    private suspend fun credentialRequest(method: String, endpoint: String, body: String? = null): JSONObject = try {
        request(method, endpoint, body, logErrorMessage = false)
    } catch (error: McpHttpException) {
        throw McpSafeException(mcpCredentialErrorText(error.status, error.errorCode))
    } catch (ignored: IOException) {
        throw McpSafeException("网络连接失败，请检查网络后再试")
    }

    override suspend fun delete(connectionId: String): McpConnectionDeleteResult = try {
        request("DELETE", mcpConnectionEndpoint(baseUrl, connectionId))
        McpConnectionDeleteResult.Deleted
    } catch (error: McpHttpException) {
        mcpDeleteResult(error.status, error.errorCode)
    }

    override suspend fun bindIdentity(toolServiceId: String, identityId: String, connectionId: String) {
        request(
            method = "PUT",
            endpoint = appBackendMcpEndpoint(
                baseUrl,
                "tool-services/${encodePathSegment(toolServiceId)}/bindings/${encodePathSegment(identityId)}",
            ),
            body = JSONObject(mapOf("connection_id" to connectionId)).toString(),
        )
    }

    override suspend fun unbindIdentity(toolServiceId: String, identityId: String) {
        request(
            method = "DELETE",
            endpoint = appBackendMcpEndpoint(
                baseUrl,
                "tool-services/${encodePathSegment(toolServiceId)}/bindings/${encodePathSegment(identityId)}",
            ),
        )
    }

    private suspend fun request(
        method: String,
        endpoint: String,
        body: String? = null,
        logErrorMessage: Boolean = true,
    ): JSONObject = withContext(Dispatchers.IO) {
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            sessionCookie()?.let { setRequestProperty("Cookie", it) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)
                ?.use { it.readText() }
                .orEmpty()
            val payload = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            Log.i(LOG_TAG, "mcp_request method=$method http_status=$status")
            if (status !in 200..299) {
                val errorCode = payload.optJSONObject("error")?.optString("code")
                    ?.takeIf(String::isNotBlank)
                val message = payload.optJSONObject("error")?.optString("message")
                    ?.takeIf(String::isNotBlank)
                    ?: payload.optString("message").takeIf(String::isNotBlank)
                    ?: "App Backend 请求失败（HTTP $status）"
                Log.w(
                    LOG_TAG,
                    "mcp_request_failed http_status=$status error_code=${errorCode ?: "none"}" +
                        if (logErrorMessage) " error_message=$message" else "",
                )
                throw McpHttpException(status, errorCode, message)
            }
            payload
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
        const val LOG_TAG = "LoveHouseMcp"
    }
}

private class McpHttpException(
    val status: Int,
    val errorCode: String?,
    message: String,
) : IllegalStateException(message)

private fun encodePathSegment(value: String): String = java.net.URLEncoder.encode(value, Charsets.UTF_8.name())
    .replace("+", "%20")

private fun JSONArray?.toConnections(): List<McpBackendConnection> = this?.let { array ->
    buildList {
        for (index in 0 until array.length()) {
            array.optJSONObject(index)?.let { add(it.toConnection()) }
        }
    }
}.orEmpty()

internal fun JSONObject.toConnectionStart(): McpConnectionStart {
    val nested = optJSONObject("connection")
    val connectionId = firstString("connection_id", "id")
        ?: nested?.firstString("connection_id", "id")
        ?: error("App Backend 未返回 connection_id")
    val status = parseStatus(firstString("status") ?: nested?.firstString("status"))
    val authorizationUrl = firstString("authorization_url")
    if (status == McpBackendConnectionStatus.AuthorizationRequired && !authorizationUrl.isNullOrBlank()) {
        check(isSafeMcpAuthorizationUrl(authorizationUrl)) { "App Backend 返回了不安全的 OAuth 地址" }
    }
    return McpConnectionStart(
        connectionId = connectionId,
        status = status,
        authorizationUrl = authorizationUrl,
        connection = nested?.toConnection(),
    )
}

internal fun JSONObject.toUpdatedConnection(): McpBackendConnection {
    if (optString("status") != "updated") throw McpSafeException("凭证变更尚未完成，请刷新后查看连接状态")
    return optJSONObject("connection")?.toConnection()
        ?: throw McpSafeException("App Backend 未返回更新后的连接状态")
}

internal fun JSONObject.toConnection(): McpBackendConnection {
    val tools = optJSONArray("tools")
    return McpBackendConnection(
        id = firstString("connection_id", "id", "server_id").orEmpty(),
        serverUrl = firstString("server_url", "url", "endpoint").orEmpty(),
        name = firstString("name", "display_name"),
        description = firstString("description"),
        status = parseStatus(firstString("status")),
        toolCount = firstInt("tool_count", "tools_count") ?: tools?.length() ?: 0,
        errorMessage = firstString("error_message", "last_error"),
        toolServiceId = firstString("tool_service_id"),
        enabled = if (has("enabled")) optBoolean("enabled") else true,
        boundIdentityIds = optJSONArray("bound_identities").toStringList(),
        tools = tools.toTools(),
        displayName = firstString("display_name"),
        note = firstString("note"),
        authType = parseAuthType(nullableString("auth_type")),
        credentialStatus = parseCredentialStatus(nullableString("credential_status")),
        credentialUpdatedAt = nullableString("credential_updated_at"),
        apiKeyHeaderName = nullableString("api_key_header_name"),
    )
}

/** Android's org.json returns "null" from optString for JSON null, so check isNull first. */
private fun JSONObject.nullableString(key: String): String? =
    if (isNull(key)) null else optString(key).takeIf(String::isNotBlank)

private fun parseAuthType(value: String?): McpAuthType? = when (value) {
    "none" -> McpAuthType.None
    "bearer" -> McpAuthType.Bearer
    "api_key" -> McpAuthType.ApiKey
    "oauth" -> McpAuthType.OAuth
    else -> null
}

private fun parseCredentialStatus(value: String?): McpCredentialStatus? = when (value) {
    "configured" -> McpCredentialStatus.Configured
    "missing" -> McpCredentialStatus.Missing
    "reauthorization_required" -> McpCredentialStatus.ReauthorizationRequired
    else -> null
}

private fun JSONArray?.toTools(): List<McpDiscoveredTool> = this?.let { array ->
    buildList {
        for (index in 0 until array.length()) {
            array.optJSONObject(index)?.let { tool ->
                tool.optString("name").takeIf(String::isNotBlank)?.let { name ->
                    add(McpDiscoveredTool(
                        name = name,
                        description = tool.optString("description").takeIf(String::isNotBlank),
                        toolId = tool.optString("tool_id").takeIf(String::isNotBlank),
                        inputSchema = tool.optJSONObject("inputSchema")?.toString(2),
                    ))
                }
            }
        }
    }
}.orEmpty()

internal fun JSONArray?.toAuthoritativeTools(): List<McpDiscoveredTool> = this?.let { array ->
    buildList {
        for (index in 0 until array.length()) {
            array.optJSONObject(index)?.let { tool ->
                val toolId = tool.optString("tool_id").takeIf(String::isNotBlank) ?: return@let
                val name = tool.optString("tool_name").takeIf(String::isNotBlank) ?: return@let
                add(McpDiscoveredTool(
                    name = name,
                    description = tool.optString("description").takeIf(String::isNotBlank),
                    toolId = toolId,
                    inputSchema = tool.optJSONObject("inputSchema")?.toString(2),
                ))
            }
        }
    }
}.orEmpty()

internal fun JSONObject.toToolPolicyState(): McpToolPolicyState {
    val values = optJSONArray("policies")
    return McpToolPolicyState(
        policyRevision = optLong("policy_revision"),
        policies = buildList {
            if (values != null) for (index in 0 until values.length()) {
                values.optJSONObject(index)?.let { add(it.toToolPolicyRecord()) }
            }
        },
    )
}

internal fun JSONObject.toToolPolicyRecord(): McpToolPolicyRecord = McpToolPolicyRecord(
    id = getString("id"),
    scopeType = McpToolPolicyScopeType.valueOf(getString("scope_type")),
    decision = McpToolPolicyDecision.valueOf(getString("policy")),
    policyRevision = optLong("policy_revision"),
    personaId = optString("persona_id").takeIf(String::isNotBlank),
    connectionId = optString("connection_id").takeIf(String::isNotBlank),
    toolId = optString("tool_id").takeIf(String::isNotBlank),
)

private fun JSONObject.toToolService(): McpToolService = McpToolService(
    id = firstString("id", "tool_service_id").orEmpty(),
    name = firstString("name"),
    displayName = firstString("display_name"),
    connectionCount = firstInt("connections") ?: 0,
    connectedConnectionCount = firstInt("connected_connections") ?: 0,
    note = firstString("note"),
    enabled = if (has("enabled")) optBoolean("enabled") else true,
)

private fun JSONArray?.toStringList(): List<String> = this?.let { array ->
    buildList {
        for (index in 0 until array.length()) {
            array.optString(index).takeIf(String::isNotBlank)?.let(::add)
        }
    }
}.orEmpty()

private fun parseStatus(value: String?): McpBackendConnectionStatus = when (value?.lowercase()) {
    "connecting", "pending" -> McpBackendConnectionStatus.Connecting
    "authorization_required", "auth_required" -> McpBackendConnectionStatus.AuthorizationRequired
    "connected", "ready" -> McpBackendConnectionStatus.Connected
    "failed", "error" -> McpBackendConnectionStatus.Failed
    "abandoned", "cancelled", "canceled" -> McpBackendConnectionStatus.Abandoned
    "auth_updating" -> McpBackendConnectionStatus.AuthUpdating
    else -> McpBackendConnectionStatus.Unknown
}

private fun JSONObject.firstString(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
    optString(key).takeIf(String::isNotBlank)
}

private fun JSONObject.firstInt(vararg keys: String): Int? = keys.firstNotNullOfOrNull { key ->
    takeIf { has(key) && !isNull(key) }?.optInt(key)
}

private fun JSONObject?.orSelf(fallback: JSONObject): JSONObject = this ?: fallback
