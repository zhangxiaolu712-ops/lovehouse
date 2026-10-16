package fyi.b612.lovehouse

import fyi.b612.lovehouse.feature.chat.ChatEventNavigationTarget
import fyi.b612.lovehouse.feature.chat.ChatPersona
import fyi.b612.lovehouse.feature.chat.ChatSessionStore
import fyi.b612.lovehouse.feature.chat.InMemoryConversationPersonaStore
import fyi.b612.lovehouse.feature.chat.positionNavigationTarget
import fyi.b612.lovehouse.feature.chat.recordDirectConversationEntryRead
import fyi.b612.lovehouse.feature.chat.recordEventReadAfterTargetVisible
import fyi.b612.lovehouse.feature.chat.shouldStartDirectConversationRead
import fyi.b612.lovehouse.feature.events.ServerEvent
import fyi.b612.lovehouse.feature.events.ServerEventConversationReadResult
import fyi.b612.lovehouse.feature.events.ServerEventDecision
import fyi.b612.lovehouse.feature.events.ServerEventDecisionResult
import fyi.b612.lovehouse.feature.events.ServerEventFeedState
import fyi.b612.lovehouse.feature.events.ServerEventKind
import fyi.b612.lovehouse.feature.events.ServerEventLoadResult
import fyi.b612.lovehouse.feature.events.ServerEventPageResult
import fyi.b612.lovehouse.feature.events.ServerEventReadResult
import fyi.b612.lovehouse.feature.events.ServerEventReadState
import fyi.b612.lovehouse.feature.events.ServerEventRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class W4CEventReadSemanticsTest {
    @Test
    fun `direct read resolves only a known canonical alias for non built in chat`() {
        val aliases = InMemoryConversationPersonaStore()
        val store = ChatSessionStore(conversationPersonas = aliases)
        val local = store.createThread(ChatPersona("custom", "Custom", "C", "custom"), temporary = false)

        assertNull(store.canonicalThreadIdForLocalThread(local.threadId))
        aliases.setThreadAlias(local.threadId, "canonical-custom-thread")
        assertEquals("canonical-custom-thread", store.canonicalThreadIdForLocalThread(local.threadId))
        assertNull(store.canonicalThreadIdForLocalThread("not-a-local-thread"))
    }

    @Test
    fun `canonical alias first created after entry cannot retroactively trigger batch read`() {
        val aliases = InMemoryConversationPersonaStore()
        val store = ChatSessionStore(conversationPersonas = aliases)
        val local = store.createThread(ChatPersona("custom", "Custom", "C", "custom"), temporary = false)
        val entryReadThreadId = store.canonicalThreadIdForLocalThread(local.threadId)

        aliases.setThreadAlias(local.threadId, "canonical-later")

        assertNull(entryReadThreadId)
        assertEquals("canonical-later", store.canonicalThreadIdForLocalThread(local.threadId))
        assertFalse(shouldStartDirectConversationRead(entryReadThreadId, null, false))
    }

    @Test
    fun `missing or invisible exact target never marks source Event read`() = runBlocking {
        val repository = RecordingReadRepository()
        val missing = positionNavigationTarget(
            locate = { null },
            loadHistory = {},
            scrollTo = {},
            awaitVisible = { false },
        )
        assertNull(recordEventReadAfterTargetVisible("event-1", missing, repository))

        val invisible = positionNavigationTarget(
            locate = { ChatEventNavigationTarget(3, "local-message") },
            loadHistory = {},
            scrollTo = {},
            awaitVisible = { false },
        )
        assertNull(recordEventReadAfterTargetVisible("event-1", invisible, repository))
        assertTrue(repository.readIds.isEmpty())
    }

    @Test
    fun `exact target visible before one single Event read`() = runBlocking {
        val calls = mutableListOf<String>()
        val repository = RecordingReadRepository(calls)
        val target = positionNavigationTarget(
            locate = { ChatEventNavigationTarget(3, "local-message") },
            loadHistory = { calls += "load" },
            scrollTo = { calls += "scroll:$it" },
            awaitVisible = { calls += "visible:$it"; true },
        )

        val result = recordEventReadAfterTargetVisible("event-1", target, repository)

        assertTrue(result is ServerEventReadResult.Recorded)
        assertEquals(listOf("scroll:3", "visible:local-message", "read:event-1"), calls)
        assertEquals(listOf("event-1"), repository.readIds)
    }

    @Test
    fun `direct entry captures one cutoff and restoration does not batch read again`() = runBlocking {
        val repository = RecordingReadRepository()
        var savedEntryStarted = false
        if (shouldStartDirectConversationRead("conversation-a", null, savedEntryStarted)) {
            savedEntryStarted = true
            recordDirectConversationEntryRead("conversation-a", null, repository)
        }

        // The navigation entry's rememberSaveable flag is restored after Activity recreation.
        assertFalse(shouldStartDirectConversationRead("conversation-a", null, savedEntryStarted))
        assertEquals(listOf("conversation-a"), repository.batchThreadIds)
        assertTrue(shouldStartDirectConversationRead("conversation-a", null, false)) // New entry.

    }

    @Test
    fun `Event navigation never triggers conversation batch read`() = runBlocking {
        val repository = RecordingReadRepository()

        assertFalse(shouldStartDirectConversationRead("conversation-a", "event-1", false))
        assertFalse(shouldStartDirectConversationRead(null, null, false))
        assertNull(recordDirectConversationEntryRead("conversation-a", "event-1", repository))
        assertNull(recordDirectConversationEntryRead(null, null, repository))
        assertTrue(repository.batchThreadIds.isEmpty())
    }

    private class RecordingReadRepository(private val calls: MutableList<String> = mutableListOf()) : ServerEventRepository {
        val readIds = mutableListOf<String>()
        val batchThreadIds = mutableListOf<String>()
        override val feed: StateFlow<ServerEventFeedState> = MutableStateFlow(ServerEventFeedState.Initial)

        override suspend fun refresh() = Unit
        override suspend fun get(eventId: String): ServerEventLoadResult = error("not used")
        override suspend fun decide(eventId: String, decision: ServerEventDecision): ServerEventDecisionResult = error("not used")
        override suspend fun page(readState: ServerEventReadState, limit: Int, cursor: String?): ServerEventPageResult = error("not used")
        override suspend fun markRead(eventId: String): ServerEventReadResult {
            calls += "read:$eventId"
            readIds += eventId
            return ServerEventReadResult.Recorded(event(eventId).copy(readState = ServerEventReadState.READ))
        }
        override suspend fun markConversationRead(threadId: String): ServerEventConversationReadResult {
            batchThreadIds += threadId
            return ServerEventConversationReadResult.Recorded(threadId, "2026-10-16T13:00:00.000Z", 2)
        }
    }

    private companion object {
        fun event(id: String) = ServerEvent(
            id = id,
            kind = ServerEventKind.ReplyCompleted,
            rawKind = "reply_completed",
            source = "chat",
            threadId = "conversation-a",
            safeSummary = "done",
            status = "active",
            createdAt = "2026-10-16T13:00:00.000Z",
            expiresAt = null,
            decidedAt = null,
        )
    }
}
