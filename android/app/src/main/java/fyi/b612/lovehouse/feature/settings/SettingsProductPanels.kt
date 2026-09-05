package fyi.b612.lovehouse.feature.settings
import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import fyi.b612.lovehouse.core.designsystem.LoveHouseIcon
import fyi.b612.lovehouse.core.designsystem.LoveHouseIconView
import fyi.b612.lovehouse.core.devicecontext.AndroidDeviceContextProvider
import fyi.b612.lovehouse.core.devicecontext.formatDeviceContextSnapshot
import fyi.b612.lovehouse.core.permissions.NativeCapability
import fyi.b612.lovehouse.core.permissions.PermissionState
import fyi.b612.lovehouse.core.permissions.PermissionStatusProvider
import fyi.b612.lovehouse.core.storage.LocalStorage
import fyi.b612.lovehouse.feature.nativelab.BiometricAuthenticators
import fyi.b612.lovehouse.feature.nativelab.BleCapabilityController
import fyi.b612.lovehouse.feature.nativelab.LocationSmokeTest
import fyi.b612.lovehouse.feature.nativelab.AudioSmokeRecorder
import fyi.b612.lovehouse.feature.nativelab.biometricErrorMessage
import fyi.b612.lovehouse.feature.nativelab.findFragmentActivity
import fyi.b612.lovehouse.feature.nativelab.openBiometricSettings
import fyi.b612.lovehouse.feature.nativelab.readSelectedResource
import fyi.b612.lovehouse.feature.nativelab.requiredBleRuntimePermissions
import fyi.b612.lovehouse.feature.nativelab.sendTestNotification
import fyi.b612.lovehouse.feature.screenobserver.ScreenObserverRuntime
import fyi.b612.lovehouse.feature.screenobserver.ScreenObserverStatus
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

private val ProductInk = Color(0xFF3F4948)
private val ProductMuted = Color(0xFF7B8785)
private val ProductAccent = Color(0xFF728F88)
private val ProductGlass = Color.White.copy(alpha = .48f)
private val ProductBorder = Color.White.copy(alpha = .42f)

private const val OwnerNameKey = "settings.owner.name"
private const val OwnerBioKey = "settings.owner.bio"
private const val OwnerAvatarKey = "settings.owner.avatar"
internal const val PersonasKey = "settings.ai_profiles"

internal data class AiProfile(val id: String, val name: String, val summary: String, val content: String, val source: String? = null)

@Composable
internal fun OwnerProfileSettings(storage: LocalStorage) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var name by remember { mutableStateOf("婷") }
    var bio by remember { mutableStateOf("LoveHouse 的主人。喜欢把生活、记忆和小伙伴认真放在同一间小屋里。") }
    var avatar by remember { mutableStateOf<String?>(null) }
    var editName by remember { mutableStateOf(name) }
    var editBio by remember { mutableStateOf(bio) }
    var editing by remember { mutableStateOf(false) }
    var saved by remember { mutableStateOf(false) }

    LaunchedEffect(storage) {
        name = storage.readString(OwnerNameKey) ?: name
        bio = storage.readString(OwnerBioKey) ?: bio
        avatar = storage.readString(OwnerAvatarKey)
        editName = name
        editBio = bio
    }
    val avatarPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            avatar = uri.toString()
        }
    }

    ProductPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            ProfileAvatar(avatar, name)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(name, color = ProductInk, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Text("Owner Profile", color = ProductAccent, fontSize = 10.sp)
                Text(bio, color = ProductMuted, fontSize = 10.sp, maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.height(10.dp))
        OutlinedButton(onClick = { avatarPicker.launch(arrayOf("image/*")) }, modifier = Modifier.fillMaxWidth()) { Text("更换头像", fontSize = 11.sp) }
    }
    ProductPanel {
        if (editing) {
            ProductField("昵称", editName, { editName = it }, singleLine = true)
            Spacer(Modifier.height(9.dp))
            ProductField("个人简介", editBio, { editBio = it }, singleLine = false)
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { editing = false }, Modifier.weight(1f)) { Text("取消") }
                Button(
                    onClick = {
                        name = editName.trim().ifBlank { "婷" }
                        bio = editBio.trim()
                        scope.launch {
                            storage.writeString(OwnerNameKey, name)
                            storage.writeString(OwnerBioKey, bio)
                            avatar?.let { storage.writeString(OwnerAvatarKey, it) }
                            saved = true
                            editing = false
                        }
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = ProductAccent),
                ) { Text("保存") }
            }
        } else {
            ProductValueRow("昵称", name)
            ProductValueRow("个人简介", bio)
            Button(onClick = { editName = name; editBio = bio; editing = true; saved = false }, modifier = Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = ProductAccent)) { Text("编辑个人资料") }
            if (saved) Text("已保存在这台设备，重新进入仍会显示。", color = ProductAccent, fontSize = 9.sp, modifier = Modifier.padding(top = 7.dp))
        }
    }
}

