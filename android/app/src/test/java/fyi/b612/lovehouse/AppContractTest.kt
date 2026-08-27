package fyi.b612.lovehouse

import fyi.b612.lovehouse.core.navigation.AppDestination
import fyi.b612.lovehouse.core.permissions.NativeCapability
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppContractTest {
    @Test
    fun `primary navigation contains exactly the five phase zero rooms`() {
        assertEquals(
            listOf("Home", "Chat", "Memory", "Engineering", "Settings"),
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
                "Photos",
                "Camera",
                "Files",
                "Microphone",
                "Location",
                "Notifications",
                "Share",
                "Biometrics",
                "Deep Link",
            ),
            NativeCapability.entries.map { it.label },
        )
    }

    @Test
    fun `settings stays selected for native lab child route`() {
        assertEquals(AppDestination.Settings, AppDestination.selectedForRoute(AppDestination.NativeLab.route))
    }
}
