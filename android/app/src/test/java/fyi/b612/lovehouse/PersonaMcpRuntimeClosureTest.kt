package fyi.b612.lovehouse

import fyi.b612.lovehouse.feature.chat.ChatPersona
import fyi.b612.lovehouse.feature.chat.ChatSessionStore
import fyi.b612.lovehouse.feature.chat.ChatThreadKind
import fyi.b612.lovehouse.feature.chat.ChatThreadSummary
import fyi.b612.lovehouse.feature.chat.ClaudeRuntime
import fyi.b612.lovehouse.feature.chat.CodexRuntime
import fyi.b612.lovehouse.feature.chat.InMemoryConversationPersonaStore
import fyi.b612.lovehouse.feature.chat.PersonaRuntimeSource
import fyi.b612.lovehouse.feature.chat.PersonaRuntimeSnapshot
import fyi.b612.lovehouse.feature.chat.PersonaProfile
import fyi.b612.lovehouse.feature.chat.PersonaRuntimeException
import fyi.b612.lovehouse.feature.chat.PersonaRuntimeFailure
import fyi.b612.lovehouse.feature.chat.personaRuntimeHttpFailure
import fyi.b612.lovehouse.feature.chat.personaRuntimeIoFailure
import fyi.b612.lovehouse.feature.chat.CodexChatClient
import fyi.b612.lovehouse.feature.chat.CodexChatResult
import fyi.b612.lovehouse.feature.chat.CodexRuntimeEvidence
import fyi.b612.lovehouse.feature.chat.ChatAttachment
import fyi.b612.lovehouse.feature.chat.ChatProcessEvent
import fyi.b612.lovehouse.feature.chat.buildChatPayload
import fyi.b612.lovehouse.feature.chat.resolveRequestedToolIds
import fyi.b612.lovehouse.feature.settings.AppEffectiveToolResolver
import fyi.b612.lovehouse.feature.settings.LocalToolProfile
import fyi.b612.lovehouse.feature.settings.McpBackendConnection
import fyi.b612.lovehouse.feature.settings.McpBackendConnectionStatus
import fyi.b612.lovehouse.feature.settings.McpConnectionDeleteResult
import fyi.b612.lovehouse.feature.settings.McpConnectionRepository
import fyi.b612.lovehouse.feature.settings.McpConnectionStart
import fyi.b612.lovehouse.feature.settings.McpDiscoveredTool
import fyi.b612.lovehouse.feature.settings.ToolAvailability
import fyi.b612.lovehouse.feature.settings.ToolCapability
import fyi.b612.lovehouse.feature.settings.ToolCapabilityKind
import fyi.b612.lovehouse.feature.settings.ToolCenterRepository
import fyi.b612.lovehouse.feature.settings.ToolProfilePreferenceStore
import fyi.b612.lovehouse.feature.settings.ToolRiskLevel
import fyi.b612.lovehouse.feature.settings.ToolTestResult
import kotlinx.coroutines.runBlocking
import java.io.IOException
import java.net.SocketTimeoutException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersonaMcpRuntimeClosureTest {
    @Test
    fun `reanchor is carried only on a real turn and survives failed send`() = runBlocking {
        val persistence = InMemoryConversationPersonaStore()
        val snapshots = mutableListOf<PersonaRuntimeSnapshot?>()
        var fail = true
        val client = object : CodexChatClient {
            override suspend fun streamMessage(threadId: String, message: String, requestedToolIds: Set<String>, attachments: List<ChatAttachment>, onText: (String) -> Unit): CodexChatResult = error("unused")
            override suspend fun streamRuntimeMessageWithPersona(config: fyi.b612.lovehouse.feature.chat.ChatRuntimeConfig,
                message: String, requestedToolIds: Set<String>, attachments: List<ChatAttachment>,
                personaRuntime: PersonaRuntimeSnapshot?, onText: (String) -> Unit,
                onProcess: (ChatProcessEvent) -> Unit): CodexChatResult {
                snapshots += personaRuntime
                if (fail) error("temporary failure")
                onText("real reply")
                return CodexChatResult("real reply", CodexRuntimeEvidence("claude_cli", "claude-cli-v1", config.threadId))
            }
        }
        val source = object : PersonaRuntimeSource {
            override suspend fun resolve(personaId: String, requestedToolIds: Set<String>, reanchorIntent: Boolean) =
                PersonaRuntimeSnapshot(personaId, 2, "real prompt", "real background", emptySet(), null, reanchorIntent)
        }
        val store = ChatSessionStore(codexClient = client, conversationPersonas = persistence, personaRuntimeSource = source)
        store.requestReanchor(ClaudeRuntime.threadId)
        assertTrue(store.reanchorPending(ClaudeRuntime.threadId))
        assertTrue(store.sendClaudeMessage("first") {}.isFailure)
        assertTrue(store.reanchorPending(ClaudeRuntime.threadId))
        fail = false
        assertTrue(store.sendClaudeMessage("second") {}.isSuccess)
        assertFalse(store.reanchorPending(ClaudeRuntime.threadId))
        assertEquals(2, persistence.materializedPersonaVersion(ClaudeRuntime.threadId))
        assertTrue(snapshots.all { it?.reanchorIntent == true })
        assertTrue(store.sendClaudeMessage("third") {}.isSuccess)
        assertFalse(snapshots.last()?.reanchorIntent ?: true)
        assertFalse(store.messages(ClaudeRuntime.threadId).any { it.body.contains("real prompt") })
    }

    @Test
    fun `payload carries Persona configuration outside message body with scoped ticket`() {
        val snapshot = PersonaRuntimeSnapshot("claude", 4, "guidance", "background", setOf("connection-a"), "opaque-ticket", true)
        val payload = buildChatPayload(ClaudeRuntime, "hello", emptySet(), personaRuntime = snapshot)
        assertTrue(payload.contains("\"persona_version\":4"))
        assertTrue(payload.contains("\"connection_ids\":[\"connection-a\"]"))
        assertTrue(payload.contains("\"execution_ticket\":\"opaque-ticket\""))
        assertFalse(payload.contains("allowed_tool_ids"))
        assertTrue(payload.contains("\"message\":{\"type\":\"text\",\"text\":\"hello\""))
        assertFalse(payload.contains("\"text\":\"guidance\""))
        assertFalse(snapshot.toString().contains("opaque-ticket"))
    }
    @Test
    fun `new conversation saves authoritative persona and store recreation restores it`() = runBlocking {
        val persistence = InMemoryConversationPersonaStore()
        val source = RecordingPersonaRuntimeSource(mutableListOf(profile("claude", "Claude")))
        val first = ChatSessionStore(
            conversationPersonas = persistence,
            personaRuntimeSource = source,
            initialThreads = emptyList(),
        )
        first.refreshPersonaProfiles()
        val created = first.createThread(first.personas.first { it.personaId == "claude" }, temporary = false)

        assertEquals("claude", persistence.personaId(created.threadId))
        assertEquals("claude", created.personaId)

        val reopened = ChatSessionStore(
            conversationPersonas = persistence,
            personaRuntimeSource = source,
            initialThreads = emptyList(),
        )
        reopened.refreshPersonaProfiles()
        assertEquals("claude", reopened.thread(created.threadId)?.personaId)
        assertEquals("Claude", reopened.persona(created.threadId)?.name)
    }

    @Test
    fun `conversation detail persona change updates the persisted truth source`() = runBlocking {
        val persistence = InMemoryConversationPersonaStore()
        val source = RecordingPersonaRuntimeSource(mutableListOf(
            profile("codex", "Codex"),
            profile("claude", "Claude"),
        ))
        val store = ChatSessionStore(
            conversationPersonas = persistence,
            personaRuntimeSource = source,
            initialThreads = listOf(thread("conversation-1", "codex")),
        )
        store.refreshPersonaProfiles()
        val claude = ChatPersona("claude", "Claude", "C", "claude_private")

        store.setPersona("conversation-1", claude)

        assertEquals("claude", persistence.personaId("conversation-1"))
        assertEquals("claude", store.thread("conversation-1")?.personaId)
        assertEquals("Claude", store.persona("conversation-1")?.name)
    }

    @Test
    fun `legacy fixed Claude thread is migrated into persisted persona id`() {
        val persistence = InMemoryConversationPersonaStore()
        val legacy = thread(ClaudeRuntime.threadId, personaId = null)

        val store = ChatSessionStore(
            conversationPersonas = persistence,
            initialThreads = listOf(legacy),
        )

        assertEquals("claude", store.thread(ClaudeRuntime.threadId)?.personaId)
        assertEquals("claude", persistence.personaId(ClaudeRuntime.threadId))
    }

    @Test
    fun `legacy fixed conversation materializes one real Persona Profile without overwriting it`() = runBlocking {
        val persistence = InMemoryConversationPersonaStore()
        val source = RecordingPersonaRuntimeSource()
        val store = ChatSessionStore(
            conversationPersonas = persistence,
            personaRuntimeSource = source,
            initialThreads = listOf(thread(ClaudeRuntime.threadId, personaId = null)),
        )

        store.refreshPersonaProfiles()
        store.refreshPersonaProfiles()

        assertEquals("claude", persistence.personaId(ClaudeRuntime.threadId))
        assertEquals(listOf("claude"), source.saved.map(PersonaProfile::personaId))
        assertEquals("Claude", source.saved.single().displayName)
        assertEquals("Claude", store.persona(ClaudeRuntime.threadId)?.name)
    }

    @Test
    fun `persona selector contains only authoritative App Backend profiles`() = runBlocking {
        val source = RecordingPersonaRuntimeSource(mutableListOf(profile("persona-real", "真实 Persona")))
        val store = ChatSessionStore(personaRuntimeSource = source, initialThreads = emptyList())

        store.refreshPersonaProfiles()

        assertEquals(listOf("persona-real"), store.personas.map(ChatPersona::personaId))
    }

    @Test
    fun `persona binding resolves complete MCP connections without per tool ownership`() = runBlocking {
        val mcp = FakeMcpRepository(
            listOf(
                connection("connection-a", "claude", "recall"),
                connection("connection-b", "claude", "recall"),
                connection("connection-c", "codex", "open"),
            ),
        )
        val resolver = AppEffectiveToolResolver(EmptyToolCenterRepository, EmptyProfiles, mcp)

        val resolved = resolver.resolve("claude", "claude-thread")

        assertEquals(
            setOf("mcp-connection:connection-a", "mcp-connection:connection-b"),
            resolved.allowedToolIds,
        )
        assertEquals(2, resolved.tools.size)
        assertEquals(resolved.allowedToolIds, resolver.cachedAllowedToolIds("claude", "claude-thread"))
        assertTrue(resolved.tools.all { it.riskLevel == ToolRiskLevel.Unknown &&
            it.capabilityKind == ToolCapabilityKind.Unknown })
        assertEquals(0, mcp.bindingWrites)
    }

    @Test
    fun `missing persona binding resolves empty tools while chat contract stays valid`() = runBlocking {
        val resolver = AppEffectiveToolResolver(
            EmptyToolCenterRepository,
            EmptyProfiles,
            FakeMcpRepository(listOf(connection("connection-a", "codex", "recall"))),
        )

        val resolved = resolver.resolve("claude", ClaudeRuntime.threadId)
        val payload = buildChatPayload(ClaudeRuntime, "普通聊天", resolved.allowedToolIds)

        assertTrue(resolved.tools.isEmpty())
        assertFalse(payload.contains("allowed_tool_ids"))
        assertTrue(payload.contains("\"persona_id\":\"claude\""))
    }

    @Test
    fun `authoritative Persona runtime failures are not converted into empty tools`() = runBlocking {
        val expected = PersonaRuntimeException(PersonaRuntimeFailure.PersonaMissing, "missing", 404)
        val resolver = AppEffectiveToolResolver(
            EmptyToolCenterRepository,
            EmptyProfiles,
            FailingAuthoritativeMcpRepository(expected),
        )

        val failure = runCatching { resolver.resolve("missing", "thread") }.exceptionOrNull()

        assertTrue(failure === expected)
    }

    @Test
    fun `Persona runtime failure classifier distinguishes missing auth timeout and network`() {
        assertEquals(PersonaRuntimeFailure.PersonaMissing, personaRuntimeHttpFailure(404, "missing").failure)
        assertEquals(PersonaRuntimeFailure.Authentication, personaRuntimeHttpFailure(401, "auth").failure)
        assertEquals(PersonaRuntimeFailure.Backend, personaRuntimeHttpFailure(503, "backend").failure)
        assertEquals(PersonaRuntimeFailure.Timeout, personaRuntimeIoFailure(SocketTimeoutException()).failure)
        assertEquals(PersonaRuntimeFailure.Network, personaRuntimeIoFailure(IOException()).failure)
    }

    @Test
    fun `MCP connection mention does not become a per tool payload allowlist`() {
        val capability = capability("mcp-connection:connection-a", "Memory")
        val requested = resolveRequestedToolIds("@Memory 找一下昨天的内容", emptySet(), listOf(capability))
        val snapshot = PersonaRuntimeSnapshot("claude", 4, "guidance", "background", setOf("connection-a"), "opaque-ticket", false)
        val payload = buildChatPayload(ClaudeRuntime, "找一下昨天的内容", emptySet(), personaRuntime = snapshot)

        assertEquals(setOf("mcp-connection:connection-a"), requested)
        assertTrue(payload.contains("\"connection_ids\":[\"connection-a\"]"))
        assertFalse(payload.contains("allowed_tool_ids"))
        assertEquals("claude", ClaudeRuntime.personaId)
        assertEquals("claude_cli", ClaudeRuntime.expectedRuntime)
    }

    @Test
    fun `Codex built in exposure remains provider neutral and unchanged`() = runBlocking {
        val builtIn = capability("builtin.engineering.read_current", "Engineering")
        val profiles = FakeProfiles(setOf(builtIn.toolId))
        val resolver = AppEffectiveToolResolver(
            object : ToolCenterRepository {
                override suspend fun capabilities() = listOf(builtIn)
                override suspend fun testTool(toolId: String) = ToolTestResult(toolId, true, "ok")
            },
            profiles,
            FakeMcpRepository(emptyList()),
        )

        val resolved = resolver.resolve("codex", CodexRuntime.threadId)

        assertEquals(setOf("builtin.engineering.read_current"), resolved.allowedToolIds)
        assertEquals(CodexRuntime.threadId, resolved.threadId)
        assertEquals("codex", resolved.personaId)
    }

    private fun thread(id: String, personaId: String?) = ChatThreadSummary(
        threadId = id,
        kind = ChatThreadKind.Agent,
        title = "Conversation",
        preview = "",
        updatedAt = "",
        personaId = personaId,
    )

    private fun connection(id: String, identity: String, tool: String) = McpBackendConnection(
        id = id,
        serverUrl = "https://example.invalid/$id",
        name = id,
        description = null,
        status = McpBackendConnectionStatus.Connected,
        toolCount = 1,
        toolServiceId = "service-$id",
        enabled = true,
        boundIdentityIds = listOf(identity),
        tools = listOf(McpDiscoveredTool(tool)),
    )

    private fun capability(id: String, label: String) = ToolCapability(
        toolId = id,
        group = id,
        groupLabel = label,
        displayName = label,
        summary = "real capability",
        availability = ToolAvailability.Available,
        detail = "",
        riskLevel = ToolRiskLevel.Low,
        capabilityKind = ToolCapabilityKind.Read,
        requiresApproval = false,
        scope = emptyList(),
    )

    private fun profile(id: String, name: String) = PersonaProfile(
        personaId = id,
        displayName = name,
        avatar = name.take(1),
        instructions = "",
        background = "",
        version = 1,
        providerNativeAnchorPreference = false,
    )
}

