package fyi.b612.lovehouse

import fyi.b612.lovehouse.feature.chat.ChatListState
import fyi.b612.lovehouse.feature.chat.ChatMessageKind
import fyi.b612.lovehouse.feature.chat.ChatSessionStore
import fyi.b612.lovehouse.feature.chat.ChatThreadKind
import fyi.b612.lovehouse.feature.chat.MockChatRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatContractTest {
    @Test
    fun `chat list carries every planned conversation kind`() {
        val threads = MockChatRepository.mockThreads

        assertEquals(ChatThreadKind.entries.toSet(), threads.map { it.kind }.toSet())
        assertTrue(threads.first { it.kind == ChatThreadKind.LivingRoom }.pinned)
        assertNotNull(threads.first { it.kind == ChatThreadKind.TemporaryTask }.taskId)
        assertTrue(threads.first { it.kind == ChatThreadKind.TemporaryTask }.expiresAtLabel?.isNotBlank() == true)
    }

    @Test
    fun `mock repository exposes chat through shared list state`() {
        val state = MockChatRepository().listState.value

        assertTrue(state is ChatListState.Content)
        assertTrue((state as ChatListState.Content).threads.isNotEmpty())
    }

    @Test
    fun `window creation preserves persona and chooses only thread lifetime`() {
        val store = ChatSessionStore()
        val persona = store.personas.first()

        val long = store.createThread(persona, temporary = false)
        val temporary = store.createThread(persona, temporary = true)

        assertEquals(ChatThreadKind.Direct, long.kind)
        assertEquals(ChatThreadKind.TemporaryTask, temporary.kind)
        assertEquals(persona.avatar, long.avatarGlyph)
        assertTrue(temporary.title.startsWith(persona.name))
    }

    @Test
    fun `living room member is added only once`() {
        val store = ChatSessionStore()
        val persona = store.personas.first { candidate -> candidate.personaId == "gemini" }

        store.addMember("living-room", persona)
        store.addMember("living-room", persona)

        assertEquals(1, store.members("living-room").count { it.memberId == persona.personaId })
    }

    @Test
    fun `merged forward creates an openable chat record card`() {
        val store = ChatSessionStore()
        val sourceIds = store.messages("persona-gpt").take(2).map { it.messageId }.toSet()

        store.forward("persona-gpt", sourceIds, "living-room", merged = true)

        val forwarded = store.messages("living-room").last()
        assertEquals(ChatMessageKind.ForwardBundle, forwarded.kind)
        assertEquals(2, forwarded.forwarded.size)
    }

    @Test
    fun `workflow advance completes current node and activates the next node`() {
        val store = ChatSessionStore()
        val before = store.task("mock-running-001")!!
        val currentIndex = before.workflow.indexOfFirst { it.status.name == "Current" }

        store.advanceTask(before.taskId)

        val after = store.task(before.taskId)!!
        assertEquals("Completed", after.workflow[currentIndex].status.name)
        assertEquals("Current", after.workflow[currentIndex + 1].status.name)
        assertTrue(store.messages("task-remote-ui").any { it.workflowEventId == after.workflow[currentIndex + 1].id })
    }

    @Test
    fun `workflow forward shares one compact task card without copying logs`() {
        val store = ChatSessionStore()
        val before = store.messages("living-room").size

        store.forwardWorkflow("mock-running-001", "living-room")

        assertEquals(before + 1, store.messages("living-room").size)
        assertEquals(ChatMessageKind.Workflow, store.messages("living-room").last().kind)
        assertTrue(store.messages("living-room").last().forwarded.isEmpty())
    }
}
