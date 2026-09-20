package fyi.b612.lovehouse.feature.settings

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fyi.b612.lovehouse.core.designsystem.LoveHouseGlass
import fyi.b612.lovehouse.core.selfcheck.DeploymentSelfCheckReport
import fyi.b612.lovehouse.core.selfcheck.DeploymentSelfCheckRunner
import fyi.b612.lovehouse.core.selfcheck.SelfCheckGroup
import fyi.b612.lovehouse.core.selfcheck.SelfCheckResult
import fyi.b612.lovehouse.core.selfcheck.SelfCheckStatus
import fyi.b612.lovehouse.core.selfcheck.safeText
import kotlinx.coroutines.launch

@Composable
internal fun DeploymentSelfCheckScreen(runner: DeploymentSelfCheckRunner) {
    var report by remember { mutableStateOf<DeploymentSelfCheckReport?>(null) }
    var running by remember { mutableStateOf(false) }
    var copied by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboardManager.current
    val runCheck = {
        if (!running) {
            running = true
            copied = false
            scope.launch {
                report = runner.run()
                running = false
            }
        }
    }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Surface(
            shape = RoundedCornerShape(17.dp),
            color = Color.White.copy(alpha = .48f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = .42f)),
        ) {
            Column(Modifier.fillMaxWidth().padding(14.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text("部署自检", color = LoveHouseGlass.Ink, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    "当前 Build: ${runner.buildIdentity.shortSha}",
                    color = LoveHouseGlass.MutedInk,
                    fontSize = 11.sp,
                )
                Text(
                    "${runner.buildIdentity.versionName} (${runner.buildIdentity.versionCode}) · ${runner.buildIdentity.buildType}",
                    color = LoveHouseGlass.MutedInk,
                    fontSize = 10.sp,
                )
                Row(Modifier.fillMaxWidth()) {
                    Button(
                        onClick = runCheck,
                        enabled = !running,
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF728F88)),
                    ) { Text(if (report == null) "开始检查" else "重新检查") }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = {
                            report?.let {
                                clipboard.setText(AnnotatedString(it.safeText()))
                                copied = true
                            }
                        },
                        enabled = report != null && !running,
                    ) { Text(if (copied) "已复制" else "复制报告") }
                }
                if (running) Text("正在只读检查 production wiring…", color = LoveHouseGlass.MutedInk, fontSize = 11.sp)
            }
        }

        report?.let { value ->
            SelfCheckSummaryCard(value)
            SelfCheckGroup.entries.forEach { group ->
                val groupResults = value.results.filter { it.group == group }
                if (groupResults.isNotEmpty()) SelfCheckGroupCard(group, groupResults)
            }
        }
    }
}

@Composable
private fun SelfCheckSummaryCard(report: DeploymentSelfCheckReport) {
    val summary = report.summary
    Surface(
        shape = RoundedCornerShape(17.dp),
        color = Color.White.copy(alpha = .42f),
        border = BorderStroke(1.dp, Color.White.copy(alpha = .38f)),
    ) {
        Column(Modifier.fillMaxWidth().padding(13.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("已检查：${summary.checked}", color = LoveHouseGlass.Ink, fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
            Text(
                SelfCheckStatus.entries.joinToString("  ") { "${it.displayLabel()}: ${summary.counts[it] ?: 0}" },
                color = LoveHouseGlass.MutedInk,
                fontSize = 10.sp,
                lineHeight = 15.sp,
            )
            Text("总体：${summary.overall.displayLabel()}", color = summary.overall.color(), fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
    }
}

@Composable
private fun SelfCheckGroupCard(group: SelfCheckGroup, results: List<SelfCheckResult>) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(group.label, color = LoveHouseGlass.MutedInk, fontSize = 11.sp, fontWeight = FontWeight.Medium)
        Surface(
            shape = RoundedCornerShape(17.dp),
            color = Color.White.copy(alpha = .44f),
            border = BorderStroke(1.dp, Color.White.copy(alpha = .4f)),
        ) {
            Column(Modifier.fillMaxWidth()) {
                results.forEach { result -> SelfCheckResultRow(result) }
            }
        }
    }
}

@Composable
private fun SelfCheckResultRow(result: SelfCheckResult) {
    var expanded by remember(result.id) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().clickable { expanded = !expanded }.padding(horizontal = 12.dp, vertical = 9.dp),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(Modifier.fillMaxWidth()) {
            Text(result.status.displayLabel(), color = result.status.color(), fontSize = 10.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.width(8.dp))
            Text(result.name, modifier = Modifier.weight(1f), color = LoveHouseGlass.Ink, fontSize = 12.sp, fontWeight = FontWeight.Medium)
        }
        Text(result.reason, color = LoveHouseGlass.MutedInk, fontSize = 10.sp, lineHeight = 14.sp)
        AnimatedVisibility(expanded && !result.detail.isNullOrBlank()) {
            Text(result.detail.orEmpty(), color = LoveHouseGlass.MutedInk, fontSize = 9.sp, lineHeight = 13.sp)
        }
    }
}

private fun SelfCheckStatus.displayLabel(): String = when (this) {
    SelfCheckStatus.N_A -> "N/A"
    else -> name
}

private fun SelfCheckStatus.color(): Color = when (this) {
    SelfCheckStatus.PASS -> Color(0xFF4F7D6E)
    SelfCheckStatus.FAIL -> Color(0xFF9B4F55)
    SelfCheckStatus.WARN -> Color(0xFF9A733D)
    SelfCheckStatus.PARTIAL -> Color(0xFF76669A)
    SelfCheckStatus.N_A,
    SelfCheckStatus.NOT_BUILT,
    -> LoveHouseGlass.MutedInk
}
