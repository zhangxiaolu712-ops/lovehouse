package fyi.b612.lovehouse.feature.nativelab

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import fyi.b612.lovehouse.core.designsystem.LoveHouseCard
import fyi.b612.lovehouse.core.designsystem.LoveHousePrimaryButton
import fyi.b612.lovehouse.core.designsystem.LoveHouseSecondaryButton
import fyi.b612.lovehouse.core.designsystem.LoveHouseSpacing
import fyi.b612.lovehouse.core.designsystem.SectionLabel
import fyi.b612.lovehouse.core.designsystem.StatusPill
import fyi.b612.lovehouse.core.permissions.CapabilityPermissionStatus
import fyi.b612.lovehouse.core.permissions.NativeCapability
import fyi.b612.lovehouse.core.permissions.PermissionState
import fyi.b612.lovehouse.core.status.SystemStatusProvider

private const val ShareSample = "来自 LoveHouse 原生小屋的一句测试分享。"

@Composable
fun NativeLabScreen(
    systemStatusProvider: SystemStatusProvider,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val status by systemStatusProvider.status.collectAsState()
    var photoResult by rememberSaveable { mutableStateOf<String?>(null) }
    var fileResult by rememberSaveable { mutableStateOf<String?>(null) }
    var shareResult by rememberSaveable { mutableStateOf<String?>(null) }

    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        photoResult = uri?.let { context.contentResolver.readSelectedResource(it).asDisplayText() }
            ?: "没有选择照片。"
    }
    val filePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        fileResult = uri?.let { context.contentResolver.readSelectedResource(it).asDisplayText() }
            ?: "没有选择文件。"
    }

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
                LoveHouseSecondaryButton("← 返回设置", onClick = onBack)
                SectionLabel("隐藏工作台")
                Text("原生能力测试", style = MaterialTheme.typography.headlineMedium)
                Text(
                    "照片、文件和分享已经可以真机试用；其他能力会在后续阶段逐项点亮。",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(LoveHouseSpacing.Small),
                ) {
                    StatusPill("版本 ${status.appVersion}")
                    StatusPill(status.backend.label)
                }
            }
        }

        items(status.permissions, key = { it.capability.name }) { capabilityStatus ->
            when (capabilityStatus.capability) {
                NativeCapability.Photos -> CapabilityCard(
                    capabilityStatus = capabilityStatus,
                    actionLabel = "选择一张照片",
                    result = photoResult,
                    onAction = {
                        photoPicker.launch(
                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                        )
                    },
                )

                NativeCapability.Files -> CapabilityCard(
                    capabilityStatus = capabilityStatus,
                    actionLabel = "选择一个文件",
                    result = fileResult,
                    onAction = { filePicker.launch(arrayOf("*/*")) },
                )

                NativeCapability.Share -> CapabilityCard(
                    capabilityStatus = capabilityStatus,
                    actionLabel = "打开系统分享面板",
                    result = shareResult,
                    onAction = {
                        shareResult = runCatching {
                            val sendIntent = Intent(Intent.ACTION_SEND).apply {
                                type = "text/plain"
                                putExtra(Intent.EXTRA_TEXT, ShareSample)
                            }
                            context.startActivity(Intent.createChooser(sendIntent, "用其他应用分享"))
                            "系统分享面板已打开。"
                        }.getOrElse {
                            "当前设备无法打开分享面板。"
                        }
                    },
                )

                else -> CapabilityCard(capabilityStatus = capabilityStatus)
            }
        }
    }
}

@Composable
private fun CapabilityCard(
    capabilityStatus: CapabilityPermissionStatus,
    actionLabel: String? = null,
    result: String? = null,
    onAction: (() -> Unit)? = null,
) {
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

        if (actionLabel != null && onAction != null) {
            LoveHousePrimaryButton(
                text = actionLabel,
                onClick = onAction,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        if (result != null) {
            Text(
                text = result,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
