package fyi.b612.lovehouse

import fyi.b612.lovehouse.feature.chat.ChatAttachment
import fyi.b612.lovehouse.feature.chat.ChatMessageIdentity
import fyi.b612.lovehouse.feature.chat.ChatSessionStore
import fyi.b612.lovehouse.feature.chat.CodexChatClient
import fyi.b612.lovehouse.feature.chat.CodexChatResult
import fyi.b612.lovehouse.feature.chat.CodexRuntimeEvidence
import fyi.b612.lovehouse.feature.chat.LocalChatMessage
import fyi.b612.lovehouse.feature.chat.LocalChatMessageRepository
import fyi.b612.lovehouse.feature.chat.buildChatPayload
import fyi.b612.lovehouse.feature.chat.isMessageId
import fyi.b612.lovehouse.feature.chat.newMessageId
import fyi.b612.lovehouse.feature.chat.ClaudeRuntime
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageIdentityTest {
    @Test
    fun `UUIDv7 is generated offline with the requested timestamp`() {
        val timestamp = 1_760_313_600_123L
        val id = newMessageId(timestamp, ByteArray(16) { index -> index.toByte() })

        assertTrue(isMessageId(id))
        assertEquals(7, UUID.fromString(id).version())
        assertEquals(timestamp, uuidV7Timestamp(id))
    }

    @Test
    fun `new user and assistant records receive distinct UUIDv7 identities`() = runBlocking {
        val repository = RecordingMessages()
        val client = object : CodexChatClient {
            override suspend fun streamMessage(
                threadId: String,
                message: String,
                requestedToolIds: Set<String>,
                attachments: List<ChatAttachment>,
                onText: (String) -> Unit,
            ): CodexChatResult = CodexChatResult(
                text = "reply",
                evidence = CodexRuntimeEvidence("claude_cli", "claude-cli-v1", threadId),
            )
        }

        assertTrue(ChatSessionStore(client, repository).sendClaudeMessage("hello") {}.isSuccess)
        val messages = repository.messages(ClaudeRuntime.threadId)

        assertEquals(2, messages.size)
        assertTrue(messages.all { isMessageId(it.messageId) })
        assertNotEquals(messages[0].messageId, messages[1].messageId)
        assertTrue(messages[0].localMessageId.startsWith("user:"))
        assertTrue(messages[1].localMessageId.startsWith("assistant:"))
    }

    @Test
    fun `chat payload carries both canonical message identities without replacing legacy execution id`() {
        val identity = ChatMessageIdentity(
            userMessageId = newMessageId(1_760_313_600_001L, ByteArray(16) { 1 }),
            assistantMessageId = newMessageId(1_760_313_600_002L, ByteArray(16) { 2 }),
        )

        val payload = buildChatPayload(
            config = ClaudeRuntime,
            message = "hello",
            allowedToolIds = emptySet(),
            executionId = "11111111-1111-4111-8111-111111111111",
            messageIdentity = identity,
        )

        assertTrue(payload.contains("\"user_message_id\":\"${identity.userMessageId}\""))
        assertTrue(payload.contains("\"assistant_message_id\":\"${identity.assistantMessageId}\""))
        assertTrue(payload.contains("\"execution_id\":\"11111111-1111-4111-8111-111111111111\""))
    }

    private fun uuidV7Timestamp(value: String): Long =
        (UUID.fromString(value).mostSignificantBits ushr 16) and 0x0000_FFFF_FFFF_FFFFL

    private class RecordingMessages : LocalChatMessageRepository {
        private val rows = linkedMapOf<String, LocalChatMessage>()
        override fun messages(threadId: String): List<LocalChatMessage> =
            rows.values.filter { it.threadId == threadId }.sortedBy(LocalChatMessage::createdAtEpochMillis)
        override fun upsert(message: LocalChatMessage) {
            rows[message.localMessageId] = message
        }
    }
}
