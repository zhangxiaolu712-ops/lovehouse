package fyi.b612.lovehouse.feature.screenobserver

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class ScreenObserverStatus(val label: String) {
    Inactive("未开启"),
    Starting("正在启动"),
    Active("正在观察"),
    Stopped("已被系统/用户停止"),
}

data class ScreenObserverUiState(
    val status: ScreenObserverStatus = ScreenObserverStatus.Inactive,
    val message: String = "屏幕内容不会上传或长期保存。",
)

internal interface ActiveScreenCapture {
    suspend fun captureFrame(): Bitmap
}

object ScreenObserverRuntime {
    private val mutableState = MutableStateFlow(ScreenObserverUiState())
    val state = mutableState.asStateFlow()

    private val lock = Any()
    private var activeCapture: ActiveScreenCapture? = null

    fun authorizationRequested() {
        mutableState.value = ScreenObserverUiState(
            status = ScreenObserverStatus.Starting,
            message = "等待系统屏幕捕获授权…",
        )
    }

    fun authorizationDenied() {
        synchronized(lock) { activeCapture = null }
        mutableState.value = ScreenObserverUiState(
            status = ScreenObserverStatus.Inactive,
            message = "未获得屏幕捕获授权，屏幕观察没有开启。",
        )
    }

    fun startFailed(message: String = "屏幕观察启动失败，请稍后重试。") {
        synchronized(lock) { activeCapture = null }
        mutableState.value = ScreenObserverUiState(
            status = ScreenObserverStatus.Inactive,
            message = message,
        )
    }

    internal fun sessionStarted(capture: ActiveScreenCapture) {
        synchronized(lock) { activeCapture = capture }
        mutableState.value = ScreenObserverUiState(
            status = ScreenObserverStatus.Active,
            message = "屏幕观察会话有效，可按需截取当前一帧。",
        )
    }

    internal fun sessionStopped(bySystem: Boolean) {
        synchronized(lock) { activeCapture = null }
        mutableState.value = if (bySystem) {
            ScreenObserverUiState(
                status = ScreenObserverStatus.Stopped,
                message = "屏幕观察已被系统或系统隐私控件停止。再次开始需要重新授权。",
            )
        } else {
            ScreenObserverUiState(
                status = ScreenObserverStatus.Inactive,
                message = "屏幕观察已停止，临时预览已清理。",
            )
        }
    }

    suspend fun captureFrame(): Result<Bitmap> {
        val capture = synchronized(lock) { activeCapture }
            ?: return Result.failure(IllegalStateException("屏幕观察尚未开启。"))
        return runCatching { capture.captureFrame() }
    }
}
