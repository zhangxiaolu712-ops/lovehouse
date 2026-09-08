package fyi.b612.lovehouse.feature.chat

import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.UUID

enum class ChatAttachmentLifecycle { LOCAL, EPHEMERAL, DURABLE }

enum class ChatAttachmentAvailability { AVAILABLE, UPLOADING, FAILED, EXPIRED, LOCAL_MISSING, UNAVAILABLE }

sealed interface ChatAttachment {
    val attachmentId: String
    val type: String
    val lifecycle: ChatAttachmentLifecycle
    val availability: ChatAttachmentAvailability
    val createdAtEpochMillis: Long
    fun displaySummary(): String
    fun transportJson(): String
}

data class ChatMediaAttachment(
    override val type: String,
    val mediaAssetId: String? = null,
    val storageRef: String? = null,
    val mimeType: String,
    val sizeBytes: Long,
    val name: String,
    val width: Int? = null,
    val height: Int? = null,
    val localCachePath: String? = null,
    override val attachmentId: String = mediaAssetId ?: UUID.randomUUID().toString(),
    override val lifecycle: ChatAttachmentLifecycle = if (mediaAssetId == null) ChatAttachmentLifecycle.LOCAL else ChatAttachmentLifecycle.EPHEMERAL,
    override val availability: ChatAttachmentAvailability = ChatAttachmentAvailability.AVAILABLE,
    override val createdAtEpochMillis: Long = System.currentTimeMillis(),
    val remoteExpiresAtEpochMillis: Long? = null,
) : ChatAttachment {
    init {
        require(type == "photo" || type == "file" || type == "audio")
        require(sizeBytes >= 0)
    }

    override fun displaySummary(): String = when (type) {
        "photo" -> "照片 · $name"
        "audio" -> "语音 · $name"
        else -> "文件 · $name"
    }

    fun resolvedAvailability(nowEpochMillis: Long = System.currentTimeMillis()): ChatAttachmentAvailability = when {
        availability != ChatAttachmentAvailability.AVAILABLE -> availability
        remoteExpiresAtEpochMillis != null && remoteExpiresAtEpochMillis <= nowEpochMillis -> ChatAttachmentAvailability.EXPIRED
        !localCachePath.isNullOrBlank() && !File(localCachePath).isFile -> ChatAttachmentAvailability.LOCAL_MISSING
        else -> ChatAttachmentAvailability.AVAILABLE
    }

    fun asUploading(): ChatMediaAttachment = copy(availability = ChatAttachmentAvailability.UPLOADING)

    override fun transportJson(): String {
        val assetId = requireNotNull(mediaAssetId) { "Local attachment has no remote media asset id" }
        val ref = requireNotNull(storageRef) { "Local attachment has no remote storage reference" }
        require(lifecycle != ChatAttachmentLifecycle.LOCAL) { "Local attachment cannot be sent to the remote runtime" }
        return buildString {
            append("{\"type\":\"").append(type).append('"')
            append(",\"media_asset_id\":\"").append(jsonEscape(assetId)).append('"')
            append(",\"storage_ref\":\"").append(jsonEscape(ref)).append('"')
            width?.let { append(",\"width\":").append(it) }
            height?.let { append(",\"height\":").append(it) }
            append('}')
        }
    }
}

data class ChatLocationAttachment(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val capturedAtEpochMillis: Long,
    val address: String? = null,
    override val attachmentId: String = UUID.randomUUID().toString(),
    override val lifecycle: ChatAttachmentLifecycle = ChatAttachmentLifecycle.LOCAL,
    override val availability: ChatAttachmentAvailability = ChatAttachmentAvailability.AVAILABLE,
    override val createdAtEpochMillis: Long = capturedAtEpochMillis,
) : ChatAttachment {
    override val type: String = "location"

    override fun displaySummary(): String = buildString {
        append("位置 · ")
        append(String.format(Locale.US, "%.6f, %.6f", latitude, longitude))
        accuracyMeters?.let { append(String.format(Locale.US, " · ±%.1fm", it)) }
    }

    override fun transportJson(): String = buildString {
        append("{\"type\":\"location\"")
        append(",\"latitude\":").append(latitude)
        append(",\"longitude\":").append(longitude)
        accuracyMeters?.let { append(",\"accuracy\":").append(it) }
        append(",\"captured_at\":\"")
            .append(Instant.ofEpochMilli(capturedAtEpochMillis).toString())
            .append('"')
        address?.takeIf(String::isNotBlank)?.let {
            append(",\"address\":\"").append(jsonEscape(it)).append('"')
        }
        append('}')
    }
}

internal enum class ChatAttachmentSegmentKind { Photos, Files, Location, Audio }

internal data class ChatAttachmentSegment(
    val kind: ChatAttachmentSegmentKind,
    val attachments: List<ChatAttachment>,
)

internal fun attachmentSegments(attachments: List<ChatAttachment>): List<ChatAttachmentSegment> = buildList {
    attachments.filterIsInstance<ChatMediaAttachment>().filter { it.type == "photo" }.takeIf(List<*>::isNotEmpty)?.let {
        add(ChatAttachmentSegment(ChatAttachmentSegmentKind.Photos, it))
    }
    attachments.filterIsInstance<ChatMediaAttachment>().filter { it.type == "file" }.takeIf(List<*>::isNotEmpty)?.let {
        add(ChatAttachmentSegment(ChatAttachmentSegmentKind.Files, it))
    }
    attachments.filterIsInstance<ChatLocationAttachment>().forEach {
        add(ChatAttachmentSegment(ChatAttachmentSegmentKind.Location, listOf(it)))
    }
    attachments.filterIsInstance<ChatMediaAttachment>().filter { it.type == "audio" }.takeIf(List<*>::isNotEmpty)?.let {
        add(ChatAttachmentSegment(ChatAttachmentSegmentKind.Audio, it))
    }
}

internal fun chatDisplayText(text: String, attachments: List<ChatAttachment>): String =
    text.trim().ifEmpty { attachments.joinToString("\n", transform = ChatAttachment::displaySummary) }

internal fun chatAttachmentsJson(attachments: List<ChatAttachment>): String =
    attachments.joinToString(prefix = "[", postfix = "]") { it.transportJson() }
