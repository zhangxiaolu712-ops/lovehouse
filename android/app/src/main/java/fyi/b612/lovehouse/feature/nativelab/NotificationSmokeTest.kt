package fyi.b612.lovehouse.feature.nativelab

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import fyi.b612.lovehouse.MainActivity
import fyi.b612.lovehouse.R
import fyi.b612.lovehouse.core.navigation.AppDestination

private const val TestChannelId = "lovehouse_native_test"
private const val TestNotificationId = 6120

internal data class NotificationSmokeResult(
    val message: String,
    val needsNotificationSettings: Boolean = false,
)

internal fun sendTestNotification(context: Context): NotificationSmokeResult {
    val manager = context.getSystemService(NotificationManager::class.java)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        manager.createNotificationChannel(
            NotificationChannel(
                TestChannelId,
                "LoveHouse 测试通知",
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "仅用于验证 LoveHouse 原生通知能力"
            },
        )
    }

    if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) {
        return NotificationSmokeResult("系统通知已关闭，请在设置中开启后重试。", needsNotificationSettings = true)
    }

    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
        manager.getNotificationChannel(TestChannelId)?.importance == NotificationManager.IMPORTANCE_NONE
    ) {
        return NotificationSmokeResult("测试通知频道已关闭，请在设置中开启后重试。", needsNotificationSettings = true)
    }

    val reopenIntent = Intent(
        Intent.ACTION_VIEW,
        Uri.parse(AppDestination.NativeLab.deepLink),
        context,
        MainActivity::class.java,
    )
    val pendingIntent = PendingIntent.getActivity(
        context,
        TestNotificationId,
        reopenIntent,
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
    )
    val notification = NotificationCompat.Builder(context, TestChannelId)
        .setSmallIcon(R.drawable.ic_notification)
        .setContentTitle("LoveHouse 原生通知测试")
        .setContentText("点击这条通知，重新进入原生能力测试页。")
        .setContentIntent(pendingIntent)
        .setAutoCancel(true)
        .setPriority(NotificationCompat.PRIORITY_DEFAULT)
        .build()

    return runCatching {
        NotificationManagerCompat.from(context).notify(TestNotificationId, notification)
        NotificationSmokeResult("测试通知已发送。点击通知可重新进入 LoveHouse。")
    }.getOrElse {
        NotificationSmokeResult("通知发送失败，请检查系统通知设置后重试。")
    }
}
