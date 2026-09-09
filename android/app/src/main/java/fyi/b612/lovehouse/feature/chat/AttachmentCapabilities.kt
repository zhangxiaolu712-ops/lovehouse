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

internal val CodexAttachmentCapabilities = AttachmentCapabilities(
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

internal val ClaudeAttachmentCapabilities = AttachmentCapabilities.Unsupported
