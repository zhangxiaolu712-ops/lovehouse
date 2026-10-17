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

enum class ServerEventReadState {
    UNREAD,
    READ,
}

data class ServerEvent(
    val id: String,
    val kind: ServerEventKind,
    val rawKind: String,
    val source: String,
    val threadId: String?,
    val executionId: String? = null,
    val messageId: String? = null,
    val toolActionId: String? = null,
    val providerRawCallId: String? = null,
    val providerSource: String? = null,
    val relationStatus: String = "legacy_unknown",
    val safeSummary: String,
    val status: String,
    val createdAt: String,
    val expiresAt: String?,
    val decidedAt: String?,
    val readState: ServerEventReadState = ServerEventReadState.UNREAD,
    val readAt: String? = null,
    val toolName: String? = null,
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

data class ServerEventPage(val events: List<ServerEvent>, val nextCursor: String?)

sealed interface ServerEventPageResult {
    data class Ready(val page: ServerEventPage) : ServerEventPageResult
    data object AuthenticationRequired : ServerEventPageResult
    data class Error(val message: String) : ServerEventPageResult
}

sealed interface ServerEventReadResult {
    data class Recorded(val event: ServerEvent) : ServerEventReadResult
    data object AuthenticationRequired : ServerEventReadResult
    data object NotFound : ServerEventReadResult
    data class Error(val message: String) : ServerEventReadResult
}

sealed interface ServerEventConversationReadResult {
    data class Recorded(val threadId: String, val cutoffAt: String, val markedReadCount: Int) : ServerEventConversationReadResult
    data object AuthenticationRequired : ServerEventConversationReadResult
    data class Error(val message: String) : ServerEventConversationReadResult
}

/** W4B Inbox: attention = unread information + pending decisions; history = the rest. */
enum class InboxSection(val wireValue: String) {
    Attention("attention"),
    History("history"),
}

enum class InboxKind(val wireValue: String) {
    All("all"),
    ReplyCompleted("reply_completed"),
    ConfirmationRequired("confirmation_required"),
}

/** Authoritative per-kind counts from the server, never derived from the loaded window. */
data class InboxCounts(
    val replyUnread: Int,
    val replyRead: Int,
    val approvalPending: Int,
    val approvalHistory: Int,
) {
    fun count(kind: InboxKind, section: InboxSection): Int = when (kind) {
        InboxKind.All -> if (section == InboxSection.Attention) replyUnread + approvalPending else replyRead + approvalHistory
        InboxKind.ReplyCompleted -> if (section == InboxSection.Attention) replyUnread else replyRead
        InboxKind.ConfirmationRequired -> if (section == InboxSection.Attention) approvalPending else approvalHistory
    }
}

sealed interface InboxCountsResult {
    data class Ready(val counts: InboxCounts) : InboxCountsResult
    data object AuthenticationRequired : InboxCountsResult
    data class Error(val message: String) : InboxCountsResult
}

sealed interface InboxBulkReadResult {
    data class Recorded(val cutoffAt: String, val markedReadCount: Int) : InboxBulkReadResult
    data object Protected : InboxBulkReadResult
    data object AuthenticationRequired : InboxBulkReadResult
    data class Error(val message: String) : InboxBulkReadResult
}

const val INBOX_DEFAULT_LIMIT = 30
const val INBOX_MAX_LIMIT = 100
const val INBOX_MAX_QUERY_LENGTH = 100

interface ServerEventRepository {
    val feed: StateFlow<ServerEventFeedState>
    suspend fun refresh()
    suspend fun get(eventId: String): ServerEventLoadResult
    suspend fun decide(eventId: String, decision: ServerEventDecision): ServerEventDecisionResult
    suspend fun page(readState: ServerEventReadState, limit: Int = 50, cursor: String? = null): ServerEventPageResult
    suspend fun markRead(eventId: String): ServerEventReadResult
    suspend fun markConversationRead(threadId: String): ServerEventConversationReadResult
    suspend fun inbox(
        section: InboxSection,
        kind: InboxKind,
        query: String? = null,
        limit: Int = INBOX_DEFAULT_LIMIT,
        cursor: String? = null,
    ): ServerEventPageResult = ServerEventPageResult.Error("收件箱尚未接入")
    suspend fun inboxCounts(): InboxCountsResult = InboxCountsResult.Error("收件箱尚未接入")
    suspend fun bulkRead(kind: InboxKind): InboxBulkReadResult = InboxBulkReadResult.Error("收件箱尚未接入")
}

internal interface ServerEventApi {
    suspend fun list(status: String, limit: Int, cursor: String? = null): ServerEventPage
    suspend fun list(readState: ServerEventReadState, limit: Int, cursor: String? = null): ServerEventPage
    suspend fun get(eventId: String): ServerEvent
    suspend fun decide(eventId: String, decision: ServerEventDecision)
    suspend fun markRead(eventId: String): ServerEvent
    suspend fun markConversationRead(threadId: String): ServerEventConversationReadResult.Recorded
    suspend fun inbox(section: InboxSection, kind: InboxKind, query: String?, limit: Int, cursor: String?): ServerEventPage =
        throw ServerEventApiException(HttpURLConnection.HTTP_NOT_IMPLEMENTED, "INBOX_UNAVAILABLE", "收件箱尚未接入")
    suspend fun inboxCounts(): InboxCounts =
        throw ServerEventApiException(HttpURLConnection.HTTP_NOT_IMPLEMENTED, "INBOX_UNAVAILABLE", "收件箱尚未接入")
    suspend fun bulkRead(kind: InboxKind): InboxBulkReadResult.Recorded =
        throw ServerEventApiException(HttpURLConnection.HTTP_NOT_IMPLEMENTED, "INBOX_UNAVAILABLE", "收件箱尚未接入")
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

    override suspend fun page(
        readState: ServerEventReadState,
        limit: Int,
        cursor: String?,
    ): ServerEventPageResult = try {
        require(limit in 1..100) { "Invalid event page size" }
        ServerEventPageResult.Ready(api.list(readState, limit, cursor))
    } catch (error: ServerEventApiException) {
        if (error.status == HttpURLConnection.HTTP_UNAUTHORIZED) {
            onAuthenticationRequired()
            ServerEventPageResult.AuthenticationRequired
        } else {
            ServerEventPageResult.Error("动态暂时无法加载，请稍后重试")
        }
    } catch (error: Throwable) {
        ServerEventPageResult.Error("动态暂时无法加载，请稍后重试")
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

    override suspend fun markRead(eventId: String): ServerEventReadResult = try {
        val event = api.markRead(eventId)
        if (event.id != eventId || event.readState != ServerEventReadState.READ) {
            ServerEventReadResult.Error("已读状态尚未确认，请刷新动态")
        } else {
            ServerEventReadResult.Recorded(event)
        }
    } catch (error: ServerEventApiException) {
        when (error.status) {
            HttpURLConnection.HTTP_UNAUTHORIZED -> {
                onAuthenticationRequired()
                ServerEventReadResult.AuthenticationRequired
            }
            HttpURLConnection.HTTP_NOT_FOUND -> ServerEventReadResult.NotFound
            else -> ServerEventReadResult.Error("已读状态暂时无法更新，请稍后重试")
        }
    } catch (error: Throwable) {
        ServerEventReadResult.Error("网络不可用，已读状态尚未确认")
    }

    override suspend fun markConversationRead(threadId: String): ServerEventConversationReadResult = try {
        val result = api.markConversationRead(threadId)
        if (result.threadId != threadId || result.cutoffAt.isBlank() || result.markedReadCount < 0) {
            ServerEventConversationReadResult.Error("已读状态尚未确认，请刷新动态")
        } else {
            result
        }
    } catch (error: ServerEventApiException) {
        if (error.status == HttpURLConnection.HTTP_UNAUTHORIZED) {
            onAuthenticationRequired()
            ServerEventConversationReadResult.AuthenticationRequired
        } else {
            ServerEventConversationReadResult.Error("已读状态暂时无法更新，请稍后重试")
        }
    } catch (error: Throwable) {
        ServerEventConversationReadResult.Error("网络不可用，已读状态尚未确认")
    }

    override suspend fun inbox(
        section: InboxSection,
        kind: InboxKind,
        query: String?,
        limit: Int,
        cursor: String?,
    ): ServerEventPageResult = try {
        require(limit in 1..INBOX_MAX_LIMIT) { "Invalid inbox page size" }
        val normalizedQuery = query?.trim()?.takeIf(String::isNotEmpty)
        require(normalizedQuery == null || normalizedQuery.length <= INBOX_MAX_QUERY_LENGTH) { "Inbox query is too long" }
        ServerEventPageResult.Ready(api.inbox(section, kind, normalizedQuery, limit, cursor))
    } catch (error: ServerEventApiException) {
        if (error.status == HttpURLConnection.HTTP_UNAUTHORIZED) {
            onAuthenticationRequired()
            ServerEventPageResult.AuthenticationRequired
        } else {
            ServerEventPageResult.Error("收件箱暂时无法加载，请稍后重试")
        }
    } catch (error: IllegalArgumentException) {
        ServerEventPageResult.Error("搜索词或条数超出范围")
    } catch (error: Throwable) {
        ServerEventPageResult.Error("收件箱暂时无法加载，请稍后重试")
    }

    override suspend fun inboxCounts(): InboxCountsResult = try {
        InboxCountsResult.Ready(api.inboxCounts())
    } catch (error: ServerEventApiException) {
        if (error.status == HttpURLConnection.HTTP_UNAUTHORIZED) {
            onAuthenticationRequired()
            InboxCountsResult.AuthenticationRequired
        } else {
            InboxCountsResult.Error("数量暂时无法读取")
        }
    } catch (error: Throwable) {
        InboxCountsResult.Error("数量暂时无法读取")
    }

    override suspend fun bulkRead(kind: InboxKind): InboxBulkReadResult = try {
        val result = api.bulkRead(kind)
        refresh()
        result
    } catch (error: ServerEventApiException) {
        when {
            error.status == HttpURLConnection.HTTP_UNAUTHORIZED -> {
                onAuthenticationRequired()
                InboxBulkReadResult.AuthenticationRequired
            }
            error.code == "BULK_READ_PROTECTED" -> InboxBulkReadResult.Protected
            else -> InboxBulkReadResult.Error("全部已读暂时无法完成，请稍后重试")
        }
    } catch (error: Throwable) {
        InboxBulkReadResult.Error("网络不可用，已读状态尚未确认")
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
        listWithQuery("status=${urlEncode(status)}", limit, cursor)
    }

    override suspend fun list(readState: ServerEventReadState, limit: Int, cursor: String?): ServerEventPage = withContext(Dispatchers.IO) {
        listWithQuery("read_state=${readState.name}", limit, cursor)
    }

    private fun listWithQuery(filter: String, limit: Int, cursor: String?): ServerEventPage {
        val query = buildList {
            add(filter)
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
        return ServerEventPage(events, payload.optNullableString("next_cursor"))
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

    override suspend fun markRead(eventId: String): ServerEvent = withContext(Dispatchers.IO) {
        request("POST", "/api/events/${urlEncode(eventId)}/read").getJSONObject("event").toServerEvent()
    }

    override suspend fun markConversationRead(threadId: String): ServerEventConversationReadResult.Recorded =
        withContext(Dispatchers.IO) {
            val payload = request(
                method = "POST",
                path = "/api/events/read-conversation",
                body = JSONObject().put("thread_id", threadId).toString(),
            )
            ServerEventConversationReadResult.Recorded(
                threadId = payload.getString("thread_id"),
                cutoffAt = payload.getString("cutoff_at"),
                markedReadCount = payload.getInt("marked_read_count"),
            )
        }

    override suspend fun inbox(
        section: InboxSection,
        kind: InboxKind,
        query: String?,
        limit: Int,
        cursor: String?,
    ): ServerEventPage = withContext(Dispatchers.IO) {
        val payload = request("GET", "/api/events/inbox?${inboxQueryString(section, kind, query, limit, cursor)}")
        val values = payload.optJSONArray("events")
        val events = buildList {
            if (values != null) for (index in 0 until values.length()) {
                values.optJSONObject(index)?.let { add(it.toServerEvent()) }
            }
        }
        ServerEventPage(events, payload.optNullableString("next_cursor"))
    }

    override suspend fun inboxCounts(): InboxCounts = withContext(Dispatchers.IO) {
        request("GET", "/api/events/inbox/counts").getJSONObject("counts").toInboxCounts()
    }

    override suspend fun bulkRead(kind: InboxKind): InboxBulkReadResult.Recorded = withContext(Dispatchers.IO) {
        val payload = request(
            method = "POST",
            path = "/api/events/read-bulk",
            body = JSONObject().put("kind", kind.wireValue).toString(),
        )
        InboxBulkReadResult.Recorded(
            cutoffAt = payload.getString("cutoff_at"),
            markedReadCount = payload.getInt("marked_read_count"),
        )
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

internal fun inboxQueryString(
    section: InboxSection,
    kind: InboxKind,
    query: String?,
    limit: Int,
    cursor: String?,
): String = buildList {
    add("section=${section.wireValue}")
    add("kind=${kind.wireValue}")
    query?.let { add("q=${urlEncode(it)}") }
    add("limit=$limit")
    cursor?.let { add("cursor=${urlEncode(it)}") }
}.joinToString("&")

internal fun JSONObject.toInboxCounts(): InboxCounts {
    val reply = getJSONObject("reply_completed")
    val approval = getJSONObject("confirmation_required")
    return InboxCounts(
        replyUnread = reply.getInt("unread"),
        replyRead = reply.getInt("read"),
        approvalPending = approval.getInt("pending"),
        approvalHistory = approval.getInt("history"),
    )
}

internal fun JSONObject.toServerEvent(): ServerEvent {
    val rawKind = getString("kind")
    return ServerEvent(
        id = getString("id"),
        kind = ServerEventKind.fromWire(rawKind),
        rawKind = rawKind,
        source = optString("source"),
        threadId = optNullableString("thread_id"),
        executionId = optNullableString("execution_id"),
        messageId = optNullableString("message_id"),
        toolActionId = optNullableString("tool_action_id"),
        providerRawCallId = optNullableString("provider_raw_call_id"),
        providerSource = optNullableString("provider_source"),
        relationStatus = optString("relation_status", "legacy_unknown"),
        safeSummary = getString("safe_summary"),
        status = getString("status"),
        createdAt = getString("created_at"),
        expiresAt = optNullableString("expires_at"),
        decidedAt = optNullableString("decided_at"),
        readState = ServerEventReadState.valueOf(optString("read_state", "UNREAD")),
        readAt = optNullableString("read_at"),
        toolName = optJSONObject("metadata")?.optNullableString("tool_name"),
    )
}

private fun JSONObject.optNullableString(name: String): String? =
    if (isNull(name)) null else optString(name).takeIf(String::isNotBlank)

private fun urlEncode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8.name())

private fun Throwable.safeMessage(): String = message?.takeIf(String::isNotBlank) ?: "事件服务暂时不可用"
