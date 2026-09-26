package fyi.b612.lovehouse.feature.events

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.json.JSONObject

enum class ServerEventKind(val wireValue: String) {
    ReplyCompleted("reply_completed"),
    ConfirmationRequired("confirmation_required"),
    Unknown("unknown"),
    ;

    companion object {
        fun fromWire(value: String): ServerEventKind = entries.firstOrNull { it.wireValue == value } ?: Unknown
    }
}

enum class ServerEventDecision(val wireValue: String) {
    Approved("approved"),
    Denied("denied"),
}

data class ServerEvent(
    val id: String,
    val kind: ServerEventKind,
    val rawKind: String,
    val source: String,
    val threadId: String?,
    val safeSummary: String,
    val status: String,
    val createdAt: String,
    val expiresAt: String?,
    val decidedAt: String?,
) {
    val canDecide: Boolean
        get() = kind == ServerEventKind.ConfirmationRequired && status == "pending"
}

sealed interface ServerEventFeedState {
    data object Initial : ServerEventFeedState
    data object Loading : ServerEventFeedState
    data object AuthenticationRequired : ServerEventFeedState
    data class Ready(val events: List<ServerEvent>) : ServerEventFeedState
    data class Error(val message: String) : ServerEventFeedState
}

sealed interface ServerEventLoadResult {
    data class Found(val event: ServerEvent) : ServerEventLoadResult
    data object AuthenticationRequired : ServerEventLoadResult
    data object NotFound : ServerEventLoadResult
    data class Error(val message: String) : ServerEventLoadResult
}

sealed interface ServerEventDecisionResult {
    data class Recorded(val event: ServerEvent) : ServerEventDecisionResult
    data class ServerState(val event: ServerEvent, val reason: String) : ServerEventDecisionResult
    data object AuthenticationRequired : ServerEventDecisionResult
    data object NotFound : ServerEventDecisionResult
    data class Error(val message: String) : ServerEventDecisionResult
}

interface ServerEventRepository {
    val feed: StateFlow<ServerEventFeedState>
    suspend fun refresh()
    suspend fun get(eventId: String): ServerEventLoadResult
    suspend fun decide(eventId: String, decision: ServerEventDecision): ServerEventDecisionResult
}

internal data class ServerEventPage(val events: List<ServerEvent>, val nextCursor: String?)

internal interface ServerEventApi {
    suspend fun list(status: String, limit: Int, cursor: String? = null): ServerEventPage
    suspend fun get(eventId: String): ServerEvent
    suspend fun decide(eventId: String, decision: ServerEventDecision)
}

internal class ServerEventApiException(
    val status: Int,
    val code: String,
    message: String,
) : Exception(message)

class AndroidServerEventRepository internal constructor(
    private val api: ServerEventApi,
    private val onAuthenticationRequired: suspend () -> Unit,
) : ServerEventRepository {
    constructor(
        baseUrl: String,
        sessionCookie: () -> String?,
        onAuthenticationRequired: suspend () -> Unit,
    ) : this(HttpServerEventApi(baseUrl, sessionCookie), onAuthenticationRequired)

    private val mutableFeed = MutableStateFlow<ServerEventFeedState>(ServerEventFeedState.Initial)
    override val feed: StateFlow<ServerEventFeedState> = mutableFeed.asStateFlow()

    override suspend fun refresh() {
        mutableFeed.value = ServerEventFeedState.Loading
        try {
            val pending = api.list("pending", FEED_LIMIT).events
            val active = api.list("active", FEED_LIMIT).events
            mutableFeed.value = ServerEventFeedState.Ready(
                (pending + active).distinctBy(ServerEvent::id).sortedByDescending(ServerEvent::createdAt),
            )
        } catch (error: ServerEventApiException) {
            if (error.status == HttpURLConnection.HTTP_UNAUTHORIZED) {
                onAuthenticationRequired()
                mutableFeed.value = ServerEventFeedState.AuthenticationRequired
            } else {
                mutableFeed.value = ServerEventFeedState.Error(error.safeMessage())
            }
        } catch (error: Throwable) {
            mutableFeed.value = ServerEventFeedState.Error(error.safeMessage())
        }
    }

    override suspend fun get(eventId: String): ServerEventLoadResult = try {
        ServerEventLoadResult.Found(api.get(eventId))
    } catch (error: ServerEventApiException) {
        when (error.status) {
            HttpURLConnection.HTTP_UNAUTHORIZED -> {
                onAuthenticationRequired()
                ServerEventLoadResult.AuthenticationRequired
            }
            HttpURLConnection.HTTP_NOT_FOUND -> ServerEventLoadResult.NotFound
            else -> ServerEventLoadResult.Error(error.safeMessage())
        }
    } catch (error: Throwable) {
        ServerEventLoadResult.Error(error.safeMessage())
    }

    override suspend fun decide(
        eventId: String,
        decision: ServerEventDecision,
    ): ServerEventDecisionResult {
        var conflictCode: String? = null
        try {
            api.decide(eventId, decision)
        } catch (error: ServerEventApiException) {
            when (error.status) {
                HttpURLConnection.HTTP_UNAUTHORIZED -> {
                    onAuthenticationRequired()
                    return ServerEventDecisionResult.AuthenticationRequired
                }
                HttpURLConnection.HTTP_NOT_FOUND -> return ServerEventDecisionResult.NotFound
                HttpURLConnection.HTTP_CONFLICT -> conflictCode = error.code
                else -> return ServerEventDecisionResult.Error(error.safeMessage())
            }
        } catch (error: Throwable) {
            return ServerEventDecisionResult.Error(error.safeMessage())
        }

        val authoritative = get(eventId)
        refresh()
        return when (authoritative) {
            is ServerEventLoadResult.Found -> if (conflictCode == null) {
                ServerEventDecisionResult.Recorded(authoritative.event)
            } else {
                ServerEventDecisionResult.ServerState(authoritative.event, conflictCode)
            }
            ServerEventLoadResult.AuthenticationRequired -> ServerEventDecisionResult.AuthenticationRequired
            ServerEventLoadResult.NotFound -> ServerEventDecisionResult.NotFound
            is ServerEventLoadResult.Error -> ServerEventDecisionResult.Error(authoritative.message)
        }
    }

    private companion object {
        const val FEED_LIMIT = 50
    }
}

