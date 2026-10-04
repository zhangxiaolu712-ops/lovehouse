package fyi.b612.lovehouse.feature.settings

import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ApiConnectionRepositoryTest {
    @Test
    fun `authoritative services and connections are parsed from App Backend`() = runBlocking {
        val api = RecordingApi()
        api.responses += JSONObject().put("services", JSONArray().put(serviceJson()))
        api.responses += JSONObject().put("connections", JSONArray().put(connectionJson()))
        val repository = AppBackendApiConnectionRepository(api)

        val service = repository.services().single()
        val connection = repository.connections().single()

        assertEquals("fixture.voice", service.serviceId)
        assertEquals(ApiServiceKind.Voice, service.serviceKind)
        assertEquals(listOf("api_key", "bearer_token"), service.acceptedCredentialTypes)
        assertEquals("connection-1", connection.id)
        assertEquals("credential-1", connection.credentialId)
        assertEquals(listOf("GET /api/api-services", "GET /api/api-connections"), api.calls.map { "${it.method} ${it.path}" })
    }

    @Test
    fun `direct secret create uses one idempotency key and rereads authoritative connection`() = runBlocking {
        val api = RecordingApi()
        api.responses += JSONObject().put("connection", connectionJson())
        api.responses += JSONObject().put("connection", connectionJson().put("display_name", "Authoritative name"))
        val repository = AppBackendApiConnectionRepository(api)
        val secret = "fixture-secret-not-for-storage"
        val input = ApiConnectionCreate(
            serviceId = "fixture.voice",
            displayName = "Voice",
            note = "note",
            credential = ApiCredentialBinding.NewSecret(
                displayName = "Voice key",
                credentialType = "api_key",
                secret = secret,
                metadata = SecretCredentialMetadata(serviceName = "Fixture Voice"),
            ),
            idempotencyKey = "phase2c-idempotency-0001",
        )

        val created = repository.create(input)
        val post = api.calls[0]

        assertEquals("Authoritative name", created.displayName)
        assertEquals("phase2c-idempotency-0001", post.headers["Idempotency-Key"])
        assertEquals(secret, JSONObject(post.body!!).getJSONObject("credential").getJSONObject("create").getString("secret"))
        assertEquals("GET", api.calls[1].method)
        assertFalse(input.toString().contains(secret))
        assertFalse(input.credential.toString().contains(secret))
    }

    @Test
    fun `existing credential create serializes credential id without secret`() {
        val body = createApiConnectionBody(
            ApiConnectionCreate(
                serviceId = "fixture.voice",
                displayName = "Existing",
                note = null,
                credential = ApiCredentialBinding.Existing("credential-1"),
            ),
        )

        val credential = body.getJSONObject("credential")
        assertEquals("credential-1", credential.getString("credential_id"))
        assertFalse(body.toString().contains("secret"))
    }

    @Test
    fun `metadata update does not silently rebind credential`() {
        val body = updateApiConnectionBody(
            ApiConnectionUpdate(
                displayName = "Renamed",
                note = "updated note",
                credentialId = "credential-2",
                updateCredential = false,
            ),
        )

        assertEquals("Renamed", body.getString("display_name"))
        assertFalse(body.has("credential_id"))
    }

    @Test
    fun `update and delete always converge through authoritative reread`() = runBlocking {
        val api = RecordingApi()
        api.responses += JSONObject().put("connection", connectionJson().put("enabled", false))
        api.responses += JSONObject().put("connection", connectionJson().put("enabled", false))
        api.responses += JSONObject().put("status", "deleted").put("connection_id", "connection-1")
        api.responses += JSONObject().put("connections", JSONArray())
        val repository = AppBackendApiConnectionRepository(api)

        assertFalse(repository.update("connection-1", ApiConnectionUpdate(enabled = false)).enabled)
        assertTrue(repository.delete("connection-1").isEmpty())
        assertEquals(
            listOf(
                "PUT /api/api-connections/connection-1",
                "GET /api/api-connections/connection-1",
                "DELETE /api/api-connections/connection-1",
                "GET /api/api-connections",
            ),
            api.calls.map { "${it.method} ${it.path}" },
        )
    }

    @Test
    fun `empty registry is an authoritative empty state`() {
        assertEquals(
            "暂无可接入 API 服务。服务目录由 App Backend 提供。",
            apiEmptyRegistryMessage(emptyList()),
        )
    }

    @Test
    fun `API page no longer reads local prototype or probes third party endpoints`() {
        val root = File(requireNotNull(System.getProperty("user.dir")))
        val source = File(root, "src/main/java/fyi/b612/lovehouse/feature/settings/ToolCenterApi.kt").readText()
        val settings = File(root, "src/main/java/fyi/b612/lovehouse/feature/settings/SettingsScreen.kt").readText()
        val repository = File(root, "src/main/java/fyi/b612/lovehouse/feature/settings/ApiConnectionRepository.kt").readText()

        assertTrue(source.contains("ApiConnectionRepository"))
        assertFalse(source.contains("ToolConnectionStore"))
        assertFalse(source.contains("ToolConnectionProbe"))
        assertFalse(source.contains("probe.test"))
        assertFalse(settings.contains("savedToolConnections"))
        assertTrue(settings.contains("App Backend API + MCP"))
        assertTrue(repository.contains("setRequestProperty(\"Cookie\", cookie)"))
        assertFalse(repository.contains("SharedPreferences"))
        assertFalse(repository.contains("DataStore"))
    }

    private class RecordingApi : ApiConnectionApi {
        val calls = mutableListOf<Call>()
        val responses = ArrayDeque<JSONObject>()

        override suspend fun request(
            method: String,
            path: String,
            body: String?,
            headers: Map<String, String>,
        ): JSONObject {
            calls += Call(method, path, body, headers)
            return responses.removeFirst()
        }
    }

    private data class Call(
        val method: String,
        val path: String,
        val body: String?,
        val headers: Map<String, String>,
    )

    private fun serviceJson() = JSONObject()
        .put("service_id", "fixture.voice")
        .put("service_kind", "VOICE")
        .put("adapter_id", "fixture.voice.v1")
        .put("display_name", "Fixture Voice")
        .put("accepted_credential_types", JSONArray().put("api_key").put("bearer_token"))
        .put("credential_required", true)
        .put("config_contract_version", 1)

    private fun connectionJson() = JSONObject()
        .put("connection_id", "connection-1")
        .put("service_id", "fixture.voice")
        .put("display_name", "Voice")
        .put("note", "note")
        .put("credential_id", "credential-1")
        .put("config", JSONObject().put("region", "eu"))
        .put("enabled", true)
        .put("status", "active")
        .put("created_at", "2026-10-05T00:00:00.000Z")
        .put("updated_at", "2026-10-05T00:00:00.000Z")
}
