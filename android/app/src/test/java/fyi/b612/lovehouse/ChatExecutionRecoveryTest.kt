package fyi.b612.lovehouse

import fyi.b612.lovehouse.feature.chat.ChatExecutionRemoteSnapshot
import fyi.b612.lovehouse.feature.chat.ChatExecutionRemoteStatus
import fyi.b612.lovehouse.feature.chat.ChatSessionStore
import fyi.b612.lovehouse.feature.chat.CodexChatClient
import fyi.b612.lovehouse.feature.chat.LocalChatDeliveryStatus
import fyi.b612.lovehouse.feature.chat.LocalChatExecution
import fyi.b612.lovehouse.feature.chat.LocalChatExecutionStatus
import fyi.b612.lovehouse.feature.chat.LocalChatMessage
import fyi.b612.lovehouse.feature.chat.LocalChatMessageRepository
import fyi.b612.lovehouse.feature.chat.LocalChatRole
import fyi.b612.lovehouse.feature.chat.buildChatPayload
import fyi.b612.lovehouse.feature.chat.ClaudeRuntime
import fyi.b612.lovehouse.feature.chat.chatIdentityCookie
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatExecutionRecoveryTest {
    private class ExecutionRepository(
        message: LocalChatMessage,
        execution: LocalChatExecution,
    ) : LocalChatMessageRepository {
        private val messages = linkedMapOf(message.localMessageId to message)
        private val executions = linkedMapOf(execution.executionId to execution)

        override fun messages(threadId: String): List<LocalChatMessage> =
            messages.values.filter { it.threadId == threadId }.sortedBy { it.createdAtEpochMillis }

        override fun upsert(message: LocalChatMessage) {
            messages[message.localMessageId] = message
        }

        override fun localThreadIdForCanonicalThread(canonicalThreadId: String): String? =
            executions.values
                .filter { it.canonicalThreadId == canonicalThreadId }
                .maxByOrNull { it.updatedAtEpochMillis }
                ?.localThreadId

        override fun pendingExecutions(): List<LocalChatExecution> =
            executions.values.filter { it.status == LocalChatExecutionStatus.Running }

        override fun upsertExecution(execution: LocalChatExecution) {
            executions[execution.executionId] = execution
        }
    }

    @Test
    fun `event canonical thread resolves through persisted execution to local chat route`() {
        val canonicalThreadId = "7c814f9a-7588-4e35-b4b6-a216f172c012"
        val localThreadId = "agent-codex"
        val executionId = "33333333-3333-4333-8333-333333333333"
        val repository = ExecutionRepository(
            message = LocalChatMessage(
                localMessageId = "user:$executionId",
                threadId = localThreadId,
                role = LocalChatRole.User,
                sender = "owner",
                content = "message",
                createdAtEpochMillis = 10L,
                status = LocalChatDeliveryStatus.Sent,
            ),
            execution = LocalChatExecution(
                executionId = executionId,
                localThreadId = localThreadId,
                provider = "codex",
                canonicalThreadId = canonicalThreadId,
                userMessageId = "user:$executionId",
                assistantMessageId = "assistant:$executionId",
                status = LocalChatExecutionStatus.Completed,
                createdAtEpochMillis = 10L,
                updatedAtEpochMillis = 20L,
            ),
        )
        val store = ChatSessionStore(messageRepository = repository)

        val route = fyi.b612.lovehouse.core.navigation.eventChatRoute(
            canonicalThreadId = canonicalThreadId,
            resolveLocalThreadId = store::localThreadIdForCanonicalThread,
            encodeRouteSegment = { it },
        )

        assertTrue(localThreadId != canonicalThreadId)
        assertEquals("chat/thread/agent-codex", route)
        assertEquals(null, store.localThreadIdForCanonicalThread("missing-canonical-thread"))
        assertEquals(localThreadId, store.localThreadIdForCanonicalThread(localThreadId))
        assertEquals(null, store.thread(canonicalThreadId))
    }

    @Test
    fun `recoverable payload carries execution id without changing legacy payload`() {
        val legacy = buildChatPayload(ClaudeRuntime, "hello", emptySet())
        val recoverable = buildChatPayload(
            ClaudeRuntime,
            "hello",
            emptySet(),
            executionId = "11111111-1111-4111-8111-111111111111",
        )

        assertFalse(legacy.contains("execution_id"))
        assertTrue(recoverable.contains("\"execution_id\":\"11111111-1111-4111-8111-111111111111\""))
    }

    @Test
    fun `App Account session is a recoverable transport header and never Chat payload data`() {
        val credential = "lovehouse_app_session=fake-session-secret"
        val executionId = "11111111-1111-4111-8111-111111111111"
        val payload = buildChatPayload(
            ClaudeRuntime,
            "hello",
            emptySet(),
            executionId = executionId,
        )

        assertEquals(credential, chatIdentityCookie(credential, executionId))
        assertEquals(null, chatIdentityCookie(credential, null))
        assertEquals(null, chatIdentityCookie(null, executionId))
        assertFalse(payload.contains(credential))
        assertFalse(payload.contains("app_account_id"))
    }

    @Test
    fun `completed execution recovery writes one stable assistant message even when recovery repeats`() = runBlocking {
        val executionId = "11111111-1111-4111-8111-111111111111"
        val userId = "user:$executionId"
        val assistantId = "assistant:$executionId"
        val execution = LocalChatExecution(
            executionId = executionId,
            localThreadId = ClaudeRuntime.threadId,
            provider = "claude",
            canonicalThreadId = ClaudeRuntime.threadId,
            userMessageId = userId,
            assistantMessageId = assistantId,
            status = LocalChatExecutionStatus.Running,
            createdAtEpochMillis = 10L,
            updatedAtEpochMillis = 10L,
        )
        val repository = ExecutionRepository(
            LocalChatMessage(
                localMessageId = userId,
                threadId = ClaudeRuntime.threadId,
                role = LocalChatRole.User,
                sender = "owner",
                content = "long request",
                createdAtEpochMillis = 10L,
                status = LocalChatDeliveryStatus.Sending,
            ),
            execution,
        )
        val client = object : CodexChatClient {
            override val supportsExecutionRecovery: Boolean = true
            override suspend fun streamMessage(
                threadId: String,
                message: String,
                requestedToolIds: Set<String>,
                attachments: List<fyi.b612.lovehouse.feature.chat.ChatAttachment>,
                onText: (String) -> Unit,
            ) = error("Recovery must not resend the user turn")

            override suspend fun chatExecution(executionId: String) = ChatExecutionRemoteSnapshot(
                executionId = executionId,
                status = ChatExecutionRemoteStatus.Completed,
                text = "final answer",
                runtime = "claude_cli",
                adapterId = "claude-cli-v1",
            )
        }
        val store = ChatSessionStore(codexClient = client, messageRepository = repository, now = { 20L })

        store.recoverPendingExecutionsOnce()
        store.recoverPendingExecutionsOnce()

        val saved = repository.messages(ClaudeRuntime.threadId)
        assertEquals(1, saved.count { it.localMessageId == assistantId })
        assertEquals("final answer", saved.single { it.localMessageId == assistantId }.content)
        assertEquals(LocalChatDeliveryStatus.Sent, saved.single { it.localMessageId == userId }.status)
        assertTrue(repository.pendingExecutions().isEmpty())
    }

    @Test
    fun `provider failure is recovered as failed without resending the turn`() = runBlocking {
        val executionId = "22222222-2222-4222-8222-222222222222"
        val execution = LocalChatExecution(
            executionId = executionId,
            localThreadId = ClaudeRuntime.threadId,
            provider = "claude",
            canonicalThreadId = ClaudeRuntime.threadId,
            userMessageId = "user:$executionId",
            assistantMessageId = "assistant:$executionId",
            status = LocalChatExecutionStatus.Running,
            createdAtEpochMillis = 10L,
            updatedAtEpochMillis = 10L,
        )
        val repository = ExecutionRepository(
            LocalChatMessage(
                localMessageId = execution.userMessageId,
                threadId = ClaudeRuntime.threadId,
                role = LocalChatRole.User,
                sender = "owner",
                content = "long request",
                createdAtEpochMillis = 10L,
                status = LocalChatDeliveryStatus.Sent,
            ),
            execution,
        )
        val client = object : CodexChatClient {
            override val supportsExecutionRecovery: Boolean = true
            override suspend fun streamMessage(
                threadId: String,
                message: String,
                requestedToolIds: Set<String>,
                attachments: List<fyi.b612.lovehouse.feature.chat.ChatAttachment>,
                onText: (String) -> Unit,
            ) = error("Recovery must not resend the user turn")

            override suspend fun chatExecution(executionId: String) = ChatExecutionRemoteSnapshot(
                executionId = executionId,
                status = ChatExecutionRemoteStatus.Failed,
                errorCode = "PROVIDER_FAILED",
                errorMessage = "Provider failed",
            )
        }

        ChatSessionStore(codexClient = client, messageRepository = repository, now = { 20L })
            .recoverPendingExecutionsOnce()

        assertEquals(
            LocalChatDeliveryStatus.Failed,
            repository.messages(ClaudeRuntime.threadId).single { it.localMessageId == execution.userMessageId }.status,
        )
        assertTrue(repository.pendingExecutions().isEmpty())
    }
}
