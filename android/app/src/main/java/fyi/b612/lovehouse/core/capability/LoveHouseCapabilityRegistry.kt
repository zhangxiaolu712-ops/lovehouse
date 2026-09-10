package fyi.b612.lovehouse.core.capability

import android.Manifest
import android.content.Context
import android.os.Build
import android.speech.SpeechRecognizer
import fyi.b612.lovehouse.core.permissions.CapabilityPermissionStatus
import fyi.b612.lovehouse.core.permissions.NativeCapability
import fyi.b612.lovehouse.core.permissions.PermissionState
import fyi.b612.lovehouse.core.permissions.PermissionStatusProvider
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

enum class LoveHouseCapabilityId(val value: String) {
    AttachmentPhoto("attachment.photo"),
    AttachmentFile("attachment.file"),
    AttachmentLocation("attachment.location"),
    AttachmentCamera("attachment.camera"),
    MediaEphemeralUpload("media.upload.ephemeral"),
    MediaSecureMaterialize("media.secure_materialize"),
    VoiceMicrophone("voice.microphone"),
    VoiceRecording("voice.recording"),
    VoiceStt("voice.stt"),
    VoiceTts("voice.tts"),
    DeviceLocation("device.location"),
    DeviceCamera("device.camera"),
    DeviceFilePicker("device.file_picker"),
    DevicePhotoPicker("device.photo_picker"),
    DeviceNotifications("device.notifications"),
    DeviceBluetooth("device.bluetooth"),
    DeviceShare("device.share"),
    DeviceBiometrics("device.biometrics"),
    DeviceDeepLink("device.deep_link"),
}

enum class LoveHouseCapabilityKind { Attachment, Media, Voice, Device }
enum class CapabilityAvailability { Available, Partial, Unavailable }
enum class ProviderConsumption { Supported, Unsupported, NotConnected }
enum class CapabilityAttachmentType { Photo, File, Location, Audio }
enum class CapabilityLifecycle { Local, Ephemeral, Durable }

data class ProviderCapabilityProfile(
    val providerId: String,
    val acceptedAttachmentTypes: Set<CapabilityAttachmentType>,
    val maxAttachmentItems: Int,
    val supportsTextWithAttachments: Boolean,
    val supportedAttachmentLifecycles: Set<CapabilityLifecycle>,
    val consumesVoiceTranscriptAsText: Boolean,
) {
    val supportsAttachments: Boolean
        get() = acceptedAttachmentTypes.isNotEmpty() && maxAttachmentItems > 0
}

data class LoveHouseCapability(
    val id: LoveHouseCapabilityId,
    val label: String,
    val kind: LoveHouseCapabilityKind,
    val availability: CapabilityAvailability,
    val requiredAndroidPermissions: Set<String> = emptySet(),
    val providerConsumption: Map<String, ProviderConsumption> = emptyMap(),
    val unavailableReason: String? = null,
)

data class LoveHouseCapabilityState(
    val capabilities: List<LoveHouseCapability> = emptyList(),
) {
    fun capability(id: LoveHouseCapabilityId): LoveHouseCapability? = capabilities.firstOrNull { it.id == id }
}

interface LoveHouseCapabilityRegistry {
    val state: StateFlow<LoveHouseCapabilityState>
    fun refresh()
    fun capability(id: LoveHouseCapabilityId): LoveHouseCapability? = state.value.capability(id)
}

