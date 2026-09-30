package fyi.b612.lovehouse

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import fyi.b612.lovehouse.feature.chat.ChatProcessEvent
import fyi.b612.lovehouse.feature.chat.ChatProcessKind
import fyi.b612.lovehouse.feature.chat.ChatProcessStatus
import fyi.b612.lovehouse.feature.chat.ProcessTimeline
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProcessTimelineUiTest {
    @get:Rule
    val compose = createComposeRule()

    @Test
    fun itemsDefaultCollapsedExpandIndependentlyAndConnectorTracksMeasuredHeight() {
        val thinking = ChatProcessEvent(
            id = "thinking",
            kind = ChatProcessKind.Thinking,
            title = "Thinking",
            status = ChatProcessStatus.Running,
            detail = "第一行\n第二行\n第三行",
        )
        val tool = ChatProcessEvent(
            id = "tool-call:one",
            kind = ChatProcessKind.ToolResult,
            title = "调用 recall",
            status = ChatProcessStatus.Succeeded,
            detail = "读取完成",
        )
        compose.setContent { MaterialTheme { ProcessTimeline(listOf(thinking, tool)) } }

        compose.onNodeWithTag("process-timeline-detail:thinking").assertDoesNotExist()
        compose.onNodeWithTag("process-timeline-detail:tool-call:one").assertDoesNotExist()
        val collapsedHeight = compose.onNodeWithTag("process-timeline-item:thinking")
            .fetchSemanticsNode().boundsInRoot.height

        compose.onNodeWithTag("process-timeline-item:thinking").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("process-timeline-detail:thinking").assertIsDisplayed()
        compose.onNodeWithTag("process-timeline-detail:tool-call:one").assertDoesNotExist()
        val expandedHeight = compose.onNodeWithTag("process-timeline-item:thinking")
            .fetchSemanticsNode().boundsInRoot.height
        assertTrue(expandedHeight > collapsedHeight)

        compose.onNodeWithTag("process-timeline-item:tool-call:one").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("process-timeline-detail:thinking").assertIsDisplayed()
        compose.onNodeWithTag("process-timeline-detail:tool-call:one").assertIsDisplayed()

        compose.onNodeWithTag("process-timeline-item:thinking").performClick()
        compose.waitForIdle()
        compose.onNodeWithTag("process-timeline-detail:thinking").assertDoesNotExist()
        compose.onNodeWithTag("process-timeline-detail:tool-call:one").assertIsDisplayed()
    }
}
