package fyi.b612.lovehouse.core.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.core.content.ContextCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class NativeCapability(
    val label: String,
    val shortLabel: String,
    val description: String,
) {
    Photos("照片", "照", "通过系统照片选择器，只把选中的一张照片交给 LoveHouse。"),
    Camera("相机", "拍", "点击后调用系统相机，拍摄结果只留在当前预览。"),
    Files("文件", "文", "通过系统文件选择器打开一个文件。"),
    Microphone("麦克风", "麦", "点击开始或停止最小录音测试，录音只保存在本机缓存。"),
    Location("位置", "位", "点击后获取一次当前位置，不会后台或持续追踪。"),
    Notifications("通知", "通", "按需授权并发送一条可重新打开 LoveHouse 的测试通知。"),
    Bluetooth("蓝牙 / BLE", "蓝", "主动扫描附近 BLE 设备，选择后连接并查看基础 GATT 信息。"),
    Share("分享", "享", "通过 Android 系统分享面板发送内容。"),
    Biometrics("生物识别", "锁", "使用 AndroidX Biometric 验证设备能力，不作为启动锁。"),
    DeepLink("深链", "链", "通过 lovehouse://settings/native-lab 打开当前测试页。"),
}

enum class PermissionState(val label: String) {
    Granted("可使用"),
    Denied("未授权"),
    NotRequested("待授权"),
    NotRequired("可使用"),
    Unsupported("不可用"),
}

data class CapabilityPermissionStatus(
    val capability: NativeCapability,
    val state: PermissionState,
)

interface PermissionStatusProvider {
    val statuses: StateFlow<List<CapabilityPermissionStatus>>
    fun refresh()
}

class AndroidPermissionStatusProvider(
    private val context: Context,
) : PermissionStatusProvider {
    private val mutableStatuses = MutableStateFlow(readStatuses())
    override val statuses: StateFlow<List<CapabilityPermissionStatus>> = mutableStatuses.asStateFlow()

    override fun refresh() {
        mutableStatuses.value = readStatuses()
    }

    private fun readStatuses(): List<CapabilityPermissionStatus> {
        val declared = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?.toSet()
            .orEmpty()

        return NativeCapability.entries.map { capability ->
            CapabilityPermissionStatus(capability, stateFor(capability, declared))
        }
    }

    private fun stateFor(capability: NativeCapability, declared: Set<String>): PermissionState {
        if (!hasRequiredHardware(capability)) return PermissionState.Unsupported

        val permission = permissionFor(capability) ?: return when (capability) {
            NativeCapability.Photos,
            NativeCapability.Files,
            NativeCapability.Share,
            NativeCapability.DeepLink,
            -> PermissionState.NotRequired

            NativeCapability.Biometrics -> biometricState()

            else -> PermissionState.NotRequested
        }

        if (permission !in declared) return PermissionState.NotRequested
        return if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
            PermissionState.Granted
        } else {
            PermissionState.Denied
        }
    }

    private fun hasRequiredHardware(capability: NativeCapability): Boolean = when (capability) {
        NativeCapability.Camera -> context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA_ANY)
        NativeCapability.Microphone -> context.packageManager.hasSystemFeature(PackageManager.FEATURE_MICROPHONE)
        NativeCapability.Location -> context.packageManager.hasSystemFeature(PackageManager.FEATURE_LOCATION)
        NativeCapability.Bluetooth -> context.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH_LE)
        else -> true
    }

    private fun biometricState(): PermissionState = when (
        BiometricManager.from(context).canAuthenticate(BIOMETRIC_STRONG or DEVICE_CREDENTIAL)
    ) {
        BiometricManager.BIOMETRIC_SUCCESS -> PermissionState.Granted
        BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED -> PermissionState.Denied
        else -> PermissionState.Unsupported
    }

    private fun permissionFor(capability: NativeCapability): String? = when (capability) {
        NativeCapability.Photos,
        NativeCapability.Files,
        NativeCapability.Share,
        NativeCapability.Biometrics,
        NativeCapability.DeepLink,
        -> null

        NativeCapability.Camera -> Manifest.permission.CAMERA
        NativeCapability.Microphone -> Manifest.permission.RECORD_AUDIO
        NativeCapability.Location -> Manifest.permission.ACCESS_COARSE_LOCATION
        NativeCapability.Bluetooth -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            Manifest.permission.BLUETOOTH_SCAN
        } else {
            Manifest.permission.ACCESS_FINE_LOCATION
        }
        NativeCapability.Notifications -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.POST_NOTIFICATIONS
        } else {
            null
        }
    }
}
