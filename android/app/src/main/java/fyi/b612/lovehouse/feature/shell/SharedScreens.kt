package fyi.b612.lovehouse.feature.shell

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import fyi.b612.lovehouse.core.designsystem.EmptyState
import fyi.b612.lovehouse.core.designsystem.LoveHouseColors
import fyi.b612.lovehouse.core.designsystem.LoveHouseSpacing
import fyi.b612.lovehouse.core.designsystem.SectionLabel
import fyi.b612.lovehouse.core.designsystem.StatusPill

@Composable
fun LoveHouseLaunchScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.55f),
                    ),
                ),
            )
            .padding(LoveHouseSpacing.Page),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Surface(
            modifier = Modifier.size(88.dp),
            shape = CircleShape,
            color = LoveHouseColors.Plum,
            shadowElevation = 8.dp,
        ) {
            Text(
                text = "♥",
                modifier = Modifier.padding(top = 16.dp),
                color = LoveHouseColors.Cream,
                style = MaterialTheme.typography.displaySmall,
                textAlign = TextAlign.Center,
            )
        }
        Text(
            text = "LoveHouse",
            modifier = Modifier.padding(top = LoveHouseSpacing.XLarge),
            style = MaterialTheme.typography.displaySmall,
        )
        Text(
            text = "一座正在醒来的小屋。",
            modifier = Modifier.padding(top = LoveHouseSpacing.Small),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun PlaceholderScreen(
    eyebrow: String,
    title: String,
    message: String,
    status: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = LoveHouseSpacing.Page, vertical = LoveHouseSpacing.XLarge),
        verticalArrangement = Arrangement.spacedBy(LoveHouseSpacing.Large),
    ) {
        SectionLabel(eyebrow)
        Text(title, style = MaterialTheme.typography.headlineMedium)
        StatusPill(status)
        EmptyState(
            title = "这个房间已经有轮廓啦。",
            message = message,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "阶段 0 特意让这里保持安静，还没有搬入任何网页页面。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
fun NavGlyph(
    glyph: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
) {
    Text(
        text = glyph,
        modifier = modifier
            .size(30.dp)
            .clip(CircleShape)
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)
                else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            )
            .padding(top = 6.dp),
        textAlign = TextAlign.Center,
        style = MaterialTheme.typography.labelLarge,
        fontWeight = FontWeight.Bold,
        color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
