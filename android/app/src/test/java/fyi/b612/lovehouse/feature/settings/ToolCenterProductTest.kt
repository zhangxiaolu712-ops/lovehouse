package fyi.b612.lovehouse.feature.settings

import org.junit.Assert.assertEquals
import org.junit.Test

class ToolCenterProductTest {
    @Test
    fun `mentions include only available preferred groups`() {
        val available = capability("engineering-read", "engineering", ToolAvailability.Available)
        val sameGroup = capability("engineering-open", "engineering", ToolAvailability.Available)
        val notEnabled = capability("livingroom-read", "livingroom", ToolAvailability.Available)
        val denied = capability("memory-read", "memory", ToolAvailability.NoPermission)

        val result = eligibleToolMentions(
            listOf(available, sameGroup, notEnabled, denied),
            setOf("engineering-read", "engineering-open", "memory-read"),
        )

        assertEquals(listOf("engineering-read"), result.map { it.toolId })
        assertEquals(
            listOf("engineering-read", "engineering-open"),
            resolveEnabledCapabilities(
                listOf(available, sameGroup, notEnabled, denied),
                setOf("engineering-read", "engineering-open", "memory-read"),
            ).map { it.toolId },
        )
    }

    private fun capability(id: String, group: String, availability: ToolAvailability) = ToolCapability(
        toolId = id,
        group = group,
        groupLabel = group,
        displayName = id,
        summary = id,
        availability = availability,
        detail = "",
        riskLevel = ToolRiskLevel.Low,
        capabilityKind = ToolCapabilityKind.Read,
        requiresApproval = false,
        scope = emptyList(),
    )
}
