package fyi.b612.lovehouse.core.designsystem

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

object LoveHouseColors {
    val Cream = Color(0xFFFFF9F2)
    val Paper = Color(0xFFFFFDF9)
    val Rose = Color(0xFFC85D7A)
    val SoftRose = Color(0xFFF5D7DE)
    val Plum = Color(0xFF6F476B)
    val Lavender = Color(0xFFE9E0EF)
    val Ink = Color(0xFF322936)
    val MutedInk = Color(0xFF746B77)
    val Moss = Color(0xFF66816C)
    val Night = Color(0xFF201A22)
    val NightCard = Color(0xFF2D2630)
}

private val LightColors = lightColorScheme(
    primary = LoveHouseColors.Rose,
    onPrimary = Color.White,
    primaryContainer = LoveHouseColors.SoftRose,
    onPrimaryContainer = LoveHouseColors.Ink,
    secondary = LoveHouseColors.Plum,
    onSecondary = Color.White,
    secondaryContainer = LoveHouseColors.Lavender,
    onSecondaryContainer = LoveHouseColors.Ink,
    background = LoveHouseColors.Cream,
    onBackground = LoveHouseColors.Ink,
    surface = LoveHouseColors.Paper,
    onSurface = LoveHouseColors.Ink,
    surfaceVariant = Color(0xFFF4ECE8),
    onSurfaceVariant = LoveHouseColors.MutedInk,
    outline = Color(0xFFD5C6CD),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFFF1A8BB),
    onPrimary = Color(0xFF4D1727),
    primaryContainer = Color(0xFF683044),
    onPrimaryContainer = Color(0xFFFFD9E2),
    secondary = Color(0xFFD9B8D7),
    onSecondary = Color(0xFF3B253A),
    background = LoveHouseColors.Night,
    onBackground = Color(0xFFF2E8F0),
    surface = LoveHouseColors.NightCard,
    onSurface = Color(0xFFF2E8F0),
    surfaceVariant = Color(0xFF443A45),
    onSurfaceVariant = Color(0xFFD3C5D0),
    outline = Color(0xFF9C8E99),
)

val LoveHouseTypography = androidx.compose.material3.Typography(
    displaySmall = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 34.sp,
        lineHeight = 40.sp,
        letterSpacing = (-0.4).sp,
    ),
    headlineMedium = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 27.sp,
        lineHeight = 34.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 21.sp,
        lineHeight = 27.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 16.sp,
        lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 16.sp,
        lineHeight = 24.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.Normal,
        fontSize = 14.sp,
        lineHeight = 21.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.SemiBold,
        fontSize = 13.sp,
        lineHeight = 18.sp,
        letterSpacing = 0.2.sp,
    ),
)

@Composable
fun LoveHouseTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = LoveHouseTypography,
        shapes = LoveHouseShapes,
        content = content,
    )
}