@Composable
private fun ProfileAvatar(uri: String?, name: String) {
    val context = LocalContext.current
    val bitmap = remember(uri) { uri?.let { runCatching { context.contentResolver.openInputStream(Uri.parse(it))?.use(BitmapFactory::decodeStream) }.getOrNull() } }
    Box(Modifier.size(62.dp).clip(CircleShape).background(Color(0xFFD4E0DD)), contentAlignment = Alignment.Center) {
        if (bitmap != null) Image(bitmap.asImageBitmap(), "Owner 头像", Modifier.fillMaxWidth()) else Text(name.take(1), color = ProductInk, fontSize = 24.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
internal fun AiProfileManager(storage: LocalStorage) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val profiles = remember { mutableStateListOf<AiProfile>() }
    var selected by remember { mutableStateOf<AiProfile?>(null) }
    var editing by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }
    var summary by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }

    fun persist() = scope.launch { storage.writeString(PersonasKey, profiles.toJson()) }
    LaunchedEffect(storage) {
        profiles.clear()
        profiles.addAll(storage.readString(PersonasKey)?.profilesFromJson().orEmpty().ifEmpty {
            listOf(
                AiProfile("g", "G 老师", "稳定 Persona · 专属记忆", "温和、清晰、可靠的长期对话伙伴。拥有独立 Persona 与专属记忆边界。"),
                AiProfile("k", "小克", "长期陪伴 · 独立记忆", "活泼而细心的长期伙伴。与其他 Persona 的 Thread 和记忆严格分离。"),
            )
        })
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            val display = uri.lastPathSegment?.substringAfterLast('/') ?: "导入文档"
            val imported = AiProfile("import-${System.currentTimeMillis()}", display.substringBeforeLast('.'), "由本地文档导入", "文档已由 Android 系统选择器读取。正式 AI 内容解析将在后端接入后完成。", display)
            profiles.add(imported); persist(); selected = imported
        }
    }

    if (selected == null && !editing) {
        profiles.forEach { profile -> AiProfileCard(profile) { selected = profile } }
        ProductPanel {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { name = ""; summary = ""; content = ""; editing = true }, Modifier.weight(1f)) { Text("新建档案", fontSize = 10.sp) }
                Button(onClick = { importer.launch(arrayOf("text/*", "application/pdf", "application/vnd.openxmlformats-officedocument.wordprocessingml.document")) }, Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = ProductAccent)) { Text("导入文档", fontSize = 10.sp) }
            }
        }
    } else {
        val current = selected
        ProductPanel {
            if (editing) {
                ProductField("档案名称", name, { name = it }, true); Spacer(Modifier.height(8.dp))
                ProductField("简短说明", summary, { summary = it }, true); Spacer(Modifier.height(8.dp))
                ProductField("完整档案内容", content, { content = it }, false)
                Spacer(Modifier.height(9.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { editing = false; if (current == null) selected = null }, Modifier.weight(1f)) { Text("取消") }
                    Button(onClick = {
                        val saved = AiProfile(current?.id ?: "profile-${System.currentTimeMillis()}", name.trim().ifBlank { "未命名档案" }, summary.trim(), content.trim(), current?.source)
                        if (current == null) profiles.add(saved) else profiles[profiles.indexOfFirst { it.id == current.id }] = saved
                        persist(); selected = saved; editing = false
                    }, Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = ProductAccent)) { Text("保存") }
                }
            } else if (current != null) {
                Text(current.name, color = ProductInk, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(current.summary, color = ProductAccent, fontSize = 10.sp)
                current.source?.let { Text("来源：$it", color = ProductMuted, fontSize = 9.sp, modifier = Modifier.padding(top = 5.dp)) }
                HorizontalDivider(Modifier.padding(vertical = 10.dp), color = ProductBorder)
                Text(current.content, color = ProductInk, fontSize = 12.sp)
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { selected = null }, Modifier.weight(1f)) { Text("返回列表") }
                    Button(onClick = { name = current.name; summary = current.summary; content = current.content; editing = true }, Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = ProductAccent)) { Text("编辑档案") }
                }
            }
        }
    }
}

