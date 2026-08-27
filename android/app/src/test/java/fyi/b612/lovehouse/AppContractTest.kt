package fyi.b612.lovehouse

import fyi.b612.lovehouse.core.navigation.AppDestination
import fyi.b612.lovehouse.core.permissions.NativeCapability
import fyi.b612.lovehouse.feature.nativelab.formatFileSize
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppContractTest {
    @Test
    fun `primary navigation contains exactly the five phase zero rooms`() {
        assertEquals(
            listOf("首页", "聊天", "记忆", "工程", "设置"),
            AppDestination.primary.map { it.label },
        )
    }

    @Test
    fun `all routes have stable native deep links`() {
        AppDestination.entries.forEach { destination ->
            assertTrue(destination.deepLink.startsWith("lovehouse://"))
            assertFalse(destination.deepLink.contains("b612.fyi"))
        }
    }

    @Test
    fun `native lab exposes the nine planned capabilities`() {
        assertEquals(
            listOf(
                "照片",
                "相机",
                "文件",
                "麦克风",
                "位置",
                "通知",
                "分享",
                "生物识别",
                "深链",
            ),
            NativeCapability.entries.map { it.label },
        )
    }

    @Test
    fun `settings stays selected for native lab child route`() {
        assertEquals(AppDestination.Settings, AppDestination.selectedForRoute(AppDestination.NativeLab.route))
    }

    @Test
    fun `selected resource sizes have compact user facing metadata`() {
        assertEquals("未知", formatFileSize(null))
        assertEquals("512 B", formatFileSize(512))
        assertEquals("1.5 KB", formatFileSize(1_536))
        assertEquals("2.0 MB", formatFileSize(2_097_152))
    }
}
