package fyi.b612.lovehouse.feature.nativelab

import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.os.CancellationSignal
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import java.util.Locale

internal data class LocationSnapshot(
    val latitude: Double,
    val longitude: Double,
    val accuracyMeters: Float?,
    val provider: String,
)

internal data class LocationSmokeResult(
    val message: String,
    val needsLocationSettings: Boolean = false,
)

internal class LocationSmokeTest(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val locationManager = appContext.getSystemService(LocationManager::class.java)
    private var cancellationSignal: CancellationSignal? = null

    fun request(onResult: (LocationSmokeResult) -> Unit) {
        cancellationSignal?.cancel()

        val provider = listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .firstOrNull { candidate -> runCatching { locationManager.isProviderEnabled(candidate) }.getOrDefault(false) }

        if (provider == null) {
            onResult(LocationSmokeResult("定位服务未开启，请先在系统设置中开启定位。", needsLocationSettings = true))
            return
        }

        val signal = CancellationSignal()
        cancellationSignal = signal

        runCatching {
            LocationManagerCompat.getCurrentLocation(
                locationManager,
                provider,
                signal,
                ContextCompat.getMainExecutor(appContext),
            ) { location ->
                cancellationSignal = null
                onResult(
                    if (location == null) {
                        LocationSmokeResult("暂时没有取得当前位置，请到开阔处后重试。")
                    } else {
                        LocationSmokeResult(formatLocationSnapshot(location.toSnapshot()))
                    },
                )
            }
        }.onFailure {
            cancellationSignal = null
            onResult(LocationSmokeResult("定位失败，请确认权限和定位服务状态后重试。"))
        }
    }

    fun cancel() {
        cancellationSignal?.cancel()
        cancellationSignal = null
    }
}

private fun Location.toSnapshot() = LocationSnapshot(
    latitude = latitude,
    longitude = longitude,
    accuracyMeters = accuracy.takeIf { hasAccuracy() },
    provider = provider.orEmpty(),
)

internal fun formatLocationSnapshot(snapshot: LocationSnapshot): String {
    val provider = when (snapshot.provider) {
        LocationManager.GPS_PROVIDER -> "卫星定位"
        LocationManager.NETWORK_PROVIDER -> "网络定位"
        else -> "系统定位"
    }
    val accuracy = snapshot.accuracyMeters?.let { String.format(Locale.CHINA, "%.1f 米", it) } ?: "未知"
    return buildString {
        appendLine("纬度：${String.format(Locale.CHINA, "%.6f", snapshot.latitude)}")
        appendLine("经度：${String.format(Locale.CHINA, "%.6f", snapshot.longitude)}")
        appendLine("精度：$accuracy")
        append("来源：$provider（单次获取）")
    }
}
