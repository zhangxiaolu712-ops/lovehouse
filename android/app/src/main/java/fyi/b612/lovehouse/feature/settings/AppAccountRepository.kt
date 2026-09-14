package fyi.b612.lovehouse.feature.settings

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

sealed interface AppAccountState {
    data object Checking : AppAccountState
    data object SignedOut : AppAccountState
    data class SignedIn(val email: String) : AppAccountState
    data class Error(val message: String, val signedInEmail: String? = null) : AppAccountState
}
interface AppAccountRepository {
    val state: StateFlow<AppAccountState>
    suspend fun refresh()
    suspend fun login(email: String, password: String)
    suspend fun register(email: String, password: String)
    suspend fun logout()
}

internal data class StoredAppSession(val email: String, val cookieHeader: String)
internal data class AppAuthResponse(val email: String?, val cookieHeader: String)

internal interface AppAccountApi {
    suspend fun session(cookieHeader: String): String?
    suspend fun login(email: String, password: String): AppAuthResponse
    suspend fun register(email: String, password: String): AppAuthResponse
    suspend fun logout(cookieHeader: String)
}

internal interface AppAccountSessionStore {
    fun load(): StoredAppSession?
    fun save(session: StoredAppSession)
    fun clear()
}

class AndroidAppAccountRepository(
    context: Context,
    baseUrl: String,
) : AppAccountRepository {
    private val store: AppAccountSessionStore = EncryptedAppAccountSessionStore(context.applicationContext)
    private val api: AppAccountApi = HttpAppAccountApi(baseUrl)
    private val mutableState = MutableStateFlow<AppAccountState>(
        store.load()?.let { AppAccountState.SignedIn(it.email) } ?: AppAccountState.SignedOut,
    )
    override val state: StateFlow<AppAccountState> = mutableState.asStateFlow()

    override suspend fun refresh() {
        val current = store.load()
        if (current == null) {
            mutableState.value = AppAccountState.SignedOut
            return
        }
        mutableState.value = AppAccountState.Checking
        runCatching { api.session(current.cookieHeader) }
            .onSuccess { email ->
                if (email == null) {
                    store.clear()
                    mutableState.value = AppAccountState.SignedOut
                } else {
                    val refreshed = current.copy(email = email)
                    store.save(refreshed)
                    mutableState.value = AppAccountState.SignedIn(email)
                }
            }
            .onFailure { mutableState.value = AppAccountState.Error(it.userMessage(), current.email) }
    }

    override suspend fun login(email: String, password: String) = authenticate(email, password, api::login)

    override suspend fun register(email: String, password: String) = authenticate(email, password, api::register)

    override suspend fun logout() {
        val current = store.load() ?: run {
            mutableState.value = AppAccountState.SignedOut
            return
        }
        runCatching { api.logout(current.cookieHeader) }
            .onSuccess {
                store.clear()
                mutableState.value = AppAccountState.SignedOut
            }
            .onFailure { mutableState.value = AppAccountState.Error(it.userMessage(), current.email) }
    }

    private suspend fun authenticate(
        email: String,
        password: String,
        action: suspend (String, String) -> AppAuthResponse,
    ) {
        val normalizedEmail = email.trim().lowercase()
        require(isValidAppAccountEmail(normalizedEmail)) { "请输入有效邮箱" }
        require(password.length >= MIN_PASSWORD_LENGTH) { "密码至少需要 $MIN_PASSWORD_LENGTH 位" }
        mutableState.value = AppAccountState.Checking
        runCatching { action(normalizedEmail, password) }
            .onSuccess { response ->
                val session = StoredAppSession(response.email ?: normalizedEmail, response.cookieHeader)
                store.save(session)
                mutableState.value = AppAccountState.SignedIn(session.email)
            }
            .onFailure { mutableState.value = AppAccountState.Error(it.userMessage()) }
    }
}

internal fun appAuthEndpoint(baseUrl: String, action: String): String =
    "${baseUrl.trimEnd('/')}/api/auth/${action.trimStart('/')}"

internal fun isValidAppAccountEmail(value: String): Boolean =
    EMAIL.matches(value.trim())

internal fun cookieHeaderFromSetCookie(headers: Map<String?, List<String>>): String? = headers.entries
    .firstOrNull { it.key.equals("Set-Cookie", ignoreCase = true) }
    ?.value
    .orEmpty()
    .mapNotNull { it.substringBefore(';').trim().takeIf { pair -> '=' in pair && !pair.startsWith("=") } }
    .distinct()
    .takeIf(List<String>::isNotEmpty)
    ?.joinToString("; ")

