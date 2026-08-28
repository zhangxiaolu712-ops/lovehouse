package fyi.b612.lovehouse.feature.nativelab

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import fyi.b612.lovehouse.core.designsystem.LoveHouseCard
import fyi.b612.lovehouse.core.designsystem.LoveHousePrimaryButton
import fyi.b612.lovehouse.core.designsystem.LoveHouseRadius
import fyi.b612.lovehouse.core.designsystem.LoveHouseSecondaryButton
import fyi.b612.lovehouse.core.designsystem.LoveHouseSpacing
import fyi.b612.lovehouse.core.designsystem.SectionLabel
import fyi.b612.lovehouse.core.designsystem.StatusPill
import fyi.b612.lovehouse.core.navigation.AppDestination
import fyi.b612.lovehouse.core.permissions.CapabilityPermissionStatus
import fyi.b612.lovehouse.core.permissions.NativeCapability
import fyi.b612.lovehouse.core.permissions.PermissionState
import fyi.b612.lovehouse.core.status.SystemStatusProvider

private const val ShareSample = "来自 LoveHouse 原生小屋的一句测试分享。"

@Composable
fun NativeLabScreen(
    systemStatusProvider: SystemStatusProvider,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val status by systemStatusProvider.status.collectAsState()

    var photoResult by rememberSaveable { mutableStateOf<String?>(null) }
    var fileResult by rememberSaveable { mutableStateOf<String?>(null) }
    var shareResult by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraResult by rememberSaveable { mutableStateOf<String?>(null) }
    var cameraPreview by remember { mutableStateOf<Bitmap?>(null) }
    var showCameraSettings by rememberSaveable { mutableStateOf(false) }

    val audioRecorder = remember(context.applicationContext) { AudioSmokeRecorder(context.applicationContext) }
    var audioResult by rememberSaveable { mutableStateOf<String?>(null) }
    var isRecording by remember { mutableStateOf(false) }
    var showMicrophoneSettings by rememberSaveable { mutableStateOf(false) }

    val locationSmokeTest = remember(context.applicationContext) { LocationSmokeTest(context.applicationContext) }
    var locationResult by rememberSaveable { mutableStateOf<String?>(null) }
    var showLocationPermissionSettings by rememberSaveable { mutableStateOf(false) }
    var showLocationSystemSettings by rememberSaveable { mutableStateOf(false) }

    var notificationResult by rememberSaveable { mutableStateOf<String?>(null) }
    var showNotificationSettings by rememberSaveable { mutableStateOf(false) }
    var biometricResult by rememberSaveable { mutableStateOf<String?>(null) }
    var showBiometricSettings by rememberSaveable { mutableStateOf(false) }
    var deepLinkResult by rememberSaveable { mutableStateOf<String?>(null) }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        photoResult = uri?.let { context.contentResolver.readSelectedResource(it).asDisplayText() }
            ?: "没有选择照片。"
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        fileResult = uri?.let { context.contentResolver.readSelectedResource(it).asDisplayText() }
            ?: "没有选择文件。"
    }

    val cameraCapture = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        cameraPreview = bitmap
        cameraResult = if (bitmap == null) "没有拍摄照片。" else "拍摄成功，照片预览如下；未上传到任何服务。"
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        systemStatusProvider.refresh()
        showCameraSettings = !granted
        if (granted) {
            cameraCapture.launch(null)
        } else {
            cameraResult = "相机权限被拒绝。不会自动再次申请；你可以重试或进入系统设置。"
        }
    }

    fun startRecording() {
        audioResult = audioRecorder.start()
        isRecording = audioRecorder.isRecording
        showMicrophoneSettings = false
    }
    val microphonePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        systemStatusProvider.refresh()
        if (granted) {
            startRecording()
        } else {
            isRecording = false
            showMicrophoneSettings = true
            audioResult = "麦克风权限被拒绝。不会自动再次申请；你可以重试或进入系统设置。"
        }
    }

    fun requestLocation() {
        locationResult = "正在获取一次当前位置…"
        showLocationPermissionSettings = false
        showLocationSystemSettings = false
        locationSmokeTest.request { result ->
            locationResult = result.message
            showLocationSystemSettings = result.needsLocationSettings
        }
    }
    val locationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants ->
        systemStatusProvider.refresh()
        val granted = grants[Manifest.permission.ACCESS_COARSE_LOCATION] == true ||
            grants[Manifest.permission.ACCESS_FINE_LOCATION] == true
        if (granted) {
            requestLocation()
        } else {
            showLocationPermissionSettings = true
            locationResult = "定位权限被拒绝。不会自动再次申请；你可以重试或进入系统设置。"
        }
    }

    fun sendNotification() {
        val result = sendTestNotification(context)
        notificationResult = result.message
        showNotificationSettings = result.needsNotificationSettings
    }
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        systemStatusProvider.refresh()
        if (granted) {
            sendNotification()
        } else {
            showNotificationSettings = true
            notificationResult = "通知权限被拒绝。不会自动再次申请；请在系统设置中开启。"
        }
    }

    val activity = remember(context) { context.findFragmentActivity() }
    val biometricPrompt = remember(activity) {
        activity?.let {
            BiometricPrompt(
                it,
                ContextCompat.getMainExecutor(it),
                object : BiometricPrompt.AuthenticationCallback() {
                    override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                        biometricResult = "生物识别验证成功。这里只验证能力，没有启用 App 启动锁。"
                    }

                    override fun onAuthenticationFailed() {
                        biometricResult = "未识别，请在系统面板中再试一次。"
                    }

                    override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                        biometricResult = biometricErrorMessage(errorCode)
                    }
                },
            )
        }
    }
    val biometricPromptInfo = remember {
        BiometricPrompt.PromptInfo.Builder()
            .setTitle("测试生物识别")
            .setSubtitle("验证这台设备是否能够安全确认身份")
            .setAllowedAuthenticators(BiometricAuthenticators)
            .build()
    }

    LaunchedEffect(systemStatusProvider) {
        systemStatusProvider.refresh()
    }
    DisposableEffect(audioRecorder, locationSmokeTest) {
        onDispose {
            audioRecorder.cancel()
            locationSmokeTest.cancel()
        }
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = LoveHouseSpacing.Page),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            top = LoveHouseSpacing.XLarge,
            bottom = LoveHouseSpacing.Section,
        ),
        verticalArrangement = Arrangement.spacedBy(LoveHouseSpacing.Medium),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(LoveHouseSpacing.Medium)) {
                LoveHouseSecondaryButton("← 返回设置", onClick = onBack)
                SectionLabel("隐藏工作台")
                Text("原生能力测试", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "照片、文件、分享及六项剩余原生能力都可在这里逐项真机验证。所有权限只在点击对应按钮后申请。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Column(verticalArrangement = Arrangement.spacedBy(LoveHouseSpacing.Small)) {
                    StatusPill("版本 ${status.appVersion}")
                    StatusPill(status.backend.label)
                }
            }
        }

        items(status.permissions, key = { it.capability.name }) { capabilityStatus ->
            when (capabilityStatus.capability) {
                NativeCapability.Photos -> CapabilityCard(
                    capabilityStatus = capabilityStatus,
                    actionLabel = "选择一张照片",
                    result = photoResult,
                    onAction = {
                        photoPicker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                    },
                )

                NativeCapability.Camera -> CapabilityCard(
                    capabilityStatus = capabilityStatus,
                    actionLabel = "拍摄一张照片",
                    secondaryActionLabel = "进入系统设置".takeIf { showCameraSettings },
                    result = cameraResult,
                    onAction = {
                        when {
                            capabilityStatus.state == PermissionState.Unsupported -> {
                                cameraResult = "这台设备没有可用的相机。"
                            }
                            context.hasPermission(Manifest.permission.CAMERA) -> cameraCapture.launch(null)
                            else -> cameraPermission.launch(Manifest.permission.CAMERA)
                        }
                    },
                    onSecondaryAction = { openAppSettings(context) },
                ) {
                    cameraPreview?.let { bitmap ->
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "刚拍摄的照片预览",
                            modifier = Modifier
                                .fillMaxWidth()
                                .aspectRatio(4f / 3f)
                                .clip(RoundedCornerShape(LoveHouseRadius.Medium)),
                            contentScale = ContentScale.Crop,
                        )
                    }
                }

                NativeCapability.Files -> CapabilityCard(
                    capabilityStatus = capabilityStatus,
                    actionLabel = "选择一个文件",
                    result = fileResult,
                    onAction = { filePicker.launch(arrayOf("*/*")) },
                )

                NativeCapability.Microphone -> CapabilityCard(
                    capabilityStatus = capabilityStatus,
                    actionLabel = if (isRecording) "停止录音" else "开始录音",
                    secondaryActionLabel = "进入系统设置".takeIf { showMicrophoneSettings },
                    result = audioResult,
                    onAction = {
                        when {
                            isRecording -> {
                                audioResult = audioRecorder.stop()
                                isRecording = audioRecorder.isRecording
                            }
                            capabilityStatus.state == PermissionState.Unsupported -> {
                                audioResult = "这台设备没有可用的麦克风。"
                            }
                            context.hasPermission(Manifest.permission.RECORD_AUDIO) -> startRecording()
                            else -> microphonePermission.launch(Manifest.permission.RECORD_AUDIO)
                        }
                    },
                    onSecondaryAction = { openAppSettings(context) },
                )

                NativeCapability.Location -> CapabilityCard(
                    capabilityStatus = capabilityStatus,
                    actionLabel = "获取一次当前位置",
                    secondaryActionLabel = when {
                        showLocationSystemSettings -> "打开定位设置"
                        showLocationPermissionSettings -> "进入系统设置"
                        else -> null
                    },
                    result = locationResult,
                    onAction = {
                        when {
                            capabilityStatus.state == PermissionState.Unsupported -> {
                                locationResult = "这台设备没有可用的定位能力。"
                            }
                            context.hasLocationPermission() -> requestLocation()
                            else -> locationPermission.launch(
                                arrayOf(
                                    Manifest.permission.ACCESS_FINE_LOCATION,
                                    Manifest.permission.ACCESS_COARSE_LOCATION,
                                ),
                            )
                        }
                    },
                    onSecondaryAction = {
                        if (showLocationSystemSettings) openLocationSettings(context) else openAppSettings(context)
                    },
                )

                NativeCapability.Notifications -> CapabilityCard(
                    capabilityStatus = capabilityStatus,
                    actionLabel = "发送测试通知",
                    secondaryActionLabel = "打开通知设置".takeIf { showNotificationSettings },
                    result = notificationResult,
                    onAction = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                            !context.hasPermission(Manifest.permission.POST_NOTIFICATIONS)
                        ) {
                            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        } else {
                            sendNotification()
                        }
                    },
                    onSecondaryAction = { openNotificationSettings(context) },
                )

                NativeCapability.Share -> CapabilityCard(
                    capabilityStatus = capabilityStatus,
                    actionLabel = "打开系统分享面板",
                    result = shareResult,
                    onAction = {
                        shareResult = runCatching {
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, ShareSample)
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "用其他应用分享"))
                            "系统分享面板已打开。"
                        }.getOrElse {
                            "当前设备无法打开分享面板。"
                        }
                    },
                )

                NativeCapability.Biometrics -> CapabilityCard(
                    capabilityStatus = capabilityStatus,
                    actionLabel = "测试生物识别",
                    secondaryActionLabel = "打开安全设置".takeIf { showBiometricSettings },
                    result = biometricResult,
                    onAction = {
                        val availability = BiometricManager.from(context).canAuthenticate(BiometricAuthenticators)
                        val unavailableMessage = biometricAvailabilityMessage(availability)
                        showBiometricSettings = availability == BiometricManager.BIOMETRIC_ERROR_NONE_ENROLLED
                        if (unavailableMessage != null) {
                            biometricResult = unavailableMessage
                        } else if (biometricPrompt == null) {
                            biometricResult = "当前页面无法连接系统生物识别面板。"
                        } else {
                            runCatching { biometricPrompt.authenticate(biometricPromptInfo) }
                                .onFailure { biometricResult = "生物识别启动失败，请稍后重试。" }
                        }
                    },
                    onSecondaryAction = { openBiometricSettings(context) },
                )

                NativeCapability.DeepLink -> CapabilityCard(
                    capabilityStatus = capabilityStatus,
                    actionLabel = "测试打开深链",
                    result = deepLinkResult,
                    onAction = {
                        deepLinkResult = runCatching {
                            context.startActivity(
                                Intent(Intent.ACTION_VIEW, Uri.parse(AppDestination.NativeLab.deepLink))
                                    .setPackage(context.packageName),
                            )
                            "已通过 lovehouse:// 深链重新打开原生能力测试页。"
                        }.getOrElse {
                            "深链打开失败，请稍后重试。"
                        }
                    },
                )
            }
        }
    }
}

