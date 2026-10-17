package fyi.b612.lovehouse.feature.events

import java.io.File
import java.net.HttpURLConnection
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class W4BInboxTest {
    private val counts = InboxCounts(replyUnread = 3, replyRead = 40, approvalPending = 2, approvalHistory = 9)

    @Test
    fun `counts are authoritative per kind and approvals use action state`() {
        assertEquals(5, counts.count(InboxKind.All, InboxSection.Attention))
        assertEquals(49, counts.count(InboxKind.All, InboxSection.History))
        assertEquals(3, counts.count(InboxKind.ReplyCompleted, InboxSection.Attention))
        assertEquals(2, counts.count(InboxKind.ConfirmationRequired, InboxSection.Attention))
        assertEquals(9, counts.count(InboxKind.ConfirmationRequired, InboxSection.History))
        assertEquals("待处理", inboxSectionLabel(InboxKind.ConfirmationRequired, InboxSection.Attention))
        assertEquals("已处理", inboxSectionLabel(InboxKind.ConfirmationRequired, InboxSection.History))
        assertEquals("未读 · 待处理", inboxSectionLabel(InboxKind.All, InboxSection.Attention))
    }

    @Test
    fun `bulk read is only offered for eligible information events`() {
        assertTrue(inboxBulkReadEnabled(InboxKind.All, counts))
        assertTrue(inboxBulkReadEnabled(InboxKind.ReplyCompleted, counts))
        assertFalse(inboxBulkReadEnabled(InboxKind.ConfirmationRequired, counts))
        assertFalse(inboxBulkReadEnabled(null, counts))
        assertFalse(inboxBulkReadEnabled(InboxKind.All, counts.copy(replyUnread = 0)))
    }

    @Test
    fun `pull size defaults to 30 and is clamped to the server max`() {
        assertEquals(30 to false, inboxPullSize(""))
        assertEquals(30 to false, inboxPullSize("0"))
        assertEquals(50 to false, inboxPullSize("50"))
        assertEquals(100 to true, inboxPullSize("500"))
    }

    @Test
    fun `future sources are reserved and never carry data`() {
        assertEquals(
            listOf(InboxSource.All, InboxSource.Reply, InboxSource.Approval),
            InboxSource.entries.filter(InboxSource::connected),
        )
        assertEquals("#7d16", inboxIdTail("01a14355-d4a0-79d3-b179-e0140cd77d16"))
        assertNull(inboxIdTail(null))
    }

    @Test
    fun `inbox query carries section kind query and limit`() {
        assertEquals(
            "section=attention&kind=reply_completed&q=%E6%94%B6+%25&limit=30&cursor=abc",
            inboxQueryString(InboxSection.Attention, InboxKind.ReplyCompleted, "收 %", 30, "abc"),
        )
        assertEquals("section=history&kind=all&limit=50", inboxQueryString(InboxSection.History, InboxKind.All, null, 50, null))
    }

    @Test
    fun `counts payload maps to the authoritative model`() {
        val payload = JSONObject(
            """{"reply_completed":{"unread":3,"read":40},"confirmation_required":{"pending":2,"history":9},"all":{"attention":5,"history":49}}""",
        )
        assertEquals(counts, payload.toInboxCounts())
    }

    @Test
    fun `approval reads only the existing safe tool name metadata`() {
        val approval = JSONObject(
            """{"id":"event-1","kind":"confirmation_required","safe_summary":"AI 请求使用工具：read_status","status":"pending","created_at":"2026-10-17T12:00:00Z","metadata":{"tool_name":"read_status","provider_metadata":{"raw_arguments":"do not display"}}}""",
        ).toServerEvent()
        assertEquals("read_status", approval.toolName)
        assertEquals("AI 请求使用工具：read_status", approval.safeSummary)
        assertEquals("pending", approval.status)

        val legacy = JSONObject(
            """{"id":"event-2","kind":"confirmation_required","safe_summary":"等待确认","status":"expired","created_at":"2026-10-17T12:00:00Z","metadata":{}}""",
        ).toServerEvent()
        assertNull(legacy.toolName)
    }

    @Test
    fun `repository bounds limit and query before calling the server`() = runBlocking {
        val api = InboxFakeApi()
        val repository = AndroidServerEventRepository(api) {}

        assertTrue(repository.inbox(InboxSection.Attention, InboxKind.All, null, 101) is ServerEventPageResult.Error)
        assertTrue(repository.inbox(InboxSection.Attention, InboxKind.All, "x".repeat(101), 30) is ServerEventPageResult.Error)
        assertTrue(api.inboxCalls.isEmpty())

        val ready = repository.inbox(InboxSection.History, InboxKind.ReplyCompleted, "  收件箱  ", 30)
        assertTrue(ready is ServerEventPageResult.Ready)
        assertEquals(listOf("history/reply_completed/收件箱/30"), api.inboxCalls)
    }

    @Test
    fun `protected bulk read and auth failures map to explicit results`() = runBlocking {
        var authRequired = 0
        val api = InboxFakeApi()
        val repository = AndroidServerEventRepository(api) { authRequired += 1 }

        api.bulkFailure = ServerEventApiException(422, "BULK_READ_PROTECTED", "protected")
        assertEquals(InboxBulkReadResult.Protected, repository.bulkRead(InboxKind.ConfirmationRequired))

        api.bulkFailure = ServerEventApiException(HttpURLConnection.HTTP_UNAUTHORIZED, "APP_AUTH_REQUIRED", "auth")
        assertEquals(InboxBulkReadResult.AuthenticationRequired, repository.bulkRead(InboxKind.All))
        assertEquals(1, authRequired)

        api.bulkFailure = null
        assertEquals(InboxBulkReadResult.Recorded("2026-10-17T12:00:00.000Z", 4), repository.bulkRead(InboxKind.All))
    }

    @Test
    fun `entry moved from Settings to the desktop and detail returns to Inbox Home`() {
        val root = File(System.getProperty("user.dir"))
        fun source(path: String) = File(root, "src/main/java/fyi/b612/lovehouse/$path").readText()
        val settings = source("feature/settings/SettingsScreen.kt")
        val home = source("feature/home/HomeScreen.kt")
        val navigation = source("core/navigation/LoveHouseNavHost.kt")
        val inbox = source("feature/events/InboxUi.kt")
        val detail = source("feature/events/ServerEventsScreen.kt")

        assertFalse(settings.contains("SettingEntry(\"动态\""))
        assertFalse(settings.contains("onOpenEvents"))
        assertTrue(home.contains("\"收件箱\""))
        assertTrue(home.contains("onClick = onOpenInbox"))
        assertTrue(navigation.contains("onOpenInbox = { navController.navigate(AppDestination.Events.route)"))
        // Inbox Home is first-level: no back arrow.
        assertTrue(inbox.contains("onBack = null"))
        // Detail: exactly one top back, fixed to Inbox Home, no duplicated in-card back.
        assertTrue(navigation.contains("navController.popBackStack(AppDestination.Events.route, inclusive = false)"))
        assertFalse(detail.contains("‹ 返回"))
        // No local read truth on Android.
        assertFalse(inbox.contains("isReadLocal"))
        assertFalse(inbox.contains("localSeen"))
        assertTrue(inbox.contains("event.toolName"))
        assertTrue(inbox.contains("event.safeSummary"))
        assertFalse(inbox.contains("搜对话关键词"))
        // Notification entry and W4A exact navigation stay intact.
        assertTrue(navigation.contains("NotificationEventTargetResolver"))
        assertTrue(navigation.contains("eventChatRoute("))
    }
}