@Composable
private fun AiProfileCard(profile: AiProfile, onClick: () -> Unit) {
    ProductPanel(Modifier.clickable(onClick = onClick)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(42.dp).clip(CircleShape).background(Color(0xFFD7E1DE)), contentAlignment = Alignment.Center) { Text(profile.name.take(1), color = ProductInk, fontWeight = FontWeight.SemiBold) }
            Column(Modifier.weight(1f).padding(start = 10.dp)) { Text(profile.name, color = ProductInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold); Text(profile.summary, color = ProductMuted, fontSize = 10.sp) }
            LoveHouseIconView(LoveHouseIcon.Forward, "查看档案", Modifier.size(16.dp), tint = ProductMuted)
        }
    }
}

@Composable
internal fun NativeCapabilitySettings(provider: PermissionStatusProvider) {
    val context = LocalContext.current
    val statuses by provider.statuses.collectAsState()
    var result by remember { mutableStateOf<String?>(null) }
    val singlePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { provider.refresh() }
    val multiPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { provider.refresh() }
    LaunchedEffect(provider) { provider.refresh() }
    fun request(capability: NativeCapability) {
        when (capability) {
            NativeCapability.Camera -> singlePermission.launch(Manifest.permission.CAMERA)
            NativeCapability.Microphone -> singlePermission.launch(Manifest.permission.RECORD_AUDIO)
            NativeCapability.Notifications -> if (Build.VERSION.SDK_INT >= 33) singlePermission.launch(Manifest.permission.POST_NOTIFICATIONS) else result = "当前 Android 版本无需通知运行时授权。"
            NativeCapability.Location -> multiPermission.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION, Manifest.permission.ACCESS_FINE_LOCATION))
            NativeCapability.Bluetooth -> if (Build.VERSION.SDK_INT >= 31) multiPermission.launch(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)) else singlePermission.launch(Manifest.permission.ACCESS_FINE_LOCATION)
            else -> result = "${capability.label}通过系统选择器或系统面板使用，不需要伪造 App 内权限开关。"
        }
    }
    ProductPanel {
        Text("App 原生能力 / 系统权限", color = ProductInk, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
        Text("Android 系统 → LoveHouse App。下列状态直接读取系统，不是 LoveHouse 自己保存的开关。", color = ProductMuted, fontSize = 9.sp)
    }
    ProductPanel {
        statuses.forEachIndexed { index, status ->
            Row(Modifier.fillMaxWidth().clickable { request(status.capability) }.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                LoveHouseIconView(status.capability.productIcon(), null, Modifier.size(18.dp), tint = ProductAccent)
                Column(Modifier.weight(1f).padding(start = 9.dp)) { Text(status.capability.label, color = ProductInk, fontSize = 12.sp); Text(status.capability.description, color = ProductMuted, fontSize = 8.sp, maxLines = 2) }
                Text(status.state.label, color = if (status.state == PermissionState.Granted || status.state == PermissionState.NotRequired) ProductAccent else ProductMuted, fontSize = 9.sp)
            }
            if (index != statuses.lastIndex) HorizontalDivider(color = ProductBorder)
        }
        OutlinedButton(onClick = { context.openAppSettings() }, Modifier.fillMaxWidth()) { Text("打开 Android 系统设置", fontSize = 10.sp) }
        result?.let { Text(it, color = ProductMuted, fontSize = 9.sp) }
    }
    DeviceContextPanel()
}

