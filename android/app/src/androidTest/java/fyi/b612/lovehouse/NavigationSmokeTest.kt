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
            runCatching { composeRule.onNodeWithText("Home").fetchSemanticsNode() }.isSuccess
        }

        listOf("Chat", "Memory", "Engineering", "Settings").forEach { label ->
            composeRule.onNodeWithText(label).performClick()
            composeRule.onNodeWithText(label).assertIsDisplayed()
        }

        composeRule.onNodeWithText("Native Lab").performClick()
        composeRule.onNodeWithText("Native Lab").assertIsDisplayed()
        composeRule.onNodeWithText("Photos").assertIsDisplayed()
        composeRule.onNodeWithText("Deep Link").assertIsDisplayed()
    }
}
