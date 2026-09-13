package fyi.b612.lovehouse.feature.chat

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

enum class ChatConnectionStatus { Untested, Connected, Failed }

data class ChatConnectionDraft(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val endpoint: String = "",
    val credential: String = "",
)

data class StoredChatConnection(
    val id: String,
    val name: String,
    val endpoint: String,
    internal val credential: String,
    val selected: Boolean,
    val status: ChatConnectionStatus,
    val lastResult: String?,
)

data class ChatConnectionTestResult(val succeeded: Boolean, val message: String)

interface ChatConnectionStore {
    val connections: StateFlow<List<StoredChatConnection>>
    fun selected(): StoredChatConnection?
    fun save(draft: ChatConnectionDraft, testResult: ChatConnectionTestResult?)
    fun select(id: String)
    fun delete(id: String)
}

/** Stores connection credentials as one AES-GCM encrypted payload backed by Android Keystore. */
class AndroidChatConnectionStore(context: Context) : ChatConnectionStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val mutableConnections = MutableStateFlow(load())
    override val connections: StateFlow<List<StoredChatConnection>> = mutableConnections.asStateFlow()

    override fun selected(): StoredChatConnection? = mutableConnections.value.firstOrNull(StoredChatConnection::selected)

    @Synchronized
    override fun save(draft: ChatConnectionDraft, testResult: ChatConnectionTestResult?) {
        require(draft.name.isNotBlank()) { "请填写连接名称" }
        require(draft.endpoint.isNotBlank()) { "请填写 URL" }
        require(draft.credential.isNotBlank()) { "请填写 Key" }
        val existing = mutableConnections.value.firstOrNull { it.id == draft.id }
        val record = StoredChatConnection(
            id = draft.id,
            name = draft.name.trim(),
            endpoint = draft.endpoint.trim(),
            credential = draft.credential,
            selected = existing?.selected ?: mutableConnections.value.none(StoredChatConnection::selected),
            status = when {
                testResult == null -> existing?.status ?: ChatConnectionStatus.Untested
                testResult.succeeded -> ChatConnectionStatus.Connected
                else -> ChatConnectionStatus.Failed
            },
            lastResult = testResult?.message ?: existing?.lastResult,
        )
        persist(mutableConnections.value.filterNot { it.id == record.id } + record)
    }

    @Synchronized
    override fun select(id: String) = persist(mutableConnections.value.map { it.copy(selected = it.id == id) })

    @Synchronized
    override fun delete(id: String) {
        val remaining = mutableConnections.value.filterNot { it.id == id }
        persist(if (remaining.none(StoredChatConnection::selected) && remaining.isNotEmpty()) {
            remaining.mapIndexed { index, item -> item.copy(selected = index == 0) }
        } else remaining)
    }

    private fun persist(records: List<StoredChatConnection>) {
        val payload = JSONArray().apply { records.forEach { put(it.toJson()) } }.toString()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        check(
            preferences.edit()
                .putString(KEY_PAYLOAD, Base64.encodeToString(cipher.doFinal(payload.toByteArray()), Base64.NO_WRAP))
                .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .commit(),
        ) { "Chat Connection 无法安全保存" }
        mutableConnections.value = records
    }

    private fun load(): List<StoredChatConnection> {
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
        const val PREFERENCES = "lovehouse_chat_connections_v1"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "lovehouse_chat_connections_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_PAYLOAD = "connections_ciphertext"
        const val KEY_IV = "connections_iv"
    }
}

private fun StoredChatConnection.toJson() = JSONObject()
    .put("id", id).put("name", name).put("endpoint", endpoint).put("credential", credential)
    .put("selected", selected).put("status", status.name).put("last_result", lastResult)

private fun JSONObject.toStoredConnection() = StoredChatConnection(
    id = getString("id"),
    name = getString("name"),
    endpoint = getString("endpoint"),
    credential = getString("credential"),
    selected = optBoolean("selected", false),
    status = runCatching { ChatConnectionStatus.valueOf(optString("status")) }.getOrDefault(ChatConnectionStatus.Untested),
    lastResult = optString("last_result").takeIf(String::isNotBlank),
)
