package fyi.b612.lovehouse.core.designsystem

import android.graphics.Typeface
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.text.font.FontFamily
import fyi.b612.lovehouse.core.storage.LocalStorage
import java.io.File

enum class LoveHouseFontStyle(val storageValue: String) {
    Serif("serif"),
    Sans("sans"),
    Handwriting("handwriting"),
    System("system"),
    Imported("imported"),
}

enum class LoveHouseIconStyle(val storageValue: String) {
    Original("original"),
    Line("line"),
    SoftGlass("soft"),
    Ink("ink"),
}

@Immutable
data class LoveHouseAppearance(
    val wallpaper: String = "house",
    val customWallpaperPath: String? = null,
    val fontStyle: LoveHouseFontStyle = LoveHouseFontStyle.Serif,
    val customFontPath: String? = null,
    val iconStyle: LoveHouseIconStyle = LoveHouseIconStyle.Original,
    val fogStrength: Int = DEFAULT_FOG_STRENGTH,
    val wallpaperEffect: String = "clear",
)

val LocalLoveHouseAppearance = staticCompositionLocalOf { LoveHouseAppearance() }
val LocalLoveHouseFontFamily = staticCompositionLocalOf<FontFamily> { FontFamily.Serif }

@Composable
fun rememberLoveHouseAppearance(localStorage: LocalStorage): LoveHouseAppearance {
    val wallpaper by localStorage.observeString(APPEARANCE_WALLPAPER_KEY).collectAsState(initial = null)
    val customWallpaper by localStorage.observeString(APPEARANCE_CUSTOM_WALLPAPER_KEY).collectAsState(initial = null)
    val font by localStorage.observeString(APPEARANCE_FONT_KEY).collectAsState(initial = null)
    val customFont by localStorage.observeString(APPEARANCE_CUSTOM_FONT_KEY).collectAsState(initial = null)
    val icon by localStorage.observeString(APPEARANCE_ICON_STYLE_KEY).collectAsState(initial = null)
    val fog by localStorage.observeString(APPEARANCE_FOG_STRENGTH_KEY).collectAsState(initial = null)
    val effect by localStorage.observeString(APPEARANCE_EFFECT_KEY).collectAsState(initial = null)
    return LoveHouseAppearance(
        wallpaper = wallpaper ?: "house",
        customWallpaperPath = customWallpaper,
        fontStyle = LoveHouseFontStyle.entries.firstOrNull { it.storageValue == font } ?: LoveHouseFontStyle.Serif,
        customFontPath = customFont,
        iconStyle = LoveHouseIconStyle.entries.firstOrNull { it.storageValue == icon } ?: LoveHouseIconStyle.Original,
        fogStrength = fog?.toIntOrNull()?.coerceIn(MIN_FOG_STRENGTH, MAX_FOG_STRENGTH) ?: DEFAULT_FOG_STRENGTH,
        wallpaperEffect = effect ?: "clear",
    )
}

fun LoveHouseAppearance.fontFamily(): FontFamily = when (fontStyle) {
    LoveHouseFontStyle.Serif -> FontFamily.Serif
    LoveHouseFontStyle.Sans -> FontFamily.SansSerif
    LoveHouseFontStyle.Handwriting -> FontFamily.Cursive
    LoveHouseFontStyle.System -> FontFamily.Default
    LoveHouseFontStyle.Imported -> customFontPath
        ?.let(::File)
        ?.takeIf(File::isFile)
        ?.let { runCatching { FontFamily(Typeface.createFromFile(it)) }.getOrNull() }
        ?: FontFamily.Default
}

fun importAppearanceAsset(context: Context, uri: Uri, directory: String, prefix: String): Result<String> = runCatching {
    val targetDirectory = File(context.filesDir, "appearance/$directory").apply { mkdirs() }
    val extension = context.contentResolver.getType(uri)?.substringAfterLast('/')?.takeIf { it.length <= 8 } ?: "bin"
    val target = File(targetDirectory, "$prefix-${System.currentTimeMillis()}.$extension")
    context.contentResolver.openInputStream(uri).use { input ->
        requireNotNull(input) { "无法读取所选文件" }
        target.outputStream().use(input::copyTo)
    }
    require(target.length() > 0L) { "所选文件为空" }
    target.absolutePath
}

const val APPEARANCE_WALLPAPER_KEY = "appearance_wallpaper_v1"
const val APPEARANCE_CUSTOM_WALLPAPER_KEY = "appearance_custom_wallpaper_v1"
const val APPEARANCE_EFFECT_KEY = "appearance_effect_v1"
const val APPEARANCE_FONT_KEY = "appearance_font_v1"
const val APPEARANCE_CUSTOM_FONT_KEY = "appearance_custom_font_v1"
const val APPEARANCE_ICON_STYLE_KEY = "appearance_icon_style_v1"
const val APPEARANCE_FOG_STRENGTH_KEY = "appearance_fog_strength_v1"

const val MIN_FOG_STRENGTH = 0
const val MAX_FOG_STRENGTH = 20
const val DEFAULT_FOG_STRENGTH = 10
