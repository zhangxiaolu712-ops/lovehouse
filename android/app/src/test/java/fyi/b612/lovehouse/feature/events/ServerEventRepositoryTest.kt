package fyi.b612.lovehouse.feature.events

import java.net.HttpURLConnection
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
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
    fun `read and unread pages preserve server cursor and do not replace action feed`() = runBlocking {
        val api = FakeServerEventApi().apply {
            readLists[ServerEventReadState.READ] = ServerEventPage(
                listOf(event("read", status = "approved").copy(readState = ServerEventReadState.READ)),
                "next-page",
            )
        }
        val repository = repository(api)

        val page = assertType<ServerEventPageResult.Ready>(repository.page(ServerEventReadState.READ, 30, "prior"))

        assertEquals("read", page.page.events.single().id)
        assertEquals("next-page", page.page.nextCursor)
        assertEquals(listOf(Triple(ServerEventReadState.READ, 30, "prior")), api.readQueries)
        assertEquals(ServerEventFeedState.Initial, repository.feed.value)
    }

    @Test
    fun `read page accepts backend maximum 100 and rejects larger requests before network`() = runBlocking {
        val api = FakeServerEventApi()
        val repository = repository(api)

        assertType<ServerEventPageResult.Ready>(repository.page(ServerEventReadState.UNREAD, 100))
        assertType<ServerEventPageResult.Error>(repository.page(ServerEventReadState.UNREAD, 101))
        assertEquals(listOf(Triple(ServerEventReadState.UNREAD, 100, null)), api.readQueries)
    }

    @Test
    fun `single read uses authoritative event and keeps pending ASK action state`() = runBlocking {
        val api = FakeServerEventApi().apply {
            readDetail = event("event-1", status = "pending").copy(
                readState = ServerEventReadState.READ,
                readAt = "2026-10-16T13:01:00.000Z",
            )
        }

        val result = assertType<ServerEventReadResult.Recorded>(repository(api).markRead("event-1"))

        assertEquals(listOf("event-1"), api.readIds)
        assertEquals(ServerEventReadState.READ, result.event.readState)
        assertEquals("pending", result.event.status)
        assertTrue(result.event.canDecide)
    }

    @Test
    fun `read transport failure or unconfirmed response never claims success`() = runBlocking {
        val failedApi = FakeServerEventApi().apply {
            readFailure = ServerEventApiException(503, "EVENT_READ_FAILED", "remote raw body")
        }
        val failedResult = assertType<ServerEventReadResult.Error>(repository(failedApi).markRead("event-1"))
        assertFalse(failedResult.message.contains("remote raw body"))

        val invalidApi = FakeServerEventApi().apply {
            readDetail = event("event-1", status = "pending")
        }
        assertType<ServerEventReadResult.Error>(repository(invalidApi).markRead("event-1"))
        Unit
    }

    @Test
    fun `conversation read reports server cutoff and never derives it from local time`() = runBlocking {
        val api = FakeServerEventApi().apply {
            batchResult = ServerEventConversationReadResult.Recorded("thread-1", "2026-10-16T13:00:00.000Z", 3)
        }

        val result = assertType<ServerEventConversationReadResult.Recorded>(repository(api).markConversationRead("thread-1"))

        assertEquals(listOf("thread-1"), api.batchThreadIds)
        assertEquals("2026-10-16T13:00:00.000Z", result.cutoffAt)
        assertEquals(3, result.markedReadCount)
    }

    @Test
    fun `read operations follow App Account authentication boundary`() = runBlocking {
        var authRefreshes = 0
        val api = FakeServerEventApi().apply {
            readFailure = ServerEventApiException(401, "APP_AUTH_REQUIRED", "auth")
            batchFailure = ServerEventApiException(401, "APP_AUTH_REQUIRED", "auth")
        }
        val repository = AndroidServerEventRepository(api) { authRefreshes++ }

        assertType<ServerEventReadResult.AuthenticationRequired>(repository.markRead("event-1"))
        assertType<ServerEventConversationReadResult.AuthenticationRequired>(repository.markConversationRead("thread-1"))
        assertEquals(2, authRefreshes)
    }

    @Test
    fun `public event parser reads read state separately from action status`() {
        val event = JSONObject()
            .put("id", "event-1")
            .put("kind", "confirmation_required")
            .put("safe_summary", "ASK")
            .put("status", "pending")
            .put("created_at", "2026-10-16T13:00:00.000Z")
            .put("read_state", "READ")
            .put("read_at", "2026-10-16T13:01:00.000Z")
            .toServerEvent()

        assertEquals(ServerEventReadState.READ, event.readState)
        assertEquals("2026-10-16T13:01:00.000Z", event.readAt)
        assertEquals("pending", event.status)
        assertEquals(ServerEventReadState.UNREAD, JSONObject()
            .put("id", "legacy")
            .put("kind", "reply_completed")
            .put("safe_summary", "reply")
            .put("status", "active")
            .put("created_at", "2026-10-16T13:00:00.000Z")
            .toServerEvent().readState)
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
    val readLists = mutableMapOf<ServerEventReadState, ServerEventPage>()
    val readQueries = mutableListOf<Triple<ServerEventReadState, Int, String?>>()
    val getIds = mutableListOf<String>()
    val decisions = mutableListOf<Pair<String, ServerEventDecision>>()
    val readIds = mutableListOf<String>()
    val batchThreadIds = mutableListOf<String>()
    var detail: ServerEvent? = null
    var readDetail: ServerEvent? = null
    var batchResult: ServerEventConversationReadResult.Recorded? = null
    var listFailure: ServerEventApiException? = null
    var getFailure: ServerEventApiException? = null
    var decisionFailure: ServerEventApiException? = null
    var readFailure: ServerEventApiException? = null
    var batchFailure: ServerEventApiException? = null

    override suspend fun list(status: String, limit: Int, cursor: String?): ServerEventPage {
        listStatuses += status
        listFailure?.let { throw it }
        return ServerEventPage(lists[status].orEmpty(), null)
    }

    override suspend fun list(readState: ServerEventReadState, limit: Int, cursor: String?): ServerEventPage {
        readQueries += Triple(readState, limit, cursor)
        listFailure?.let { throw it }
        return readLists[readState] ?: ServerEventPage(emptyList(), null)
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

    override suspend fun markRead(eventId: String): ServerEvent {
        readIds += eventId
        readFailure?.let { throw it }
        return requireNotNull(readDetail) { "fake read detail is missing" }
    }

    override suspend fun markConversationRead(threadId: String): ServerEventConversationReadResult.Recorded {
        batchThreadIds += threadId
        batchFailure?.let { throw it }
        return requireNotNull(batchResult) { "fake conversation cutoff is missing" }
    }
}
