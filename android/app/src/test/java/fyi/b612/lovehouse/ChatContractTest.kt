package fyi.b612.lovehouse

import fyi.b612.lovehouse.feature.chat.ChatListState
import fyi.b612.lovehouse.feature.chat.ChatMessageKind
import fyi.b612.lovehouse.feature.chat.ChatSessionStore
import fyi.b612.lovehouse.feature.chat.ChatThreadKind
import fyi.b612.lovehouse.feature.chat.MockChatRepository
import fyi.b612.lovehouse.feature.chat.LocalChatDeliveryStatus
import fyi.b612.lovehouse.feature.chat.LocalChatMessage
import fyi.b612.lovehouse.feature.chat.LocalChatMessageRepository
import fyi.b612.lovehouse.feature.chat.LocalChatRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlinx.coroutines.runBlocking

class ChatContractTest {
    private class DurableLocalMessages : LocalChatMessageRepository {
        private val rows = linkedMapOf<String, LocalChatMessage>()

        override fun messages(threadId: String): List<LocalChatMessage> =
            rows.values.filter { it.threadId == threadId }.sortedBy { it.createdAtEpochMillis }

        override fun upsert(message: LocalChatMessage) {
            rows[message.localMessageId] = message
        }
    }

    @Test
    fun `codex messages use one fixed LoveHouse thread and real runtime evidence`() = runBlocking {
        val observedThreads = mutableListOf<String>()
        val client = object : fyi.b612.lovehouse.feature.chat.CodexChatClient {
            override suspend fun streamMessage(threadId: String, message: String, onText: (String) -> Unit): fyi.b612.lovehouse.feature.chat.CodexChatResult {
                observedThreads += threadId
                onText("reply to $message")
                return fyi.b612.lovehouse.feature.chat.CodexChatResult(
                    "reply to $message",
                    fyi.b612.lovehouse.feature.chat.CodexRuntimeEvidence("codex_cli", "codex-cli-v1", threadId),
                )
            }
        }
        val store = ChatSessionStore(client)

        assertTrue(store.sendCodexMessage("agent-codex", "turn one") {}.isSuccess)
        assertTrue(store.sendCodexMessage("agent-codex", "turn two") {}.isSuccess)

        assertEquals(2, observedThreads.size)
        assertEquals(observedThreads.first(), observedThreads.last())
        assertTrue(store.messages("agent-codex").any { it.body == "reply to turn two" })
    }

    @Test
    fun `real codex messages rehydrate as one canonical record per message`() = runBlocking {
        val repository = DurableLocalMessages()
        val observedThreads = mutableListOf<String>()
        var clock = 1_000L
        val client = object : fyi.b612.lovehouse.feature.chat.CodexChatClient {
            override suspend fun streamMessage(threadId: String, message: String, onText: (String) -> Unit): fyi.b612.lovehouse.feature.chat.CodexChatResult {
                observedThreads += threadId
                onText("first segment")
                onText("first segment\n\nfinal segment for $message")
                return fyi.b612.lovehouse.feature.chat.CodexChatResult(
                    "first segment\n\nfinal segment for $message",
                    fyi.b612.lovehouse.feature.chat.CodexRuntimeEvidence("codex_cli", "codex-cli-v1", threadId),
                )
            }
        }
        val firstStore = ChatSessionStore(client, repository) { clock++ }

        assertTrue(firstStore.sendCodexMessage("agent-codex", "turn one") {}.isSuccess)
        assertTrue(firstStore.sendCodexMessage("agent-codex", "turn two") {}.isSuccess)

        val persisted = repository.messages(observedThreads.singleDistinct())
        assertEquals(4, persisted.size)
        assertEquals(listOf(LocalChatRole.User, LocalChatRole.Assistant, LocalChatRole.User, LocalChatRole.Assistant), persisted.map { it.role })
        assertEquals(2, persisted.count { it.role == LocalChatRole.Assistant })
        assertTrue(persisted.all { it.status == LocalChatDeliveryStatus.Sent })
        assertTrue(persisted.filter { it.role == LocalChatRole.User }.all { it.runtime == null && it.adapterId == null })
        assertTrue(persisted.filter { it.role == LocalChatRole.Assistant }.all { it.runtime == "codex_cli" && it.adapterId == "codex-cli-v1" })

        val reopenedStore = ChatSessionStore(client, repository) { clock++ }
        assertEquals(4, reopenedStore.messages("agent-codex").size)
        assertEquals(2, reopenedStore.messages("agent-codex").count { it.body.contains("first segment") })
        assertTrue(reopenedStore.sendCodexMessage("agent-codex", "turn three") {}.isSuccess)
        assertEquals(6, repository.messages(observedThreads.singleDistinct()).size)
        assertEquals(1, observedThreads.distinct().size)
    }

    private fun <T> List<T>.singleDistinct(): T = distinct().single()
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