internal class HttpServerEventApi(
    private val baseUrl: String,
    private val sessionCookie: () -> String?,
) : ServerEventApi {
    override suspend fun list(status: String, limit: Int, cursor: String?): ServerEventPage = withContext(Dispatchers.IO) {
        val query = buildList {
            add("status=${urlEncode(status)}")
            add("limit=$limit")
            cursor?.let { add("cursor=${urlEncode(it)}") }
        }.joinToString("&")
        val payload = request("GET", "/api/events?$query")
        val values = payload.optJSONArray("events")
        val events = buildList {
            if (values != null) for (index in 0 until values.length()) {
                values.optJSONObject(index)?.let { add(it.toServerEvent()) }
            }
        }
        ServerEventPage(events, payload.optNullableString("next_cursor"))
    }

    override suspend fun get(eventId: String): ServerEvent = withContext(Dispatchers.IO) {
        request("GET", "/api/events/${urlEncode(eventId)}").getJSONObject("event").toServerEvent()
    }

    override suspend fun decide(eventId: String, decision: ServerEventDecision) = withContext(Dispatchers.IO) {
        request(
            method = "POST",
            path = "/api/events/${urlEncode(eventId)}/decision",
            body = JSONObject().put("decision", decision.wireValue).toString(),
        )
        Unit
    }

    private fun request(method: String, path: String, body: String? = null): JSONObject {
        val cookie = sessionCookie()?.takeIf(String::isNotBlank)
            ?: throw ServerEventApiException(HttpURLConnection.HTTP_UNAUTHORIZED, "APP_AUTH_REQUIRED", "请先登录 LoveHouse App Account")
        val connection = (URL("${baseUrl.trimEnd('/')}$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cookie", cookie)
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            val text = (if (status in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            val payload = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            if (status !in 200..299) {
                val error = payload.optJSONObject("error")
                val code = error?.optString("code")?.takeIf(String::isNotBlank) ?: "SERVER_EVENT_REQUEST_FAILED"
                val message = error?.optString("message")?.takeIf(String::isNotBlank)
                    ?: "事件请求失败（HTTP $status）"
                throw ServerEventApiException(status, code, message)
            }
            return payload
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val CONNECT_TIMEOUT_MS = 15_000
        const val READ_TIMEOUT_MS = 30_000
    }
}

internal fun serverEventEndpoint(baseUrl: String, eventId: String? = null, decision: Boolean = false): String {
    val root = "${baseUrl.trimEnd('/')}/api/events"
    val detail = eventId?.let { "$root/${urlEncode(it)}" } ?: root
    return if (decision) "$detail/decision" else detail
}

private fun JSONObject.toServerEvent(): ServerEvent {
    val rawKind = getString("kind")
    return ServerEvent(
        id = getString("id"),
        kind = ServerEventKind.fromWire(rawKind),
        rawKind = rawKind,
        source = optString("source"),
        threadId = optNullableString("thread_id"),
        safeSummary = getString("safe_summary"),
        status = getString("status"),
        createdAt = getString("created_at"),
        expiresAt = optNullableString("expires_at"),
        decidedAt = optNullableString("decided_at"),
    )
}

private fun JSONObject.optNullableString(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf(String::isNotBlank)

private fun urlEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

private fun Throwable.safeMessage(): String = message?.takeIf(String::isNotBlank) ?: "事件服务暂时不可用"
