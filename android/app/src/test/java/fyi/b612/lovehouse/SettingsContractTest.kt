package fyi.b612.lovehouse

import fyi.b612.lovehouse.feature.settings.AddConnectionRequest
import fyi.b612.lovehouse.feature.settings.AddConnectionState
import fyi.b612.lovehouse.feature.settings.ConnectionListState
import fyi.b612.lovehouse.feature.settings.EngineeringControlArea
import fyi.b612.lovehouse.feature.settings.MockSettingsRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsContractTest {
    @Test
    fun `one generic connection request accepts any required credential shape`() {
        val repository = MockSettingsRepository()

        repository.addConnection(AddConnectionRequest("one-time-value"))

        assertEquals(AddConnectionState.WaitingServerConfirmation, repository.addConnectionState.value)
    }

    @Test
    fun `blank connection request is rejected locally`() {
        val repository = MockSettingsRepository()

        repository.addConnection(AddConnectionRequest(" "))

        assertTrue(repository.addConnectionState.value is AddConnectionState.Error)
    }

    @Test
    fun `connected view keeps engineering controls behind the simple entry`() {
        val state = MockSettingsRepository().connections.value

        assertTrue(state is ConnectionListState.Content)
        assertEquals(7, EngineeringControlArea.entries.size)
    }
}
