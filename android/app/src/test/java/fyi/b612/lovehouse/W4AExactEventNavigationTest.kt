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
import fyi.b612.lovehouse.feature.chat.findChatEventNavigationTarget
import fyi.b612.lovehouse.feature.events.NotificationEventTargetResolution
import fyi.b612.lovehouse.feature.events.ServerEvent
import fyi.b612.lovehouse.feature.events.ServerEventDecision
import fyi.b612.lovehouse.feature.events.ServerEventDecisionResult
import fyi.b612.lovehouse.feature.events.ServerEventFeedState
import fyi.b612.lovehouse.feature.events.ServerEventKind
import fyi.b612.lovehouse.feature.events.ServerEventLoadResult
import fyi.b612.lovehouse.feature.events.ServerEventRepository
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
        assertTrue(chat.contains("listState.scrollToItem(target.messageIndex)"))
        assertTrue(chat.contains("isNavigationTargetVisible(listState.layoutInfo.visibleItemsInfo.map { it.key }"))
        // Real user interactions still dismiss everything together.
        assertTrue(chat.contains("if (isUserDragging) dismissExactNavigationFocus()"))
        assertTrue(chat.contains("currentOnInputInteraction()"))
        assertTrue(chat.contains("onInputInteraction = dismissExactNavigationFocus"))
        assertTrue(chat.contains("focusedNavigationMessageId = null"))
        assertTrue(chat.contains("focusedNavigationToolActionId = null"))
        assertTrue(chat.contains("navigationConfirmation = null"))
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
