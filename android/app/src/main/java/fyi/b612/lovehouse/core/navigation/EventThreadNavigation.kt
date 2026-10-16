package fyi.b612.lovehouse.core.navigation

internal fun eventChatRoute(
    canonicalThreadId: String,
    resolveLocalThreadId: (String) -> String?,
    encodeRouteSegment: (String) -> String,
): String? = resolveLocalThreadId(canonicalThreadId)
    ?.let(encodeRouteSegment)
    ?.let { localThreadId -> "chat/thread/$localThreadId" }

internal fun eventChatRoute(
    canonicalThreadId: String,
    messageId: String,
    toolActionId: String?,
    sourceEventId: String,
    resolveLocalThreadId: (String) -> String?,
    encodeRouteSegment: (String) -> String,
): String? = resolveLocalThreadId(canonicalThreadId)?.let { localThreadId ->
    buildString {
        append("chat/thread/")
        append(encodeRouteSegment(localThreadId))
        append("?targetMessageId=")
        append(encodeRouteSegment(messageId))
        toolActionId?.let {
            append("&targetToolActionId=")
            append(encodeRouteSegment(it))
        }
        append("&sourceEventId=")
        append(encodeRouteSegment(sourceEventId))
    }
}
