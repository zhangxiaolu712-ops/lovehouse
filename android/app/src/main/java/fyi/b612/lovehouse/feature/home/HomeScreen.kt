package fyi.b612.lovehouse.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import fyi.b612.lovehouse.core.designsystem.LoveHouseCard
import fyi.b612.lovehouse.core.designsystem.LoveHouseSpacing
import fyi.b612.lovehouse.core.designsystem.SectionLabel
import fyi.b612.lovehouse.core.designsystem.StatusPill

@Composable
fun HomeScreen(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.22f),
                    ),
                ),
            )
            .padding(horizontal = LoveHouseSpacing.Page, vertical = LoveHouseSpacing.XLarge),
        verticalArrangement = Arrangement.spacedBy(LoveHouseSpacing.Large),
    ) {
        SectionLabel("原生小屋 · 阶段 0")
        Text("小屋醒来啦。", style = MaterialTheme.typography.displaySmall)
        Text(
            "五个安静的房间，共用一套原生地基。以后再一盏一盏地点亮。",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StatusPill("原生壳已就绪")

        LoveHouseCard(modifier = Modifier.fillMaxWidth()) {
            Text("今天的 LoveHouse", style = MaterialTheme.typography.titleLarge)
            Text(
                "没有搬入任何网页页面，导航、深链和原生能力契约已经独立站稳。",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                MiniRoom("5", "房间")
                MiniRoom("9", "原生能力")
                MiniRoom("0", "后端调用")
            }
        }
    }
}

@Composable
private fun MiniRoom(value: String, label: String) {
    Column {
        Text(value, style = MaterialTheme.typography.headlineMedium)
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
