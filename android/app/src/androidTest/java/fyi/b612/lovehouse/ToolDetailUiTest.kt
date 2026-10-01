package fyi.b612.lovehouse

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import fyi.b612.lovehouse.feature.chat.ChatBackdrop
import fyi.b612.lovehouse.feature.chat.ChatVisualContext
import fyi.b612.lovehouse.feature.chat.ToolDetailBottomSheet
import fyi.b612.lovehouse.feature.chat.ToolDetailEnvelope
import fyi.b612.lovehouse.feature.chat.ToolDetailField
import fyi.b612.lovehouse.feature.chat.ToolDetailSheetRequest
import fyi.b612.lovehouse.feature.chat.ToolDetailSheetState
import fyi.b612.lovehouse.feature.chat.ToolDetailValue
import org.junit.Rule
import org.junit.Test

class ToolDetailUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun unknownMcpRendersNestedRequestResponseAndLongTextWithoutDedicatedRenderer() {
        val longForecast = "forecast ".repeat(100)
        val detail = ToolDetailEnvelope.GenericTool(
            schemaVersion = 1,
            callId = "future-call",
            createdAt = "2026-10-01T00:00:00.000Z",
            truncated = true,
            originalLength = longForecast.length,
            arguments = ToolDetailValue.ObjectValue(
                listOf(
                    ToolDetailField("city", ToolDetailValue.Text("Shanghai")),
                    ToolDetailField("days", ToolDetailValue.NumberValue(3.0)),
                    ToolDetailField(
                        "options",
                        ToolDetailValue.ObjectValue(
                            listOf(ToolDetailField("units", ToolDetailValue.Text("metric"))),
                        ),
                    ),
                ),
            ),
            result = ToolDetailValue.ObjectValue(
                listOf(
                    ToolDetailField(
                        "forecast",
                        ToolDetailValue.ListValue(listOf(ToolDetailValue.Text(longForecast))),
                    ),
                ),
            ),
            isError = false,
        )

        compose.setContent {
            MaterialTheme {
                ToolDetailBottomSheet(
                    state = loadedState("future_unknown_mcp_tool", detail),
                    visualContext = visualContext,
                    onDismiss = {},
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("future_unknown_mcp_tool").assertIsDisplayed()
        compose.onNodeWithTag("tool-detail-request").assertIsDisplayed()
        compose.onNodeWithTag("tool-detail-response").assertIsDisplayed()
        compose.onNodeWithText("Shanghai").assertIsDisplayed()
        compose.onNodeWithText("metric").assertIsDisplayed()
        compose.onNodeWithTag("tool-detail-truncated").assertIsDisplayed()
        compose.onNodeWithTag("tool-detail-long-text-toggle").assertIsDisplayed().performClick()
    }

    @Test
    fun missingOrExpiredDetailShowsSafeFallback() {
        compose.setContent {
            MaterialTheme {
                ToolDetailBottomSheet(
                    state = ToolDetailSheetState(
                        request = ToolDetailSheetRequest("assistant:one", "tool-call:missing", "未知工具"),
                        loading = false,
                        detail = null,
                    ),
                    visualContext = visualContext,
                    onDismiss = {},
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithTag("tool-detail-unavailable").assertIsDisplayed()
        compose.onNodeWithText("详细内容已过期或不可用").assertIsDisplayed()
    }

    @Test
    fun jsonStringResultRendersEveryNestedItemWithoutToolSpecificUi() {
        val detail = ToolDetailEnvelope.GenericTool(
            schemaVersion = 1,
            callId = "future-json-call",
            createdAt = "2026-10-01T00:00:00.000Z",
            truncated = false,
            originalLength = 80,
            arguments = ToolDetailValue.ObjectValue(emptyList()),
            result = ToolDetailValue.ListValue(
                listOf(
                    ToolDetailValue.ObjectValue(
                        listOf(
                            ToolDetailField("type", ToolDetailValue.Text("text")),
                            ToolDetailField(
                                "text",
                                ToolDetailValue.Text(
                                    """{"items":[{"label":"first"},{"label":"second"},{"label":"third"}]}""",
                                ),
                            ),
                        ),
                    ),
                ),
            ),
            isError = false,
        )

        compose.setContent {
            MaterialTheme {
                ToolDetailBottomSheet(
                    state = loadedState("future_unknown_mcp_tool", detail),
                    visualContext = visualContext,
                    onDismiss = {},
                )
            }
        }
        compose.waitForIdle()

        compose.onNodeWithText("items").assertIsDisplayed()
        compose.onNodeWithText("first").assertIsDisplayed()
        compose.onNodeWithText("second").assertIsDisplayed()
        compose.onNodeWithText("third").assertIsDisplayed()
    }

    private fun loadedState(title: String, detail: ToolDetailEnvelope) = ToolDetailSheetState(
        request = ToolDetailSheetRequest("assistant:one", "tool-call:${detail.callId}", title),
        loading = false,
        detail = detail,
    )

    private val visualContext = ChatVisualContext(ChatBackdrop.Green, null)
}
