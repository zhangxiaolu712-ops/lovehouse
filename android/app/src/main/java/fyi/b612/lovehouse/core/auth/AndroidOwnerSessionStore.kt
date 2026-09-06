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

class AndroidOwnerSessionStore(
    context: Context,
    private val debugBootstrapToken: String? = null,
    private val nowEpochSeconds: () -> Long = { System.currentTimeMillis() / 1_000 },
) : OwnerSessionStore {
    private val preferences = context.applicationContext.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
    private val mutableState = MutableStateFlow(resolve().summary)
    override val state: StateFlow<OwnerSessionSummary> = mutableState.asStateFlow()

    init {
        if (!preferences.contains(KEY_CIPHERTEXT)) {
            debugBootstrapToken?.takeIf(String::isNotBlank)?.let { bootstrap ->
                runCatching { saveAccessToken(bootstrap) }
            }
        }
    }

    @Synchronized
    override fun currentBearer(): OwnerBearerToken {
        val resolved = resolve()
        mutableState.value = resolved.summary
        return resolved.bearer ?: throw OwnerSessionException(
            failure = when (resolved.summary.status) {
                OwnerSessionStatus.Expired -> OwnerSessionFailure.Expired
                OwnerSessionStatus.Rejected -> OwnerSessionFailure.Rejected
                else -> OwnerSessionFailure.Missing
            },
            message = ownerSessionMessage(
                when (resolved.summary.status) {
                    OwnerSessionStatus.Expired -> OwnerSessionFailure.Expired
                    OwnerSessionStatus.Rejected -> OwnerSessionFailure.Rejected
                    else -> OwnerSessionFailure.Missing
                },
            ),
        )
    }

    @Synchronized
    override fun saveAccessToken(token: String): OwnerSessionSummary {
        val normalized = token.trim()
        val parsed = parseOwnerAccessToken(normalized)
        if (isExpired(parsed.expiresAtEpochSeconds, nowEpochSeconds())) {
            throw OwnerSessionException(OwnerSessionFailure.Expired, ownerSessionMessage(OwnerSessionFailure.Expired))
        }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val encrypted = cipher.doFinal(normalized.toByteArray(Charsets.UTF_8))
        preferences.edit()
            .putString(KEY_CIPHERTEXT, android.util.Base64.encodeToString(encrypted, android.util.Base64.NO_WRAP))
            .putString(KEY_IV, android.util.Base64.encodeToString(cipher.iv, android.util.Base64.NO_WRAP))
            .remove(KEY_REJECTED_FINGERPRINT)
            .apply()
        return resolve().summary.also { mutableState.value = it }
    }

    @Synchronized
    override fun reject(fingerprint: String) {
        preferences.edit()
            .remove(KEY_CIPHERTEXT)
            .remove(KEY_IV)
            .putString(KEY_REJECTED_FINGERPRINT, fingerprint)
            .apply()
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
            .remove(KEY_CIPHERTEXT)
            .remove(KEY_IV)
            .apply {
                if (currentFingerprint == null) remove(KEY_REJECTED_FINGERPRINT)
                else putString(KEY_REJECTED_FINGERPRINT, currentFingerprint)
            }
            .apply()
        mutableState.value = OwnerSessionSummary(status = OwnerSessionStatus.Missing)
    }

    private fun resolve(): ResolvedSession {
        decryptRuntimeToken()?.let { token ->
            val parsed = runCatching { parseOwnerAccessToken(token) }.getOrNull()
            if (parsed != null) {
                val summary = OwnerSessionSummary(
                    status = if (isExpired(parsed.expiresAtEpochSeconds, nowEpochSeconds())) OwnerSessionStatus.Expired else OwnerSessionStatus.Active,
                    source = OwnerSessionSource.Runtime,
                    expiresAtEpochSeconds = parsed.expiresAtEpochSeconds,
                    fingerprint = parsed.fingerprint,
                )
                return ResolvedSession(
                    summary,
                    token.takeUnless { summary.status == OwnerSessionStatus.Expired }
                        ?.let { OwnerBearerToken(it, parsed.fingerprint) },
                )
            }
        }

        val bootstrap = debugBootstrapToken?.trim().orEmpty()
        if (bootstrap.isNotEmpty()) {
            val parsed = runCatching { parseOwnerAccessToken(bootstrap) }.getOrNull()
            if (parsed != null) {
                val rejected = preferences.getString(KEY_REJECTED_FINGERPRINT, null) == parsed.fingerprint
                val status = when {
                    rejected -> OwnerSessionStatus.Rejected
                    isExpired(parsed.expiresAtEpochSeconds, nowEpochSeconds()) -> OwnerSessionStatus.Expired
                    else -> OwnerSessionStatus.Active
                }
                return ResolvedSession(
                    OwnerSessionSummary(status, OwnerSessionSource.DebugBootstrap, parsed.expiresAtEpochSeconds, parsed.fingerprint),
                    bootstrap.takeIf { status == OwnerSessionStatus.Active }
                        ?.let { OwnerBearerToken(it, parsed.fingerprint) },
                )
            }
        }
        return ResolvedSession(OwnerSessionSummary(OwnerSessionStatus.Missing), null)
    }

    private fun decryptRuntimeToken(): String? {
        val encodedCiphertext = preferences.getString(KEY_CIPHERTEXT, null) ?: return null
        val encodedIv = preferences.getString(KEY_IV, null) ?: return null
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

    private data class ResolvedSession(
        val summary: OwnerSessionSummary,
        val bearer: OwnerBearerToken?,
    )

    private companion object {
        const val PREFERENCES = "owner_session_v1"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "lovehouse_owner_session_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_CIPHERTEXT = "access_token_ciphertext"
        const val KEY_IV = "access_token_iv"
        const val KEY_REJECTED_FINGERPRINT = "rejected_fingerprint"
    }
}
