package fyi.b612.lovehouse.feature.nativelab

import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.os.SystemClock
import java.io.File
import java.util.Locale

internal class AudioSmokeRecorder(
    private val context: Context,
) {
    private var recorder: MediaRecorder? = null
    private var outputFile: File? = null
    private var startedAt: Long? = null

    val isRecording: Boolean
        get() = recorder != null

    fun start(): String {
        if (isRecording) return "录音已经开始，请先停止。"

        val file = File.createTempFile("lovehouse-audio-", ".m4a", context.cacheDir)
        val nextRecorder = createRecorder(context)

        return runCatching {
            nextRecorder.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(96_000)
                setAudioSamplingRate(44_100)
                setOutputFile(file.absolutePath)
                prepare()
                start()
            }
            recorder = nextRecorder
            outputFile = file
            startedAt = SystemClock.elapsedRealtime()
            "正在录音。点击“停止录音”完成 smoke test。"
        }.getOrElse {
            nextRecorder.releaseSafely()
            file.delete()
            "录音启动失败，请确认麦克风没有被其他应用占用后重试。"
        }
    }

    fun stop(): String {
        val activeRecorder = recorder ?: return "当前没有正在进行的录音。"
        val file = outputFile
        val durationMillis = startedAt?.let { SystemClock.elapsedRealtime() - it } ?: 0L

        recorder = null
        outputFile = null
        startedAt = null

        return runCatching {
            activeRecorder.stop()
            val size = file?.takeIf(File::exists)?.length()
            val seconds = String.format(Locale.CHINA, "%.1f", durationMillis / 1_000.0)
            "录音 smoke test 成功：$seconds 秒，${formatFileSize(size)}；仅保存在本机缓存。"
        }.getOrElse {
            file?.delete()
            "录音停止失败，请录制一秒以上后重试。"
        }.also {
            activeRecorder.releaseSafely()
        }
    }

    fun cancel() {
        recorder?.releaseSafely()
        recorder = null
        outputFile?.delete()
        outputFile = null
        startedAt = null
    }

    @Suppress("DEPRECATION")
    private fun createRecorder(context: Context): MediaRecorder =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) MediaRecorder(context) else MediaRecorder()
}

private fun MediaRecorder.releaseSafely() {
    runCatching { reset() }
    runCatching { release() }
}
