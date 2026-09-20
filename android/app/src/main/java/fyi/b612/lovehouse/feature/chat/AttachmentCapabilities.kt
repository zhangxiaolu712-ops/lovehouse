package fyi.b612.lovehouse.feature.chat

enum class ChatAttachmentType(val wireValue: String) {
    Photo("photo"),
    File("file"),
    Location("location"),
    Audio("audio"),
}

data class AttachmentCapabilities(
    val acceptedTypes: Set<ChatAttachmentType>,
    val maxItems: Int,
    val supportsTextWithAttachments: Boolean,
    val supportedLifecycles: Set<ChatAttachmentLifecycle>,
) {
    init {
        require(maxItems >= 0)
        require(acceptedTypes.isNotEmpty() || maxItems == 0)
    }

    val supported: Boolean
        get() = acceptedTypes.isNotEmpty() && maxItems > 0

    companion object {
        val Unsupported = AttachmentCapabilities(
            acceptedTypes = emptySet(),
            maxItems = 0,
            supportsTextWithAttachments = false,
            supportedLifecycles = emptySet(),
        )
    }
}

internal val GlobalChatAttachmentCapabilities = AttachmentCapabilities(
    acceptedTypes = setOf(
        ChatAttachmentType.Photo,
        ChatAttachmentType.File,
        ChatAttachmentType.Location,
    ),
    maxItems = 12,
    supportsTextWithAttachments = true,
    supportedLifecycles = setOf(
        ChatAttachmentLifecycle.LOCAL,
        ChatAttachmentLifecycle.EPHEMERAL,
    ),
)

internal val CodexAttachmentCapabilities = GlobalChatAttachmentCapabilities
internal val ClaudeAttachmentCapabilities = GlobalChatAttachmentCapabilities

internal fun AttachmentCapabilities.rejectionReason(
    text: String,
    attachments: List<ChatAttachment>,
): String? {
    if (attachments.isEmpty()) return null
    if (!supported) return "当前 Runtime 不支持消费附件；附件草稿仍保留在 LoveHouse"
    if (attachments.size > maxItems) return "当前 Runtime 每轮最多接收 $maxItems 个附件"
    if (text.isNotBlank() && !supportsTextWithAttachments) return "当前 Runtime 不支持文字与附件同轮发送"
    val unsupportedType = attachments.firstOrNull { attachment ->
        ChatAttachmentType.entries.none { it.wireValue == attachment.type && it in acceptedTypes }
    }
    if (unsupportedType != null) return "当前 Runtime 不支持 ${unsupportedType.type} 附件"
    val unsupportedLifecycle = attachments.firstOrNull { it.lifecycle !in supportedLifecycles }
    return unsupportedLifecycle?.let { "当前 Runtime 不支持 ${it.lifecycle.name} 附件生命周期" }
}