class AndroidLoveHouseCapabilityRegistry(
    private val context: Context,
    private val permissions: PermissionStatusProvider,
    private val providerProfiles: List<ProviderCapabilityProfile>,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) : LoveHouseCapabilityRegistry {
    private val mutableState = MutableStateFlow(
        buildLoveHouseCapabilities(
            permissionStatuses = permissions.statuses.value,
            speechRecognitionAvailable = SpeechRecognizer.isRecognitionAvailable(context),
            providerProfiles = providerProfiles,
            sdkInt = Build.VERSION.SDK_INT,
        ),
    )
    override val state: StateFlow<LoveHouseCapabilityState> = mutableState.asStateFlow()

    init {
        scope.launch {
            permissions.statuses.collect { statuses ->
                mutableState.value = buildLoveHouseCapabilities(
                    permissionStatuses = statuses,
                    speechRecognitionAvailable = SpeechRecognizer.isRecognitionAvailable(context),
                    providerProfiles = providerProfiles,
                    sdkInt = Build.VERSION.SDK_INT,
                )
            }
        }
    }

    override fun refresh() {
        permissions.refresh()
        mutableState.value = buildLoveHouseCapabilities(
            permissionStatuses = permissions.statuses.value,
            speechRecognitionAvailable = SpeechRecognizer.isRecognitionAvailable(context),
            providerProfiles = providerProfiles,
            sdkInt = Build.VERSION.SDK_INT,
        )
    }
}

