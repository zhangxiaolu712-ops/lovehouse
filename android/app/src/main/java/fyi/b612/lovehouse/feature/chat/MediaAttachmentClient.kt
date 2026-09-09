package fyi.b612.lovehouse.feature.chat

import android.content.ContentResolver
import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.util.Log
import fyi.b612.lovehouse.BuildConfig
import fyi.b612.lovehouse.core.auth.OwnerSessionStore
import fyi.b612.lovehouse.core.capability.readSelectedResource
import java.io.BufferedReader
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.runInterruptible
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

class MediaAttachmentException(message: String) : Exception(message)

enum class MediaAttachmentStage {
    Selected,
    LocalMetadataReady,
    TemporaryReferenceStarted,
    PresignStarted,
    PresignCompleted,
    TemporaryReferenceCompleted,
    PutStarted,
    PutCompleted,
    AttachmentReady,
    Failed,
    Finished,
    AllAttachmentsReady,
}

data class MediaAttachmentProgress(
    val attachmentId: String,
    val index: Int,
    val total: Int,
    val stage: MediaAttachmentStage,
    val failure: String? = null,
)

internal suspend fun <T> observeMediaUploadAttempt(
    attachmentId: String,
    index: Int,
    total: Int,
    initialStage: MediaAttachmentStage = MediaAttachmentStage.Selected,
    onProgress: suspend (MediaAttachmentProgress) -> Unit,
    operation: suspend (stage: suspend (MediaAttachmentStage) -> Unit) -> T,
): T {
    var currentStage = initialStage
    suspend fun stage(value: MediaAttachmentStage) {
        currentStage = value
        onProgress(MediaAttachmentProgress(attachmentId, index, total, value))
    }
    try {
        return operation(::stage)
    } catch (error: TimeoutCancellationException) {
        onProgress(
            MediaAttachmentProgress(
                attachmentId,
                index,
                total,
                MediaAttachmentStage.Failed,
                "timeout_after_${currentStage.name}",
            ),
        )
        throw MediaAttachmentException("第 $index/$total 个附件在 ${mediaAttachmentStageLabel(currentStage)} 阶段超时，附件草稿已保留")
    } catch (error: CancellationException) {
        onProgress(
            MediaAttachmentProgress(
                attachmentId,
                index,
                total,
                MediaAttachmentStage.Failed,
                error::class.simpleName,
            ),
        )
        throw error
    } catch (error: Exception) {
        onProgress(
            MediaAttachmentProgress(
                attachmentId,
                index,
                total,
                MediaAttachmentStage.Failed,
                error::class.simpleName,
            ),
        )
        throw error
    } finally {
        onProgress(MediaAttachmentProgress(attachmentId, index, total, MediaAttachmentStage.Finished))
    }
}

internal fun mediaAttachmentStageLabel(stage: MediaAttachmentStage): String = when (stage) {
    MediaAttachmentStage.PresignStarted,
    MediaAttachmentStage.TemporaryReferenceStarted,
    -> "申请临时媒体引用"
    MediaAttachmentStage.PutStarted -> "上传"
    else -> stage.name
}

interface MediaAttachmentClient {
    suspend fun importLocal(uris: List<Uri>, type: String): List<ChatMediaAttachment>
    suspend fun makeEphemeral(
        attachments: List<ChatAttachment>,
        onProgress: suspend (MediaAttachmentProgress) -> Unit = {},
    ): List<ChatAttachment>
}

