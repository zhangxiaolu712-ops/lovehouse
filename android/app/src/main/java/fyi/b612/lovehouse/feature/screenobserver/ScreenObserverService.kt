package fyi.b612.lovehouse.feature.screenobserver

import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.IntentCompat
import fyi.b612.lovehouse.R

class ScreenObserverService : Service() {
    private var captureSession: MediaProjectionCaptureSession? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ActionStart -> startSession(intent)
            ActionStop -> stopSession(bySystem = false, stopProjection = true)
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        captureSession?.let {
            captureSession = null
            it.stopAndRelease()
            ScreenObserverRuntime.sessionStopped(bySystem = true)
        }
        super.onDestroy()
    }

    private fun startSession(intent: Intent) {
        if (captureSession != null) return
        val resultCode = intent.getIntExtra(ExtraResultCode, Activity.RESULT_CANCELED)
        val resultData = IntentCompat.getParcelableExtra(intent, ExtraResultData, Intent::class.java)
        if (resultCode != Activity.RESULT_OK || resultData == null) {
            ScreenObserverRuntime.startFailed("系统没有返回有效的屏幕捕获授权。")
            stopSelf()
            return
        }

        createNotificationChannel()
        ServiceCompat.startForeground(
            this,
            NotificationId,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION,
        )

        runCatching {
            val projectionManager = getSystemService(MediaProjectionManager::class.java)
            val projection = projectionManager.getMediaProjection(resultCode, resultData)
                ?: error("系统没有返回屏幕捕获会话。")
            MediaProjectionCaptureSession(applicationContext, projection) {
                stopSession(bySystem = true, stopProjection = false)
            }
        }.onSuccess { session ->
            captureSession = session
            ScreenObserverRuntime.sessionStarted(session)
        }.onFailure {
            ScreenObserverRuntime.startFailed("屏幕观察会话创建失败，请重新授权后再试。")
            ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
            stopSelf()
        }
    }

    private fun stopSession(bySystem: Boolean, stopProjection: Boolean) {
        val session = captureSession
        captureSession = null
        if (stopProjection) session?.stopAndRelease()
        ScreenObserverRuntime.sessionStopped(bySystem)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(
                NotificationChannelId,
                "屏幕观察",
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "屏幕观察运行状态"
                setShowBadge(false)
            },
        )
    }

    private fun buildNotification() = NotificationCompat.Builder(this, NotificationChannelId)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("LoveHouse · 屏幕观察中")
        .setContentText("可返回原生能力测试截取一帧，或随时停止观察。")
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                0,
                Intent(Intent.ACTION_VIEW, Uri.parse("lovehouse://settings/native-lab")).setPackage(packageName),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .addAction(
            0,
            "停止观察",
            PendingIntent.getService(
                this,
                1,
                stopIntent(this),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setCategory(NotificationCompat.CATEGORY_SERVICE)
        .setPriority(NotificationCompat.PRIORITY_LOW)
        .build()

    companion object {
        private const val ActionStart = "fyi.b612.lovehouse.screenobserver.START"
        private const val ActionStop = "fyi.b612.lovehouse.screenobserver.STOP"
        private const val ExtraResultCode = "result_code"
        private const val ExtraResultData = "result_data"
        private const val NotificationChannelId = "screen_observer"
        private const val NotificationId = 2105

        fun startIntent(context: Context, resultCode: Int, resultData: Intent): Intent =
            Intent(context, ScreenObserverService::class.java)
                .setAction(ActionStart)
                .putExtra(ExtraResultCode, resultCode)
                .putExtra(ExtraResultData, resultData)

        fun stopIntent(context: Context): Intent =
            Intent(context, ScreenObserverService::class.java).setAction(ActionStop)
    }
}
