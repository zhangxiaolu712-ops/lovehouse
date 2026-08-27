package fyi.b612.lovehouse.feature.nativelab

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import fyi.b612.lovehouse.core.designsystem.LoveHouseCard
import fyi.b612.lovehouse.core.designsystem.LoveHouseSecondaryButton
import fyi.b612.lovehouse.core.designsystem.LoveHouseSpacing
import fyi.b612.lovehouse.core.designsystem.SectionLabel
import fyi.b612.lovehouse.core.designsystem.StatusPill
import fyi.b612.lovehouse.core.permissions.PermissionState
import fyi.b612.lovehouse.core.status.SystemStatusProvider

@Composable
fun NativeLabScreen(
    systemStatusProvider: SystemStatusProvider,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val status by systemStatusProvider.status.collectAsState()

    LaunchedEffect(systemStatusProvider) {
        systemStatusProvider.refresh()
    }

    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = LoveHouseSpacing.Page),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(
            top = LoveHouseSpacing.XLarge,
            bottom = LoveHouseSpacing.Section,
        ),
        verticalArrangement = Arrangement.spacedBy(LoveHouseSpacing.Medium),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(LoveHouseSpacing.Medium)) {
                LoveHouseSecondaryButton("← Settings", onClick = onBack)
                SectionLabel("Hidden workshop")
                Text("Native Lab", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "These are stable capability slots, not active permissions. Each one will light up only when its feature arrives.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(LoveHouseSpacing.Small),
                ) {
                    StatusPill("v${status.appVersion}")
                    StatusPill(status.backend.label)
                }
            }
        }

        items(status.permissions, key = { it.capability.name }) { capabilityStatus ->
            LoveHouseCard(modifier = Modifier.fillMaxWidth()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(capabilityStatus.capability.label, style = MaterialTheme.typography.titleLarge)
                        Text(
                            capabilityStatus.capability.description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    StatusPill(
                        capabilityStatus.state.label,
                        color = when (capabilityStatus.state) {
                            PermissionState.Granted,
                            PermissionState.NotRequired,
                            -> MaterialTheme.colorScheme.tertiary

                            PermissionState.Denied -> MaterialTheme.colorScheme.error
                            PermissionState.NotRequested,
                            PermissionState.Unsupported,
                            -> MaterialTheme.colorScheme.secondary
                        },
                    )
                }
            }
        }
    }
}
