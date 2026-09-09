package fyi.b612.lovehouse

import android.Manifest
import fyi.b612.lovehouse.core.capability.CapabilityAvailability
import fyi.b612.lovehouse.core.capability.CapabilityAttachmentType
import fyi.b612.lovehouse.core.capability.CapabilityLifecycle
import fyi.b612.lovehouse.core.capability.LoveHouseCapabilityId
import fyi.b612.lovehouse.core.capability.ProviderCapabilityProfile
import fyi.b612.lovehouse.core.capability.ProviderConsumption
import fyi.b612.lovehouse.core.capability.buildLoveHouseCapabilities
import fyi.b612.lovehouse.core.permissions.CapabilityPermissionStatus
import fyi.b612.lovehouse.core.permissions.NativeCapability
import fyi.b612.lovehouse.core.permissions.PermissionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GlobalCapabilityRegistryTest {
    private val codex = ProviderCapabilityProfile(
        providerId = "codex",
        acceptedAttachmentTypes = setOf(CapabilityAttachmentType.Photo, CapabilityAttachmentType.File, CapabilityAttachmentType.Location),
        maxAttachmentItems = 12,
        supportsTextWithAttachments = true,
        supportedAttachmentLifecycles = setOf(CapabilityLifecycle.Local, CapabilityLifecycle.Ephemeral),
        consumesVoiceTranscriptAsText = true,
    )
    private val claude = ProviderCapabilityProfile(
        providerId = "claude",
        acceptedAttachmentTypes = emptySet(),
        maxAttachmentItems = 0,
        supportsTextWithAttachments = false,
        supportedAttachmentLifecycles = emptySet(),
        consumesVoiceTranscriptAsText = true,
    )

    @Test
    fun `attachment capability belongs to LoveHouse while providers only declare consumption`() {
        val state = buildLoveHouseCapabilities(grantedPermissions(), true, listOf(codex, claude), 35)

        val photo = state.capability(LoveHouseCapabilityId.AttachmentPhoto)!!
        assertEquals(CapabilityAvailability.Available, photo.availability)
        assertEquals(ProviderConsumption.Supported, photo.providerConsumption["codex"])
        assertEquals(ProviderConsumption.Unsupported, photo.providerConsumption["claude"])
        assertEquals(12, codex.maxAttachmentItems)
    }

    @Test
    fun `permission and runtime availability remain separate truthful states`() {
        val permissions = grantedPermissions().map {
            if (it.capability == NativeCapability.Location) it.copy(state = PermissionState.Denied) else it
        }
        val state = buildLoveHouseCapabilities(permissions, false, listOf(codex, claude), 35)

        val location = state.capability(LoveHouseCapabilityId.AttachmentLocation)!!
        assertEquals(CapabilityAvailability.Unavailable, location.availability)
        assertEquals(ProviderConsumption.Supported, location.providerConsumption["codex"])
        assertTrue(Manifest.permission.ACCESS_COARSE_LOCATION in location.requiredAndroidPermissions)
        assertEquals(CapabilityAvailability.Unavailable, state.capability(LoveHouseCapabilityId.VoiceStt)!!.availability)
        assertEquals(CapabilityAvailability.Unavailable, state.capability(LoveHouseCapabilityId.VoiceTts)!!.availability)
    }

    @Test
    fun `camera transport is registered as partial rather than fake success`() {
        val state = buildLoveHouseCapabilities(grantedPermissions(), true, listOf(codex, claude), 35)
        val cameraAttachment = state.capability(LoveHouseCapabilityId.AttachmentCamera)!!

        assertEquals(CapabilityAvailability.Partial, cameraAttachment.availability)
        assertTrue(cameraAttachment.unavailableReason!!.contains("transport"))
        assertEquals(state.capabilities.size, state.capabilities.map { it.id }.distinct().size)
    }

    private fun grantedPermissions(): List<CapabilityPermissionStatus> = NativeCapability.entries.map {
        CapabilityPermissionStatus(it, PermissionState.Granted)
    }
}
