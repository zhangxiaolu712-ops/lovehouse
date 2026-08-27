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
        SectionLabel("Native shell · phase 0")
        Text("The house is awake.", style = MaterialTheme.typography.displaySmall)
        Text(
            "Five quiet rooms, one native foundation. We will light them one by one.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        StatusPill("Shell ready")

        LoveHouseCard(modifier = Modifier.fillMaxWidth()) {
            Text("Today in LoveHouse", style = MaterialTheme.typography.titleLarge)
            Text(
                "No HTML has been moved in. Navigation, deep links and native capability contracts are standing on their own.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                MiniRoom("5", "rooms")
                MiniRoom("9", "native labs")
                MiniRoom("0", "backend calls")
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
