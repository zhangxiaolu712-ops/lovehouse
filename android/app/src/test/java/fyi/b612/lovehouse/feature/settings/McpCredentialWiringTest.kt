package fyi.b612.lovehouse.feature.settings

import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class McpCredentialWiringTest {
    private val secret = "test-secret-value-not-real"

    @Test
    fun `credential bodies match the backend wire contract`() {
        assertEquals("""{"auth_type":"none"}""", mcpCredentialFields(McpCredentialInput.None).toString())

        val bearer = mcpCredentialFields(McpCredentialInput.Bearer(secret))
        assertEquals("bearer", bearer.getString("auth_type"))
        assertEquals(secret, bearer.getString("token"))
        assertEquals(setOf("auth_type", "token"), bearer.keys().asSequence().toSet())

        val apiKey = mcpCredentialFields(McpCredentialInput.ApiKey("X-Api-Key", secret))
        assertEquals("api_key", apiKey.getString("auth_type"))
        assertEquals("X-Api-Key", apiKey.getString("header_name"))
        assertEquals(secret, apiKey.getString("value"))
        assertEquals(setOf("auth_type", "header_name", "value"), apiKey.keys().asSequence().toSet())
    }

    @Test
    fun `omitted credential and explicit none are different create requests`() {
        val url = "https://mcp.example/runtime"
        val omitted = createMcpConnectionBody(url, null)
        assertEquals(url, omitted.getString("server_url"))
        assertFalse(omitted.has("credential"))
        assertFalse(omitted.has("tool_service_id"))

        val none = createMcpConnectionBody(url, McpCredentialInput.None)
        assertEquals("none", none.getJSONObject("credential").getString("auth_type"))

        val bearer = createMcpConnectionBody(url, McpCredentialInput.Bearer(secret))
        assertEquals("bearer", bearer.getJSONObject("credential").getString("auth_type"))
        assertFalse(bearer.has("tool_service_id"))
    }

    @Test
    fun `existing service add account keeps authoritative tool service identity`() {
        val body = createMcpConnectionBody(
            serverUrl = "https://mcp.example/runtime",
            credential = McpCredentialInput.Bearer(secret),
            toolServiceId = "service-a",
        )

        assertEquals("service-a", body.getString("tool_service_id"))
        assertEquals("bearer", body.getJSONObject("credential").getString("auth_type"))

        val oauthCreate = createMcpConnectionBody(
            serverUrl = "https://mcp.example/runtime",
            credential = null,
            toolServiceId = "service-a",
        )
        assertEquals("service-a", oauthCreate.getString("tool_service_id"))
        assertFalse(oauthCreate.has("credential"))
    }

    @Test
    fun `oauth choice never sends a credential`() {
        val draft = McpCredentialDraft.forNew()
        assertEquals(McpAuthChoice.OAuth, draft.choice)
        assertNull(draft.toInput())
    }

    @Test
    fun `public credential metadata parses for every auth type`() {
        val none = connectionJson("none", "configured").toConnection()
        assertEquals(McpAuthType.None, none.authType)
        assertEquals(McpCredentialStatus.Configured, none.credentialStatus)
        assertNull(none.credentialUpdatedAt)

        val bearer = connectionJson("bearer", "configured", updatedAt = "2026-10-03T12:34:56.789Z").toConnection()
        assertEquals(McpAuthType.Bearer, bearer.authType)
        assertEquals("2026-10-03T12:34:56.789Z", bearer.credentialUpdatedAt)
        assertNull(bearer.apiKeyHeaderName)

        val apiKey = connectionJson("api_key", "configured", headerName = "X-Api-Key").toConnection()
        assertEquals(McpAuthType.ApiKey, apiKey.authType)
        assertEquals("X-Api-Key", apiKey.apiKeyHeaderName)

        val oauth = connectionJson("oauth", "reauthorization_required", status = "authorization_required").toConnection()
        assertEquals(McpAuthType.OAuth, oauth.authType)
        assertEquals(McpCredentialStatus.ReauthorizationRequired, oauth.credentialStatus)
        assertEquals(McpBackendConnectionStatus.AuthorizationRequired, oauth.status)

        assertEquals(McpCredentialStatus.Missing, connectionJson("bearer", "missing").toConnection().credentialStatus)
        assertEquals(McpBackendConnectionStatus.AuthUpdating, connectionJson("bearer", "configured", status = "auth_updating").toConnection().status)
    }

    @Test
    fun `summary uses metadata only and never fabricates a masked secret`() {
        val bearer = connectionJson("bearer", "configured", updatedAt = "2026-10-03T12:34:56.789Z").toConnection()
        assertEquals("Bearer Token · 凭证已配置 · 更新于 2026-10-03", mcpCredentialSummary(bearer))
        val apiKey = connectionJson("api_key", "configured", headerName = "X-Api-Key").toConnection()
        assertEquals("API Key · X-Api-Key · 凭证已配置", mcpCredentialSummary(apiKey))
        assertEquals("无鉴权 · 可用", mcpCredentialSummary(connectionJson("none", "configured").toConnection()))
        listOf(bearer, apiKey).forEach { assertFalse(mcpCredentialSummary(it)!!.contains("•")) }
    }

    @Test
    fun `secret never appears in printed state and is cleared after submit`() {
        assertFalse(McpCredentialInput.Bearer(secret).toString().contains(secret))
        assertFalse(McpCredentialInput.ApiKey("X-Api-Key", secret).toString().contains(secret))

        val typed = McpCredentialDraft.forNew().withChoice(McpAuthChoice.Bearer).withToken(secret)
        assertTrue(typed.hasSecret)
        assertFalse(typed.toString().contains(secret))
        val cleared = typed.cleared()
        assertFalse(cleared.hasSecret)
        assertEquals("", cleared.token)
        assertEquals(McpAuthChoice.Bearer, cleared.choice)
    }

    @Test
    fun `reopening a connection rebuilds the form from metadata without any secret`() {
        val responseWithStraySecret = connectionJson("api_key", "configured", headerName = "X-Api-Key")
            .put("value", secret)
            .put("token", secret)
        val connection = responseWithStraySecret.toConnection()
        assertFalse(connection.toString().contains(secret))

        val reopened = McpCredentialDraft.from(connection)
        assertEquals(McpAuthChoice.ApiKey, reopened.choice)
        assertEquals("X-Api-Key", reopened.headerName)
        assertEquals("", reopened.apiKey)
        assertEquals("", reopened.token)
        assertFalse(reopened.hasSecret)
    }

    @Test
    fun `draft validation follows the credential type`() {
        val bearer = McpCredentialDraft.forNew().withChoice(McpAuthChoice.Bearer)
        assertEquals("请输入 Token", bearer.validationError())
        assertNull(bearer.withToken(secret).validationError())

        val apiKey = McpCredentialDraft.forNew().withChoice(McpAuthChoice.ApiKey)
        assertEquals("请输入 Header Name", apiKey.validationError())
        assertEquals("Header Name 格式不正确", apiKey.withHeaderName("X Api Key").withApiKey(secret).validationError())
        assertEquals("Header Name 格式不正确", apiKey.withHeaderName("X".repeat(129)).withApiKey(secret).validationError())
        assertNull(apiKey.withHeaderName("X-Api-Key").withApiKey(secret).validationError())
        assertNull(McpCredentialDraft.forNew().withChoice(McpAuthChoice.None).validationError())
    }

    @Test
    fun `updated response requires status updated and a connection`() {
        val ok = JSONObject().put("status", "updated").put("connection", connectionJson("none", "configured"))
        assertEquals(McpAuthType.None, ok.toUpdatedConnection().authType)
        expectSafeFailure { JSONObject().put("status", "auth_updating").toUpdatedConnection() }
        expectSafeFailure { JSONObject().put("status", "updated").toUpdatedConnection() }
    }

    @Test
    fun `reauthorize response yields an oauth start with authorization url`() {
        val payload = JSONObject()
            .put("status", "authorization_required")
            .put("connection_id", "c1")
            .put("authorization_url", "https://provider.example/oauth/authorize")
            .put("connection", connectionJson("oauth", "reauthorization_required", status = "authorization_required"))
        val start = payload.toConnectionStart()
        assertEquals("c1", start.connectionId)
        assertEquals(McpBackendConnectionStatus.AuthorizationRequired, start.status)
        assertEquals("https://provider.example/oauth/authorize", start.authorizationUrl)
        assertEquals(McpAuthType.OAuth, start.connection?.authType)
    }

    @Test
    fun `put delete and reauthorize each converge to an authoritative get`() = runBlocking {
        val repository = RecordingRepository()

        val afterPut = runMcpCredentialMutation(repository, "c1") { updateCredential("c1", McpCredentialInput.Bearer(secret)) }
        val afterDelete = runMcpCredentialMutation(repository, "c1") { removeCredential("c1") }
        val afterReauth = runMcpCredentialMutation(repository, "c1") { reauthorizeOAuth("c1") }

        assertEquals(
            listOf("put c1 bearer", "get c1", "delete c1", "get c1", "reauthorize c1", "get c1"),
            repository.calls,
        )
        listOf(afterPut, afterDelete, afterReauth).forEach { assertEquals("authoritative", it.name) }
        assertFalse(repository.calls.joinToString().contains(secret))
    }

    @Test
    fun `single save plans the credential mutation from dirty state`() {
        val none = connectionJson("none", "configured").toConnection()
        val bearer = connectionJson("bearer", "configured").toConnection()
        val apiKey = connectionJson("api_key", "configured", headerName = "X-Api-Key").toConnection()
        val draft = McpCredentialDraft.forNew()

        assertEquals(McpCredentialPlan.NoChange, planMcpCredentialChange(McpCredentialDraft.from(bearer), bearer))
        assertEquals(McpCredentialPlan.NoChange, planMcpCredentialChange(McpCredentialDraft.from(apiKey), apiKey))
        assertEquals(McpCredentialPlan.NoChange, planMcpCredentialChange(draft.withChoice(McpAuthChoice.OAuth), bearer))
        assertEquals(McpCredentialPlan.NoChange, planMcpCredentialChange(draft.withChoice(McpAuthChoice.None), none))
        assertEquals(McpCredentialPlan.Remove, planMcpCredentialChange(draft.withChoice(McpAuthChoice.None), bearer))

        val putBearer = planMcpCredentialChange(McpCredentialDraft.from(bearer).withToken(secret), bearer)
        assertTrue(putBearer is McpCredentialPlan.Put && putBearer.input is McpCredentialInput.Bearer)
        assertFalse(putBearer.toString().contains(secret))
        assertEquals(McpCredentialPlan.Invalid("请输入 Token"), planMcpCredentialChange(draft.withChoice(McpAuthChoice.Bearer), apiKey))

        val headerOnly = McpCredentialDraft.from(apiKey).withHeaderName("X-Other-Key")
        assertEquals(McpCredentialPlan.Invalid("修改 Header Name 需要同时重新输入 API Key"), planMcpCredentialChange(headerOnly, apiKey))
        val putApiKey = planMcpCredentialChange(headerOnly.withApiKey(secret), apiKey)
        assertTrue(putApiKey is McpCredentialPlan.Put && putApiKey.input is McpCredentialInput.ApiKey)
        assertEquals(
            McpCredentialPlan.Invalid("请输入 API Key"),
            planMcpCredentialChange(draft.withChoice(McpAuthChoice.ApiKey).withHeaderName("X-Api-Key"), bearer),
        )
    }

    @Test
    fun `single save runs details then credential then one authoritative get`() = runBlocking {
        val repository = RecordingRepository()
        val plan = McpCredentialPlan.Put(McpCredentialInput.Bearer(secret))
        val fresh = saveMcpConnectionEdits(repository, "c1", plan) { repository.calls += "details" }
        assertEquals(listOf("details", "put c1 bearer", "get c1"), repository.calls)
        assertEquals("authoritative", fresh.name)

        repository.calls.clear()
        saveMcpConnectionEdits(repository, "c1", McpCredentialPlan.Remove) { repository.calls += "details" }
        assertEquals(listOf("details", "delete c1", "get c1"), repository.calls)

        repository.calls.clear()
        saveMcpConnectionEdits(repository, "c1", McpCredentialPlan.NoChange) { repository.calls += "details" }
        assertEquals(listOf("details", "get c1"), repository.calls)
    }

    @Test
    fun `invalid credential blocks the save before anything is written`() = runBlocking {
        val repository = RecordingRepository()
        try {
            saveMcpConnectionEdits(repository, "c1", McpCredentialPlan.Invalid("请输入 Token")) { repository.calls += "details" }
            fail("expected failure")
        } catch (error: McpSafeException) {
            assertEquals("请输入 Token", error.message)
        }
        assertTrue(repository.calls.isEmpty())
    }

    @Test
    fun `credential failure after details reports safe partial result`() = runBlocking {
        val repository = RecordingRepository(failPut = true)
        try {
            saveMcpConnectionEdits(repository, "c1", McpCredentialPlan.Put(McpCredentialInput.Bearer(secret))) {
                repository.calls += "details"
            }
            fail("expected failure")
        } catch (error: McpSafeException) {
            assertEquals("服务和账号信息已保存，但凭证未更新：凭证未通过远端 MCP 验证，未更新", error.message)
            assertFalse(error.message!!.contains(secret))
        }
        assertEquals(listOf("details", "put c1 bearer"), repository.calls)
    }

    @Test
    fun `failed authoritative refresh surfaces safe text only`() = runBlocking {
        val repository = RecordingRepository(failGet = true)
        try {
            runMcpCredentialMutation(repository, "c1") { removeCredential("c1") }
            fail("expected failure")
        } catch (error: McpSafeException) {
            assertEquals("凭证已提交，但重新读取连接状态失败，请刷新", error.mcpSafeText())
        }
    }

    @Test
    fun `errors map from status and code without backend message`() {
        assertEquals("App 账号登录已失效，请重新登录后再试", mcpCredentialErrorText(401, null))
        assertEquals("这个连接不存在或已被移除", mcpCredentialErrorText(404, "MCP_CONNECTION_NOT_FOUND"))
        assertEquals("凭证格式不正确，请检查后再试", mcpCredentialErrorText(400, "INVALID_MCP_CREDENTIAL"))
        assertEquals("凭证未通过远端 MCP 验证，未更新", mcpCredentialErrorText(502, "MCP_CREDENTIAL_UPDATE_FAILED"))
        assertEquals("改为无鉴权后远端 MCP 未通过验证，凭证未移除", mcpCredentialErrorText(502, "MCP_CREDENTIAL_REMOVE_FAILED"))
        assertEquals("无法发起 OAuth 授权，请稍后再试", mcpCredentialErrorText(502, "MCP_OAUTH_REAUTHORIZATION_FAILED"))
        assertEquals("连接远端 MCP 失败，请检查地址和凭证", mcpCredentialErrorText(502, "MCP_CONNECTION_FAILED"))
        assertEquals("没有权限执行这个操作", mcpCredentialErrorText(403, null))
        assertEquals("操作失败（HTTP 500），请稍后再试", mcpCredentialErrorText(500, "SOMETHING_ELSE"))
        assertEquals("操作失败，请稍后再试", IllegalStateException("Authorization: Bearer $secret").mcpSafeText())
    }

    @Test
    fun `account meta shows credential summary from metadata`() {
        val connection = connectionJson("bearer", "configured").toConnection()
        assertEquals("未绑定人格档案 · Bearer Token · 凭证已配置 · 已连接 · 0 个工具", mcpAccountMeta(connection, emptyList()))
    }

    private fun expectSafeFailure(block: () -> Unit) {
        try {
            block()
            fail("expected McpSafeException")
        } catch (expected: McpSafeException) {
            assertTrue(expected.message!!.isNotBlank())
        }
    }

    private fun connectionJson(
        authType: String,
        credentialStatus: String,
        status: String = "connected",
        updatedAt: String? = null,
        headerName: String? = null,
    ): JSONObject = JSONObject()
        .put("connection_id", "c1")
        .put("server_url", "https://mcp.example/runtime")
        .put("status", status)
        .put("auth_type", authType)
        .put("credential_status", credentialStatus)
        .put("credential_updated_at", updatedAt ?: JSONObject.NULL)
        .apply { if (headerName != null) put("api_key_header_name", headerName) }

    private class RecordingRepository(
        private val failGet: Boolean = false,
        private val failPut: Boolean = false,
    ) : McpConnectionRepository {
        val calls = mutableListOf<String>()
        private val stale = McpBackendConnection("c1", "https://mcp.example/runtime", "stale", null, McpBackendConnectionStatus.Connected, 0)

        override suspend fun connections(): List<McpBackendConnection> = emptyList()
        override suspend fun connection(id: String): McpBackendConnection {
            calls += "get $id"
            if (failGet) throw IllegalStateException("backend detail that must not be shown")
            return stale.copy(name = "authoritative")
        }
        override suspend fun registry(): List<McpBackendConnection> = emptyList()
        override suspend fun connect(serverUrl: String): McpConnectionStart = error("unused")
        override suspend fun delete(connectionId: String): McpConnectionDeleteResult = error("unused")
        override suspend fun bindIdentity(toolServiceId: String, identityId: String, connectionId: String) = Unit
        override suspend fun unbindIdentity(toolServiceId: String, identityId: String) = Unit
        override suspend fun updateCredential(connectionId: String, credential: McpCredentialInput): McpBackendConnection {
            val type = when (credential) {
                McpCredentialInput.None -> "none"
                is McpCredentialInput.Bearer -> "bearer"
                is McpCredentialInput.ApiKey -> "api_key"
            }
            calls += "put $connectionId $type"
            if (failPut) throw McpSafeException(mcpCredentialErrorText(502, "MCP_CREDENTIAL_UPDATE_FAILED"))
            return stale
        }
        override suspend fun removeCredential(connectionId: String): McpBackendConnection {
            calls += "delete $connectionId"
            return stale
        }
        override suspend fun reauthorizeOAuth(connectionId: String): McpConnectionStart {
            calls += "reauthorize $connectionId"
            return McpConnectionStart(connectionId, McpBackendConnectionStatus.AuthorizationRequired, "https://provider.example/oauth")
        }
    }
}
