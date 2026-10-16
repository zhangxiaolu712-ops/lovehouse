package fyi.b612.lovehouse.feature.events

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ServerEventReadHttpTest {
    @Test
    fun `read state page and read mutations use authenticated server contract`() = runBlocking {
        val requests = mutableListOf<Triple<String, String, String>>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/events") { exchange ->
            val body = exchange.requestBody.bufferedReader().use { it.readText() }
            requests += Triple(exchange.requestMethod, exchange.requestURI.toString(), body)
            assertEquals("lovehouse_session=test-cookie", exchange.requestHeaders.getFirst("Cookie"))
            val response = when {
                exchange.requestURI.path.endsWith("/read-conversation") ->
                    """{"thread_id":"thread-1","cutoff_at":"2026-10-16T13:00:00.000Z","marked_read_count":2}"""
                exchange.requestURI.path.endsWith("/read") ->
                    """{"event":{"id":"event-1","kind":"reply_completed","safe_summary":"done","status":"active","created_at":"2026-10-16T13:00:00.000Z","read_state":"READ","read_at":"2026-10-16T13:00:01.000Z"}}"""
                else -> """{"events":[],"next_cursor":"next-page"}"""
            }.toByteArray(Charsets.UTF_8)
            exchange.responseHeaders.set("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, response.size.toLong())
            exchange.responseBody.use { it.write(response) }
        }
        server.start()
        try {
            val api = HttpServerEventApi("http://127.0.0.1:${server.address.port}") { "lovehouse_session=test-cookie" }

            assertEquals("next-page", api.list(ServerEventReadState.UNREAD, 25, "opaque-cursor").nextCursor)
            assertEquals(ServerEventReadState.READ, api.markRead("event-1").readState)
            assertEquals(2, api.markConversationRead("thread-1").markedReadCount)

            assertEquals("GET", requests[0].first)
            assertTrue(requests[0].second.contains("read_state=UNREAD&limit=25&cursor=opaque-cursor"))
            assertEquals(Triple("POST", "/api/events/event-1/read", ""), requests[1])
            assertEquals("POST", requests[2].first)
            assertEquals("/api/events/read-conversation", requests[2].second)
            assertEquals("{\"thread_id\":\"thread-1\"}", requests[2].third)
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `missing App Account session sends no read request`() = runBlocking {
        val api = HttpServerEventApi("http://127.0.0.1:1") { null }
        try {
            api.markRead("event-1")
            error("request must require a session")
        } catch (error: ServerEventApiException) {
            assertEquals(401, error.status)
            assertEquals("APP_AUTH_REQUIRED", error.code)
        }
    }
}