@Composable
internal fun LocalResourceSettings(provider: PermissionStatusProvider) {
    val context = LocalContext.current
    var result by remember { mutableStateOf("选择或拍摄后仅保留本地预览，不会上传。") }
    var preview by remember { mutableStateOf<Bitmap?>(null) }
    val photo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching { context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
            result = context.contentResolver.readSelectedResource(uri).asDisplayText()
            preview = runCatching { context.contentResolver.openInputStream(uri)?.use(BitmapFactory::decodeStream) }.getOrNull()
        } else result = "没有选择照片。"
    }
    val file = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        result = uri?.let { context.contentResolver.readSelectedResource(it).asDisplayText() } ?: "没有选择文件。"
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicturePreview()) { bitmap ->
        preview = bitmap; result = if (bitmap == null) "没有拍摄照片。" else "拍摄成功 · 本地待上传/待发送"
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        provider.refresh(); if (granted) camera.launch(null) else result = "相机权限未授予，请在系统权限页开启。"
    }
    ProductPanel {
        Text("本地媒体与文件", color = ProductInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text("真实 Android 选择器；当前流程止于本地预览与待上传状态。", color = ProductMuted, fontSize = 9.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 9.dp)) {
            NativeAction("照片", LoveHouseIcon.Photo, Modifier.weight(1f)) { photo.launch(arrayOf("image/*")) }
            NativeAction("相机", LoveHouseIcon.Camera, Modifier.weight(1f)) {
                if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) camera.launch(null) else cameraPermission.launch(Manifest.permission.CAMERA)
            }
            NativeAction("文件", LoveHouseIcon.File, Modifier.weight(1f)) { file.launch(arrayOf("*/*")) }
        }
        preview?.let { Image(it.asImageBitmap(), "本地预览", Modifier.fillMaxWidth().height(150.dp).clip(RoundedCornerShape(13.dp)).padding(top = 8.dp)) }
        Text(result, color = ProductMuted, fontSize = 9.sp, lineHeight = 13.sp, modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
internal fun NotificationProductSettings(provider: PermissionStatusProvider) {
    val context = LocalContext.current
    var result by remember { mutableStateOf("发送一条真实 Android 通知，验证产品提醒入口。") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        provider.refresh(); result = if (granted) sendTestNotification(context).message else "通知权限未授予。"
    }
    ProductPanel {
        Text("Android 通知能力", color = ProductInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(result, color = ProductMuted, fontSize = 9.sp, modifier = Modifier.padding(vertical = 7.dp))
        Button(onClick = {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) permission.launch(Manifest.permission.POST_NOTIFICATIONS)
            else result = sendTestNotification(context).message
        }, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = ProductAccent)) { Text("发送测试通知", fontSize = 10.sp) }
    }
}

@Composable
internal fun DeviceProductSettings(provider: PermissionStatusProvider) {
    val context = LocalContext.current
    val controller = remember(context.applicationContext) { BleCapabilityController(context.applicationContext) }
    val state by controller.state.collectAsState()
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { provider.refresh(); controller.refreshBluetoothState() }
    DeviceContextPanel()
    ProductPanel {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Nearby / BLE Devices", color = ProductInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold); Text("仅扫描、连接与 GATT 枚举；不支持设备控制。", color = ProductMuted, fontSize = 9.sp); Text(state.message, color = ProductMuted, fontSize = 8.sp) }
            Text(if (state.isScanning) "停止" else "扫描", Modifier.clickable {
                if (requiredBleRuntimePermissions().any { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }) permission.launch(requiredBleRuntimePermissions())
                else if (state.isScanning) controller.stopScan() else controller.startScan()
            }.padding(9.dp), color = ProductAccent, fontSize = 10.sp)
        }
        state.devices.take(5).forEach { device ->
            Row(Modifier.fillMaxWidth().clickable { controller.connect(device) }.padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(device.name, Modifier.weight(1f), color = ProductInk, fontSize = 10.sp); Text("${device.rssi} dBm", color = ProductMuted, fontSize = 8.sp)
            }
        }
        state.connectedName?.let { Text("已连接：$it · 已枚举 ${state.services.size} 个 GATT Service。仅验证连接和枚举，不宣称设备控制。", color = ProductAccent, fontSize = 9.sp) }
    }
}

@Composable
internal fun BiometricProductSettings() {
    val context = LocalContext.current
    val activity = remember(context) { context.findFragmentActivity() }
    var result by remember { mutableStateOf("使用 Android 生物识别或设备凭据验证。") }
    val prompt = remember(activity) {
        activity?.let { host -> BiometricPrompt(host, ContextCompat.getMainExecutor(host), object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(value: BiometricPrompt.AuthenticationResult) { result = "身份验证成功。" }
            override fun onAuthenticationFailed() { result = "未识别，请再试一次。" }
            override fun onAuthenticationError(code: Int, message: CharSequence) { result = biometricErrorMessage(code) }
        }) }
    }
    ProductPanel {
        Text("真实身份验证", color = ProductInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
        Text(result, color = ProductMuted, fontSize = 9.sp, modifier = Modifier.padding(vertical = 8.dp))
        Button(onClick = {
            val availability = BiometricManager.from(context).canAuthenticate(BiometricAuthenticators)
            if (availability == BiometricManager.BIOMETRIC_SUCCESS) prompt?.authenticate(BiometricPrompt.PromptInfo.Builder().setTitle("确认是你").setSubtitle("验证 LoveHouse 隐私锁能力").setAllowedAuthenticators(BiometricAuthenticators).build())
            else { result = "设备尚未配置可用的身份验证。"; openBiometricSettings(context) }
        }, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = ProductAccent)) { Text("验证身份", fontSize = 10.sp) }
    }
}

