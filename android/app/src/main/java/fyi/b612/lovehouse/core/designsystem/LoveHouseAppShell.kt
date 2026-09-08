package fyi.b612.lovehouse.core.designsystem

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import fyi.b612.lovehouse.R
import fyi.b612.lovehouse.core.storage.LocalStorage
import androidx.compose.ui.unit.dp
import android.graphics.BitmapFactory
import androidx.compose.runtime.remember

object LoveHouseGlass {
    val Background = Color(0xFFF8F5EF).copy(alpha = 0.48f)
    val StrongBackground = Color(0xFFF8F5EF).copy(alpha = 0.72f)
    val Border = Color.White.copy(alpha = 0.70f)
    val StrongBorder = Color.White.copy(alpha = 0.82f)
    val Shadow = Color(0x21614641)
    val Ink = Color(0xFF35413E)
    val MutedInk = Color(0xFF5F6C68)
}

@Composable
fun LoveHouseAppShell(
    localStorage: LocalStorage,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val appearance = LocalLoveHouseAppearance.current
    val customWallpaper = remember(appearance.customWallpaperPath) {
        appearance.customWallpaperPath?.let { path -> runCatching { BitmapFactory.decodeFile(path)?.asImageBitmap() }.getOrNull() }
    }
    val fog = appearance.fogStrength / MAX_FOG_STRENGTH.toFloat()
    Box(modifier.fillMaxSize()) {
        when (appearance.wallpaper) {
            "warm" -> Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFFF3E9D3), Color(0xFFD9CCB4)))))
            "rose" -> Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFFF2D9DF), Color(0xFFD9B8C4), Color(0xFFF4E9E4)))))
            "night" -> Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF26374B), Color(0xFF536174), Color(0xFF1F2937)))))
            "custom" -> if (customWallpaper != null) {
                Image(customWallpaper, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            } else {
                DefaultWallpaper(appearance.wallpaperEffect)
            }
            else -> Image(
                painter = painterResource(R.drawable.wallpaper_default_green),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().then(if (appearance.wallpaperEffect == "blur") Modifier.blur(9.dp) else Modifier),
                contentScale = ContentScale.Crop,
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            if (appearance.wallpaperEffect == "dim") Color(0x55343A37) else Color(0xFFFAF9F6).copy(alpha = 0.12f + fog * 0.24f),
                            if (appearance.wallpaperEffect == "soft") Color(0x66F8F5EF) else Color(0xFFF8F5EF).copy(alpha = 0.06f + fog * 0.16f),
                            if (appearance.wallpaperEffect == "dim") Color(0x49343A37) else Color(0xFFEEF5E9).copy(alpha = 0.09f + fog * 0.19f),
                        ),
                    ),
                ),
        )
        content()
    }
}

@Composable
private fun DefaultWallpaper(effect: String) {
    Image(
        painter = painterResource(R.drawable.wallpaper_default_green),
        contentDescription = null,
        modifier = Modifier.fillMaxSize().then(if (effect == "blur") Modifier.blur(9.dp) else Modifier),
        contentScale = ContentScale.Crop,
    )
}
