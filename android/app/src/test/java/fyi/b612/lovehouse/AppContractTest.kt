package fyi.b612.lovehouse

import android.bluetooth.BluetoothGattCharacteristic
import fyi.b612.lovehouse.core.devicecontext.BatteryContext
import fyi.b612.lovehouse.core.devicecontext.BluetoothContext
import fyi.b612.lovehouse.core.devicecontext.ChargingSource
import fyi.b612.lovehouse.core.devicecontext.DeviceContextSnapshot
import fyi.b612.lovehouse.core.devicecontext.NetworkContext
import fyi.b612.lovehouse.core.devicecontext.NetworkTransport
import fyi.b612.lovehouse.core.devicecontext.ScreenObserverContext
import fyi.b612.lovehouse.core.devicecontext.formatDeviceContextSnapshot
import fyi.b612.lovehouse.core.navigation.AppDestination
import fyi.b612.lovehouse.core.permissions.NativeCapability
import fyi.b612.lovehouse.feature.nativelab.LocationSnapshot
import fyi.b612.lovehouse.feature.nativelab.biometricAvailabilityMessage
import fyi.b612.lovehouse.feature.nativelab.formatFileSize
import fyi.b612.lovehouse.feature.nativelab.formatLocationSnapshot
import fyi.b612.lovehouse.feature.nativelab.formatGattProperties
import fyi.b612.lovehouse.feature.screenobserver.ScreenObserverStatus
import androidx.biometric.BiometricManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneOffset

class AppContractTest {
    @Test
    fun `primary navigation contains exactly the five phase zero rooms`() {
        assertEquals(
            listOf("首页", "聊天", "记忆", "工程", "设置"),
            AppDestination.primary.map { it.label },
        )
    }

    @Test
    fun `all routes have stable native deep links`() {
        AppDestination.entries.forEach { destination ->
            assertTrue(destination.deepLink.startsWith("lovehouse://"))
            assertFalse(destination.deepLink.contains("b612.fyi"))
        }
    }

    @Test
    fun `native lab exposes phase zero capabilities and BLE`() {
        assertEquals(
            listOf(
                "照片",
                "相机",
                "文件",
                "麦克风",
                "位置",
                "通知",
                "蓝牙 / BLE",
                "分享",
                "生物识别",
                "深链",
            ),
            NativeCapability.entries.map { it.label },
        )
        assertTrue(NativeCapability.entries.none { "以后" in it.description })
    }

    @Test
    fun `gatt properties have compact Chinese labels`() {
        assertEquals(
            "读 / 通知",
            formatGattProperties(
                BluetoothGattCharacteristic.PROPERTY_READ or BluetoothGattCharacteristic.PROPERTY_NOTIFY,
            ),
        )
        assertEquals("无公开属性", formatGattProperties(0))
    }

    @Test
    fun `screen observer session states have explicit Chinese labels`() {
        assertEquals(
            listOf("未开启", "正在启动", "正在观察", "已被系统/用户停止"),
            ScreenObserverStatus.entries.map { it.label },
        )
    }

    @Test
    fun `device context snapshot has a compact Chinese rendering`() {
        assertEquals(
            "电量：63%\n" +
                "充电：否\n" +
                "充电来源：USB\n" +
                "网络：Wi-Fi\n" +
                "网络可用：是\n" +
                "已验证联网：是\n" +
                "按流量计费：否\n" +
                "VPN：开启\n" +
                "蓝牙：开启\n" +
                "BLE 权限：已授权\n" +
                "屏幕观察：开启\n" +
                "更新时间：00:00:00",
            formatDeviceContextSnapshot(
                DeviceContextSnapshot(
                    capturedAtEpochMillis = 0,
                    battery = BatteryContext(
                        levelPercent = 63,
                        isCharging = false,
                        chargingSource = ChargingSource.Usb,
                        isLow = false,
                    ),
                    network = NetworkContext(
                        hasNetwork = true,
                        isValidated = true,
                        transport = NetworkTransport.Wifi,
                        isMetered = false,
                        isVpnActive = true,
                    ),
                    bluetooth = BluetoothContext(
                        isSupported = true,
                        isEnabled = true,
                        blePermissionsGranted = true,
                    ),
                    screenObserver = ScreenObserverContext(isActive = true),
                ),
                zoneId = ZoneOffset.UTC,
            ),
        )
    }

    @Test
    fun `native lab is isolated from the formal settings destination`() {
        assertEquals(null, AppDestination.selectedForRoute(AppDestination.NativeLab.route))
    }

    @Test
    fun `selected resource sizes have compact user facing metadata`() {
        assertEquals("未知", formatFileSize(null))
        assertEquals("512 B", formatFileSize(512))
        assertEquals("1.5 KB", formatFileSize(1_536))
        assertEquals("2.0 MB", formatFileSize(2_097_152))
    }

    @Test
    fun `location result contains basic acceptance fields in Chinese`() {
        assertEquals(
            "纬度：31.230416\n经度：121.473701\n精度：12.5 米\n来源：卫星定位（单次获取）",
            formatLocationSnapshot(
                LocationSnapshot(
                    latitude = 31.230416,
                    longitude = 121.473701,
                    accuracyMeters = 12.5f,
                    provider = "gps",
                ),
            ),
        )
    }

    @Test
    fun `unsupported biometrics has a Chinese status`() {
        assertEquals(
            "这台设备没有可用的生物识别硬件。",
            biometricAvailabilityMessage(BiometricManager.BIOMETRIC_ERROR_NO_HARDWARE),
        )
    }
}
