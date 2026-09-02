package fyi.b612.lovehouse.core.designsystem

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import fyi.b612.lovehouse.R
import fyi.b612.lovehouse.core.storage.LocalStorage
import androidx.compose.ui.unit.dp

object LoveHouseGlass {
    val Background = Color(0xFFF8F5EF).copy(alpha = 0.48f)
    val StrongBackground = Color(0xFFF8F5EF).copy(alpha = 0.72f)
    val Border = Color.White.copy(alpha = 0.70f)
    val StrongBorder = Color.White.copy(alpha = 0.82f)
    val Shadow = Color(0x21614641)
    val Ink = Color(0xFF4B504D)
    val MutedInk = Color(0xFF7C8580)
}

@Composable
fun LoveHouseAppShell(
    localStorage: LocalStorage,
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val wallpaper by localStorage.observeString(APPEARANCE_WALLPAPER_KEY).collectAsState(initial = null)
    val effect by localStorage.observeString(APPEARANCE_EFFECT_KEY).collectAsState(initial = null)
    Box(modifier.fillMaxSize()) {
        when (wallpaper ?: "house") {
            "warm" -> Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFFF3E9D3), Color(0xFFD9CCB4)))))
            "rose" -> Box(Modifier.fillMaxSize().background(Brush.linearGradient(listOf(Color(0xFFF2D9DF), Color(0xFFD9B8C4), Color(0xFFF4E9E4)))))
            "night" -> Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0xFF26374B), Color(0xFF536174), Color(0xFF1F2937)))))
            else -> Image(
                painter = painterResource(R.drawable.wallpaper_default_green),
                contentDescription = null,
                modifier = Modifier.fillMaxSize().then(if (effect == "blur") Modifier.blur(9.dp) else Modifier),
                contentScale = ContentScale.Crop,
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        listOf(
                            if (effect == "dim") Color(0x55343A37) else Color(0x59FAF9F6),
                            if (effect == "soft") Color(0x66F8F5EF) else Color(0x26F8F5EF),
                            if (effect == "dim") Color(0x49343A37) else Color(0x42EEF5E9),
                        ),
                    ),
                ),
        )
        content()
    }
}

const val APPEARANCE_WALLPAPER_KEY = "appearance_wallpaper_v1"
const val APPEARANCE_EFFECT_KEY = "appearance_effect_v1"
