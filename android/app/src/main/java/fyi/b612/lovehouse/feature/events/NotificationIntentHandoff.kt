package fyi.b612.lovehouse.feature.events

import android.content.Intent
import java.net.URI
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal data class NotificationIntentTarget(
    val deliveryId: Long,
    val eventId: String,
)

/**
 * Process-local handoff from Activity intents to the existing authenticated Event target route.
 * The event id is navigation input only; the destination still loads authoritative server state.
 */
internal object NotificationIntentHandoff {
    private val mutablePending = MutableStateFlow<NotificationIntentTarget?>(null)
    private val consumedEventIds = linkedSetOf<String>()
    private var nextDeliveryId = 0L

    val pending: StateFlow<NotificationIntentTarget?> = mutablePending.asStateFlow()

    @Synchronized
    fun offer(action: String?, data: String?): Boolean {
        val eventId = notificationEventId(action, data) ?: return false
        if (eventId in consumedEventIds) return true
        if (mutablePending.value?.eventId == eventId) return true
        nextDeliveryId += 1
        mutablePending.value = NotificationIntentTarget(nextDeliveryId, eventId)
        return true
    }

    @Synchronized
    fun consume(deliveryId: Long) {
        if (mutablePending.value?.deliveryId == deliveryId) {
            mutablePending.value?.eventId?.let(consumedEventIds::add)
            while (consumedEventIds.size > MAX_CONSUMED_EVENT_IDS) {
                consumedEventIds.remove(consumedEventIds.first())
            }
            mutablePending.value = null
        }
    }

    @Synchronized
    internal fun resetForTest() {
        mutablePending.value = null
        consumedEventIds.clear()
        nextDeliveryId = 0L
    }
}

private const val MAX_CONSUMED_EVENT_IDS = 64

internal fun notificationEventId(action: String?, data: String?): String? {
    if (action != Intent.ACTION_VIEW || data.isNullOrBlank()) return null
    val uri = runCatching { URI(data) }.getOrNull() ?: return null
    if (uri.scheme != "lovehouse" || uri.host != "event") return null
    val eventId = uri.path.orEmpty().removePrefix("/")
    if (!isLoveHouseUuid(eventId) || uri.path.orEmpty().count { it == '/' } != 1) return null
    val query = uri.rawQuery
        ?.split('&')
        ?.mapNotNull { part ->
            val pieces = part.split('=', limit = 2)
            pieces.firstOrNull()?.let { key -> key to pieces.getOrNull(1).orEmpty() }
        }
        ?.toMap()
        .orEmpty()
    return eventId.takeIf { query.size == 1 && query["open_target"] == "true" }
}

internal fun notificationEventTargetRoute(eventId: String): String {
    require(isLoveHouseUuid(eventId))
    return "events/$eventId?openTarget=true"
}
