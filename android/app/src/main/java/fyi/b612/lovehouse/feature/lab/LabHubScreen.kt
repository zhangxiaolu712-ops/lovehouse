package fyi.b612.lovehouse.feature.lab

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fyi.b612.lovehouse.core.designsystem.LoveHouseGlass

@Composable
fun LabHubScreen(
    onOpenConnectionControl: () -> Unit,
    onOpenToolCenter: () -> Unit,
    onOpenNativeLab: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onBack)
    LazyColumn(
        modifier = modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding(),
        contentPadding = PaddingValues(horizontal = 18.dp, vertical = 14.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("返回桌面", modifier = Modifier.clickable(onClick = onBack).padding(vertical = 8.dp), color = LoveHouseGlass.MutedInk, fontSize = 11.sp)
            Text("Lab", color = LoveHouseGlass.Ink, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
            Text("施工测试入口", color = LoveHouseGlass.MutedInk, fontSize = 10.sp)
        }
        item { LabEntry("连接与工程控制", "连接入口、状态与工程控制测试", onOpenConnectionControl) }
        item { LabEntry("MCP Tools Lab", "内置工具状态、测试与 agent-codex 本机偏好", onOpenToolCenter) }
        item { LabEntry("Native Lab", "Android 原生能力与设备状态测试", onOpenNativeLab) }
    }
}

@Composable
private fun LabEntry(title: String, subtitle: String, onClick: () -> Unit) {
    Surface(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
        shape = RoundedCornerShape(18.dp),
        color = Color.White.copy(alpha = 0.42f),
        border = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.58f)),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 15.dp, vertical = 16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, color = LoveHouseGlass.Ink, fontSize = 14.sp, fontWeight = FontWeight.Medium)
                Text(subtitle, color = LoveHouseGlass.MutedInk, fontSize = 10.sp, lineHeight = 14.sp)
            }
            Text("›", color = LoveHouseGlass.Ink, fontSize = 20.sp)
        }
    }
}
