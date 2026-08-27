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
    Photos("Photos", "PH", "Pick images without exposing the whole library."),
    Camera("Camera", "CA", "Capture a photo inside a future LoveHouse flow."),
    Files("Files", "FI", "Open and save documents through the system picker."),
    Microphone("Microphone", "MI", "Record voice only after a clear user action."),
    Location("Location", "LO", "Attach a place when a scene explicitly needs it."),
    Notifications("Notifications", "NO", "Deliver opt-in LoveHouse reminders."),
    Share("Share", "SH", "Send content through the Android Sharesheet."),
    Biometrics("Biometrics", "BI", "Protect private rooms with device credentials."),
    DeepLink("Deep Link", "DL", "Open a stable native destination from lovehouse://."),
}

enum class PermissionState(val label: String) {
    Granted("Ready"),
    Denied("Denied"),
    NotRequested("Planned"),
    NotRequired("Ready"),
    Unsupported("Unavailable"),
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
        NativeCapability.Photos -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.READ_MEDIA_IMAGES
        } else {
            Manifest.permission.READ_EXTERNAL_STORAGE
        }

        NativeCapability.Camera -> Manifest.permission.CAMERA
        NativeCapability.Microphone -> Manifest.permission.RECORD_AUDIO
        NativeCapability.Location -> Manifest.permission.ACCESS_COARSE_LOCATION
        NativeCapability.Notifications -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Manifest.permission.POST_NOTIFICATIONS
        } else {
            null
        }

        NativeCapability.Files,
        NativeCapability.Share,
        NativeCapability.Biometrics,
        NativeCapability.DeepLink,
        -> null
    }
}