private class RecordingPersonaRuntimeSource(
    val saved: MutableList<PersonaProfile> = mutableListOf(),
) : PersonaRuntimeSource {

    override suspend fun resolve(
        personaId: String,
        requestedToolIds: Set<String>,
        reanchorIntent: Boolean,
    ): PersonaRuntimeSnapshot? = null

    override suspend fun profiles(): List<PersonaProfile> = saved.toList()

    override suspend fun save(profile: PersonaProfile): PersonaProfile = profile.copy(version = 1).also(saved::add)
}

private class FailingAuthoritativeMcpRepository(
    private val failure: RuntimeException,
) : McpConnectionRepository {
    override suspend fun effectiveConnections(personaId: String) = throw failure
    override suspend fun connections(): List<McpBackendConnection> = error("not used")
    override suspend fun connection(id: String): McpBackendConnection = error("not used")
    override suspend fun registry(): List<McpBackendConnection> = error("not used")
    override suspend fun connect(serverUrl: String): McpConnectionStart = error("not used")
    override suspend fun delete(connectionId: String) = error("not used")
    override suspend fun bindIdentity(toolServiceId: String, identityId: String, connectionId: String) = error("not used")
    override suspend fun unbindIdentity(toolServiceId: String, identityId: String) = error("not used")
}

