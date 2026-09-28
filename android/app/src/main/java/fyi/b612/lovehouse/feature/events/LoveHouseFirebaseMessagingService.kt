package fyi.b612.lovehouse.feature.events

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage
import fyi.b612.lovehouse.BuildConfig
import fyi.b612.lovehouse.MainActivity
import fyi.b612.lovehouse.R
import fyi.b612.lovehouse.feature.settings.EncryptedAppAccountSessionStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

class LoveHouseFirebaseMessagingService : FirebaseMessagingService() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onNewToken(token: String) {
        scope.launch {
            runCatching { createRemoteEventPushCoordinator(applicationContext).registerRefreshedToken(token) }
        }
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val target = pushNotificationTarget(message.data) ?: return
        val authoritative = runBlocking(Dispatchers.IO) {
            withTimeoutOrNull(AUTHORITATIVE_FETCH_TIMEOUT_MS) {
                when (val result = createNotificationEventRepository(applicationContext).get(target.eventId)) {
                    is ServerEventLoadResult.Found -> result.event
                    else -> null
                }
            }
        }
        if (authoritative != null) {
            ServerEventPresentationState.notifyEventArrival()
            if (shouldSuppressReplyCompletedNotification(authoritative, ServerEventPresentationState.state.value)) {
                return
            }
        }
        val displayTarget = authoritative?.let { PushNotificationTarget(it.id, it.kind.wireValue) } ?: target
        showEventNotification(this, displayTarget)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}

internal fun createNotificationEventRepository(context: Context): ServerEventRepository {
    val sessionStore = EncryptedAppAccountSessionStore(context.applicationContext)
    return AndroidServerEventRepository(
        baseUrl = BuildConfig.LOVEHOUSE_APP_BACKEND_URL,
        sessionCookie = { sessionStore.load()?.cookieHeader },
        onAuthenticationRequired = {},
    )
}

internal data class PushNotificationTarget(val eventId: String, val kind: String)

internal fun pushNotificationTarget(data: Map<String, String>): PushNotificationTarget? {
    if (data.keys != setOf("event_id", "kind")) return null
    val eventId = data["event_id"]?.takeIf(::isLoveHouseUuid) ?: return null
    val kind = data["kind"]?.takeIf { it == "reply_completed" || it == "confirmation_required" } ?: return null
    return PushNotificationTarget(eventId, kind)
}

internal fun eventNotificationDeepLink(eventId: String): String =
    "lovehouse://event/${eventId.also { require(isLoveHouseUuid(it)) }}?open_target=true"

private fun showEventNotification(context: Context, target: PushNotificationTarget) {
    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) != PackageManager.PERMISSION_GRANTED
    ) return
    val manager = context.getSystemService(NotificationManager::class.java)
    if (Build.VERSION.SDK_INT >= 26) {
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "LoveHouse 动态提醒", NotificationManager.IMPORTANCE_DEFAULT),
        )
    }
    val contentIntent = PendingIntent.getActivity(
        context,
        target.eventId.hashCode(),
        Intent(Intent.ACTION_VIEW, Uri.parse(eventNotificationDeepLink(target.eventId)), context, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP
        },
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val text = if (target.kind == "reply_completed") "聊天回复已完成" else "有一项操作需要你确认"
    val notification = NotificationCompat.Builder(context, CHANNEL_ID)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("LoveHouse")
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setContentIntent(contentIntent)
        .setAutoCancel(true)
        .setCategory(NotificationCompat.CATEGORY_MESSAGE)
        .build()
    NotificationManagerCompat.from(context).notify(target.eventId.hashCode(), notification)
}

private const val CHANNEL_ID = "lovehouse_events"
private const val AUTHORITATIVE_FETCH_TIMEOUT_MS = 8_000L
