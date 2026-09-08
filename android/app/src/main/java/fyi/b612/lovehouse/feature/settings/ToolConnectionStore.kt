package fyi.b612.lovehouse.feature.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

enum class ToolConnectionKind { Api, Mcp }
enum class ToolConnectionAuth { None, ApiKey, BearerToken }
enum class ToolConnectionStatus { Untested, Connected, Failed }

data class ToolConnectionDraft(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val kind: ToolConnectionKind = ToolConnectionKind.Api,
    val endpoint: String = "",
    val auth: ToolConnectionAuth = ToolConnectionAuth.None,
    val credential: String = "",
    val note: String = "",
)

data class StoredToolConnection(
    val id: String,
    val name: String,
    val kind: ToolConnectionKind,
    val endpoint: String,
    val auth: ToolConnectionAuth,
    internal val credential: String,
    val note: String,
    val enabled: Boolean,
    val status: ToolConnectionStatus,
    val lastResult: String?,
    val discoveredTools: List<String>,
)

interface ToolConnectionStore {
    val connections: StateFlow<List<StoredToolConnection>>
    fun save(draft: ToolConnectionDraft, result: ToolConnectionProbeResult, enabled: Boolean = true)
    fun setEnabled(id: String, enabled: Boolean)
    fun delete(id: String)
}

/** Stores the complete connection payload encrypted with an app-private Android Keystore key. */
class AndroidToolConnectionStore(context: Context) : ToolConnectionStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val mutableConnections = MutableStateFlow(load())
    override val connections: StateFlow<List<StoredToolConnection>> = mutableConnections.asStateFlow()

    @Synchronized
    override fun save(draft: ToolConnectionDraft, result: ToolConnectionProbeResult, enabled: Boolean) {
        val record = StoredToolConnection(
            id = draft.id,
            name = draft.name.trim(),
            kind = draft.kind,
            endpoint = draft.endpoint.trim(),
            auth = draft.auth,
            credential = draft.credential,
            note = draft.note.trim(),
            enabled = enabled,
            status = if (result.succeeded) ToolConnectionStatus.Connected else ToolConnectionStatus.Failed,
            lastResult = result.message,
            discoveredTools = result.tools,
        )
        persist(mutableConnections.value.filterNot { it.id == record.id } + record)
    }

    @Synchronized
    override fun setEnabled(id: String, enabled: Boolean) =
        persist(mutableConnections.value.map { if (it.id == id) it.copy(enabled = enabled) else it })

    @Synchronized
    override fun delete(id: String) = persist(mutableConnections.value.filterNot { it.id == id })

    private fun persist(records: List<StoredToolConnection>) {
        val payload = JSONArray().apply { records.forEach { put(it.toJson()) } }.toString()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val committed = preferences.edit()
            .putString(KEY_PAYLOAD, Base64.encodeToString(cipher.doFinal(payload.toByteArray()), Base64.NO_WRAP))
            .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .commit()
        check(committed) { "工具连接无法安全保存" }
        mutableConnections.value = records
    }

    private fun load(): List<StoredToolConnection> {
        val ciphertext = preferences.getString(KEY_PAYLOAD, null) ?: return emptyList()
        val iv = preferences.getString(KEY_IV, null) ?: return emptyList()
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            }
            val array = JSONArray(String(cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP))))
            buildList { for (index in 0 until array.length()) add(array.getJSONObject(index).toStoredConnection()) }
        }.getOrDefault(emptyList())
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private companion object {
        const val PREFERENCES = "lovehouse_tool_connections_v1"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "lovehouse_tool_connections_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_PAYLOAD = "connections_ciphertext"
        const val KEY_IV = "connections_iv"
    }
}

private fun StoredToolConnection.toJson() = JSONObject()
    .put("id", id).put("name", name).put("kind", kind.name).put("endpoint", endpoint)
    .put("auth", auth.name).put("credential", credential).put("note", note).put("enabled", enabled)
    .put("status", status.name).put("last_result", lastResult)
    .put("tools", JSONArray(discoveredTools))

private fun JSONObject.toStoredConnection() = StoredToolConnection(
    id = getString("id"), name = getString("name"), kind = ToolConnectionKind.valueOf(getString("kind")),
    endpoint = getString("endpoint"), auth = ToolConnectionAuth.valueOf(getString("auth")),
    credential = optString("credential"), note = optString("note"), enabled = optBoolean("enabled", true),
    status = runCatching { ToolConnectionStatus.valueOf(optString("status")) }.getOrDefault(ToolConnectionStatus.Untested),
    lastResult = optString("last_result").takeIf(String::isNotBlank),
    discoveredTools = optJSONArray("tools")?.let { array -> buildList { for (i in 0 until array.length()) add(array.getString(i)) } }.orEmpty(),
)
