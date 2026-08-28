package fyi.b612.lovehouse

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
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
        composeRule.onNodeWithText("深链").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun remainingNativeCapabilityActionsAreReachable() {
        composeRule.onNodeWithText("设置").performClick()
        composeRule.onNodeWithText("原生能力测试").performClick()

        listOf(
            "拍摄一张照片",
            "开始录音",
            "获取一次当前位置",
            "发送测试通知",
            "测试生物识别",
            "测试打开深链",
        ).forEach { label ->
            composeRule.onNodeWithText(label).performScrollTo().assertIsDisplayed()
        }
    }

    @Test
    @Suppress("DEPRECATION")
    fun runtimePermissionsAreDeclaredWithoutBeingRequestedAtLaunch() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val declared = context.packageManager
            .getPackageInfo(context.packageName, PackageManager.GET_PERMISSIONS)
            .requestedPermissions
            ?.toSet()
            .orEmpty()

        assertTrue(
            declared.containsAll(
                setOf(
                    Manifest.permission.CAMERA,
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                ),
            ),
        )
    }
}