private class HttpAppAccountApi(private val baseUrl: String) : AppAccountApi {
    override suspend fun session(cookieHeader: String): String? = withContext(Dispatchers.IO) {
        val response = request("GET", "session", cookieHeader = cookieHeader, allowUnauthorized = true)
        if (response.status == HttpURLConnection.HTTP_UNAUTHORIZED || !response.body.optBoolean("authenticated", true)) {
            null
        } else {
            response.body.accountEmail() ?: error("App Backend session 未返回邮箱")
        }
    }

    override suspend fun login(email: String, password: String): AppAuthResponse =
        authenticate("login", email, password)

    override suspend fun register(email: String, password: String): AppAuthResponse =
        authenticate("register", email, password)

    override suspend fun logout(cookieHeader: String) {
        request("POST", "logout", cookieHeader = cookieHeader)
    }

    private suspend fun authenticate(action: String, email: String, password: String): AppAuthResponse = withContext(Dispatchers.IO) {
        val response = request(
            method = "POST",
            action = action,
            body = JSONObject().put("email", email).put("password", password).toString(),
        )
        val cookie = cookieHeaderFromSetCookie(response.headers)
            ?: error("App Backend 未返回安全登录会话")
        AppAuthResponse(response.body.accountEmail(), cookie)
    }

    private fun request(
        method: String,
        action: String,
        body: String? = null,
        cookieHeader: String? = null,
        allowUnauthorized: Boolean = false,
    ): HttpResponse {
        val connection = (URL(appAuthEndpoint(baseUrl, action)).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            cookieHeader?.let { setRequestProperty("Cookie", it) }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val payload = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (status !in 200..299 && !(allowUnauthorized && status == HttpURLConnection.HTTP_UNAUTHORIZED)) {
                val message = payload.optJSONObject("error")?.optString("message")?.takeIf(String::isNotBlank)
                    ?: when (payload.optJSONObject("error")?.optString("code")) {
                        "INVALID_CREDENTIALS" -> "邮箱或密码不正确"
                        else -> "App Account 请求失败（HTTP $status）"
                    }
                throw AppAccountApiException(message)
            }
            return HttpResponse(status, payload, connection.headerFields)
        } finally {
            connection.disconnect()
        }
    }

    private data class HttpResponse(
        val status: Int,
        val body: JSONObject,
        val headers: Map<String?, List<String>>,
    )

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
    }
}

private class EncryptedAppAccountSessionStore(context: Context) : AppAccountSessionStore {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun load(): StoredAppSession? {
        val ciphertext = preferences.getString(KEY_PAYLOAD, null) ?: return null
        val iv = preferences.getString(KEY_IV, null) ?: return null
        return runCatching {
            val cipher = Cipher.getInstance(TRANSFORMATION).apply {
                init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
            }
            val payload = JSONObject(String(cipher.doFinal(Base64.decode(ciphertext, Base64.NO_WRAP))))
            StoredAppSession(payload.getString("email"), payload.getString("cookie"))
        }.getOrNull()
    }

    override fun save(session: StoredAppSession) {
        val payload = JSONObject().put("email", session.email).put("cookie", session.cookieHeader).toString()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        check(
            preferences.edit()
                .putString(KEY_PAYLOAD, Base64.encodeToString(cipher.doFinal(payload.toByteArray()), Base64.NO_WRAP))
                .putString(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
                .commit(),
        ) { "App Account 会话无法安全保存" }
    }

    override fun clear() {
        preferences.edit().remove(KEY_PAYLOAD).remove(KEY_IV).apply()
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
        const val PREFERENCES = "lovehouse_app_account_v1"
        const val KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "lovehouse_app_account_session_v1"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val KEY_PAYLOAD = "session_ciphertext"
        const val KEY_IV = "session_iv"
    }
}

private class AppAccountApiException(message: String) : Exception(message)

private fun JSONObject.accountEmail(): String? = sequenceOf(
    optString("email"),
    optJSONObject("user")?.optString("email"),
    optJSONObject("account")?.optString("email"),
    optJSONObject("session")?.optJSONObject("user")?.optString("email"),
).filterNotNull().firstOrNull(String::isNotBlank)

private fun Throwable.userMessage(): String = message?.takeIf(String::isNotBlank) ?: "App Account 连接失败"

private val EMAIL = Regex("^[^\\s@]+@[^\\s@]+\\.[^\\s@]+$")
private const val MIN_PASSWORD_LENGTH = 8
