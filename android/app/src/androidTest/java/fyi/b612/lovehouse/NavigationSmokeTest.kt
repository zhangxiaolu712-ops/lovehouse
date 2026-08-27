package fyi.b612.lovehouse

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class NavigationSmokeTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun fiveRoomsAndNativeLabAreReachable() {
        composeRule.waitUntil(timeoutMillis = 3_000) {
            runCatching { composeRule.onNodeWithText("首页").fetchSemanticsNode() }.isSuccess
        }

        listOf("聊天", "记忆", "工程", "设置").forEach { label ->
            composeRule.onNodeWithText(label).performClick()
            composeRule.onNodeWithText(label).assertIsDisplayed()
        }

        composeRule.onNodeWithText("原生能力测试").performClick()
        composeRule.onNodeWithText("原生能力测试").assertIsDisplayed()
        composeRule.onNodeWithText("照片").assertIsDisplayed()
        composeRule.onNodeWithText("深链").assertIsDisplayed()
    }
}
