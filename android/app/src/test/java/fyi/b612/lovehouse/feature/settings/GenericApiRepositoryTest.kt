package fyi.b612.lovehouse.feature.settings

import java.io.File
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GenericApiRepositoryTest {
    @Test
    fun `repository uses successor routes and authoritative reread`() = runBlocking {
        val api = RecordingGenericApiApi()
        api.responses += JSONObject().put("providers", JSONArray().put(provider()))
        api.responses += JSONObject().put("connections", JSONArray().put(connection()))
        api.responses += JSONObject().put("connection", connection())
        val repository = AppBackendGenericApiRepository(api)

        assertEquals("deepseek", repository.providers().single().id)
        assertEquals("model-a", repository.connections().single().selectedModels.single().id)
        assertEquals("connection-a", repository.connection("connection-a").id)
        assertEquals(listOf(
            "GET /api/generic-api/providers",
            "GET /api/generic-api/connections",
            "GET /api/generic-api/connections/connection-a",
        ), api.calls.map { "${it.method} ${it.path}" })
    }

    @Test
    fun `test create update and delete serialize stable provider credential and model ids`() = runBlocking {
        val api = RecordingGenericApiApi()
        api.responses += JSONObject().put("test", JSONObject()
            .put("status", "CONNECTED").put("code", JSONObject.NULL)
            .put("models", JSONArray().put(model())))
        api.responses += JSONObject().put("connection", connection())
        api.responses += JSONObject().put("connection", connection())
        api.responses += JSONObject().put("status", "deleted")
        val repository = AppBackendGenericApiRepository(api)
        val draft = GenericApiDraft("deepseek", "DeepSeek", "https://api.deepseek.com",
            "OPENAI_COMPATIBLE", "credential-a", listOf(GenericApiModel("model-a", "Model A")))

        assertEquals("model-a", repository.test(draft).models.single().id)
        repository.create(draft)
        repository.update("connection-a", draft)
        repository.delete("connection-a")

        val testBody = JSONObject(api.calls[0].body!!)
        assertFalse(testBody.has("display_name"))
        assertFalse(testBody.has("selected_models"))
        val createBody = JSONObject(api.calls[1].body!!)
        assertEquals("credential-a", createBody.getString("credential_id"))
        assertEquals("model-a", createBody.getJSONArray("selected_models").getJSONObject(0).getString("model_id"))
        assertEquals("DELETE", api.calls[3].method)
    }

    @Test
    fun `safe error mapping distinguishes auth quota rate limit and manual fallback`() {
        assertEquals("密钥无效，请检查后重试", genericApiErrorText(401, "GENERIC_API_INVALID_CREDENTIAL"))
        assertEquals("服务额度或余额不足，请检查账户", genericApiErrorText(429, "GENERIC_API_QUOTA_EXHAUSTED"))
        assertEquals("服务请求过于频繁，请稍后重试", genericApiErrorText(429, "GENERIC_API_RATE_LIMITED"))
        assertEquals("这家服务不支持自动获取模型，请手动填写模型 ID",
            genericApiErrorText(409, "GENERIC_API_DISCOVERY_UNSUPPORTED"))
    }

    @Test
    fun `new secret creates Vault credential existing selection binds and replacement keeps id`() = runBlocking {
        val vault = RecordingVault()
        val created = resolveGenericApiCredential(vault, false, null, null, "new-secret",
            "bearer_token", "DeepSeek")
        assertEquals("created-id", created)
        assertEquals("new-secret", vault.created?.secret)

        val bound = resolveGenericApiCredential(vault, true, "existing-id", null, "",
            "api_key", "Relay")
        assertEquals("existing-id", bound)

        val replaced = resolveGenericApiCredential(vault, false, null, "created-id", "replacement",
            "bearer_token", "DeepSeek")
        assertEquals("created-id", replaced)
        assertEquals("replacement", vault.updated?.replacementSecret)
        assertEquals("created-id", vault.updatedId)
    }

    @Test
    fun `API UI has no old catalog or Android direct third party authority`() {
        val root = File(System.getProperty("user.dir"))
        val ui = File(root, "src/main/java/fyi/b612/lovehouse/feature/settings/ToolCenterApi.kt").readText()
        val wiring = File(root, "src/main/java/fyi/b612/lovehouse/app/AppDependencies.kt").readText()
        assertTrue(ui.contains("GenericApiRepository"))
        assertTrue(ui.contains("SecretVaultRepository"))
        assertFalse(ui.contains("ApiCapabilityCatalogSection"))
        assertFalse(ui.contains("ApiModelResourceSection"))
        assertFalse(wiring.contains("AndroidToolConnectionStore"))
        assertFalse(wiring.contains("HttpToolConnectionProbe"))
        assertFalse(ui.contains("SharedPreferences"))
        assertFalse(ui.contains("DataStore"))
    }

    private data class Call(val method: String, val path: String, val body: String?)

    private class RecordingGenericApiApi : GenericApiApi {
        val responses = ArrayDeque<JSONObject>()
        val calls = mutableListOf<Call>()
        override suspend fun request(method: String, path: String, body: String?): JSONObject {
            calls += Call(method, path, body)
            return responses.removeFirst()
        }
    }

    private class RecordingVault : SecretVaultRepository {
        var created: SecretCredentialCreate? = null
        var updated: SecretCredentialUpdate? = null
        var updatedId: String? = null
        override suspend fun list() = emptyList<SecretCredential>()
        override suspend fun get(credentialId: String) = credential(credentialId)
        override suspend fun create(input: SecretCredentialCreate): SecretCredential {
            created = input
            return credential("created-id")
        }
        override suspend fun update(credentialId: String, input: SecretCredentialUpdate): SecretCredential {
            updatedId = credentialId
            updated = input
            return credential(credentialId)
        }
        override suspend fun delete(credentialId: String) = Unit
        private fun credential(id: String) = SecretCredential(id, "Credential", "bearer_token",
            SecretCredentialMetadata(), true, "2026-10-12T00:00:00Z", "2026-10-12T00:00:00Z")
    }

    private fun provider() = JSONObject()
        .put("provider_id", "deepseek")
        .put("display_name", "DeepSeek")
        .put("default_base_url", "https://api.deepseek.com")
        .put("discovery_kind", "OPENAI_COMPATIBLE")

    private fun model() = JSONObject().put("model_id", "model-a").put("display_name", "Model A")

    private fun connection() = JSONObject()
        .put("connection_id", "connection-a")
        .put("provider_id", "deepseek")
        .put("display_name", "DeepSeek")
        .put("base_url", "https://api.deepseek.com")
        .put("discovery_kind", "OPENAI_COMPATIBLE")
        .put("credential_id", "credential-a")
        .put("selected_models", JSONArray().put(model()))
        .put("created_at", "2026-10-12T00:00:00.000Z")
        .put("updated_at", "2026-10-12T00:00:00.000Z")
}
