package fyi.b612.lovehouse.feature.settings

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SecretVaultRepositoryTest {
    @Test
    fun `create uses authoritative contract and redacts diagnostic text`() {
        val input = SecretCredentialCreate(
            displayName = "Search",
            credentialType = "api_key",
            secret = "fixture-secret-value",
            metadata = SecretCredentialMetadata(serviceName = "Fixture Search", note = "primary"),
        )
        val body = createSecretCredentialBody(input)

        assertEquals("Search", body.getString("display_name"))
        assertEquals("api_key", body.getString("credential_type"))
        assertEquals("fixture-secret-value", body.getString("secret"))
        assertEquals("Fixture Search", body.getJSONObject("metadata").getString("service_name"))
        assertFalse(input.toString().contains("fixture-secret-value"))
    }

    @Test
    fun `metadata update omits secret unless replacement is explicit`() {
        val metadataOnly = updateSecretCredentialBody(
            SecretCredentialUpdate(
                displayName = "Renamed",
                metadata = SecretCredentialMetadata(serviceName = "Service", note = "updated"),
            ),
        )
        assertFalse(metadataOnly.has("secret"))

        val replacement = updateSecretCredentialBody(
            SecretCredentialUpdate(replacementSecret = "replacement-fixture"),
        )
        assertEquals("replacement-fixture", replacement.getString("secret"))
        assertFalse(SecretCredentialUpdate(replacementSecret = "replacement-fixture").toString().contains("replacement-fixture"))
    }

    @Test
    fun `public metadata parser never expects secret or preview`() {
        val parsed = credentialJson("credential-1").toSecretCredential()
        assertEquals("credential-1", parsed.id)
        assertEquals("Fixture Service", parsed.metadata.serviceName)
        assertTrue(parsed.configured)
        assertEquals(FIXED_SECRET_MASK, "••••••••••••••")
    }

    @Test
    fun `repository performs list read create update delete and refreshes authoritative fields`() = runBlocking {
        val api = RecordingSecretVaultApi()
        val repository = AppBackendSecretVaultRepository(api, Unit)

        assertEquals(1, repository.list().size)
        assertEquals("credential-1", repository.get("credential-1").id)
        assertEquals("credential-created", repository.create(
            SecretCredentialCreate("Created", "bearer", "new-fixture", SecretCredentialMetadata()),
        ).id)
        assertEquals("credential-updated", repository.update(
            "credential-1",
            SecretCredentialUpdate(displayName = "Updated", replacementSecret = "replacement-fixture"),
        ).id)
        repository.delete("credential-1")

        assertEquals(listOf("GET", "GET", "POST", "PUT", "DELETE"), api.calls.map { it.method })
        assertEquals("/api/credentials/credential-1", api.calls[1].path)
        assertEquals("deleted", api.deleteStatus)
        assertNull(api.persistedSecret)
    }

    private data class Call(val method: String, val path: String, val body: String?)

    private class RecordingSecretVaultApi : SecretVaultApi {
        val calls = mutableListOf<Call>()
        var deleteStatus: String? = null
        val persistedSecret: String? = null

        override suspend fun request(method: String, path: String, body: String?): JSONObject {
            calls += Call(method, path, body)
            return when (method) {
                "GET" -> if (path == "/api/credentials") {
                    JSONObject().put("credentials", JSONArray().put(credentialJson("credential-1")))
                } else JSONObject().put("credential", credentialJson("credential-1"))
                "POST" -> JSONObject().put("credential", credentialJson("credential-created"))
                "PUT" -> JSONObject().put("credential", credentialJson("credential-updated"))
                "DELETE" -> JSONObject().put("status", "deleted").put("credential_id", "credential-1")
                else -> error("unexpected method")
            }.also { if (method == "DELETE") deleteStatus = it.optString("status") }
        }
    }
}

private fun credentialJson(id: String): JSONObject = JSONObject()
    .put("credential_id", id)
    .put("display_name", "Fixture")
    .put("credential_type", "api_key")
    .put("metadata", JSONObject().put("service_name", "Fixture Service").put("note", "safe note"))
    .put("credential_status", "configured")
    .put("created_at", "2026-10-04T01:00:00.000Z")
    .put("updated_at", "2026-10-04T02:00:00.000Z")
