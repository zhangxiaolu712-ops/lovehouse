package fyi.b612.lovehouse.feature.settings

import android.content.Intent
import android.graphics.Typeface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fyi.b612.lovehouse.BuildConfig
import fyi.b612.lovehouse.core.designsystem.APPEARANCE_CUSTOM_FONT_KEY
import fyi.b612.lovehouse.core.designsystem.APPEARANCE_CUSTOM_WALLPAPER_KEY
import fyi.b612.lovehouse.core.designsystem.APPEARANCE_FOG_STRENGTH_KEY
import fyi.b612.lovehouse.core.designsystem.APPEARANCE_FONT_KEY
import fyi.b612.lovehouse.core.designsystem.APPEARANCE_ICON_STYLE_KEY
import fyi.b612.lovehouse.core.designsystem.APPEARANCE_WALLPAPER_KEY
import fyi.b612.lovehouse.core.designsystem.LocalLoveHouseAppearance
import fyi.b612.lovehouse.core.designsystem.LoveHouseFontStyle
import fyi.b612.lovehouse.core.designsystem.MAX_FOG_STRENGTH
import fyi.b612.lovehouse.core.designsystem.importAppearanceAsset
import fyi.b612.lovehouse.core.storage.LocalStorage
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private val RealInk = Color(0xFF3F4948)
private val RealMuted = Color(0xFF7B8785)
private val RealAccent = Color(0xFF728F88)
private val RealHairline = Color.White.copy(alpha = .42f)

@Composable
internal fun AppearanceProductSettings(storage: LocalStorage) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val appearance = LocalLoveHouseAppearance.current
    var feedback by remember { mutableStateOf<String?>(null) }
    val wallpaperPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        importAppearanceAsset(context, uri, "wallpapers", "global-wallpaper")
            .onSuccess { path -> scope.launch {
                storage.writeString(APPEARANCE_CUSTOM_WALLPAPER_KEY, path)
                storage.writeString(APPEARANCE_WALLPAPER_KEY, "custom")
                feedback = "自定义壁纸已保存在本机并应用"
            } }
            .onFailure { feedback = "壁纸导入失败：${it.message.orEmpty()}" }
    }
    val fontPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri ?: return@rememberLauncherForActivityResult
        importAppearanceAsset(context, uri, "fonts", "global-font")
            .onSuccess { path ->
                if (runCatching { Typeface.createFromFile(path) }.isSuccess) scope.launch {
                    storage.writeString(APPEARANCE_CUSTOM_FONT_KEY, path)
                    storage.writeString(APPEARANCE_FONT_KEY, LoveHouseFontStyle.Imported.storageValue)
                    feedback = "字体已导入本机并应用"
                } else feedback = "所选文件不是可用字体"
            }
            .onFailure { feedback = "字体导入失败：${it.message.orEmpty()}" }
    }

    ProductPanel {
        RealHeading("全局字体")
        RealChoiceGrid(
            listOf("宋体" to "serif", "无衬线" to "sans", "手写" to "handwriting", "系统" to "system", "导入字体" to "import"),
            if (appearance.fontStyle == LoveHouseFontStyle.Imported) "import" else appearance.fontStyle.storageValue,
        ) { value ->
            if (value == "import") fontPicker.launch("font/*")
            else scope.launch { storage.writeString(APPEARANCE_FONT_KEY, value) }
        }
        RealNote("导入字体只保存在本机，不上传数据库或后端。")
    }
    ProductPanel {
        RealHeading("全局壁纸")
        RealChoiceGrid(
            listOf("绿荫" to "house", "暖纸" to "warm", "粉雾" to "rose", "夜空" to "night", "＋ 相册" to "custom"),
            appearance.wallpaper,
        ) { value ->
            if (value == "custom") wallpaperPicker.launch("image/*")
            else scope.launch { storage.writeString(APPEARANCE_WALLPAPER_KEY, value) }
        }
        RealNote("切换会通过同一全局视觉上下文同步到 Desktop、Chat、ChatList 与 Settings。")
    }
    ProductPanel {
        RealHeading("全局图标样式")
        RealChoiceGrid(
            listOf("原始" to "original", "细线" to "line", "柔玻璃" to "soft", "墨线" to "ink"),
            appearance.iconStyle.storageValue,
        ) { value -> scope.launch { storage.writeString(APPEARANCE_ICON_STYLE_KEY, value) } }
    }
    ProductPanel {
        RealHeading("雾面效果 · ${appearance.fogStrength}")
        Slider(
            value = appearance.fogStrength.toFloat(),
            onValueChange = { value -> scope.launch { storage.writeString(APPEARANCE_FOG_STRENGTH_KEY, value.roundToInt().toString()) } },
            valueRange = 0f..MAX_FOG_STRENGTH.toFloat(),
            steps = MAX_FOG_STRENGTH - 1,
        )
        RealNote("拖动后立即作用于全局玻璃强度，并在本机持久化。")
    }
    feedback?.let { ProductPanel { Text(it, color = RealAccent, fontSize = 10.sp) } }
}

