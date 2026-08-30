package fyi.b612.lovehouse.core.devicecontext

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

interface DeviceContextProvider {
    fun getCurrentDeviceContext(): DeviceContextSnapshot
}

data class DeviceContextSnapshot(
    val capturedAtEpochMillis: Long,
    val battery: BatteryContext,
    val network: NetworkContext,
    val bluetooth: BluetoothContext,
    val screenObserver: ScreenObserverContext,
)

data class BatteryContext(
    val levelPercent: Int?,
    val isCharging: Boolean,
    val chargingSource: ChargingSource,
    val isLow: Boolean,
)

enum class ChargingSource(val label: String) {
    Usb("USB"),
    Ac("AC"),
    Wireless("无线"),
    Unknown("未知"),
}

data class NetworkContext(
    val hasNetwork: Boolean,
    val isValidated: Boolean,
    val transport: NetworkTransport,
    val isMetered: Boolean,
    val isVpnActive: Boolean,
)

enum class NetworkTransport(val label: String) {
    Wifi("Wi-Fi"),
    Cellular("移动网络"),
    Ethernet("以太网"),
    Vpn("VPN"),
    Other("其他"),
    None("无"),
}

data class BluetoothContext(
    val isSupported: Boolean,
    val isEnabled: Boolean?,
    val blePermissionsGranted: Boolean,
)

data class ScreenObserverContext(
    val isActive: Boolean,
)

fun formatDeviceContextSnapshot(
    snapshot: DeviceContextSnapshot,
    zoneId: ZoneId = ZoneId.systemDefault(),
): String {
    val batteryLevel = snapshot.battery.levelPercent?.let { "$it%" } ?: "未知"
    val bluetoothState = when {
        !snapshot.bluetooth.isSupported -> "不支持"
        snapshot.bluetooth.isEnabled == true -> "开启"
        snapshot.bluetooth.isEnabled == false -> "关闭"
        else -> "未知（需要蓝牙权限）"
    }
    val capturedTime = DateTimeFormatter.ofPattern("HH:mm:ss")
        .withZone(zoneId)
        .format(Instant.ofEpochMilli(snapshot.capturedAtEpochMillis))

    return listOf(
        "电量：$batteryLevel${if (snapshot.battery.isLow) "（低电量）" else ""}",
        "充电：${snapshot.battery.isCharging.asChineseYesNo()}",
        "充电来源：${snapshot.battery.chargingSource.label}",
        "网络：${snapshot.network.transport.label}",
        "网络可用：${snapshot.network.hasNetwork.asChineseYesNo()}",
        "已验证联网：${snapshot.network.isValidated.asChineseYesNo()}",
        "按流量计费：${snapshot.network.isMetered.asChineseYesNo()}",
        "VPN：${if (snapshot.network.isVpnActive) "开启" else "关闭"}",
        "蓝牙：$bluetoothState",
        "BLE 权限：${if (snapshot.bluetooth.blePermissionsGranted) "已授权" else "未授权"}",
        "屏幕观察：${if (snapshot.screenObserver.isActive) "开启" else "关闭"}",
        "更新时间：$capturedTime",
    ).joinToString("\n")
}

private fun Boolean.asChineseYesNo(): String = if (this) "是" else "否"
