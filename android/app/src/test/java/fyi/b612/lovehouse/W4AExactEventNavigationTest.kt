package fyi.b612.lovehouse

import fyi.b612.lovehouse.core.navigation.eventChatRoute
import fyi.b612.lovehouse.feature.chat.ChatMessageUi
import fyi.b612.lovehouse.feature.chat.ChatProcessEvent
import fyi.b612.lovehouse.feature.chat.ChatProcessKind
import fyi.b612.lovehouse.feature.chat.ChatProcessStatus
import fyi.b612.lovehouse.feature.chat.NavigationFocusPhase
import fyi.b612.lovehouse.feature.chat.holdsViewport
import fyi.b612.lovehouse.feature.chat.initialNavigationFocusPhase
import fyi.b612.lovehouse.feature.chat.isNavigationTargetVisible
import fyi.b612.lovehouse.feature.chat.matchesNavigationConfirmation
import fyi.b612.lovehouse.feature.chat.positionNavigationTarget
import fyi.b612.lovehouse.feature.chat.findChatEventNavigationTarget
import fyi.b612.lovehouse.feature.events.NotificationEventTargetResolution
import fyi.b612.lovehouse.feature.events.ServerEvent
import fyi.b612.lovehouse.feature.events.ServerEventDecision
import fyi.b612.lovehouse.feature.events.ServerEventDecisionResult
import fyi.b612.lovehouse.feature.events.ServerEventFeedState
import fyi.b612.lovehouse.feature.events.ServerEventKind
import fyi.b612.lovehouse.feature.events.ServerEventLoadResult
import fyi.b612.lovehouse.feature.events.ServerEventRepository
import fyi.b612.lovehouse.feature.events.ServerEventReadState
import fyi.b612.lovehouse.feature.events.ServerEventPageResult
import fyi.b612.lovehouse.feature.events.ServerEventReadResult
import fyi.b612.lovehouse.feature.events.ServerEventConversationReadResult
import fyi.b612.lovehouse.feature.events.resolveNotificationEventTarget
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class W4AExactEventNavigationTest {
    @Test
    fun `reply resolves exact canonical message without guessing`() = runBlocking {
        val event = replyEvent()
        val repository = RecordingRepository(event)

        val result = resolveNotificationEventTarget(
            eventId = event.id,
            repository = repository,
            resolveLocalThreadId = { LOCAL_THREAD_ID },
            rehydrateThread = { local, canonical -> local == LOCAL_THREAD_ID && canonical == THREAD_ID },
            containsTarget = { local, message, tool ->
                local == LOCAL_THREAD_ID && message == MESSAGE_ID && tool == null
            },
        )

        assertEquals(
            NotificationEventTargetResolution.Chat(THREAD_ID, LOCAL_THREAD_ID, MESSAGE_ID, null, EVENT_ID),
            result,
        )
        assertEquals(1, repository.getCalls)
        assertTrue(repository.decisions.isEmpty())
    }

    @Test
    fun `confirmation resolves exact linked Tool Action and repeated resolution remains navigation only`() = runBlocking {
        val event = confirmationEvent()
        val repository = RecordingRepository(event)
        suspend fun resolve() = resolveNotificationEventTarget(
            eventId = event.id,
            repository = repository,
            resolveLocalThreadId = { LOCAL_THREAD_ID },
            rehydrateThread = { _, _ -> true },
            containsTarget = { _, message, tool -> message == MESSAGE_ID && tool == TOOL_ACTION_ID },
        )

        val first = resolve()
        val second = resolve()

        assertEquals(first, second)
        assertEquals(
            NotificationEventTargetResolution.Chat(
                THREAD_ID,
                LOCAL_THREAD_ID,
                MESSAGE_ID,
                TOOL_ACTION_ID,
                EVENT_ID,
            ),
            first,
        )
        assertEquals(2, repository.getCalls)
        assertTrue(repository.decisions.isEmpty())
    }

    @Test
    fun `missing exact local target keeps Event and never opens an approximate chat location`() = runBlocking {
        val confirmation = confirmationEvent()
        val reply = replyEvent()

        val confirmationResult = resolveNotificationEventTarget(
            confirmation.id,
            RecordingRepository(confirmation),
            resolveLocalThreadId = { LOCAL_THREAD_ID },
            rehydrateThread = { _, _ -> true },
            containsTarget = { _, _, _ -> false },
        )
        val replyResult = resolveNotificationEventTarget(
            reply.id,
            RecordingRepository(reply),
            resolveLocalThreadId = { LOCAL_THREAD_ID },
            rehydrateThread = { _, _ -> true },
            containsTarget = { _, _, _ -> false },
        )

        assertEquals(NotificationEventTargetResolution.Confirmation(EVENT_ID), confirmationResult)
        assertEquals(NotificationEventTargetResolution.Fallback, replyResult)
    }

    @Test
    fun `message and Tool Action focus uses canonical identities only`() {
        val exact = ChatMessageUi(
            messageId = "assistant:local",
            canonicalMessageId = MESSAGE_ID,
            author = "Codex",
            avatar = "C",
            body = "done",
            time = "now",
            mine = false,
            processEvents = listOf(
                ChatProcessEvent(
                    id = "provider-item",
                    kind = ChatProcessKind.ToolCall,
                    title = "wake_up",
                    status = ChatProcessStatus.Succeeded,
                    toolActionId = TOOL_ACTION_ID,
                ),
            ),
        )
        val sameTextWrongIdentity = exact.copy(
            messageId = "assistant:other",
            canonicalMessageId = "0199db1d-6c00-7004-8004-000000000004",
        )

        assertEquals(
            "assistant:local",
            findChatEventNavigationTarget(listOf(sameTextWrongIdentity, exact), MESSAGE_ID, TOOL_ACTION_ID)?.localMessageId,
        )
        assertNull(findChatEventNavigationTarget(listOf(exact), MESSAGE_ID, "0199db1d-6c00-7005-8005-000000000005"))
    }

    @Test
    fun `exact chat route carries message Tool Action and source Event identities`() {
        val route = eventChatRoute(
            canonicalThreadId = THREAD_ID,
            messageId = MESSAGE_ID,
            toolActionId = TOOL_ACTION_ID,
            sourceEventId = EVENT_ID,
            resolveLocalThreadId = { LOCAL_THREAD_ID },
            encodeRouteSegment = { it },
        )

        assertEquals(
            "chat/thread/$LOCAL_THREAD_ID?targetMessageId=$MESSAGE_ID&targetToolActionId=$TOOL_ACTION_ID&sourceEventId=$EVENT_ID",
            route,
        )
    }

    @Test
    fun `UI exposes explicit unavailable state and exact focus inputs`() {
        val root = File(System.getProperty("user.dir"))
        val navigation = File(root, "src/main/java/fyi/b612/lovehouse/core/navigation/LoveHouseNavHost.kt").readText()
        val chat = File(root, "src/main/java/fyi/b612/lovehouse/feature/chat/PersonaChatScreen.kt").readText()
        val events = File(root, "src/main/java/fyi/b612/lovehouse/feature/events/ServerEventsScreen.kt").readText()

        assertTrue(navigation.contains("targetMessageId"))
        assertTrue(navigation.contains("targetToolActionId"))
        assertTrue(navigation.contains("sourceEventId"))
        assertTrue(chat.contains("findChatEventNavigationTarget"))
        assertTrue(chat.contains("focusedNavigationToolActionId"))
        assertTrue(events.contains("目标消息暂时无法定位"))
    }

    @Test
    fun `navigation focus owns the viewport until a real user interaction`() {
        assertEquals(NavigationFocusPhase.Idle, initialNavigationFocusPhase(null))
        assertEquals(NavigationFocusPhase.Pending, initialNavigationFocusPhase(MESSAGE_ID))
        assertTrue(NavigationFocusPhase.Pending.holdsViewport())
        assertTrue(NavigationFocusPhase.Positioning.holdsViewport())
        assertTrue(NavigationFocusPhase.Active.holdsViewport())
        assertFalse(NavigationFocusPhase.Idle.holdsViewport())
        assertFalse(NavigationFocusPhase.Dismissed.holdsViewport())
    }

    @Test
    fun `focus activates only once the target row is actually on screen`() {
        assertFalse(isNavigationTargetVisible(listOf("local-1", "local-2"), "local-target"))
        assertTrue(isNavigationTargetVisible(listOf("local-1", "local-target"), "local-target"))
    }

    @Test
    fun `resolved confirmation still restores the approval focus card`() {
        val approved = confirmationEvent().copy(status = "approved")
        val denied = confirmationEvent().copy(status = "denied")
        assertTrue(approved.matchesNavigationConfirmation(MESSAGE_ID, TOOL_ACTION_ID))
        assertTrue(denied.matchesNavigationConfirmation(MESSAGE_ID, TOOL_ACTION_ID))
        assertFalse(approved.matchesNavigationConfirmation(MESSAGE_ID, "0199db1d-6c00-7005-8005-000000000005"))
        assertFalse(replyEvent().matchesNavigationConfirmation(MESSAGE_ID, TOOL_ACTION_ID))
    }

    @Test
    fun `programmatic actions never dismiss navigation focus`() {
        val root = File(System.getProperty("user.dir"))
        val chat = File(root, "src/main/java/fyi/b612/lovehouse/feature/chat/PersonaChatScreen.kt").readText()

        // No timer, no IME level check and no focus restoration may end the focus.
        assertFalse(chat.contains("EXACT_NAVIGATION_FOCUS_MILLIS"))
        assertFalse(chat.contains("if (screenImeVisible) dismissExactNavigationFocus()"))
        assertFalse(chat.contains("if (focusState.isFocused) onInputInteraction()"))
        // Bottom-follow is suspended while navigation owns the viewport.
        assertTrue(chat.contains("if (navigationFocusPhase.holdsViewport()) return@LaunchedEffect"))
        assertTrue(chat.contains("if (navigationHoldsViewport) {"))
        // Focus is activated only after the scrolled target is confirmed visible.
        assertTrue(chat.contains("listState.scrollToItem(index)"))
        assertTrue(chat.contains("isNavigationTargetVisible(listState.layoutInfo.visibleItemsInfo.map { it.key }, localMessageId)"))
        // Real user interactions still dismiss everything together.
        assertTrue(chat.contains("if (isUserDragging) dismissExactNavigationFocus()"))
        assertTrue(chat.contains("currentOnInputInteraction()"))
        assertTrue(chat.contains("onInputInteraction = dismissExactNavigationFocus"))
        assertTrue(chat.contains("focusedNavigationMessageId = null"))
        assertTrue(chat.contains("focusedNavigationToolActionId = null"))
        assertTrue(chat.contains("navigationConfirmation = null"))
    }

    @Test
    fun `navigation focus hints use terracotta message focus and deep green Tool Action focus`() {
        val root = File(System.getProperty("user.dir"))
        val chat = File(root, "src/main/java/fyi/b612/lovehouse/feature/chat/PersonaChatScreen.kt").readText()
        val events = File(root, "src/main/java/fyi/b612/lovehouse/feature/events/ServerEventsScreen.kt").readText()

        assertTrue(chat.contains("NavigationFocusTerracotta = Color(0xFFC96442)"))
        assertTrue(chat.contains("NavigationFocusDeepGreen = Color(0xFF2F6A55)"))
        assertTrue(chat.contains("navigationFocused = focused"))
        assertTrue(events.contains("定位自通知 · ${'$'}{event.statusLabel()}"))
    }

    @Test
    fun `off-screen historical target scrolls by identity until it is in the viewport`() = runBlocking {
        // 40 messages, viewport of 6 rows parked at the bottom; target sits far above it.
        val messages = (0 until 40).map { historyMessage(it) }.toMutableList()
        val viewport = FakeViewport(size = 6, itemCount = { messages.size }).apply { parkAtBottom() }
        val targetCanonical = canonicalId(5)
        assertFalse(viewport.isVisible(messages.indexOfFirst { it.canonicalMessageId == targetCanonical }))

        var historyLoads = 0
        val target = positionNavigationTarget(
            locate = { findChatEventNavigationTarget(messages, targetCanonical) },
            loadHistory = { historyLoads++ },
            scrollTo = { viewport.scrollTo(it) },
            awaitVisible = { key -> viewport.isVisible(messages.indexOfFirst { it.messageId == key }) },
        )

        assertEquals("local-5", target?.localMessageId)
        assertEquals(5, target?.messageIndex)
        assertTrue(viewport.isVisible(5))
        assertEquals(0, historyLoads)
    }

    @Test
    fun `target outside the loaded window is loaded first and then positioned by its new index`() = runBlocking {
        // Only the latest 20 messages are loaded; the target (#3) arrives with the history prepend.
        val messages = (20 until 40).map { historyMessage(it) }.toMutableList()
        val viewport = FakeViewport(size = 6, itemCount = { messages.size }).apply { parkAtBottom() }
        val targetCanonical = canonicalId(3)
        val scrolledTo = mutableListOf<Int>()

        val target = positionNavigationTarget(
            locate = { findChatEventNavigationTarget(messages, targetCanonical) },
            loadHistory = { messages.addAll(0, (0 until 20).map { historyMessage(it) }) },
            scrollTo = { index -> scrolledTo += index; viewport.scrollTo(index) },
            awaitVisible = { key -> viewport.isVisible(messages.indexOfFirst { it.messageId == key }) },
        )

        assertEquals("local-3", target?.localMessageId)
        assertEquals(listOf(3), scrolledTo)
        assertTrue(viewport.isVisible(3))
    }

    @Test
    fun `prepend during positioning re-resolves the index from the stable identity`() = runBlocking {
        val messages = (10 until 40).map { historyMessage(it) }.toMutableList()
        val viewport = FakeViewport(size = 6, itemCount = { messages.size }).apply { parkAtBottom() }
        val targetCanonical = canonicalId(12)
        var prepended = false
        val scrolledTo = mutableListOf<Int>()

        val target = positionNavigationTarget(
            locate = { findChatEventNavigationTarget(messages, targetCanonical) },
            loadHistory = {},
            scrollTo = { index ->
                scrolledTo += index
                viewport.scrollTo(index)
                if (!prepended) {
                    prepended = true
                    messages.addAll(0, (0 until 10).map { historyMessage(it) })
                }
            },
            awaitVisible = { key -> viewport.isVisible(messages.indexOfFirst { it.messageId == key }) },
        )

        assertEquals(listOf(2, 12), scrolledTo)
        assertEquals(12, target?.messageIndex)
        assertTrue(viewport.isVisible(12))
    }

    @Test
    fun `missing target after history load never scrolls to a guessed position`() = runBlocking {
        val messages = (0 until 30).map { historyMessage(it) }.toMutableList()
        var scrolled = false
        val target = positionNavigationTarget(
            locate = { findChatEventNavigationTarget(messages, canonicalId(99)) },
            loadHistory = {},
            scrollTo = { scrolled = true },
            awaitVisible = { true },
        )
        assertNull(target)
        assertFalse(scrolled)
    }

    @Test
    fun `composer layout cannot pull the list back to the bottom while navigation positions`() {
        val root = File(System.getProperty("user.dir"))
        val chat = File(root, "src/main/java/fyi/b612/lovehouse/feature/chat/PersonaChatScreen.kt").readText()

        assertTrue(chat.contains("if (followLatest && !navigationFocusPhase.holdsViewport() && messages.isNotEmpty())"))
        assertTrue(chat.contains("loadHistory = { canonicalThreadId?.let { store.rehydrateThreadFromPersistence(threadId, it) } }"))
        assertFalse(chat.contains("isScrollInProgress"))
    }

    private fun canonicalId(n: Int) = "0199db1d-6c00-7009-8009-%012d".format(n)

    private fun historyMessage(n: Int) = ChatMessageUi(
        messageId = "local-$n",
        canonicalMessageId = canonicalId(n),
        author = if (n % 2 == 0) "Codex" else "我",
        avatar = "C",
        body = "message $n",
        time = "now",
        mine = n % 2 != 0,
    )

    /** Minimal stand-in for a LazyList viewport: [first, first + size) rows are visible. */
    private class FakeViewport(private val size: Int, private val itemCount: () -> Int) {
        private var first = 0
        fun parkAtBottom() { first = (itemCount() - size).coerceAtLeast(0) }
        fun scrollTo(index: Int) { first = index.coerceIn(0, (itemCount() - size).coerceAtLeast(0)) }
        fun isVisible(index: Int) = index >= 0 && index in first until first + size
    }

    private fun replyEvent() = ServerEvent(
        id = EVENT_ID,
        kind = ServerEventKind.ReplyCompleted,
        rawKind = "reply_completed",
        source = "chat",
        threadId = THREAD_ID,
        executionId = EXECUTION_ID,
        messageId = MESSAGE_ID,
        safeSummary = "聊天回复已完成",
        status = "active",
        createdAt = "2026-10-16T00:00:00.000Z",
        expiresAt = null,
        decidedAt = null,
    )

    private fun confirmationEvent() = replyEvent().copy(
        kind = ServerEventKind.ConfirmationRequired,
        rawKind = "confirmation_required",
        source = "mcp",
        toolActionId = TOOL_ACTION_ID,
        relationStatus = "linked",
        safeSummary = "AI 请求使用工具：wake_up",
        status = "pending",
    )

    private class RecordingRepository(private val event: ServerEvent) : ServerEventRepository {
        override val feed: StateFlow<ServerEventFeedState> = MutableStateFlow(ServerEventFeedState.Initial)
        var getCalls = 0
        val decisions = mutableListOf<Pair<String, ServerEventDecision>>()

        override suspend fun refresh() = Unit
        override suspend fun get(eventId: String): ServerEventLoadResult {
            getCalls += 1
            return ServerEventLoadResult.Found(event)
        }
        override suspend fun decide(eventId: String, decision: ServerEventDecision): ServerEventDecisionResult {
            decisions += eventId to decision
            return ServerEventDecisionResult.Recorded(event)
        }
        override suspend fun page(readState: ServerEventReadState, limit: Int, cursor: String?): ServerEventPageResult =
            error("W4A resolver must not list read state")
        override suspend fun markRead(eventId: String): ServerEventReadResult =
            error("W4A resolver must not mark read")
        override suspend fun markConversationRead(threadId: String): ServerEventConversationReadResult =
            error("W4A resolver must not batch-read")
    }

    private companion object {
        const val EVENT_ID = "11111111-1111-4111-8111-111111111111"
        const val EXECUTION_ID = "22222222-2222-4222-8222-222222222222"
        const val THREAD_ID = "33333333-3333-4333-8333-333333333333"
        const val LOCAL_THREAD_ID = "agent-codex"
        const val MESSAGE_ID = "0199db1d-6c00-7002-8002-000000000002"
        const val TOOL_ACTION_ID = "0199db1d-6c00-7003-8003-000000000003"
    }
}
