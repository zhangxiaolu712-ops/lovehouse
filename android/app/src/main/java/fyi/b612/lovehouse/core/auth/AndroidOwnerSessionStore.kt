package fyi.b612.lovehouse.core.auth

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONObject

class AndroidOwnerSessionStore(
    context: Context,
    private val refresher: OwnerSessionRefresher,
    private val debugBootstrapToken: String? = null,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000 },
) : OwnerSessionStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(resolve().summary)
    override val state: StateFlow<OwnerSessionSummary> = mutableState.asStateFlow()
    private val refreshCoordinator = OwnerSessionRefreshCoordinator(
        nowEpochSeconds = nowEpochSeconds,
        load = ::loadCurrentSession,
        save = ::persistSession,
        reject = ::reject,
        refresher = refresher,
    )

    init {
        if (!hasRuntimeSession()) {
            debugBootstrapToken?.takeIf(String::isNotBlank)?.let { bootstrap ->
                runCatching { saveAccessToken(bootstrap) }
            }
        }
    }

    override suspend fun currentBearer(forceRefresh: Boolean): OwnerBearerToken {
        return try {
            val session = refreshCoordinator.validSession(forceRefresh)
            updateState(session)
            OwnerBearerToken(session.accessToken, session.fingerprint)
        } catch (error: OwnerSessionException) {
            mutableState.value = resolve().summary
            throw error
        }
    }

    @Synchronized
    override fun saveSession(session: OwnerSessionInput): OwnerSessionSummary {
        val normalized = normalizeOwnerSession(session, nowEpochSeconds())
        persistSession(normalized)
        return updateState(normalized)
    }

    @Synchronized
    override fun reject(fingerprint: String) {
        preferences.edit()
            .remove(KEY_SESSION_CIPHERTEXT)
            .remove(KEY_SESSION_IV)
            .remove(KEY_ACCESS_CIPHERTEXT)
            .remove(KEY_ACCESS_IV)
            .putString(KEY_REJECTED_FINGERPRINT, fingerprint)
            .commit()
        mutableState.value = OwnerSessionSummary(
            status = OwnerSessionStatus.Rejected,
            fingerprint = fingerprint,
        )
    }

    @Synchronized
    override fun clear() {
        val currentFingerprint = resolve().summary.fingerprint
            ?: debugBootstrapToken?.takeIf(String::isNotBlank)?.let(::ownerTokenFingerprint)
        preferences.edit()
            .remove(KEY_SESSION_CIPHERTEXT)
            .remove(KEY_SESSION_IV)
            .remove(KEY_ACCESS_CIPHERTEXT)
            .remove(KEY_ACCESS_IV)
            .apply {
                if (currentFingerprint == null) remove(KEY_REJECTED_FINGERPRINT)
                else putString(KEY_REJECTED_FINGERPRINT, currentFingerprint)
            }
            .commit()
        mutableState.value = OwnerSessionSummary(status = OwnerSessionStatus.Missing)
    }

    @Synchronized
    private fun persistSession(session: StoredOwnerSession) {
        val payload = JSONObject()
            .put("access_token", session.accessToken)
            .put("expires_at", session.expiresAtEpochSeconds)
            .put("token_type", session.tokenType)
            .apply { session.refreshToken?.let { put("refresh_token", it) } }
            .toString()
        val encrypted = encrypt(payload)
        val committed = preferences.edit()
            .putString(KEY_SESSION_CIPHERTEXT, encrypted.ciphertext)
            .putString(KEY_SESSION_IV, encrypted.iv)
            .remove(KEY_ACCESS_CIPHERTEXT)
            .remove(KEY_ACCESS_IV)
            .remove(KEY_REJECTED_FINGERPRINT)
            .commit()
        if (!committed) {
            throw OwnerSessionException(OwnerSessionFailure.Invalid, "Owner Session 无法安全保存")
        }
    }

    private fun hasRuntimeSession(): Boolean =
        preferences.contains(KEY_SESSION_CIPHERTEXT) || preferences.contains(KEY_ACCESS_CIPHERTEXT)

    private fun resolve(): ResolvedSession {
        loadCurrentSession()?.let { session ->
            val expired = isExpired(session.expiresAtEpochSeconds, nowEpochSeconds())
            val summary = OwnerSessionSummary(
                status = if (expired) OwnerSessionStatus.Expired else OwnerSessionStatus.Active,
                source = session.source,
                expiresAtEpochSeconds = session.expiresAtEpochSeconds,
                fingerprint = session.fingerprint,
                canRefresh = session.refreshToken != null,
            )
            return ResolvedSession(summary)
        }
        val rejectedFingerprint = preferences.getString(KEY_REJECTED_FINGERPRINT, null)
        return ResolvedSession(
            if (rejectedFingerprint == null) OwnerSessionSummary(OwnerSessionStatus.Missing)
            else OwnerSessionSummary(OwnerSessionStatus.Rejected, fingerprint = rejectedFingerprint),
        )
    }

    private fun loadCurrentSession(): StoredOwnerSession? {
        decryptV2Session()?.let { return it }
        decryptV1AccessToken()?.let { token ->
            val parsed = runCatching { parseOwnerAccessToken(token) }.getOrNull() ?: return@let
            return StoredOwnerSession(
                accessToken = token,
                refreshToken = null,
                expiresAtEpochSeconds = parsed.expiresAtEpochSeconds,
                tokenType = "bearer",
                fingerprint = parsed.fingerprint,
                source = OwnerSessionSource.Runtime,
            )
        }
        val bootstrap = debugBootstrapToken?.trim().orEmpty()
        if (bootstrap.isNotEmpty()) {
            val parsed = runCatching { parseOwnerAccessToken(bootstrap) }.getOrNull()
            if (parsed != null && preferences.getString(KEY_REJECTED_FINGERPRINT, null) != parsed.fingerprint) {
                return StoredOwnerSession(
                    accessToken = bootstrap,
                    refreshToken = null,
                    expiresAtEpochSeconds = parsed.expiresAtEpochSeconds,
                    tokenType = "bearer",
                    fingerprint = parsed.fingerprint,
                    source = OwnerSessionSource.DebugBootstrap,
                )
            }
        }
        return null
    }

    private fun decryptV2Session(): StoredOwnerSession? {
        val payload = decrypt(KEY_SESSION_CIPHERTEXT, KEY_SESSION_IV) ?: return null
        return runCatching {
            val json = JSONObject(payload)
            val accessToken = json.getString("access_token")
            val parsed = parseOwnerAccessToken(accessToken)
            StoredOwnerSession(
                accessToken = accessToken,
                refreshToken = json.optString("refresh_token").takeIf(String::isNotBlank)?.also(::validateRefreshToken),
                expiresAtEpochSeconds = json.optLong("expires_at", parsed.expiresAtEpochSeconds),
                tokenType = json.optString("token_type").takeIf(String::isNotBlank),
                fingerprint = parsed.fingerprint,
                source = OwnerSessionSource.Runtime,
            )
        }.getOrNull()
    }

    private fun decryptV1AccessToken(): String? = decrypt(KEY_ACCESS_CIPHERTEXT, KEY_ACCESS_IV)

    private fun encrypt(value: String): EncryptedValue {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        return EncryptedValue(
            ciphertext = android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP),
            iv = android.util.Base64.encodeToString(cipher.iv, android.util.Base64.NO_WRAP),
        )
    }

    private fun decrypt(ciphertextKey: String, ivKey: String): String? {
        val encodedCiphertext = preferences.getString(ciphertextKey, null) ?: return null
        val encodedIv = preferences.getString(ivKey, null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(
                    Cipher.DECRYPT_MODE,
                    secretKey(),
                    GCMParameterSpec(128, android.util.Base64.decode(encodedIv, android.util.Base64.NO_WRAP)),
                )
            }
            String(
                cipher.doFinal(android.util.Base64.decode(encodedCiphertext, android.util.Base64.NO_WRAP)),
                Charsets.UTF_8,
            )
        }.getOrNull()
    }

    private fun secretKey(): SecretKey {
        val keyStore = KeyStore.getInstance(KEYSTORE).apply { load(null) }
        (keyStore.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE).run {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
            generateKey()
        }
    }

    private fun updateState(session: StoredOwnerSession): OwnerSessionSummary = OwnerSessionSummary(
        status = if (isExpired(session.expiresAtEpochSeconds, nowEpochSeconds())) OwnerSessionStatus.Expired else OwnerSessionStatus.Active,
        source = session.source,
        expiresAtEpochSeconds = session.expiresAtEpochSeconds,
        fingerprint = session.fingerprint,
        canRefresh = session.refreshToken != null,
    ).also { mutableState.value = it }

    private data class ResolvedSession(val summary: OwnerSessionSummary)
    private data class EncryptedValue(val ciphertext: String, val iv: String)

    private companion object {
        const val PREFERENCES = "owner_session_v1"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "lovehouse_owner_session_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_SESSION_CIPHERTEXT = "session_v2_ciphertext"
        const val KEY_SESSION_IV = "session_v2_iv"
        const val KEY_ACCESS_CIPHERTEXT = "access_token_ciphertext"
        const val KEY_ACCESS_IV = "access_token_iv"
        const val KEY_REJECTED_FINGERPRINT = "rejected_fingerprint"
    }
}
