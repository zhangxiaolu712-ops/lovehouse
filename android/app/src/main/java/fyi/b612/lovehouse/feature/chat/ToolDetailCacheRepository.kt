package fyi.b612.lovehouse.feature.chat

import org.json.JSONArray
import org.json.JSONObject

internal const val TOOL_DETAIL_CACHE_TTL_MILLIS: Long = 72L * 60L * 60L * 1000L

data class CachedToolDetail(
    val threadId: String,
    val assistantMessageId: String,
    val eventId: String,
    val detail: ToolDetailEnvelope,
    val createdAtEpochMillis: Long,
    val expiresAtEpochMillis: Long,
)

interface ToolDetailCacheRepository {
    fun upsert(
        threadId: String,
        assistantMessageId: String,
        eventId: String,
        detail: ToolDetailEnvelope,
    ): CachedToolDetail?

    fun detail(
        assistantMessageId: String,
        eventId: String,
    ): CachedToolDetail?

    fun deleteForAssistant(assistantMessageId: String): Int
    fun deleteForThread(threadId: String): Int
    fun cleanupExpired(nowEpochMillis: Long): Int
}

object NoOpToolDetailCacheRepository : ToolDetailCacheRepository {
    override fun upsert(
        threadId: String,
        assistantMessageId: String,
        eventId: String,
        detail: ToolDetailEnvelope,
    ): CachedToolDetail? = null

    override fun detail(assistantMessageId: String, eventId: String): CachedToolDetail? = null
    override fun deleteForAssistant(assistantMessageId: String): Int = 0
    override fun deleteForThread(threadId: String): Int = 0
    override fun cleanupExpired(nowEpochMillis: Long): Int = 0
}

internal fun toolDetailEventId(callId: String): String = "tool-call:$callId"

internal fun hasValidToolDetailIdentity(eventId: String, detail: ToolDetailEnvelope): Boolean =
    detail.callId.isNotBlank() && eventId == toolDetailEventId(detail.callId)

internal fun toolDetailExpiresAt(createdAtEpochMillis: Long): Long =
    Math.addExact(createdAtEpochMillis, TOOL_DETAIL_CACHE_TTL_MILLIS)

internal fun mergeToolDetails(
    existing: ToolDetailEnvelope,
    incoming: ToolDetailEnvelope,
): ToolDetailEnvelope? {
    if (existing.callId != incoming.callId || existing.schemaVersion != incoming.schemaVersion) return null
    return when {
        existing is ToolDetailEnvelope.GenericTool && incoming is ToolDetailEnvelope.GenericTool ->
            incoming.copy(
                createdAt = existing.createdAt,
                truncated = existing.truncated || incoming.truncated,
                originalLength = maxOf(existing.originalLength, incoming.originalLength),
                arguments = incoming.arguments ?: existing.arguments,
                result = incoming.result ?: existing.result,
                isError = incoming.isError ?: existing.isError,
            )
        existing is ToolDetailEnvelope.Command && incoming is ToolDetailEnvelope.Command ->
            incoming.copy(
                createdAt = existing.createdAt,
                truncated = existing.truncated || incoming.truncated,
                originalLength = maxOf(existing.originalLength, incoming.originalLength),
                command = incoming.command ?: existing.command,
                output = incoming.output ?: existing.output,
                exitCode = incoming.exitCode ?: existing.exitCode,
                status = incoming.status ?: existing.status,
            )
        else -> null
    }
}

internal fun serializeToolDetailEnvelope(detail: ToolDetailEnvelope): String {
    val json = JSONObject()
        .put("schema_version", detail.schemaVersion)
        .put("call_id", detail.callId)
        .put("created_at", detail.createdAt)
        .put("truncated", detail.truncated)
        .put("original_length", detail.originalLength)
    when (detail) {
        is ToolDetailEnvelope.GenericTool -> {
            json.put("detail_kind", "generic_tool")
            detail.arguments?.let { json.put("arguments", serializeToolDetailValue(it)) }
            detail.result?.let { json.put("result", serializeToolDetailValue(it)) }
            detail.isError?.let { json.put("is_error", it) }
        }
        is ToolDetailEnvelope.Command -> {
            json.put("detail_kind", "command")
            detail.command?.let { json.put("command", it) }
            detail.output?.let { json.put("output", it) }
            detail.exitCode?.let { json.put("exit_code", it) }
            detail.status?.let { json.put("status", it) }
        }
    }
    return json.toString()
}

internal fun parseStoredToolDetailEnvelope(payload: String, expectedCallId: String): ToolDetailEnvelope? =
    runCatching {
        parseToolDetailEnvelope(
            JSONObject().put("tool_detail", JSONObject(payload)).toString(),
            expectedCallId,
        )
    }.getOrNull()

private fun serializeToolDetailValue(value: ToolDetailValue): JSONObject = when (value) {
    is ToolDetailValue.Text -> JSONObject().put("type", "text").put("text", value.text)
    is ToolDetailValue.NumberValue -> JSONObject().put("type", "number").put("value", value.value)
    is ToolDetailValue.BooleanValue -> JSONObject().put("type", "boolean").put("value", value.value)
    ToolDetailValue.NullValue -> JSONObject().put("type", "null")
    is ToolDetailValue.Omitted -> JSONObject().put("type", "omitted").put("reason", "内容已省略")
    is ToolDetailValue.ListValue -> JSONObject().put(
        "type",
        "list",
    ).put("items", JSONArray().also { array -> value.items.forEach { array.put(serializeToolDetailValue(it)) } })
    is ToolDetailValue.ObjectValue -> JSONObject().put(
        "type",
        "object",
    ).put("fields", JSONArray().also { array ->
        value.fields.forEach { field ->
            array.put(JSONObject().put("key", field.key).put("value", serializeToolDetailValue(field.value)))
        }
    })
}
