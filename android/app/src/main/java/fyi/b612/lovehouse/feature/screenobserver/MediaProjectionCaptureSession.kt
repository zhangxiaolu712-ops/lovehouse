package fyi.b612.lovehouse.feature.screenobserver

import android.content.Context
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.view.WindowManager
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.withTimeout
import java.util.concurrent.atomic.AtomicBoolean

internal class MediaProjectionCaptureSession(
    context: Context,
    private val mediaProjection: MediaProjection,
    private val onProjectionStopped: () -> Unit,
) : ActiveScreenCapture {
    private val released = AtomicBoolean(false)
    private val captureThread = HandlerThread("LoveHouseScreenObserver").apply { start() }
    private val captureHandler = Handler(captureThread.looper)
    private val densityDpi = context.resources.configuration.densityDpi

    private var imageReader: ImageReader
    private var virtualDisplay: VirtualDisplay? = null
    private var pendingCapture: CompletableDeferred<Bitmap>? = null

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            releaseOutput()
            onProjectionStopped()
        }

        override fun onCapturedContentResize(width: Int, height: Int) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                resizeOutput(width, height)
            }
        }
    }

    init {
        val bounds = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            context.getSystemService(WindowManager::class.java).maximumWindowMetrics.bounds
        } else {
            @Suppress("DEPRECATION")
            context.resources.displayMetrics.run { android.graphics.Rect(0, 0, widthPixels, heightPixels) }
        }
        val width = bounds.width().coerceAtLeast(1)
        val height = bounds.height().coerceAtLeast(1)
        imageReader = createImageReader(width, height)
        mediaProjection.registerCallback(projectionCallback, captureHandler)
        try {
            virtualDisplay = mediaProjection.createVirtualDisplay(
                "LoveHouseScreenObserver",
                width,
                height,
                densityDpi,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader.surface,
                null,
                captureHandler,
            ) ?: error("系统没有创建屏幕捕获显示。")
        } catch (error: Throwable) {
            mediaProjection.unregisterCallback(projectionCallback)
            imageReader.close()
            captureThread.quitSafely()
            mediaProjection.stop()
            throw error
        }
    }

    override suspend fun captureFrame(): Bitmap {
        if (released.get()) error("屏幕观察会话已经结束。")
        val request = CompletableDeferred<Bitmap>()
        captureHandler.post {
            if (released.get()) {
                request.completeExceptionally(IllegalStateException("屏幕观察会话已经结束。"))
                return@post
            }
            pendingCapture?.completeExceptionally(IllegalStateException("上一帧仍在处理中。"))
            pendingCapture = request
            imageReader.acquireLatestImage()?.let(::completeCapture)
        }
        return try {
            withTimeout(FrameTimeoutMillis) { request.await() }
        } catch (_: TimeoutCancellationException) {
            throw IllegalStateException("暂时没有取得屏幕帧，请稍后重试。")
        } finally {
            captureHandler.post {
                if (pendingCapture === request) pendingCapture = null
            }
        }
    }

    fun stopAndRelease() {
        if (!released.compareAndSet(false, true)) return
        mediaProjection.unregisterCallback(projectionCallback)
        releaseOutputResources()
        mediaProjection.stop()
        captureThread.quitSafely()
    }

    private fun createImageReader(width: Int, height: Int): ImageReader =
        ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).also { reader ->
            reader.setOnImageAvailableListener({ availableReader ->
                if (pendingCapture != null) {
                    availableReader.acquireLatestImage()?.let(::completeCapture)
                }
            }, captureHandler)
        }

    private fun completeCapture(image: Image) {
        val request = pendingCapture
        pendingCapture = null
        if (request == null) {
            image.close()
            return
        }
        runCatching { image.toBitmap() }
            .onSuccess(request::complete)
            .onFailure(request::completeExceptionally)
        image.close()
    }

    private fun resizeOutput(width: Int, height: Int) {
        if (released.get() || width <= 0 || height <= 0) return
        pendingCapture?.completeExceptionally(IllegalStateException("屏幕尺寸正在变化，请稍后重试。"))
        pendingCapture = null
        val oldReader = imageReader
        val replacement = createImageReader(width, height)
        val display = virtualDisplay ?: run {
            replacement.close()
            return
        }
        display.resize(width, height, densityDpi)
        display.surface = replacement.surface
        imageReader = replacement
        oldReader.close()
    }

    private fun releaseOutput() {
        if (!released.compareAndSet(false, true)) return
        releaseOutputResources()
        captureThread.quitSafely()
    }

    private fun releaseOutputResources() {
        pendingCapture?.completeExceptionally(IllegalStateException("屏幕观察会话已经结束。"))
        pendingCapture = null
        virtualDisplay?.release()
        virtualDisplay = null
        imageReader.close()
    }

    private fun Image.toBitmap(): Bitmap {
        val plane = planes.firstOrNull() ?: error("屏幕帧没有可读取的像素数据。")
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        if (pixelStride <= 0 || rowStride <= 0) error("屏幕帧像素格式无效。")
        val paddedWidth = (rowStride / pixelStride).coerceAtLeast(width)
        val padded = Bitmap.createBitmap(paddedWidth, height, Bitmap.Config.ARGB_8888)
        plane.buffer.rewind()
        padded.copyPixelsFromBuffer(plane.buffer)
        if (paddedWidth == width) return padded
        return Bitmap.createBitmap(padded, 0, 0, width, height).also { padded.recycle() }
    }

    private companion object {
        const val FrameTimeoutMillis = 4_000L
    }
}