private object EmptyToolCenterRepository : ToolCenterRepository {
    override suspend fun capabilities(): List<ToolCapability> = emptyList()
    override suspend fun testTool(toolId: String) = ToolTestResult(toolId, false, "not used")
}

private object EmptyProfiles : ToolProfilePreferenceStore {
    override fun profile(personaId: String, threadId: String) = LocalToolProfile(personaId, threadId, emptySet())
    override fun setPreferred(personaId: String, threadId: String, toolId: String, enabled: Boolean) = Unit
}

private class FakeProfiles(private val enabled: Set<String>) : ToolProfilePreferenceStore {
    override fun profile(personaId: String, threadId: String) = LocalToolProfile(personaId, threadId, enabled)
    override fun setPreferred(personaId: String, threadId: String, toolId: String, enabled: Boolean) = Unit
}

private class FakeMcpRepository(
    private val values: List<McpBackendConnection>,
) : McpConnectionRepository {
    var bindingWrites: Int = 0
        private set

    override suspend fun connections() = values
    override suspend fun connection(id: String) = values.first { it.id == id }
    override suspend fun registry() = values
    override suspend fun connect(serverUrl: String): McpConnectionStart = error("not used")
    override suspend fun delete(connectionId: String) = McpConnectionDeleteResult.AlreadyAbsent
    override suspend fun bindIdentity(toolServiceId: String, identityId: String, connectionId: String) {
        bindingWrites += 1
    }
    override suspend fun unbindIdentity(toolServiceId: String, identityId: String) {
        bindingWrites += 1
    }
}
