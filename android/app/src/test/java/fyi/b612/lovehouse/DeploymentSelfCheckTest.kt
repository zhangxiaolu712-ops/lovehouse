package fyi.b612.lovehouse

import fyi.b612.lovehouse.core.capability.CapabilityAttachmentType
import fyi.b612.lovehouse.core.capability.CapabilityLifecycle
import fyi.b612.lovehouse.core.capability.LoveHouseCapabilityId
import fyi.b612.lovehouse.core.capability.LoveHouseCapabilityRegistry
import fyi.b612.lovehouse.core.capability.LoveHouseCapabilityState
import fyi.b612.lovehouse.core.capability.ProviderCapabilityProfile
import fyi.b612.lovehouse.core.capability.buildLoveHouseCapabilities
import fyi.b612.lovehouse.core.permissions.CapabilityPermissionStatus
import fyi.b612.lovehouse.core.permissions.NativeCapability
import fyi.b612.lovehouse.core.permissions.PermissionState
import fyi.b612.lovehouse.core.selfcheck.AttachmentContractSelfCheckContributor
import fyi.b612.lovehouse.core.selfcheck.BuildIdentity
import fyi.b612.lovehouse.core.selfcheck.BuildIdentitySelfCheckContributor
import fyi.b612.lovehouse.core.selfcheck.CapabilityReadinessProbe
import fyi.b612.lovehouse.core.selfcheck.NativeCapabilitySelfCheckContributor
import fyi.b612.lovehouse.core.selfcheck.ProductionWiringSelfCheckContributor
import fyi.b612.lovehouse.core.selfcheck.ProductionWiringSnapshot
import fyi.b612.lovehouse.core.selfcheck.SelfCheckGroup
import fyi.b612.lovehouse.core.selfcheck.SelfCheckResult
import fyi.b612.lovehouse.core.selfcheck.SelfCheckStatus
import fyi.b612.lovehouse.core.selfcheck.sanitizeSelfCheckReport
import fyi.b612.lovehouse.core.selfcheck.staticIntentReadiness
import fyi.b612.lovehouse.core.selfcheck.summarizeSelfChecks
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeploymentSelfCheckTest {
    @Test
    fun `summary aggregation follows deployment severity`() {
        val results = listOf(
            result("pass", SelfCheckStatus.PASS),
            result("warn", SelfCheckStatus.WARN),
            result("partial", SelfCheckStatus.PARTIAL),
            result("na", SelfCheckStatus.N_A),
            result("not-built", SelfCheckStatus.NOT_BUILT),
        )
        val warning = summarizeSelfChecks(results)
        assertEquals(SelfCheckStatus.WARN, warning.overall)
        assertEquals(5, warning.checked)
        assertEquals(1, warning.counts[SelfCheckStatus.PARTIAL])

        val failed = summarizeSelfChecks(results + result("fail", SelfCheckStatus.FAIL))
        assertEquals(SelfCheckStatus.FAIL, failed.overall)
    }

    @Test
    fun `every global capability receives a self-check registration`() = runBlocking {
        val registry = FixedCapabilityRegistry(completeState())
        val results = NativeCapabilitySelfCheckContributor(registry, CapabilityReadinessProbe { null }).checks()

        assertEquals(LoveHouseCapabilityId.entries.size, results.size)
        assertEquals(
            LoveHouseCapabilityId.entries.map { "capability.${it.value}" }.toSet(),
            results.map { it.id }.toSet(),
        )
        assertFalse(results.any { it.status == SelfCheckStatus.FAIL })
    }

    @Test
    fun `missing capability registration is a deployment failure`() = runBlocking {
        val missing = LoveHouseCapabilityId.DeviceShare
        val state = completeState().copy(capabilities = completeState().capabilities.filterNot { it.id == missing })
        val results = NativeCapabilitySelfCheckContributor(FixedCapabilityRegistry(state), CapabilityReadinessProbe { null }).checks()

        val result = results.single { it.id == "capability.${missing.value}" }
        assertEquals(SelfCheckStatus.FAIL, result.status)
        assertTrue(result.reason.contains("未在"))
    }

    @Test
    fun `permission denied is warn instead of deployment fail`() = runBlocking {
        val denied = grantedPermissions().map {
            if (it.capability == NativeCapability.Location) it.copy(state = PermissionState.Denied) else it
        }
        val state = buildLoveHouseCapabilities(denied, true, profiles(), 35)
        val results = NativeCapabilitySelfCheckContributor(FixedCapabilityRegistry(state), CapabilityReadinessProbe { null }).checks()

        assertEquals(SelfCheckStatus.WARN, results.single { it.id == "capability.device.location" }.status)
        assertEquals(SelfCheckStatus.WARN, results.single { it.id == "capability.attachment.location" }.status)
    }

    @Test
    fun `unresolved static intent probe does not fail a registered production capability`() = runBlocking {
        val staticProbe = staticIntentReadiness(
            isResolved = false,
            readyReason = "Production picker contract 已接线（OPEN_DOCUMENT + OPENABLE + MIME）",
        )
        val results = NativeCapabilitySelfCheckContributor(
            registry = FixedCapabilityRegistry(completeState()),
            readinessProbe = CapabilityReadinessProbe { id ->
                if (id == LoveHouseCapabilityId.DeviceFilePicker) staticProbe else null
            },
        ).checks()

        val filePicker = results.single { it.id == "capability.device.file_picker" }
        assertEquals(SelfCheckStatus.WARN, filePicker.status)
        assertFalse(filePicker.status == SelfCheckStatus.FAIL)
        assertTrue(filePicker.reason.contains("静态 handler probe 未解析"))
        assertTrue(filePicker.reason.contains("不等同于运行时不可用"))
    }

    @Test
    fun `static intent probe remains informational for equivalent native capabilities`() {
        listOf(
            "Production 照片选择 contract 已接线",
            "Production 相机 contract 已接线",
            "Production 系统分享 contract 已接线",
            "Production Deep Link contract 已接线",
        ).forEach { reason ->
            val readiness = staticIntentReadiness(isResolved = false, readyReason = reason)
            assertEquals(SelfCheckStatus.WARN, readiness.status)
            assertFalse(readiness.status == SelfCheckStatus.FAIL)
        }
    }

    @Test
    fun `mock production source is detected`() = runBlocking {
        val results = ProductionWiringSelfCheckContributor(
            ProductionWiringSnapshot(
                permissionSourceProduction = false,
                settingsUsesGlobalRegistry = true,
                chatUsesGlobalRegistry = true,
                chatHistorySourceProduction = true,
                mediaSourceProduction = true,
                toolRegistrySourceProduction = true,
                toolConnectionSourceProduction = true,
                mcpSourceProduction = true,
                appIdentitySourceProduction = true,
                ownerSessionSourceProduction = true,
            ),
        ).checks()

        assertEquals(SelfCheckStatus.FAIL, results.single { it.id == "wiring.settings" }.status)
    }

    @Test
    fun `attachment self-check is provider neutral and virtual-window safe`() = runBlocking {
        val results = AttachmentContractSelfCheckContributor(FixedCapabilityRegistry(completeState())).checks()

        assertEquals(SelfCheckStatus.PASS, results.single { it.id == "attachment.canonical-draft" }.status)
        assertEquals(SelfCheckStatus.PASS, results.single { it.id == "attachment.virtual-window" }.status)
        assertEquals(SelfCheckStatus.PASS, results.single { it.id == "attachment.shared-materializer" }.status)
    }

    @Test
    fun `camera original transport remains partial and tts remains not built`() = runBlocking {
        val results = NativeCapabilitySelfCheckContributor(FixedCapabilityRegistry(completeState()), CapabilityReadinessProbe { null }).checks()

        assertEquals(SelfCheckStatus.PARTIAL, results.single { it.id == "capability.attachment.camera" }.status)
        assertEquals(SelfCheckStatus.NOT_BUILT, results.single { it.id == "capability.voice.tts" }.status)
    }

    @Test
    fun `copied report sanitizer removes secrets coordinates and query strings`() {
        val unsafe = buildString {
            append("Authorization: Bearer ")
            append("sample-value ")
            append("access_token=")
            append("sample-access ")
            append("refresh_token=")
            append("sample-refresh ")
            append("cookie=")
            append("sample-session ")
            append("latitude=31.123 longitude:121.456 ")
            append("https://example.test/path?")
            append("token=")
            append("sample-query ")
            append("eyJabc")
            append(".def.ghi")
        }
        val safe = sanitizeSelfCheckReport(unsafe)

        listOf(
            "sample-value",
            "sample-access",
            "sample-refresh",
            "sample-session",
            "31.123",
            "121.456",
            "sample-query",
            "eyJabc",
        ).forEach {
            assertFalse("leaked $it", safe.contains(it))
        }
        assertTrue(safe.contains("[REDACTED"))
    }

    @Test
    fun `build identity uses the injected real git sha`() = runBlocking {
        assertTrue(BuildConfig.LOVEHOUSE_GIT_SHA.matches(Regex("[0-9a-fA-F]{40}")))
        val result = BuildIdentitySelfCheckContributor(
            BuildIdentity(
                versionName = BuildConfig.VERSION_NAME,
                versionCode = BuildConfig.VERSION_CODE.toLong(),
                buildType = BuildConfig.BUILD_TYPE,
                gitSha = BuildConfig.LOVEHOUSE_GIT_SHA,
                buildTime = BuildConfig.LOVEHOUSE_BUILD_TIME,
            ),
        ).checks().single()
        assertEquals(SelfCheckStatus.PASS, result.status)
    }

    private fun completeState(): LoveHouseCapabilityState = buildLoveHouseCapabilities(
        permissionStatuses = grantedPermissions(),
        speechRecognitionAvailable = true,
        providerProfiles = profiles(),
        sdkInt = 35,
    )

    private fun grantedPermissions(): List<CapabilityPermissionStatus> = NativeCapability.entries.map {
        CapabilityPermissionStatus(it, PermissionState.Granted)
    }

    private fun profiles() = listOf("codex", "claude").map { providerId ->
        ProviderCapabilityProfile(
            providerId = providerId,
            acceptedAttachmentTypes = setOf(CapabilityAttachmentType.Photo, CapabilityAttachmentType.File, CapabilityAttachmentType.Location),
            maxAttachmentItems = 12,
            supportsTextWithAttachments = true,
            supportedAttachmentLifecycles = setOf(CapabilityLifecycle.Local, CapabilityLifecycle.Ephemeral),
            consumesVoiceTranscriptAsText = true,
        )
    }

    private fun result(id: String, status: SelfCheckStatus) = SelfCheckResult(
        id = id,
        group = SelfCheckGroup.Build,
        name = id,
        status = status,
        reason = id,
    )

    private class FixedCapabilityRegistry(state: LoveHouseCapabilityState) : LoveHouseCapabilityRegistry {
        private val mutableState = MutableStateFlow(state)
        override val state: StateFlow<LoveHouseCapabilityState> = mutableState
        override fun refresh() = Unit
    }
}