@Composable
private fun NativeAction(label: String, icon: LoveHouseIcon, modifier: Modifier, onClick: () -> Unit) {
    Surface(modifier.clickable(onClick = onClick), RoundedCornerShape(12.dp), Color.White.copy(.31f), border = BorderStroke(1.dp, ProductBorder)) {
        Column(Modifier.padding(vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally) { LoveHouseIconView(icon, null, Modifier.size(18.dp), tint = ProductAccent); Text(label, color = ProductInk, fontSize = 9.sp, modifier = Modifier.padding(top = 3.dp)) }
    }
}

@Composable
private fun DeviceContextPanel() {
    val context = LocalContext.current
    val screen by ScreenObserverRuntime.state.collectAsState()
    val provider = remember(context.applicationContext) {
        AndroidDeviceContextProvider(
            context = context.applicationContext,
            isScreenObserverActive = { ScreenObserverRuntime.state.value.status == ScreenObserverStatus.Active },
        )
    }
    var snapshot by remember { mutableStateOf(provider.getCurrentDeviceContext()) }
    ProductPanel {
        Row(verticalAlignment = Alignment.CenterVertically) { Text("当前 Android 设备", Modifier.weight(1f), color = ProductInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold); Text("刷新", Modifier.clickable { snapshot = provider.getCurrentDeviceContext() }.padding(8.dp), color = ProductAccent, fontSize = 10.sp) }
        Text(formatDeviceContextSnapshot(snapshot), color = ProductMuted, fontSize = 9.sp, lineHeight = 14.sp)
        Text("屏幕观察服务：${screen.status.label}", color = if (screen.status == ScreenObserverStatus.Active) ProductAccent else ProductMuted, fontSize = 9.sp, modifier = Modifier.padding(top = 6.dp))
    }
}

@Composable
internal fun ConnectionFormsSettings() {
    data class Connection(val name: String, val type: String, val endpoint: String, val auth: String, val detail: String)
    val connections = remember { mutableStateListOf<Connection>() }
    var form by remember { mutableStateOf(false) }
    var name by remember { mutableStateOf("") }; var endpoint by remember { mutableStateOf("") }; var credential by remember { mutableStateOf("") }
    var type by remember { mutableIntStateOf(0) }; var auth by remember { mutableIntStateOf(0) }; var testState by remember { mutableStateOf<String?>(null) }
    connections.forEach { connection ->
        ProductPanel {
            Row(verticalAlignment = Alignment.CenterVertically) { Column(Modifier.weight(1f)) { Text(connection.name, color = ProductInk, fontSize = 13.sp, fontWeight = FontWeight.Medium); Text("${connection.type} · ${connection.endpoint}", color = ProductMuted, fontSize = 9.sp); Text(connection.detail, color = ProductAccent, fontSize = 9.sp) }; Text("删除", Modifier.clickable { connections.remove(connection) }.padding(8.dp), color = Color(0xFF9A6F6B), fontSize = 9.sp) }
        }
    }
    ProductPanel {
        if (!form) Button(onClick = { form = true }, Modifier.fillMaxWidth(), colors = ButtonDefaults.buttonColors(containerColor = ProductAccent)) { Text("添加连接") }
        else {
            Text("Connection Form", color = ProductInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(7.dp)); ProductField("名称 / 显示名", name, { name = it }, true)
            Spacer(Modifier.height(7.dp)); Selector("Connection 类型", listOf("MCP", "API", "VPS / Backend", "Database", "Agent Runtime", "Voice Provider", "设备服务", "同步服务"), type) { type = it }
            Spacer(Modifier.height(7.dp)); ProductField("URL / Base URL / Endpoint", endpoint, { endpoint = it }, true)
            Spacer(Modifier.height(7.dp)); Selector("Auth 类型", listOf("无", "Connection Code", "OAuth", "API Key", "Header"), auth) { auth = it }
            Spacer(Modifier.height(7.dp)); ProductField("Connection Code / Credential", credential, { credential = it }, true)
            Text("Project / Model / Runtime / Scope / Header 等类型特有参数将在选择对应连接类型时使用。Secret 不会以明文长期保存。", color = ProductMuted, fontSize = 8.sp, modifier = Modifier.padding(vertical = 7.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { testState = "后端尚未接入，未执行虚假连接测试。" }, Modifier.weight(1f)) { Text("Test Connection", fontSize = 9.sp) }
                Button(onClick = {
                    connections.add(Connection(name.ifBlank { "未命名连接" }, listOf("MCP", "API", "VPS / Backend", "Database", "Agent Runtime", "Voice Provider", "设备服务", "同步服务")[type], endpoint.ifBlank { "未配置" }, listOf("无", "Connection Code", "OAuth", "API Key", "Header")[auth], "未连接 · 待后端接入")); credential = ""; form = false
                }, Modifier.weight(1f), colors = ButtonDefaults.buttonColors(containerColor = ProductAccent)) { Text("Save", fontSize = 9.sp) }
            }
            testState?.let { Text(it, color = Color(0xFF9A7857), fontSize = 9.sp, modifier = Modifier.padding(top = 6.dp)) }
        }
    }
}

@Composable private fun ConsoleMetric(title: String, value: String, modifier: Modifier) { Surface(modifier, RoundedCornerShape(12.dp), Color.White.copy(.30f)) { Column(Modifier.padding(9.dp)) { Text(title, color = ProductMuted, fontSize = 8.sp); Text(value, color = ProductInk, fontSize = 10.sp, fontWeight = FontWeight.Medium) } } }

@Composable private fun Selector(title: String, choices: List<String>, selected: Int, onSelect: (Int) -> Unit) { Column { Text(title, color = ProductMuted, fontSize = 9.sp); LazyColumn(Modifier.fillMaxWidth().height(76.dp)) { items(choices.indices.toList()) { index -> Text(choices[index], Modifier.fillMaxWidth().clip(RoundedCornerShape(8.dp)).background(if (index == selected) Color(0xFFCADBD6) else Color.Transparent).clickable { onSelect(index) }.padding(horizontal = 9.dp, vertical = 5.dp), color = ProductInk, fontSize = 9.sp) } } } }

@Composable internal fun ProductField(label: String, value: String, onValueChange: (String) -> Unit, singleLine: Boolean) { Column { Text(label, color = ProductMuted, fontSize = 9.sp); Surface(Modifier.fillMaxWidth(), RoundedCornerShape(12.dp), Color.White.copy(.34f), border = BorderStroke(1.dp, ProductBorder)) { BasicTextField(value, onValueChange, Modifier.fillMaxWidth().padding(11.dp), singleLine = singleLine, minLines = if (singleLine) 1 else 4, textStyle = androidx.compose.ui.text.TextStyle(ProductInk, fontSize = 11.sp), decorationBox = { inner -> if (value.isEmpty()) Text("请输入$label", color = ProductMuted, fontSize = 10.sp); inner() }) } } }

@Composable private fun ProductValueRow(label: String, value: String) { Column(Modifier.padding(bottom = 10.dp)) { Text(label, color = ProductMuted, fontSize = 9.sp); Text(value, color = ProductInk, fontSize = 12.sp) } }

@Composable internal fun ProductPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) { Surface(modifier.fillMaxWidth(), RoundedCornerShape(17.dp), ProductGlass, border = BorderStroke(1.dp, ProductBorder)) { Column(Modifier.fillMaxWidth().padding(12.dp), content = content) } }