internal fun buildLoveHouseCapabilities(
    permissionStatuses: List<CapabilityPermissionStatus>,
    speechRecognitionAvailable: Boolean,
    providerProfiles: List<ProviderCapabilityProfile>,
    sdkInt: Int,
): LoveHouseCapabilityState {
    val permissionByCapability = permissionStatuses.associate { it.capability to it.state }
    fun permissionAvailability(capability: NativeCapability): Pair<CapabilityAvailability, String?> =
        when (permissionByCapability[capability]) {
            PermissionState.Granted, PermissionState.NotRequired -> CapabilityAvailability.Available to null
            PermissionState.Denied, PermissionState.NotRequested -> CapabilityAvailability.Unavailable to "需要 Android ${capability.label}权限"
            PermissionState.Unsupported -> CapabilityAvailability.Unavailable to "当前设备不支持${capability.label}"
            null -> CapabilityAvailability.Unavailable to "${capability.label}状态尚未读取"
        }

    fun attachmentConsumers(type: CapabilityAttachmentType): Map<String, ProviderConsumption> = providerProfiles.associate { profile ->
        profile.providerId to if (type in profile.acceptedAttachmentTypes) ProviderConsumption.Supported else ProviderConsumption.Unsupported
    }
    val voiceConsumers = providerProfiles.associate { profile ->
        profile.providerId to if (profile.consumesVoiceTranscriptAsText) ProviderConsumption.Supported else ProviderConsumption.Unsupported
    }
    val notConnectedConsumers = providerProfiles.associate { it.providerId to ProviderConsumption.NotConnected }
    fun native(
        id: LoveHouseCapabilityId,
        label: String,
        nativeCapability: NativeCapability,
        permissionNames: Set<String> = emptySet(),
    ): LoveHouseCapability {
        val (availability, reason) = permissionAvailability(nativeCapability)
        return LoveHouseCapability(
            id = id,
            label = label,
            kind = LoveHouseCapabilityKind.Device,
            availability = availability,
            requiredAndroidPermissions = permissionNames,
            providerConsumption = notConnectedConsumers,
            unavailableReason = reason,
        )
    }

    val (locationAvailability, locationReason) = permissionAvailability(NativeCapability.Location)
    val (cameraAvailability, cameraReason) = permissionAvailability(NativeCapability.Camera)
    val (microphoneAvailability, microphoneReason) = permissionAvailability(NativeCapability.Microphone)
    val notificationPermission = if (sdkInt >= 33) setOf(Manifest.permission.POST_NOTIFICATIONS) else emptySet()
    val bluetoothPermissions = if (sdkInt >= 31) {
        setOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        setOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    return LoveHouseCapabilityState(
        capabilities = listOf(
            LoveHouseCapability(LoveHouseCapabilityId.AttachmentPhoto, "照片附件", LoveHouseCapabilityKind.Attachment, CapabilityAvailability.Available, providerConsumption = attachmentConsumers(CapabilityAttachmentType.Photo)),
            LoveHouseCapability(LoveHouseCapabilityId.AttachmentFile, "文件附件", LoveHouseCapabilityKind.Attachment, CapabilityAvailability.Available, providerConsumption = attachmentConsumers(CapabilityAttachmentType.File)),
            LoveHouseCapability(LoveHouseCapabilityId.AttachmentLocation, "定位附件", LoveHouseCapabilityKind.Attachment, locationAvailability, setOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION), attachmentConsumers(CapabilityAttachmentType.Location), locationReason),
            LoveHouseCapability(
                LoveHouseCapabilityId.AttachmentCamera,
                "相机原图附件",
                LoveHouseCapabilityKind.Attachment,
                if (cameraAvailability == CapabilityAvailability.Available) CapabilityAvailability.Partial else CapabilityAvailability.Unavailable,
                setOf(Manifest.permission.CAMERA),
                attachmentConsumers(CapabilityAttachmentType.Photo),
                cameraReason ?: "相机可拍摄，但原图附件 transport 尚未接通",
            ),
            LoveHouseCapability(LoveHouseCapabilityId.MediaEphemeralUpload, "临时媒体上传", LoveHouseCapabilityKind.Media, CapabilityAvailability.Available, providerConsumption = providerProfiles.associate { it.providerId to if (it.supportsAttachments && CapabilityLifecycle.Ephemeral in it.supportedAttachmentLifecycles) ProviderConsumption.Supported else ProviderConsumption.Unsupported }),
            LoveHouseCapability(LoveHouseCapabilityId.MediaSecureMaterialize, "受控媒体读取与清理", LoveHouseCapabilityKind.Media, CapabilityAvailability.Available, providerConsumption = providerProfiles.associate { it.providerId to if (it.supportsAttachments) ProviderConsumption.Supported else ProviderConsumption.Unsupported }),
            LoveHouseCapability(LoveHouseCapabilityId.VoiceMicrophone, "麦克风", LoveHouseCapabilityKind.Voice, microphoneAvailability, setOf(Manifest.permission.RECORD_AUDIO), voiceConsumers, microphoneReason),
            LoveHouseCapability(LoveHouseCapabilityId.VoiceRecording, "本地录音", LoveHouseCapabilityKind.Voice, microphoneAvailability, setOf(Manifest.permission.RECORD_AUDIO), voiceConsumers, microphoneReason),
            LoveHouseCapability(LoveHouseCapabilityId.VoiceStt, "系统语音识别", LoveHouseCapabilityKind.Voice, if (microphoneAvailability == CapabilityAvailability.Available && speechRecognitionAvailable) CapabilityAvailability.Available else CapabilityAvailability.Unavailable, setOf(Manifest.permission.RECORD_AUDIO), voiceConsumers, when { microphoneReason != null -> microphoneReason; !speechRecognitionAvailable -> "当前设备没有可用的系统语音识别服务"; else -> null }),
            LoveHouseCapability(LoveHouseCapabilityId.VoiceTts, "文字朗读", LoveHouseCapabilityKind.Voice, CapabilityAvailability.Unavailable, providerConsumption = notConnectedConsumers, unavailableReason = "LoveHouse 尚未接入可验证的 TTS 实现"),
            native(LoveHouseCapabilityId.DeviceLocation, "一次性位置", NativeCapability.Location, setOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION)),
            native(LoveHouseCapabilityId.DeviceCamera, "相机", NativeCapability.Camera, setOf(Manifest.permission.CAMERA)),
            native(LoveHouseCapabilityId.DeviceFilePicker, "文件选择", NativeCapability.Files),
            native(LoveHouseCapabilityId.DevicePhotoPicker, "照片选择", NativeCapability.Photos),
            native(LoveHouseCapabilityId.DeviceNotifications, "Android 通知", NativeCapability.Notifications, notificationPermission),
            native(LoveHouseCapabilityId.DeviceBluetooth, "蓝牙 / BLE", NativeCapability.Bluetooth, bluetoothPermissions),
            native(LoveHouseCapabilityId.DeviceShare, "系统分享", NativeCapability.Share),
            native(LoveHouseCapabilityId.DeviceBiometrics, "生物识别", NativeCapability.Biometrics),
            native(LoveHouseCapabilityId.DeviceDeepLink, "Deep Link", NativeCapability.DeepLink),
        ),
    )
}
