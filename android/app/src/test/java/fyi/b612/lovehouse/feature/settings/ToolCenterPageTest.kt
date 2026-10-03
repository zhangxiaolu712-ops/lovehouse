package fyi.b612.lovehouse.feature.settings

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Test

class ToolCenterPageTest {
    @Test
    fun `tool permission shows only deny ask allow`() {
        assertEquals(listOf("禁止", "每次确认", "直接允许"), ToolPermission.entries.map { it.label })
    }

    @Test
    fun `services become cards and orphans only show without services`() {
        val account = connection("c1", toolServiceId = "s1", url = "https://mcp.example.com/api/mcp")
        val secondAccount = connection("c2", toolServiceId = "s1", url = "https://mcp.example.com/api/mcp")
        val service = McpToolService("s1", "example", "示例 MCP", 1, 1)

        val grouped = buildMcpCards(
            listOf(service),
            mapOf("s1" to listOf(account, secondAccount)),
            listOf(account, secondAccount),
        )
        assertEquals(listOf("service:s1"), grouped.map { it.key })
        assertEquals("示例 MCP", grouped.single().name)
        assertEquals("mcp.example.com", grouped.single().host)
        assertEquals(listOf("c1", "c2"), grouped.single().accounts.map { it.id })

        val orphans = buildMcpCards(emptyList(), emptyMap(), listOf(account))
        assertEquals(listOf("connection:c1"), orphans.map { it.key })
    }

    @Test
    fun `card status reflects real account states`() {
        assertEquals("还没有账号" to false, mcpCardStatus(emptyList()))
        assertEquals("已连接" to true, mcpCardStatus(listOf(connection("a"))))
        assertEquals(
            "已连接 · 1/2 个账号可用" to true,
            mcpCardStatus(listOf(connection("a"), connection("b", status = McpBackendConnectionStatus.Failed))),
        )
        assertEquals("等待授权" to false, mcpCardStatus(listOf(connection("a", status = McpBackendConnectionStatus.AuthorizationRequired))))
    }

    @Test
    fun `account meta uses bound personas status and tool count`() {
        val bound = connection("a", bound = listOf("p1"), toolCount = 3)
        assertEquals("人格档案 p1 · 已连接 · 3 个工具", mcpAccountMeta(bound, emptyList()))
        val off = connection("b", enabled = false)
        assertEquals("未绑定人格档案 · 已连接 · 0 个工具 · 已关闭", mcpAccountMeta(off, emptyList()))
    }

    @Test
    fun `persona binding saves only the difference`() = runBlocking {
        val repository = RecordingRepository()
        applyPersonaBindings(repository, "s1", "c1", before = setOf("keep", "drop"), after = setOf("keep", "add"))
        assertEquals(listOf("bind s1 add c1", "unbind s1 drop"), repository.calls)
    }

    @Test
    fun `api key keeps the stored auth type and defaults to api key`() {
        assertEquals(ToolConnectionAuth.None, apiAuthFor(ToolConnectionAuth.BearerToken, ""))
        assertEquals(ToolConnectionAuth.BearerToken, apiAuthFor(ToolConnectionAuth.BearerToken, "secret"))
        assertEquals(ToolConnectionAuth.ApiKey, apiAuthFor(ToolConnectionAuth.None, "secret"))
        assertEquals(ToolConnectionAuth.ApiKey, apiAuthFor(null, "secret"))
    }

    private fun connection(
        id: String,
        toolServiceId: String? = null,
        url: String = "https://mcp.example.com",
        status: McpBackendConnectionStatus = McpBackendConnectionStatus.Connected,
        bound: List<String> = emptyList(),
        toolCount: Int = 0,
        enabled: Boolean = true,
    ) = McpBackendConnection(
        id = id,
        serverUrl = url,
        name = null,
        description = null,
        status = status,
        toolCount = toolCount,
        toolServiceId = toolServiceId,
        enabled = enabled,
        boundIdentityIds = bound,
    )

    private class RecordingRepository : McpConnectionRepository {
        val calls = mutableListOf<String>()
        override suspend fun connections(): List<McpBackendConnection> = emptyList()
        override suspend fun connection(id: String): McpBackendConnection = error("unused")
        override suspend fun registry(): List<McpBackendConnection> = emptyList()
        override suspend fun connect(serverUrl: String): McpConnectionStart = error("unused")
        override suspend fun delete(connectionId: String): McpConnectionDeleteResult = error("unused")
        override suspend fun bindIdentity(toolServiceId: String, identityId: String, connectionId: String) {
            calls += "bind $toolServiceId $identityId $connectionId"
        }
        override suspend fun unbindIdentity(toolServiceId: String, identityId: String) {
            calls += "unbind $toolServiceId $identityId"
        }
    }
}