private fun NativeCapability.productIcon() = when (this) { NativeCapability.Photos -> LoveHouseIcon.Photo; NativeCapability.Camera -> LoveHouseIcon.Camera; NativeCapability.Files -> LoveHouseIcon.File; NativeCapability.Microphone -> LoveHouseIcon.Mic; NativeCapability.Location -> LoveHouseIcon.Location; NativeCapability.Notifications -> LoveHouseIcon.Bell; NativeCapability.Bluetooth -> LoveHouseIcon.ModelSwitch; NativeCapability.Share -> LoveHouseIcon.Forward; NativeCapability.Biometrics -> LoveHouseIcon.Settings; NativeCapability.DeepLink -> LoveHouseIcon.Expand }
private fun Context.openAppSettings() { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
private fun List<AiProfile>.toJson() = JSONArray().apply { forEach { put(JSONObject().put("id", it.id).put("name", it.name).put("summary", it.summary).put("content", it.content).put("source", it.source)) } }.toString()
internal fun String.profilesFromJson(): List<AiProfile> = runCatching { val array = JSONArray(this); List(array.length()) { i -> array.getJSONObject(i).let { AiProfile(it.getString("id"), it.getString("name"), it.optString("summary"), it.optString("content"), it.optString("source").ifBlank { null }) } } }.getOrDefault(emptyList())