@Composable
internal fun LocalStorageUsageSettings() {
    val context = LocalContext.current
    val usage by produceState<Long?>(initialValue = null, context.applicationContext) {
        value = withContext(Dispatchers.IO) {
            sequenceOf(context.filesDir, context.cacheDir)
                .filter(File::exists)
                .flatMap { it.walkTopDown() }
                .filter(File::isFile)
                .sumOf(File::length)
        }
    }
    ProductPanel {
        RealHeading("LoveHouse 本机文件")
        Text(usage?.let(::formatBytes) ?: "正在读取…", color = RealInk, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        RealNote("实时统计 App 私有 files/cache；不冒充整台手机容量，也不自动删除任何资料。")
    }
}

@Composable
internal fun UnavailableSettingsRows(title: String, rows: List<String>, reason: String) {
    ProductPanel {
        RealHeading(title)
        rows.forEach { label ->
            Row(Modifier.fillMaxWidth().padding(vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(label, Modifier.weight(1f), color = RealInk, fontSize = 11.sp)
                Text("待接入", color = RealMuted, fontSize = 9.sp)
            }
        }
        RealNote(reason)
    }
}

@Composable
internal fun VersionProductSettings() {
    val context = LocalContext.current
    ProductPanel {
        RealHeading("LoveHouse ${BuildConfig.VERSION_NAME}")
        RealNote("Android · versionCode ${BuildConfig.VERSION_CODE}")
        Surface(
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp).clickable {
                val share = Intent(Intent.ACTION_SEND).apply {
                    type = "text/plain"
                    putExtra(Intent.EXTRA_TEXT, "LoveHouse Android ${BuildConfig.VERSION_NAME}")
                }
                context.startActivity(Intent.createChooser(share, "分享 LoveHouse"))
            },
            shape = RoundedCornerShape(12.dp),
            color = Color.White.copy(alpha = .31f),
            border = BorderStroke(1.dp, RealHairline),
        ) {
            Box(Modifier.fillMaxWidth().padding(10.dp), contentAlignment = Alignment.Center) {
                Text("使用 Android 系统分享", color = RealInk, fontSize = 10.sp)
            }
        }
        RealNote("在线更新检查尚未接入；不会显示虚假的“已是最新版本”。")
    }
}

@Composable
private fun RealChoiceGrid(items: List<Pair<String, String>>, selected: String?, onSelect: (String) -> Unit) {
    items.chunked(3).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(top = 7.dp), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
            row.forEach { (label, value) ->
                Surface(
                    modifier = Modifier.weight(1f).height(38.dp).clickable { onSelect(value) },
                    shape = RoundedCornerShape(12.dp),
                    color = if (selected == value) Color(0xFFCADBD6).copy(alpha = .82f) else Color.White.copy(alpha = .31f),
                    border = BorderStroke(1.dp, RealHairline),
                ) { Box(contentAlignment = Alignment.Center) { Text(label, color = RealInk, fontSize = 9.sp) } }
            }
            repeat(3 - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable private fun RealHeading(text: String) = Text(text, color = RealInk, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
@Composable private fun RealNote(text: String) = Text(text, color = RealMuted, fontSize = 9.sp, modifier = Modifier.padding(top = 5.dp))

internal fun formatBytes(bytes: Long): String {
    if (bytes < 1024L) return "$bytes B"
    val units = listOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble()
    var index = -1
    while (value >= 1024.0 && index < units.lastIndex) { value /= 1024.0; index++ }
    return String.format(Locale.CHINA, "%.1f %s", value, units[index])
}
