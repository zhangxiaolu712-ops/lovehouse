package fyi.b612.lovehouse.feature.settings

import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class McpToolPolicyWiringTest {
    @Test
    fun `tool decisions use account connection tool scope and server issued identity`() {
        McpToolPolicyDecision.entries.forEach { decision ->
            val fields = mcpToolPolicyFields(
                McpToolPolicyDraft.accountConnectionTool(
                    connectionId = "connection-a",
                    toolId = "mcp:connection-a:shared_name",
                    decision = decision,
                ),
            )

            assertEquals("ACCOUNT_CONNECTION_TOOL", fields["scope_type"])
            assertEquals(decision.name, fields["policy"])
            assertEquals("connection-a", fields["connection_id"])
            assertEquals("mcp:connection-a:shared_name", fields["tool_id"])
            assertFalse(fields.containsKey("persona_id"))
        }
    }

    @Test
    fun `authoritative tools preserve ids schemas and isolate same names by connection`() {
        val tools = JSONArray()
            .put(JSONObject().put("tool_id", "mcp:connection-a:shared").put("tool_name", "shared")
                .put("inputSchema", JSONObject().put("type", "object").put("title", "A")))
            .put(JSONObject().put("tool_id", "mcp:connection-b:shared").put("tool_name", "shared")
                .put("inputSchema", JSONObject().put("type", "object").put("title", "B")))
            .toAuthoritativeTools()

        assertEquals(listOf("shared", "shared"), tools.map { it.name })
        assertEquals(listOf("mcp:connection-a:shared", "mcp:connection-b:shared"), tools.map { it.toolId })
        assertTrue(tools[0].inputSchema!!.contains("\"title\": \"A\""))
        assertTrue(tools[1].inputSchema!!.contains("\"title\": \"B\""))
    }

    @Test
    fun `inherited ask and explicit ask remain distinct`() {
        val inherited = McpToolPolicyState(0, emptyList())
        val explicit = McpToolPolicyState(7, listOf(record("policy-ask", "connection-a", "tool-a", McpToolPolicyDecision.ASK, 7)))

        assertNull(inherited.accountConnectionToolPolicy("connection-a", "tool-a"))
        assertEquals(
            McpToolPolicyDecision.ASK,
            explicit.accountConnectionToolPolicy("connection-a", "tool-a")!!.decision,
        )
    }

    @Test
    fun `policy list parser keeps authoritative revision and typed scope`() {
        val state = JSONObject()
            .put("policy_revision", 12)
            .put("policies", JSONArray().put(JSONObject()
                .put("id", "policy-1")
                .put("scope_type", "ACCOUNT_CONNECTION_TOOL")
                .put("policy", "ALLOW")
                .put("connection_id", "connection-a")
                .put("tool_id", "mcp:connection-a:read")
                .put("policy_revision", 12)))
            .toToolPolicyState()

        assertEquals(12L, state.policyRevision)
        assertEquals(McpToolPolicyScopeType.ACCOUNT_CONNECTION_TOOL, state.policies.single().scopeType)
        assertEquals(McpToolPolicyDecision.ALLOW, state.policies.single().decision)
    }

    @Test
    fun `deny ask and allow save then reread authoritative revision`() = runBlocking {
        McpToolPolicyDecision.entries.forEach { decision ->
            val repository = PolicyRepository()

            val state = saveAccountConnectionToolPolicy(repository, "connection-a", "tool-a", decision)

            assertEquals(1L, state.policyRevision)
            assertEquals(decision, state.accountConnectionToolPolicy("connection-a", "tool-a")!!.decision)
            assertEquals(1, repository.listReads)
        }
    }

    @Test
    fun `delete override rereads and restores inherited ask`() = runBlocking {
        val repository = PolicyRepository()
        val saved = saveAccountConnectionToolPolicy(repository, "connection-a", "tool-a", McpToolPolicyDecision.ALLOW)
        val id = saved.accountConnectionToolPolicy("connection-a", "tool-a")!!.id

        val restored = deleteAccountConnectionToolPolicy(repository, id)

        assertEquals(2, restored.policyRevision)
        assertNull(restored.accountConnectionToolPolicy("connection-a", "tool-a"))
    }

    @Test
    fun `same tool name on different connections never shares policy`() = runBlocking {
        val repository = PolicyRepository()
        saveAccountConnectionToolPolicy(repository, "connection-a", "mcp:connection-a:shared", McpToolPolicyDecision.DENY)
        val state = saveAccountConnectionToolPolicy(repository, "connection-b", "mcp:connection-b:shared", McpToolPolicyDecision.ALLOW)

        assertEquals(McpToolPolicyDecision.DENY, state.accountConnectionToolPolicy("connection-a", "mcp:connection-a:shared")!!.decision)
        assertEquals(McpToolPolicyDecision.ALLOW, state.accountConnectionToolPolicy("connection-b", "mcp:connection-b:shared")!!.decision)
    }

    @Test
    fun `network failure cannot become a successful local policy state`() {
        val repository = PolicyRepository(failMutation = true)

        assertThrows(IllegalStateException::class.java) {
            runBlocking {
                saveAccountConnectionToolPolicy(repository, "connection-a", "tool-a", McpToolPolicyDecision.DENY)
            }
        }
        assertEquals(0, repository.listReads)
        assertTrue(repository.records.isEmpty())
    }

    @Test
    fun `service and connection metadata stay on separate request contracts`() {
        assertEquals(
            mapOf("display_name" to "Memory Service", "note" to "service note"),
            mcpToolServiceUpdateFields(McpToolServiceUpdate("Memory Service", "service note")),
        )
        assertEquals(
            mapOf("display_name" to "Owner Account", "note" to "connection note"),
            mcpConnectionUpdateFields(McpConnectionUpdate("Owner Account", "connection note", updateMetadata = true)),
        )
        assertEquals(
            mapOf("enabled" to false),
            mcpConnectionUpdateFields(McpConnectionUpdate(enabled = false)),
        )
    }

    @Test
    fun `tool permission labels map exactly to backend decisions`() {
        assertEquals(McpToolPolicyDecision.DENY, ToolPermission.Deny.toPolicyDecision())
        assertEquals(McpToolPolicyDecision.ASK, ToolPermission.Ask.toPolicyDecision())
        assertEquals(McpToolPolicyDecision.ALLOW, ToolPermission.Allow.toPolicyDecision())
        assertEquals(ToolPermission.Deny, McpToolPolicyDecision.DENY.toToolPermission())
        assertEquals(ToolPermission.Ask, McpToolPolicyDecision.ASK.toToolPermission())
        assertEquals(ToolPermission.Allow, McpToolPolicyDecision.ALLOW.toToolPermission())
    }

    private fun record(
        id: String,
        connectionId: String,
        toolId: String,
        decision: McpToolPolicyDecision,
        revision: Long,
    ) = McpToolPolicyRecord(
        id = id,
        scopeType = McpToolPolicyScopeType.ACCOUNT_CONNECTION_TOOL,
        decision = decision,
        policyRevision = revision,
        connectionId = connectionId,
        toolId = toolId,
    )

    private class PolicyRepository(
        private val failMutation: Boolean = false,
    ) : McpConnectionRepository {
        val records = linkedMapOf<String, McpToolPolicyRecord>()
        var revision = 0L
        var listReads = 0
        private var nextId = 1

        override suspend fun toolPolicies(): McpToolPolicyState {
            listReads += 1
            return McpToolPolicyState(revision, records.values.toList())
        }

        override suspend fun putToolPolicy(draft: McpToolPolicyDraft): McpToolPolicyMutation {
            if (failMutation) error("network unavailable")
            revision += 1
            val existing = records.values.firstOrNull {
                it.scopeType == draft.scopeType && it.personaId == draft.personaId &&
                    it.connectionId == draft.connectionId && it.toolId == draft.toolId
            }
            val value = McpToolPolicyRecord(
                id = existing?.id ?: "policy-${nextId++}",
                scopeType = draft.scopeType,
                decision = draft.decision,
                policyRevision = revision,
                personaId = draft.personaId,
                connectionId = draft.connectionId,
                toolId = draft.toolId,
            )
            records[value.id] = value
            return McpToolPolicyMutation(revision, value)
        }

        override suspend fun deleteToolPolicy(policyId: String): McpToolPolicyMutation {
            val removed = records.remove(policyId) ?: error("missing policy")
            revision += 1
            return McpToolPolicyMutation(revision, removed.copy(policyRevision = revision))
        }

        override suspend fun connections() = emptyList<McpBackendConnection>()
        override suspend fun connection(id: String) = error("unused")
        override suspend fun registry() = emptyList<McpBackendConnection>()
        override suspend fun connect(serverUrl: String) = error("unused")
        override suspend fun delete(connectionId: String) = error("unused")
        override suspend fun bindIdentity(toolServiceId: String, identityId: String, connectionId: String) = Unit
        override suspend fun unbindIdentity(toolServiceId: String, identityId: String) = Unit
    }
}
