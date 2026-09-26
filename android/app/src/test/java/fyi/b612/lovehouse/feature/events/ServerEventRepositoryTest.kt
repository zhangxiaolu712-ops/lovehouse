package fyi.b612.lovehouse.feature.events

import java.net.HttpURLConnection
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerEventRepositoryTest {
    @Test
    fun `pending and active server lists form one generic feed`() = runBlocking {
        val api = FakeServerEventApi().apply {
            lists["pending"] = listOf(event("pending", ServerEventKind.ConfirmationRequired))
            lists["active"] = listOf(event("active", ServerEventKind.ReplyCompleted))
        }
        val repository = repository(api)

        repository.refresh()

        val ready = assertType<ServerEventFeedState.Ready>(repository.feed.value)
        assertEquals(setOf("pending", "active"), ready.events.map(ServerEvent::id).toSet())
        assertEquals(listOf("pending", "active"), api.listStatuses)
    }

    @Test
    fun `401 follows existing App Account authentication handling`() = runBlocking {
        var refreshCount = 0
        val api = FakeServerEventApi().apply {
            listFailure = ServerEventApiException(HttpURLConnection.HTTP_UNAUTHORIZED, "APP_AUTH_REQUIRED", "auth")
        }
        val repository = AndroidServerEventRepository(api) { refreshCount += 1 }

        repository.refresh()

        assertType<ServerEventFeedState.AuthenticationRequired>(repository.feed.value)
        assertEquals(1, refreshCount)
    }

    @Test
    fun `approve posts decision then reads authoritative event`() = runBlocking {
        val api = FakeServerEventApi().apply { detail = event("event-1", status = "approved") }
        val repository = repository(api)

        val result = repository.decide("event-1", ServerEventDecision.Approved)

        assertType<ServerEventDecisionResult.Recorded>(result)
        assertEquals(listOf("event-1" to ServerEventDecision.Approved), api.decisions)
        assertEquals(listOf("event-1"), api.getIds)
    }

    @Test
    fun `deny posts denied decision`() = runBlocking {
        val api = FakeServerEventApi().apply { detail = event("event-1", status = "denied") }
        val repository = repository(api)

        repository.decide("event-1", ServerEventDecision.Denied)

        assertEquals(ServerEventDecision.Denied, api.decisions.single().second)
    }

    @Test
    fun `same approved retry converges to authoritative approved state`() = runBlocking {
        val api = FakeServerEventApi().apply { detail = event("event-1", status = "approved") }
        val repository = repository(api)

        val result = repository.decide("event-1", ServerEventDecision.Approved)

        assertEquals("approved", assertType<ServerEventDecisionResult.Recorded>(result).event.status)
    }

    @Test
    fun `opposite decision conflict refetches server truth`() = runBlocking {
        val api = FakeServerEventApi().apply {
            decisionFailure = ServerEventApiException(HttpURLConnection.HTTP_CONFLICT, "EVENT_DECISION_CONFLICT", "conflict")
            detail = event("event-1", status = "denied")
        }
        val repository = repository(api)

        val result = repository.decide("event-1", ServerEventDecision.Approved)

        val serverState = assertType<ServerEventDecisionResult.ServerState>(result)
        assertEquals("denied", serverState.event.status)
        assertEquals("EVENT_DECISION_CONFLICT", serverState.reason)
    }

    @Test
    fun `expired conflict cannot become local success`() = runBlocking {
        val api = FakeServerEventApi().apply {
            decisionFailure = ServerEventApiException(HttpURLConnection.HTTP_CONFLICT, "EVENT_EXPIRED", "expired")
            detail = event("event-1", status = "expired")
        }
        val repository = repository(api)

        val result = repository.decide("event-1", ServerEventDecision.Approved)

        val serverState = assertType<ServerEventDecisionResult.ServerState>(result)
        assertEquals("expired", serverState.event.status)
        assertFalse(serverState.event.canDecide)
    }

    @Test
    fun `event unavailable is reported as not found`() = runBlocking {
        val api = FakeServerEventApi().apply {
            getFailure = ServerEventApiException(HttpURLConnection.HTTP_NOT_FOUND, "EVENT_NOT_FOUND", "missing")
        }

        assertType<ServerEventLoadResult.NotFound>(repository(api).get("missing"))
        Unit
    }

    @Test
    fun `unknown future kind is safe and not decidable`() {
        val value = event("future", kind = ServerEventKind.Unknown, rawKind = "future_control_plane_event")

        assertEquals("future_control_plane_event", value.rawKind)
        assertFalse(value.canDecide)
        assertEquals("动态", value.kindLabel())
    }

    @Test
    fun `reply completed may omit thread without crashing model`() {
        val value = event("reply", kind = ServerEventKind.ReplyCompleted, threadId = null)

        assertEquals(null, value.threadId)
        assertEquals("回复已完成", value.kindLabel())
    }

    @Test
    fun `event endpoints do not carry decision authority`() {
        assertEquals("https://app.b612.fyi/api/events", serverEventEndpoint("https://app.b612.fyi/"))
        assertEquals("https://app.b612.fyi/api/events/event-1", serverEventEndpoint("https://app.b612.fyi", "event-1"))
        assertEquals("https://app.b612.fyi/api/events/event-1/decision", serverEventEndpoint("https://app.b612.fyi", "event-1", decision = true))
        assertFalse(serverEventEndpoint("https://app.b612.fyi", "event-1").contains("approved"))
    }

    @Test
    fun `known kind mapping keeps current contract and tolerates future values`() {
        assertEquals(ServerEventKind.ReplyCompleted, ServerEventKind.fromWire("reply_completed"))
        assertEquals(ServerEventKind.ConfirmationRequired, ServerEventKind.fromWire("confirmation_required"))
        assertEquals(ServerEventKind.Unknown, ServerEventKind.fromWire("future_kind"))
    }

    private fun repository(api: FakeServerEventApi) = AndroidServerEventRepository(api) {}

    private inline fun <reified T> assertType(value: Any?): T {
        assertTrue("Expected ${T::class.java.simpleName}, got ${value?.javaClass?.simpleName}", value is T)
        return value as T
    }

    private fun event(
        id: String,
        kind: ServerEventKind = ServerEventKind.ConfirmationRequired,
        rawKind: String = kind.wireValue,
        status: String = if (kind == ServerEventKind.ReplyCompleted) "active" else "pending",
        threadId: String? = "thread-1",
    ) = ServerEvent(
        id = id,
        kind = kind,
        rawKind = rawKind,
        source = "chat",
        threadId = threadId,
        safeSummary = "安全摘要",
        status = status,
        createdAt = "2026-09-27T10:00:00.000Z",
        expiresAt = if (kind == ServerEventKind.ConfirmationRequired) "2026-09-27T11:00:00.000Z" else null,
        decidedAt = null,
    )
}

private class FakeServerEventApi : ServerEventApi {
    val lists = mutableMapOf<String, List<ServerEvent>>()
    val listStatuses = mutableListOf<String>()
    val getIds = mutableListOf<String>()
    val decisions = mutableListOf<Pair<String, ServerEventDecision>>()
    var detail: ServerEvent? = null
    var listFailure: ServerEventApiException? = null
    var getFailure: ServerEventApiException? = null
    var decisionFailure: ServerEventApiException? = null

    override suspend fun list(status: String, limit: Int, cursor: String?): ServerEventPage {
        listStatuses += status
        listFailure?.let { throw it }
        return ServerEventPage(lists[status].orEmpty(), null)
    }

    override suspend fun get(eventId: String): ServerEvent {
        getIds += eventId
        getFailure?.let { throw it }
        return requireNotNull(detail) { "fake event detail is missing" }
    }

    override suspend fun decide(eventId: String, decision: ServerEventDecision) {
        decisions += eventId to decision
        decisionFailure?.let { throw it }
    }
}