@Composable
private fun CapabilityCard(
    capabilityStatus: CapabilityPermissionStatus,
    actionLabel: String? = null,
    secondaryActionLabel: String? = null,
    result: String? = null,
    onAction: (() -> Unit)? = null,
    onSecondaryAction: (() -> Unit)? = null,
    content: (@Composable () -> Unit)? = null,
) {
    LoveHouseCard(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(capabilityStatus.capability.label, style = MaterialTheme.typography.titleLarge)
                Text(
                    capabilityStatus.capability.description,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            StatusPill(
                capabilityStatus.state.label,
                color = when (capabilityStatus.state) {
                    PermissionState.Granted,
                    PermissionState.NotRequired,
                    -> MaterialTheme.colorScheme.tertiary

                    PermissionState.Denied -> MaterialTheme.colorScheme.error
                    PermissionState.NotRequested,
                    PermissionState.Unsupported,
                    -> MaterialTheme.colorScheme.secondary
                },
            )
        }

        if (actionLabel != null && onAction != null) {
            LoveHousePrimaryButton(
                text = actionLabel,
                onClick = onAction,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (secondaryActionLabel != null && onSecondaryAction != null) {
            LoveHouseSecondaryButton(
                text = secondaryActionLabel,
                onClick = onSecondaryAction,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        if (result != null) {
            Text(
                text = result,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        content?.invoke()
    }
}

private fun Context.hasPermission(permission: String): Boolean =
    ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

private fun Context.hasLocationPermission(): Boolean =
    hasPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ||
        hasPermission(Manifest.permission.ACCESS_FINE_LOCATION)

private fun openAppSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")),
    )
}

private fun openLocationSettings(context: Context) {
    context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS))
}

private fun openNotificationSettings(context: Context) {
    context.startActivity(
        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
            .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
    )
}
