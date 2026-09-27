package fyi.b612.lovehouse.core.navigation

internal fun eventChatRoute(
    canonicalThreadId: String,
    resolveLocalThreadId: (String) -> String?,
    encodeRouteSegment: (String) -> String,
): String? = resolveLocalThreadId(canonicalThreadId)
    ?.let(encodeRouteSegment)
    ?.let { localThreadId -> "chat/thread/$localThreadId" }
