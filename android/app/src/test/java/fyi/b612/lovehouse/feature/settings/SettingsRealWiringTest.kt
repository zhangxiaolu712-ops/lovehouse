package fyi.b612.lovehouse.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class SettingsRealWiringTest {
    @Test
    fun formatsActualLocalByteCountsWithoutInventingDeviceCapacity() {
        assertEquals("0 B", formatBytes(0))
        assertEquals("1.0 KB", formatBytes(1024))
        assertEquals("1.5 MB", formatBytes(1024L * 1024L + 512L * 1024L))
    }
}
