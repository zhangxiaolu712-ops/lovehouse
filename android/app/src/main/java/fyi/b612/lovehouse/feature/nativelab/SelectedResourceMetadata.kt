package fyi.b612.lovehouse.feature.nativelab

import android.content.ContentResolver
import android.net.Uri
import android.provider.OpenableColumns
import java.util.Locale

internal data class SelectedResourceMetadata(
    val displayName: String,
    val mimeType: String,
    val sizeBytes: Long?,
) {
    fun asDisplayText(): String = buildString {
        appendLine("名称：$displayName")
        appendLine("类型：$mimeType")
        append("大小：${formatFileSize(sizeBytes)}")
    }
}

internal fun ContentResolver.readSelectedResource(uri: Uri): SelectedResourceMetadata {
    var displayName: String? = null
    var sizeBytes: Long? = null

    query(
        uri,
        arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
        null,
        null,
        null,
    )?.use { cursor ->
        if (cursor.moveToFirst()) {
            val nameIndex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
            val sizeIndex = cursor.getColumnIndex(OpenableColumns.SIZE)
            if (nameIndex >= 0 && !cursor.isNull(nameIndex)) displayName = cursor.getString(nameIndex)
            if (sizeIndex >= 0 && !cursor.isNull(sizeIndex)) sizeBytes = cursor.getLong(sizeIndex)
        }
    }

    return SelectedResourceMetadata(
        displayName = displayName ?: "未命名项目",
        mimeType = getType(uri) ?: "未知类型",
        sizeBytes = sizeBytes,
    )
}

internal fun formatFileSize(sizeBytes: Long?): String = when {
    sizeBytes == null || sizeBytes < 0 -> "未知"
    sizeBytes < 1_024 -> "$sizeBytes B"
    sizeBytes < 1_048_576 -> String.format(Locale.CHINA, "%.1f KB", sizeBytes / 1_024.0)
    else -> String.format(Locale.CHINA, "%.1f MB", sizeBytes / 1_048_576.0)
}
