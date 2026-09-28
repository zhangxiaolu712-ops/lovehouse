package fyi.b612.lovehouse

import fyi.b612.lovehouse.feature.events.NotificationEventTargetResolution
import fyi.b612.lovehouse.feature.events.ServerEvent
import fyi.b612.lovehouse.feature.events.ServerEventDecision
import fyi.b612.lovehouse.feature.events.ServerEventDecisionResult
import fyi.b612.lovehouse.feature.events.ServerEventFeedState
import fyi.b612.lovehouse.feature.events.ServerEventKind
import fyi.b612.lovehouse.feature.events.ServerEventLoadResult
import fyi.b612.lovehouse.feature.events.ServerEventPresentationSnapshot
import fyi.b612.lovehouse.feature.events.ServerEventRepository
import fyi.b612.lovehouse.feature.events.applyChatConfirmationDecision
import fyi.b612.lovehouse.feature.events.pendingConfirmationForThread
import fyi.b612.lovehouse.feature.events.resolveNotificationEventTarget
import fyi.b612.lovehouse.feature.events.shouldSuppressReplyCompletedNotification
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Phase3bConfirmationUxTest {
    private val root = File(System.getProperty("user.dir"))
    private fun source(path: String) = File(root, "src/main/java/fyi/b612/lovehouse/$path").readText()

    @Test
    fun `Chat only projects pending confirmation for its canonical thread`() {
        val chatA = confirmation("event-a", "canonical-a")
        val chatB = confirmation("event-b", "canonical-b")
        val ready = ServerEventFeedState.Ready(listOf(chatA, chatB))

        assertEquals(chatA, pendingConfirmationForThread(ready, "canonical-a"))
        assertEquals(chatB, pendingConfirmationForThread(ready, "canonical-b"))
        assertNull(pendingConfirmationForThread(ready, "canonical-c"))
        assertNull(pendingConfirmationForThread(ready, null))
        assertNull(pendingConfirmationForThread(ServerEventFeedState.Loading, "canonical-a"))
    }

    @Test
    fun `Chat decision delegates to authoritative repository and keeps returned server state`() = runBlocking {
        val approved = confirmation("event-a", "canonical-a").copy(status = "approved")
        val repository = RecordingRepository(
            event = approved,
            decisionResult = ServerEventDecisionResult.Recorded(approved),
        )
        var projected: ServerEvent? = null

        val message = applyChatConfirmationDecision(
            repository,
            approved.id,
            ServerEventDecision.Approved,
        ) { projected = it }

        assertEquals(listOf(approved.id to ServerEventDecision.Approved), repository.decisions)
        assertEquals(approved, projected)
        assertEquals("已允许，决定已记录", message)
    }

    @Test
    fun `confirmation notification fetches Event and resolves directly to actionable target`() = runBlocking {
        val event = confirmation("event-a", "canonical-a")
        val repository = RecordingRepository(event)
        var mappingCalls = 0

        val result = resolveNotificationEventTarget(
            eventId = event.id,
            repository = repository,
            resolveLocalThreadId = { mappingCalls += 1; null },
            rehydrateThread = { _, _ -> error("confirmation must not rehydrate Chat") },
        )

        assertEquals(1, repository.getCalls)
        assertEquals(0, mappingCalls)
        assertEquals(NotificationEventTargetResolution.Confirmation(event.id), result)
    }

    @Test
    fun `reply notification suppression requires foreground and exact visible canonical thread`() {
        val event = reply("reply-a", "canonical-a")
        assertTrue(shouldSuppressReplyCompletedNotification(
            event,
            ServerEventPresentationSnapshot(appForeground = true, visibleCanonicalThreadId = "canonical-a"),
        ))
        assertFalse(shouldSuppressReplyCompletedNotification(
            event,
            ServerEventPresentationSnapshot(appForeground = true, visibleCanonicalThreadId = "canonical-b"),
        ))
        assertFalse(shouldSuppressReplyCompletedNotification(
            event,
            ServerEventPresentationSnapshot(appForeground = false, visibleCanonicalThreadId = "canonical-a"),
        ))
        assertFalse(shouldSuppressReplyCompletedNotification(
            confirmation("confirmation-a", "canonical-a"),
            ServerEventPresentationSnapshot(appForeground = true, visibleCanonicalThreadId = "canonical-a"),
        ))
        val fcm = source("feature/events/LoveHouseFirebaseMessagingService.kt")
        assertTrue(fcm.contains("createNotificationEventRepository(applicationContext).get(target.eventId)"))
        assertTrue(fcm.contains("ServerEventPresentationState.notifyEventArrival()"))
    }

    @Test
    fun `canonical thread travels through thread-aware runtime source rather than provider identity`() {
        val runtime = source("feature/chat/PersonaRuntimeSource.kt")
        val store = source("feature/chat/ChatSessionStore.kt")

        assertTrue(runtime.contains("put(\"thread_id\", it)"))
        assertTrue(store.contains("personaRuntimeSource.resolveForThread"))
        assertTrue(store.contains("canonicalThreadId,"))
        assertFalse(runtime.contains("thread_id\", personaId"))
    }

    private fun confirmation(id: String, threadId: String) = ServerEvent(
        id = id,
        kind = ServerEventKind.ConfirmationRequired,
        rawKind = "confirmation_required",
        source = "mcp",
        threadId = threadId,
        safeSummary = "AI 请求使用工具：read_livingroom",
        status = "pending",
        createdAt = "2026-09-28T00:00:00.000Z",
        expiresAt = "2026-09-28T00:02:00.000Z",
        decidedAt = null,
    )

    private fun reply(id: String, threadId: String) = ServerEvent(
        id = id,
        kind = ServerEventKind.ReplyCompleted,
        rawKind = "reply_completed",
        source = "chat",
        threadId = threadId,
        safeSummary = "聊天回复已完成",
        status = "active",
        createdAt = "2026-09-28T00:00:00.000Z",
        expiresAt = null,
        decidedAt = null,
    )

    private class RecordingRepository(
        private val event: ServerEvent,
        private val decisionResult: ServerEventDecisionResult = ServerEventDecisionResult.Recorded(event),
    ) : ServerEventRepository {
        override val feed: StateFlow<ServerEventFeedState> = MutableStateFlow(ServerEventFeedState.Ready(listOf(event)))
        var getCalls = 0
        val decisions = mutableListOf<Pair<String, ServerEventDecision>>()

        override suspend fun refresh() = Unit
        override suspend fun get(eventId: String): ServerEventLoadResult {
            getCalls += 1
            return ServerEventLoadResult.Found(event)
        }
        override suspend fun decide(eventId: String, decision: ServerEventDecision): ServerEventDecisionResult {
            decisions += eventId to decision
            return decisionResult
        }
    }
}
