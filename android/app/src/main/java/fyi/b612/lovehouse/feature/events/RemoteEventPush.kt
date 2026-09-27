package fyi.b612.lovehouse.feature.events

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.FirebaseOptions
import com.google.firebase.messaging.FirebaseMessaging
import fyi.b612.lovehouse.BuildConfig
import fyi.b612.lovehouse.feature.settings.AppAccountPushLifecycle
import fyi.b612.lovehouse.feature.settings.EncryptedAppAccountSessionStore
import java.net.HttpURLConnection
import java.net.URL
import java.util.UUID
import kotlin.coroutines.resume
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import org.json.JSONObject

internal data class FirebaseClientConfiguration(
    val applicationId: String,
    val apiKey: String,
    val projectId: String,
    val senderId: String,
) {
    val configured: Boolean
        get() = applicationId.isNotBlank() && apiKey.isNotBlank() && projectId.isNotBlank() && senderId.isNotBlank()
}

internal interface PushTokenSource {
    suspend fun currentToken(): String?
}

internal interface PushInstallationApi {
    suspend fun upsert(installationId: String, transport: String, pushToken: String, cookieHeader: String)
    suspend fun disable(installationId: String, transport: String, cookieHeader: String)
}

internal interface InstallationIdentityStore {
    fun id(): String
}

interface RemoteEventPushRegistration {
    suspend fun refreshRegistration()
}

internal class RemoteEventPushCoordinator(
    private val installationStore: InstallationIdentityStore,
    private val tokenSource: PushTokenSource,
    private val api: PushInstallationApi,
    private val sessionCookie: () -> String?,
) : AppAccountPushLifecycle, RemoteEventPushRegistration {
    override suspend fun refreshRegistration() {
        val cookie = sessionCookie() ?: return
        val token = tokenSource.currentToken()?.takeIf(String::isNotBlank) ?: return
        api.upsert(installationStore.id(), FCM_TRANSPORT, token, cookie)
    }

    suspend fun registerRefreshedToken(token: String) {
        val cookie = sessionCookie() ?: return
        if (token.isNotBlank()) api.upsert(installationStore.id(), FCM_TRANSPORT, token, cookie)
    }

    override suspend fun onAuthenticated(cookieHeader: String) {
        val token = tokenSource.currentToken()?.takeIf(String::isNotBlank) ?: return
        api.upsert(installationStore.id(), FCM_TRANSPORT, token, cookieHeader)
    }

    override suspend fun beforeLogout(cookieHeader: String) {
        api.disable(installationStore.id(), FCM_TRANSPORT, cookieHeader)
    }
}

internal class AndroidInstallationIdentityStore(context: Context) : InstallationIdentityStore {
    private val preferences = context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)

    override fun id(): String {
        preferences.getString(KEY_ID, null)?.takeIf(::isLoveHouseUuid)?.let { return it }
        val generated = UUID.randomUUID().toString()
        check(preferences.edit().putString(KEY_ID, generated).commit()) { "installation identity unavailable" }
        return generated
    }

    private companion object {
        const val PREFERENCES = "lovehouse_push_installation_v1"
        const val KEY_ID = "installation_id"
    }
}

internal class FirebasePushTokenSource(
    private val context: Context,
    private val configuration: FirebaseClientConfiguration,
) : PushTokenSource {
    override suspend fun currentToken(): String? {
        val messaging = firebaseMessagingOrNull(context, configuration) ?: return null
        return suspendCancellableCoroutine { continuation ->
            messaging.token
                .addOnSuccessListener { continuation.resume(it) }
                .addOnFailureListener { continuation.resume(null) }
        }
    }
}

internal class HttpPushInstallationApi(private val baseUrl: String) : PushInstallationApi {
    override suspend fun upsert(
        installationId: String,
        transport: String,
        pushToken: String,
        cookieHeader: String,
    ) = request(
        method = "PUT",
        installationId = installationId,
        transport = transport,
        cookieHeader = cookieHeader,
        body = JSONObject().put("push_token", pushToken).toString(),
    )

    override suspend fun disable(installationId: String, transport: String, cookieHeader: String) = request(
        method = "DELETE",
        installationId = installationId,
        transport = transport,
        cookieHeader = cookieHeader,
    )

    private suspend fun request(
        method: String,
        installationId: String,
        transport: String,
        cookieHeader: String,
        body: String? = null,
    ) = withContext(Dispatchers.IO) {
        require(isLoveHouseUuid(installationId))
        val endpoint = pushInstallationEndpoint(baseUrl, installationId, transport)
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cookie", cookieHeader)
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            check(connection.responseCode in 200..299) { "push registration unavailable" }
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 15_000
    }
}

internal fun createRemoteEventPushCoordinator(context: Context): RemoteEventPushCoordinator {
    val appContext = context.applicationContext
    val sessionStore = EncryptedAppAccountSessionStore(appContext)
    val configuration = FirebaseClientConfiguration(
        applicationId = BuildConfig.LOVEHOUSE_FIREBASE_APPLICATION_ID,
        apiKey = BuildConfig.LOVEHOUSE_FIREBASE_API_KEY,
        projectId = BuildConfig.LOVEHOUSE_FIREBASE_PROJECT_ID,
        senderId = BuildConfig.LOVEHOUSE_FIREBASE_SENDER_ID,
    )
    return RemoteEventPushCoordinator(
        installationStore = AndroidInstallationIdentityStore(appContext),
        tokenSource = FirebasePushTokenSource(appContext, configuration),
        api = HttpPushInstallationApi(BuildConfig.LOVEHOUSE_APP_BACKEND_URL),
        sessionCookie = { sessionStore.load()?.cookieHeader },
    )
}

internal fun pushInstallationEndpoint(baseUrl: String, installationId: String, transport: String): String =
    "${baseUrl.trimEnd('/')}/api/installations/$installationId/push-endpoints/$transport"

internal fun isLoveHouseUuid(value: String): Boolean = runCatching {
    UUID.fromString(value).toString().equals(value, ignoreCase = true)
}.getOrDefault(false)

private fun firebaseMessagingOrNull(
    context: Context,
    configuration: FirebaseClientConfiguration,
): FirebaseMessaging? {
    if (!configuration.configured) return null
    if (FirebaseApp.getApps(context).isEmpty()) {
        FirebaseApp.initializeApp(
            context,
            FirebaseOptions.Builder()
                .setApplicationId(configuration.applicationId)
                .setApiKey(configuration.apiKey)
                .setProjectId(configuration.projectId)
                .setGcmSenderId(configuration.senderId)
                .build(),
        )
    }
    return FirebaseMessaging.getInstance()
}

private const val FCM_TRANSPORT = "fcm"
