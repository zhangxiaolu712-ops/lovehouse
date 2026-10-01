package fyi.b612.lovehouse.feature.chat

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import fyi.b612.lovehouse.core.designsystem.LoveHouseIcon
import fyi.b612.lovehouse.core.designsystem.LoveHouseIconOpticalSize
import fyi.b612.lovehouse.core.designsystem.LoveHouseIconView

private val ToolDetailInk = Color(0xFF3E4847)
private val ToolDetailMuted = Color(0xFF7F8B88)
private val ToolDetailAccent = Color(0xFF718E87)
private const val TOOL_DETAIL_TEXT_PREVIEW_CHARS = 420

internal data class ToolDetailPresentation(
    val request: ToolDetailValue?,
    val response: ToolDetailValue?,
    val isError: Boolean,
    val truncated: Boolean,
)

internal data class ToolDetailSheetRequest(
    val assistantMessageId: String,
    val eventId: String,
    val title: String,
)

internal data class ToolDetailSheetState(
    val request: ToolDetailSheetRequest,
    val loading: Boolean = true,
    val detail: ToolDetailEnvelope? = null,
)

internal fun toolDetailPresentation(detail: ToolDetailEnvelope): ToolDetailPresentation = when (detail) {
    is ToolDetailEnvelope.GenericTool -> ToolDetailPresentation(
        request = detail.arguments,
        response = detail.result,
        isError = detail.isError == true,
        truncated = detail.truncated,
    )
    is ToolDetailEnvelope.Command -> ToolDetailPresentation(
        request = detail.command?.let { command ->
            ToolDetailValue.ObjectValue(listOf(ToolDetailField("command", ToolDetailValue.Text(command))))
        },
        response = listOfNotNull(
            detail.output?.let { ToolDetailField("output", ToolDetailValue.Text(it)) },
            detail.exitCode?.let { ToolDetailField("exit_code", ToolDetailValue.NumberValue(it.toDouble())) },
            detail.status?.let { ToolDetailField("status", ToolDetailValue.Text(it)) },
        ).takeIf { it.isNotEmpty() }?.let(ToolDetailValue::ObjectValue),
        isError = detail.status == "failed" || (detail.exitCode != null && detail.exitCode != 0),
        truncated = detail.truncated,
    )
}

