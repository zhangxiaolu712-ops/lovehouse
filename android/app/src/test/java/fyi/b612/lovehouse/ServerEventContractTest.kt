package fyi.b612.lovehouse

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerEventContractTest {
    private val root = File(System.getProperty("user.dir"))
    private fun source(path: String) = File(root, "src/main/java/fyi/b612/lovehouse/$path").readText()

    @Test
    fun `production repository reuses encrypted App Account cookie`() {
        val dependencies = source("app/AppDependencies.kt")
        val repository = source("feature/events/ServerEvents.kt")

        assertTrue(dependencies.contains("sessionCookie = appAccountSource::backendSessionCookie"))
        assertTrue(dependencies.contains("onAuthenticationRequired = appAccountSource::refresh"))
        assertTrue(repository.contains("setRequestProperty(\"Cookie\", cookie)"))
        assertFalse(repository.contains("Authorization"))
        assertFalse(repository.contains("OwnerSession"))
    }

    @Test
    fun `event navigation is generic and deep link carries only event id`() {
        val destinations = source("core/navigation/AppDestination.kt")
        val navigation = source("core/navigation/LoveHouseNavHost.kt")

        assertTrue(destinations.contains("EventDetail(\"events/{eventId}?openTarget={openTarget}\""))
        assertTrue(destinations.contains("\"lovehouse://event/{eventId}\""))
        assertFalse(destinations.contains("decision={decision}"))
        assertFalse(destinations.contains("action=approve"))
        assertTrue(navigation.contains("navArgument(\"eventId\")"))
    }

    @Test
    fun `reply event resolves canonical thread to its distinct local chat route`() {
        val canonicalThreadId = "7c814f9a-7588-4e35-b4b6-a216f172c012"
        val localThreadId = "agent-codex"
        val route = fyi.b612.lovehouse.core.navigation.eventChatRoute(
            canonicalThreadId = canonicalThreadId,
            resolveLocalThreadId = { requested -> localThreadId.takeIf { requested == canonicalThreadId } },
            encodeRouteSegment = { it },
        )

        assertTrue(localThreadId != canonicalThreadId)
        assertEquals("chat/thread/agent-codex", route)
    }

    @Test
    fun `reply event with no local mapping safely refuses navigation`() {
        val route = fyi.b612.lovehouse.core.navigation.eventChatRoute(
            canonicalThreadId = "missing-canonical-thread",
            resolveLocalThreadId = { null },
            encodeRouteSegment = { it },
        )

        assertNull(route)
    }

    @Test
    fun `ordinary local chat navigation remains a direct local route`() {
        val localThreadId = "agent-codex"
        val route = fyi.b612.lovehouse.core.navigation.eventChatRoute(
            canonicalThreadId = localThreadId,
            resolveLocalThreadId = { requested -> requested.takeIf { it == localThreadId } },
            encodeRouteSegment = { it },
        )

        assertEquals("chat/thread/agent-codex", route)
    }

    @Test
    fun `foreground refresh has no polling or background service`() {
        val navigation = source("core/navigation/LoveHouseNavHost.kt")
        val events = source("feature/events/ServerEvents.kt")

        assertTrue(navigation.contains("Lifecycle.Event.ON_START"))
        assertTrue(navigation.contains("dependencies.serverEvents.refresh()"))
        assertFalse(events.contains("while (true)"))
        assertFalse(events.contains("WebSocket"))
        assertFalse(events.contains("FirebaseMessaging"))
    }

    @Test
    fun `real event path is isolated from remote task mock approval`() {
        val dependencies = source("app/AppDependencies.kt")
        val navigation = source("core/navigation/LoveHouseNavHost.kt")
        val events = source("feature/events/ServerEvents.kt")

        assertTrue(dependencies.contains("AndroidServerEventRepository"))
        assertTrue(navigation.contains("ServerEventsScreen"))
        assertFalse(events.contains("RemoteTaskMocks"))
        assertFalse(events.contains("applyMockApproval"))
    }

    @Test
    fun `decision UI states that recording is not action execution`() {
        val screen = source("feature/events/ServerEventsScreen.kt")

        assertTrue(screen.contains("你的决定将记录到服务器；这不代表原操作已经执行。"))
        assertFalse(screen.contains("Claude 已执行"))
        assertFalse(screen.contains("MCP 已经执行"))
    }

    @Test
    fun `Android exposes no client create-event operation`() {
        val events = source("feature/events/ServerEvents.kt")

        assertFalse(events.contains("createEvent"))
        assertFalse(events.contains("POST\", \"/api/events\""))
        assertTrue(events.contains("/decision"))
    }

    @Test
    fun `generic naming leaves future UI extension seam without new kinds`() {
        val events = source("feature/events/ServerEvents.kt")
        val screen = source("feature/events/ServerEventsScreen.kt")

        assertTrue(events.contains("interface ServerEventRepository"))
        assertTrue(events.contains("Unknown(\"unknown\")"))
        assertFalse(events.contains("ApprovalRepository"))
        assertTrue(screen.contains("较新的动态类型"))
    }
}
