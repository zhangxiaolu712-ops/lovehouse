package fyi.b612.lovehouse.feature.events

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ServerEventPresentationSnapshot(
    val appForeground: Boolean = false,
    val visibleCanonicalThreadId: String? = null,
    val arrivalRevision: Long = 0,
)

/** Process-local presentation hints only; server_event remains the authoritative truth. */
object ServerEventPresentationState {
    private val mutableState = MutableStateFlow(ServerEventPresentationSnapshot())
    val state: StateFlow<ServerEventPresentationSnapshot> = mutableState.asStateFlow()

    fun setAppForeground(foreground: Boolean) {
        mutableState.value = mutableState.value.copy(appForeground = foreground)
    }

    fun setVisibleCanonicalThread(threadId: String?) {
        mutableState.value = mutableState.value.copy(visibleCanonicalThreadId = threadId)
    }

    fun clearVisibleCanonicalThread(threadId: String) {
        if (mutableState.value.visibleCanonicalThreadId == threadId) {
            setVisibleCanonicalThread(null)
        }
    }

    fun notifyEventArrival() {
        mutableState.value = mutableState.value.copy(arrivalRevision = mutableState.value.arrivalRevision + 1)
    }

    internal fun resetForTest() {
        mutableState.value = ServerEventPresentationSnapshot()
    }
}

internal fun shouldSuppressReplyCompletedNotification(
    event: ServerEvent,
    presentation: ServerEventPresentationSnapshot,
): Boolean = event.kind == ServerEventKind.ReplyCompleted &&
    event.threadId != null &&
    presentation.appForeground &&
    presentation.visibleCanonicalThreadId == event.threadId

internal fun pendingConfirmationForThread(
    state: ServerEventFeedState,
    canonicalThreadId: String?,
): ServerEvent? = if (canonicalThreadId == null) null else
    (state as? ServerEventFeedState.Ready)?.events?.firstOrNull { event ->
        event.kind == ServerEventKind.ConfirmationRequired &&
            event.status == "pending" &&
            event.threadId == canonicalThreadId
    }