internal fun toolDetailValueText(value: ToolDetailValue): String? = when (value) {
    is ToolDetailValue.Text -> value.text
    is ToolDetailValue.NumberValue -> if (value.value % 1.0 == 0.0) value.value.toLong().toString() else value.value.toString()
    is ToolDetailValue.BooleanValue -> value.value.toString()
    ToolDetailValue.NullValue -> "null"
    is ToolDetailValue.Omitted -> value.reason
    is ToolDetailValue.ListValue, is ToolDetailValue.ObjectValue -> null
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun ToolDetailBottomSheet(
    state: ToolDetailSheetState,
    visualContext: ChatVisualContext,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = visualContext.popupGlass,
        scrimColor = visualContext.bottomTint.copy(alpha = .18f),
        shape = RoundedCornerShape(topStart = 25.dp, topEnd = 25.dp),
        dragHandle = null,
        modifier = Modifier.testTag("tool-detail-sheet"),
    ) {
        Column(
            Modifier.fillMaxWidth().heightIn(max = 680.dp).padding(bottom = 18.dp),
        ) {
            Row(
                Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp, top = 16.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(Modifier.weight(1f)) {
                    Text(state.request.title, color = ToolDetailInk, fontSize = 15.sp, fontWeight = FontWeight.Medium)
                    Text("工具详情 · 本机安全缓存", color = ToolDetailMuted, fontSize = 9.sp)
                }
                Surface(
                    modifier = Modifier.size(36.dp).clickable(onClick = onDismiss),
                    shape = RoundedCornerShape(12.dp),
                    color = Color.White.copy(alpha = .28f),
                ) {
                    LoveHouseIconView(
                        LoveHouseIcon.Close,
                        "关闭",
                        Modifier.padding(10.dp),
                        ToolDetailMuted,
                        LoveHouseIconOpticalSize.Compact,
                    )
                }
            }

            when {
                state.loading -> Text(
                    "正在读取详情…",
                    Modifier.padding(horizontal = 20.dp, vertical = 24.dp).testTag("tool-detail-loading"),
                    color = ToolDetailMuted,
                    fontSize = 11.sp,
                )
                state.detail == null -> Text(
                    "详细内容已过期或不可用",
                    Modifier.padding(horizontal = 20.dp, vertical = 24.dp).testTag("tool-detail-unavailable"),
                    color = ToolDetailMuted,
                    fontSize = 11.sp,
                )
                else -> {
                    val presentation = toolDetailPresentation(state.detail)
                    LazyColumn(
                        Modifier.fillMaxWidth().weight(1f, fill = false).testTag("tool-detail-content"),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        if (presentation.truncated) {
                            item {
                                Text(
                                    "内容已截断",
                                    Modifier.padding(horizontal = 20.dp).testTag("tool-detail-truncated"),
                                    color = ToolDetailAccent,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                        if (presentation.isError) {
                            item {
                                Text(
                                    "工具返回错误",
                                    Modifier.padding(horizontal = 20.dp).testTag("tool-detail-error"),
                                    color = MaterialTheme.colorScheme.error,
                                    fontSize = 9.sp,
                                    fontWeight = FontWeight.Medium,
                                )
                            }
                        }
                        item {
                            ToolDetailSection("Request", presentation.request, "tool-detail-request")
                        }
                        item {
                            ToolDetailSection("Response", presentation.response, "tool-detail-response")
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolDetailSection(
    title: String,
    value: ToolDetailValue?,
    tag: String,
) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp).testTag(tag),
        verticalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Text(title, color = ToolDetailAccent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        Surface(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(14.dp),
            color = Color.White.copy(alpha = .34f),
            border = BorderStroke(.6.dp, Color.White.copy(alpha = .55f)),
        ) {
            if (value == null) {
                Text("无可用内容", Modifier.padding(13.dp), color = ToolDetailMuted, fontSize = 10.sp)
            } else {
                ToolDetailValueView(value, Modifier.padding(13.dp))
            }
        }
    }
}

@Composable
private fun ToolDetailValueView(
    value: ToolDetailValue,
    modifier: Modifier = Modifier,
    depth: Int = 0,
) {
    val scalar = toolDetailValueText(value)
    when {
        scalar != null -> ToolDetailScalarText(value, scalar, modifier)
        value is ToolDetailValue.ObjectValue -> Column(
            modifier,
            verticalArrangement = Arrangement.spacedBy(if (depth == 0) 9.dp else 6.dp),
        ) {
            if (value.fields.isEmpty()) Text("空对象", color = ToolDetailMuted, fontSize = 10.sp)
            value.fields.forEach { field ->
                Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text(field.key, color = ToolDetailMuted, fontSize = 8.5.sp, fontWeight = FontWeight.Medium)
                    ToolDetailValueView(
                        field.value,
                        Modifier.fillMaxWidth().padding(start = if (depth < 2) 8.dp else 0.dp),
                        depth + 1,
                    )
                }
            }
        }
        value is ToolDetailValue.ListValue -> Column(
            modifier,
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            if (value.items.isEmpty()) Text("空列表", color = ToolDetailMuted, fontSize = 10.sp)
            value.items.forEachIndexed { index, item ->
                Row(verticalAlignment = Alignment.Top) {
                    Text("${index + 1}.", color = ToolDetailMuted, fontSize = 9.sp)
                    ToolDetailValueView(item, Modifier.weight(1f).padding(start = 7.dp), depth + 1)
                }
            }
        }
    }
}

@Composable
private fun ToolDetailScalarText(
    value: ToolDetailValue,
    text: String,
    modifier: Modifier,
) {
    var expanded by remember(text) { mutableStateOf(false) }
    val longText = value is ToolDetailValue.Text && text.length > TOOL_DETAIL_TEXT_PREVIEW_CHARS
    val visible = if (longText && !expanded) text.take(TOOL_DETAIL_TEXT_PREVIEW_CHARS).trimEnd() + "…" else text
    Column(modifier) {
        Text(
            visible,
            color = if (value is ToolDetailValue.Omitted) ToolDetailMuted else ToolDetailInk,
            fontSize = 10.sp,
            lineHeight = 15.sp,
            fontFamily = if (longText) FontFamily.Monospace else FontFamily.Default,
        )
        if (longText) {
            Text(
                if (expanded) "收起" else "展开全部",
                Modifier.padding(top = 5.dp).clickable { expanded = !expanded }
                    .testTag("tool-detail-long-text-toggle"),
                color = ToolDetailAccent,
                fontSize = 9.sp,
                fontWeight = FontWeight.Medium,
            )
        }
    }
}
