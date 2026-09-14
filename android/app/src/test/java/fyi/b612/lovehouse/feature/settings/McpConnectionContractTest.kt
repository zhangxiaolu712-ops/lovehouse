package fyi.b612.lovehouse.feature.settings

import fyi.b612.lovehouse.core.navigation.AppDestination
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class McpConnectionContractTest {
    @Test
    fun `app backend endpoints stay separate from private bridge`() {
        val base = "https://app.b612.fyi/"

        assertEquals("https://app.b612.fyi/api/mcp/connections", appBackendMcpEndpoint(base, "connections"))
        assertEquals("https://app.b612.fyi/api/mcp/registry", appBackendMcpEndpoint(base, "/registry"))
        assertEquals(
            "https://app.b612.fyi/api/mcp/connections/connection%2Fpending",
            mcpConnectionEndpoint(base, "connection/pending"),
        )
    }

    @Test
    fun `delete contract maps success active and missing without url matching`() {
        assertEquals(McpConnectionDeleteResult.Deleted, mcpDeleteResult(200, null))
        assertEquals(McpConnectionDeleteResult.Active, mcpDeleteResult(409, "CONNECTION_ACTIVE"))
        assertEquals(
            McpConnectionDeleteResult.AlreadyAbsent,
            mcpDeleteResult(404, "MCP_CONNECTION_NOT_FOUND"),
        )
    }

    @Test
    fun `only unfinished connection rows expose delete`() {
        assertTrue(McpBackendConnectionStatus.AuthorizationRequired.canDelete())
        assertTrue(McpBackendConnectionStatus.Connecting.canDelete())
        assertTrue(McpBackendConnectionStatus.Failed.canDelete())
        assertTrue(McpBackendConnectionStatus.Abandoned.canDelete())
        assertFalse(McpBackendConnectionStatus.Connected.canDelete())
        assertFalse(McpBackendConnectionStatus.Unknown.canDelete())
    }

    @Test
    fun `delete outcomes refresh safely and keep active error truthful`() {
        val deleted = McpConnectionDeleteResult.Deleted.uiOutcome()
        val missing = McpConnectionDeleteResult.AlreadyAbsent.uiOutcome()
        val active = McpConnectionDeleteResult.Active.uiOutcome()

        assertEquals("未完成的 MCP connection 已删除", deleted.message)
        assertEquals("该连接已不存在，列表已刷新", missing.message)
        assertEquals("已连接的 MCP 不能在这里删除", active.message)
        assertTrue(deleted.refreshConnectionsAndRegistry)
        assertTrue(missing.refreshConnectionsAndRegistry)
        assertTrue(active.refreshConnectionsAndRegistry)
    }

    @Test
    fun `create body uses server url and preserves opaque path and query`() {
        val endpoint = "https://memory.example/actors/owner/custom-path?transport=streamable-http&space=shared"

        assertEquals(endpoint, opaqueMcpServerEndpoint(endpoint))
        assertEquals(mapOf("server_url" to endpoint), createMcpConnectionFields(endpoint))
        assertFalse(createMcpConnectionFields(endpoint).containsKey("url"))
        assertEquals(null, opaqueMcpServerEndpoint("   "))
    }

    @Test
    fun `oauth callback deep link carries backend connection result`() {
        assertEquals(
            "lovehouse://lab/tool-center?connection_id={connectionId}&status={status}",
            AppDestination.McpOAuthCallback.deepLink,
        )
        assertTrue(AppDestination.McpOAuthCallback.route.startsWith("settings/mcp/oauth"))
    }

    @Test
    fun `oauth browser boundary rejects non-https urls`() {
        assertTrue(isSafeMcpAuthorizationUrl("https://provider.example/oauth/authorize"))
        assertFalse(isSafeMcpAuthorizationUrl("http://provider.example/oauth/authorize"))
        assertFalse(isSafeMcpAuthorizationUrl("javascript:alert(1)"))
    }

    @Test
    fun `authorization required opens browser while connected refreshes status`() {
        val authorizationRequired = McpConnectionStart(
            connectionId = "connection-1",
            status = McpBackendConnectionStatus.AuthorizationRequired,
            authorizationUrl = "https://provider.example/oauth/authorize",
        )
        val connected = authorizationRequired.copy(
            status = McpBackendConnectionStatus.Connected,
            authorizationUrl = null,
        )

        assertEquals(McpConnectionNextAction.OpenAuthorization, authorizationRequired.nextAction())
        assertEquals(McpConnectionNextAction.RefreshStatus, connected.nextAction())
    }

    @Test
    fun `registry metadata wins without duplicating a connection`() {
        val connection = McpBackendConnection(
            id = "connection-1",
            serverUrl = "https://memory.example/mcp",
            name = null,
            description = null,
            status = McpBackendConnectionStatus.Connected,
            toolCount = 0,
        )
        val registered = connection.copy(name = "LoveHouse Memory", toolCount = 7)

        val merged = mergeConnectionSources(listOf(connection), listOf(registered))

        assertEquals(1, merged.size)
        assertEquals("LoveHouse Memory", merged.single().name)
        assertEquals(7, merged.single().toolCount)
    }
}