private class InboxFakeApi : ServerEventApi {
    val inboxCalls = mutableListOf<String>()
    var bulkFailure: ServerEventApiException? = null

    override suspend fun list(status: String, limit: Int, cursor: String?) = ServerEventPage(emptyList(), null)
    override suspend fun list(readState: ServerEventReadState, limit: Int, cursor: String?) = ServerEventPage(emptyList(), null)
    override suspend fun get(eventId: String): ServerEvent = throw ServerEventApiException(404, "EVENT_NOT_FOUND", "missing")
    override suspend fun decide(eventId: String, decision: ServerEventDecision) = Unit
    override suspend fun markRead(eventId: String): ServerEvent = throw ServerEventApiException(404, "EVENT_NOT_FOUND", "missing")
    override suspend fun markConversationRead(threadId: String) =
        ServerEventConversationReadResult.Recorded(threadId, "2026-10-17T12:00:00.000Z", 0)

    override suspend fun inbox(section: InboxSection, kind: InboxKind, query: String?, limit: Int, cursor: String?): ServerEventPage {
        inboxCalls += "${section.wireValue}/${kind.wireValue}/${query.orEmpty()}/$limit"
        return ServerEventPage(emptyList(), null)
    }

    override suspend fun inboxCounts() = InboxCounts(0, 0, 0, 0)

    override suspend fun bulkRead(kind: InboxKind): InboxBulkReadResult.Recorded {
        bulkFailure?.let { throw it }
        return InboxBulkReadResult.Recorded("2026-10-17T12:00:00.000Z", 4)
    }
}
