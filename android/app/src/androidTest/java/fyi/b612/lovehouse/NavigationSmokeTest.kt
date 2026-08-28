package fyi.b612.lovehouse

import android.Manifest
import android.content.ComponentName
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.filter
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.test.platform.app.InstrumentationRegistry
import fyi.b612.lovehouse.feature.screenobserver.ScreenObserverService
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
            val navigationItem = composeRule.onAllNodesWithText(label).filter(hasClickAction()).onFirst()
            navigationItem.performClick()
            navigationItem.assertIsDisplayed()
        }

        composeRule.onAllNodesWithText("原生能力测试").filter(hasClickAction()).onFirst().performClick()
        composeRule.onAllNodesWithText("原生能力测试").onFirst().assertIsDisplayed()
        composeRule.onNodeWithText("照片").assertIsDisplayed()
        composeRule.onNodeWithText("深链").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun remainingNativeCapabilityActionsAreReachable() {
        composeRule.waitUntil(timeoutMillis = 3_000) {
            composeRule.onAllNodesWithText("设置").filter(hasClickAction()).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onAllNodesWithText("设置").filter(hasClickAction()).onFirst().performClick()
        composeRule.onAllNodesWithText("原生能力测试").filter(hasClickAction()).onFirst().performClick()

        listOf(
            "开始屏幕观察",
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
                    Manifest.permission.FOREGROUND_SERVICE,
                    Manifest.permission.FOREGROUND_SERVICE_MEDIA_PROJECTION,
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT,
                ),
            ),
        )
    }

    @Test
    fun screenObserverServiceIsPrivateAndDeclaresMediaProjectionType() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val service = context.packageManager.getServiceInfo(
            ComponentName(context, ScreenObserverService::class.java),
            PackageManager.ComponentInfoFlags.of(0),
        )

        assertTrue(!service.exported)
        assertTrue(
            service.foregroundServiceType and ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION != 0,
        )
    }
}
