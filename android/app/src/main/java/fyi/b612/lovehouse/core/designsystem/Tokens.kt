package fyi.b612.lovehouse.core.designsystem

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

object LoveHouseSpacing {
    val Tiny = 4.dp
    val Small = 8.dp
    val Medium = 12.dp
    val Large = 18.dp
    val XLarge = 24.dp
    val Page = 24.dp
    val Section = 32.dp
}

object LoveHouseRadius {
    val Small = 10.dp
    val Medium = 16.dp
    val Large = 24.dp
    val Pill = 999.dp
}

val LoveHouseShapes = Shapes(
    extraSmall = RoundedCornerShape(LoveHouseRadius.Small),
    small = RoundedCornerShape(LoveHouseRadius.Small),
    medium = RoundedCornerShape(LoveHouseRadius.Medium),
    large = RoundedCornerShape(LoveHouseRadius.Large),
    extraLarge = RoundedCornerShape(30.dp),
)
