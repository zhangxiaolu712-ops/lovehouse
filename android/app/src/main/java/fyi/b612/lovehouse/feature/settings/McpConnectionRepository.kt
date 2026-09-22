package fyi.b612.lovehouse.feature.settings

import java.net.HttpURLConnection
import java.net.URL
import android.util.Log
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
    Unknown,
}

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
)

data class McpDiscoveredTool(
    val name: String,
    val description: String? = null,
)

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
    suspend fun connect(serverUrl: String): McpConnectionStart
    suspend fun delete(connectionId: String): McpConnectionDeleteResult
    suspend fun bindIdentity(toolServiceId: String, identityId: String, connectionId: String)
    suspend fun unbindIdentity(toolServiceId: String, identityId: String)
    suspend fun legacyConnections(): List<LegacyMcpConnection> = emptyList()
    suspend fun beginLegacyClaim(connectionId: String): String = error("旧连接认领尚未接入")
}

internal fun appBackendMcpEndpoint(baseUrl: String, path: String): String =
    "${baseUrl.trimEnd('/')}/api/mcp/${path.trimStart('/')}"

internal fun opaqueMcpServerEndpoint(value: String): String? = value.takeIf(String::isNotBlank)

internal fun createMcpConnectionFields(serverUrl: String): Map<String, String> =
    mapOf("server_url" to serverUrl)

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
        val payload = try { request("GET", endpoint) } catch (error: McpHttpException) {
            if (error.status == 404) return emptyList() else throw error
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

    override suspend fun connect(serverUrl: String): McpConnectionStart {
        val endpoint = opaqueMcpServerEndpoint(serverUrl) ?: error("请输入完整的 MCP Server URL")
        val payload = request(
            method = "POST",
            endpoint = appBackendMcpEndpoint(baseUrl, "connections"),
            body = JSONObject(createMcpConnectionFields(endpoint)).toString(),
        )
        val nested = payload.optJSONObject("connection")
        val connectionId = payload.firstString("connection_id", "id")
            ?: nested?.firstString("connection_id", "id")
            ?: error("App Backend 未返回 connection_id")
        val status = parseStatus(payload.firstString("status") ?: nested?.firstString("status"))
        val authorizationUrl = payload.firstString("authorization_url")
        if (status == McpBackendConnectionStatus.AuthorizationRequired && !authorizationUrl.isNullOrBlank()) {
            check(isSafeMcpAuthorizationUrl(authorizationUrl)) { "App Backend 返回了不安全的 OAuth 地址" }
        }
        Log.i(
            LOG_TAG,
            "create_connection connection_id=$connectionId response_status=${status.name.lowercase()} " +
                "authorization_url_present=${!authorizationUrl.isNullOrBlank()}",
        )
        return McpConnectionStart(
            connectionId = connectionId,
            status = status,
            authorizationUrl = authorizationUrl,
            connection = nested?.toConnection(),
        )
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

    private suspend fun request(method: String, endpoint: String, body: String? = null): JSONObject = withContext(Dispatchers.IO) {
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
                Log.w(LOG_TAG, "mcp_request_failed http_status=$status error_code=${errorCode ?: "none"} error_message=$message")
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

private fun JSONObject.toConnection(): McpBackendConnection {
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
    )
}

private fun JSONArray?.toTools(): List<McpDiscoveredTool> = this?.let { array ->
    buildList {
        for (index in 0 until array.length()) {
            array.optJSONObject(index)?.let { tool ->
                tool.optString("name").takeIf(String::isNotBlank)?.let { name ->
                    add(McpDiscoveredTool(name, tool.optString("description").takeIf(String::isNotBlank)))
                }
            }
        }
    }
}.orEmpty()

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
    else -> McpBackendConnectionStatus.Unknown
}

private fun JSONObject.firstString(vararg keys: String): String? = keys.firstNotNullOfOrNull { key ->
    optString(key).takeIf(String::isNotBlank)
}

private fun JSONObject.firstInt(vararg keys: String): Int? = keys.firstNotNullOfOrNull { key ->
    takeIf { has(key) && !isNull(key) }?.optInt(key)
}

private fun JSONObject?.orSelf(fallback: JSONObject): JSONObject = this ?: fallback
