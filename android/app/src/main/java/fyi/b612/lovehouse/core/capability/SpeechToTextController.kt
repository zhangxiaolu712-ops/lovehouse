package fyi.b612.lovehouse.core.capability

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import java.util.Locale

const val STT_UNAVAILABLE_MESSAGE = "当前设备没有可用的系统语音识别服务"

data class SpeechToTextState(
    val listening: Boolean = false,
    val processing: Boolean = false,
    val transcript: String = "",
    val finished: Boolean = false,
    val error: String? = null,
)

class SpeechToTextController(
    context: Context,
    private val onState: (SpeechToTextState) -> Unit,
) : RecognitionListener {
    private val recognizer = SpeechRecognizer.createSpeechRecognizer(context.applicationContext).also { it.setRecognitionListener(this) }
    private var latest = SpeechToTextState()

    fun start() {
        latest = SpeechToTextState(listening = true)
        onState(latest)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        }
        runCatching { recognizer.startListening(intent) }.onFailure {
            update(listening = false, processing = false, finished = false, error = "无法开始语音识别，请稍后重试")
        }
    }

    fun stop() { if (latest.listening) runCatching { recognizer.stopListening() } }
    fun dismiss() { runCatching { recognizer.cancel() }; latest = SpeechToTextState(); onState(latest) }
    fun destroy() = recognizer.destroy()

    override fun onReadyForSpeech(params: Bundle?) = Unit
    override fun onBeginningOfSpeech() = Unit
    override fun onRmsChanged(rmsdB: Float) = Unit
    override fun onBufferReceived(buffer: ByteArray?) = Unit
    override fun onEndOfSpeech() = update(listening = false, processing = true)
    override fun onError(error: Int) {
        val message = when (error) {
            SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "没有识别到清晰语音，请重新按住说话"
            SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "语音识别网络不可用"
            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "需要麦克风权限才能语音输入"
            else -> "语音识别暂时不可用（$error）"
        }
        update(listening = false, processing = false, finished = latest.transcript.isNotBlank(), error = message)
    }
    override fun onResults(results: Bundle?) {
        val text = results.bestTranscript().ifBlank { latest.transcript }
        latest = latest.copy(listening = false, processing = false, transcript = text, finished = text.isNotBlank(), error = null)
        onState(latest)
    }
    override fun onPartialResults(partialResults: Bundle?) {
        val text = partialResults.bestTranscript()
        if (text.isNotBlank()) update(transcript = text, error = null)
    }
    override fun onEvent(eventType: Int, params: Bundle?) = Unit

    private fun update(
        listening: Boolean = latest.listening,
        processing: Boolean = latest.processing,
        transcript: String = latest.transcript,
        finished: Boolean = latest.finished,
        error: String? = latest.error,
    ) {
        latest = SpeechToTextState(listening, processing, transcript, finished, error)
        onState(latest)
    }
}

private fun Bundle?.bestTranscript(): String =
    this?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull().orEmpty()
