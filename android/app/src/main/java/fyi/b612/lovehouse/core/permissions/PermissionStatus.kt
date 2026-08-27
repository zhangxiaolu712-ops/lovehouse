package fyi.b612.lovehouse.core.permissions

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
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
    Camera("相机", "拍", "以后在明确操作后，从 LoveHouse 内拍摄照片。"),
    Files("文件", "文", "通过系统文件选择器打开一个文件。"),
    Microphone("麦克风", "麦", "以后只在明确操作后录制语音。"),
    Location("位置", "位", "以后只在具体场景需要时附加位置。"),
    Notifications("通知", "通", "以后发送由你主动开启的 LoveHouse 提醒。"),
    Share("分享", "享", "通过 Android 系统分享面板发送内容。"),
    Biometrics("生物识别", "锁", "以后使用设备凭据保护私密房间。"),
    DeepLink("深链", "链", "通过 lovehouse:// 打开稳定的原生页面。"),
}

enum class PermissionState(val label: String) {
    Granted("可使用"),
    Denied("未授权"),
    NotRequested("待接入"),
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
        val permission = permissionFor(capability) ?: return when (capability) {
            NativeCapability.Photos,
            NativeCapability.Files,
            NativeCapability.Share,
            NativeCapability.DeepLink,
            -> PermissionState.NotRequired

            NativeCapability.Biometrics -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                PermissionState.NotRequested
            } else {
                PermissionState.Unsupported
            }

            else -> PermissionState.NotRequested
        }

        if (permission !in declared) return PermissionState.NotRequested
        return if (ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED) {
            PermissionState.Granted
        } else {
            PermissionState.Denied
        }
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
        NativeCapability.Notifications -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.POST_NOTIFICATIONS
        } else {
            null
        }
    }
}