class HttpMediaAttachmentClient(
    context: Context,
    private val ownerSession: OwnerSessionStore,
    chatEndpoint: String = BuildConfig.LOVEHOUSE_CHAT_URL,
) : MediaAttachmentClient {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver = appContext.contentResolver
    private val uploadEndpoint = chatEndpoint.replace(Regex("/v1/chat/?$"), "/v1/media/upload-url")

    override suspend fun importLocal(uris: List<Uri>, type: String): List<ChatMediaAttachment> = withContext(Dispatchers.IO) {
        require(type == "photo" || type == "file")
        uris.map { uri -> importOne(uri, type) }
    }

    override suspend fun makeEphemeral(
        attachments: List<ChatAttachment>,
        onProgress: suspend (MediaAttachmentProgress) -> Unit,
    ): List<ChatAttachment> = withContext(Dispatchers.IO) {
        if (attachments.none { it is ChatMediaAttachment && it.lifecycle == ChatAttachmentLifecycle.LOCAL }) {
            return@withContext attachments
        }
        val bearer = ownerSession.currentBearer()
        val localMedia = attachments.filterIsInstance<ChatMediaAttachment>()
            .filter { it.lifecycle == ChatAttachmentLifecycle.LOCAL }
        var mediaIndex = 0
        val resolved = attachments.map { attachment ->
            when {
                attachment !is ChatMediaAttachment -> attachment
                attachment.lifecycle != ChatAttachmentLifecycle.LOCAL -> attachment
                attachment.type == "audio" -> throw MediaAttachmentException("原始语音附件 transport 尚未接通")
                else -> {
                    val progressIndex = ++mediaIndex
                    uploadOne(
                        attachment = attachment,
                        index = progressIndex,
                        total = localMedia.size,
                        token = bearer.value,
                        fingerprint = bearer.fingerprint,
                        onProgress = onProgress,
                    )
                }
            }
        }
        emitProgress(
            MediaAttachmentProgress("all", localMedia.size, localMedia.size, MediaAttachmentStage.AllAttachmentsReady),
            onProgress,
        )
        resolved
    }

    private fun importOne(uri: Uri, type: String): ChatMediaAttachment {
        val metadata = resolver.readSelectedResource(uri)
        val size = metadata.sizeBytes?.takeIf { it >= 0 }
            ?: throw MediaAttachmentException("无法读取附件大小，请选择本机可访问的文件")
        val mime = metadata.mimeType.takeUnless { it == "未知类型" }
            ?: throw MediaAttachmentException("无法识别附件类型")
        val attachmentId = UUID.randomUUID().toString()
        val target = cacheLocally(uri, attachmentId, metadata.displayName)
            ?: throw MediaAttachmentException("无法将附件安全复制到本地缓存")
        val dimensions = if (type == "photo") imageDimensions(target) else null
        return ChatMediaAttachment(
            type = type,
            mimeType = mime,
            sizeBytes = size,
            name = metadata.displayName,
            width = dimensions?.first,
            height = dimensions?.second,
            localCachePath = target.absolutePath,
            attachmentId = attachmentId,
            lifecycle = ChatAttachmentLifecycle.LOCAL,
            availability = ChatAttachmentAvailability.AVAILABLE,
        )
    }

    private suspend fun uploadOne(
        attachment: ChatMediaAttachment,
        index: Int,
        total: Int,
        token: String,
        fingerprint: String,
        onProgress: suspend (MediaAttachmentProgress) -> Unit,
    ): ChatMediaAttachment {
        val source = attachment.localCachePath?.let(::File)?.takeIf(File::isFile)
            ?: throw MediaAttachmentException("本地文件不可用，请重新选择附件")
        return observeMediaUploadAttempt(
            attachmentId = attachment.attachmentId,
            index = index,
            total = total,
            onProgress = { emitProgress(it, onProgress) },
        ) { stage ->
            stage(MediaAttachmentStage.Selected)
            stage(MediaAttachmentStage.LocalMetadataReady)
            stage(MediaAttachmentStage.TemporaryReferenceStarted)
            stage(MediaAttachmentStage.PresignStarted)
            val signing = withTimeout(PRESIGN_TIMEOUT_MILLIS) {
                runInterruptible(Dispatchers.IO) {
                    jsonRequest(
                        token,
                        fingerprint,
                        JSONObject()
                            .put("filename", attachment.name)
                            .put("mime_type", attachment.mimeType)
                            .put("size", attachment.sizeBytes),
                    )
                }
            }
            stage(MediaAttachmentStage.PresignCompleted)
            stage(MediaAttachmentStage.TemporaryReferenceCompleted)
            stage(MediaAttachmentStage.PutStarted)
            withTimeout(PUT_TIMEOUT_MILLIS) {
                runInterruptible(Dispatchers.IO) { putFile(source, attachment, signing) }
            }
            stage(MediaAttachmentStage.PutCompleted)
            stage(MediaAttachmentStage.AttachmentReady)
            attachment.copy(
                mediaAssetId = signing.getString("media_asset_id"),
                storageRef = signing.getString("storage_ref"),
                mimeType = signing.getString("mime_type"),
                sizeBytes = signing.getLong("size"),
                name = signing.getString("name"),
                lifecycle = ChatAttachmentLifecycle.EPHEMERAL,
                availability = ChatAttachmentAvailability.AVAILABLE,
                remoteExpiresAtEpochMillis = signing.optString("asset_expires_at").takeIf(String::isNotBlank)?.let {
                    runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
                },
            )
        }
    }

    private fun putFile(source: File, attachment: ChatMediaAttachment, signing: JSONObject) {
        val upload = (URL(signing.getString("upload_url")).openConnection() as HttpURLConnection).apply {
            requestMethod = "PUT"
            connectTimeout = PUT_CONNECT_TIMEOUT_MILLIS.toInt()
            readTimeout = PUT_TIMEOUT_MILLIS.toInt()
            doOutput = true
            setFixedLengthStreamingMode(attachment.sizeBytes)
            val required = signing.getJSONObject("required_headers")
            setRequestProperty("Content-Type", required.getString("Content-Type"))
            setRequestProperty("Content-Length", required.getString("Content-Length"))
        }
        try {
            source.inputStream().use { input -> upload.outputStream.use(input::copyTo) }
            if (upload.responseCode !in 200..299) throw MediaAttachmentException("附件上传失败（HTTP ${upload.responseCode}）")
            upload.inputStream?.close()
        } finally {
            upload.disconnect()
        }
    }

    private suspend fun emitProgress(
        progress: MediaAttachmentProgress,
        onProgress: suspend (MediaAttachmentProgress) -> Unit,
    ) {
        Log.i(
            MEDIA_LOG_TAG,
            "attachment=${progress.attachmentId.take(8)} index=${progress.index}/${progress.total} stage=${progress.stage}" +
                (progress.failure?.let { " failure=$it" } ?: ""),
        )
        onProgress(progress)
    }

    private fun jsonRequest(token: String, fingerprint: String, body: JSONObject): JSONObject {
        val connection = (URL(uploadEndpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 30_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $token")
        }
        try {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body.toString()) }
            val code = connection.responseCode
            val content = (if (code in 200..299) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
            if (code == 401 || code == 403) ownerSession.reject(fingerprint)
            if (code !in 200..299) {
                val message = runCatching { JSONObject(content).optString("message") }.getOrNull()
                throw MediaAttachmentException(message?.takeIf(String::isNotBlank) ?: "附件服务不可用（HTTP $code）")
            }
            return JSONObject(content)
        } finally {
            connection.disconnect()
        }
    }

    private fun cacheLocally(uri: Uri, attachmentId: String, name: String): File? = runCatching {
        val directory = File(appContext.filesDir, "chat-media").apply { mkdirs() }
        val safeName = name.replace(Regex("[^A-Za-z0-9._-]+"), "-").takeLast(80).ifBlank { "attachment" }
        val target = File(directory, "$attachmentId-$safeName")
        resolver.openInputStream(uri)?.use { input -> target.outputStream().use(input::copyTo) }
            ?: return@runCatching null
        target
    }.getOrNull()

    private fun imageDimensions(file: File): Pair<Int, Int>? = runCatching {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        file.inputStream().use { BitmapFactory.decodeStream(it, null, options) }
        if (options.outWidth > 0 && options.outHeight > 0) options.outWidth to options.outHeight else null
    }.getOrNull()

    private companion object {
        const val MEDIA_LOG_TAG = "LoveHouseMedia"
        const val PRESIGN_TIMEOUT_MILLIS = 45_000L
        const val PUT_CONNECT_TIMEOUT_MILLIS = 20_000L
        const val PUT_TIMEOUT_MILLIS = 150_000L
    }
}
