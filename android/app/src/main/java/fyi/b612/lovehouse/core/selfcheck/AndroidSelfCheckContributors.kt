package fyi.b612.lovehouse.core.selfcheck

import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.core.app.NotificationManagerCompat
import fyi.b612.lovehouse.MainActivity
import fyi.b612.lovehouse.core.auth.OwnerSessionStatus
import fyi.b612.lovehouse.core.auth.OwnerSessionStore
import fyi.b612.lovehouse.core.capability.CapabilityAvailability
import fyi.b612.lovehouse.core.capability.CapabilityMaturity
import fyi.b612.lovehouse.core.capability.LoveHouseCapability
import fyi.b612.lovehouse.core.capability.LoveHouseCapabilityId
import fyi.b612.lovehouse.core.capability.LoveHouseCapabilityKind
import fyi.b612.lovehouse.core.capability.LoveHouseCapabilityRegistry
import fyi.b612.lovehouse.core.capability.ProviderConsumption
import fyi.b612.lovehouse.core.devicecontext.DeviceContextProvider
import fyi.b612.lovehouse.feature.chat.AttachmentCapabilities
import fyi.b612.lovehouse.feature.chat.ChatAttachmentAvailability
import fyi.b612.lovehouse.feature.chat.ChatAttachmentDraft
import fyi.b612.lovehouse.feature.chat.ChatAttachmentLifecycle
import fyi.b612.lovehouse.feature.chat.ChatMediaAttachment
import fyi.b612.lovehouse.feature.chat.rejectionReason
import fyi.b612.lovehouse.feature.settings.AppAccountRepository
import fyi.b612.lovehouse.feature.settings.AppAccountState
import fyi.b612.lovehouse.feature.settings.CapabilityRegistry

data class CapabilityReadiness(
    val status: SelfCheckStatus,
    val reason: String,
    val detail: String? = null,
)

fun interface CapabilityReadinessProbe {
    fun readiness(id: LoveHouseCapabilityId): CapabilityReadiness?
}

class AndroidCapabilityReadinessProbe(
    context: Context,
    private val deviceContextProvider: DeviceContextProvider,
) : CapabilityReadinessProbe {
    private val appContext = context.applicationContext

    override fun readiness(id: LoveHouseCapabilityId): CapabilityReadiness? = when (id) {
        LoveHouseCapabilityId.DevicePhotoPicker -> intentReadiness(
            photoPickerIntent(),
            "Production 照片选择 contract 已接线",
        )
        LoveHouseCapabilityId.DeviceFilePicker -> intentReadiness(
            Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),
            "Production picker contract 已接线（OPEN_DOCUMENT + OPENABLE + MIME）",
        )
        LoveHouseCapabilityId.DeviceCamera -> intentReadiness(
            Intent(MediaStore.ACTION_IMAGE_CAPTURE),
            "Production 相机 contract 已接线",
        )
        LoveHouseCapabilityId.DeviceShare -> intentReadiness(
            Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, "LoveHouse"),
            "Production 系统分享 contract 已接线",
        )
        LoveHouseCapabilityId.DeviceDeepLink -> intentReadiness(
            Intent(Intent.ACTION_VIEW, Uri.parse("lovehouse://settings"), appContext, MainActivity::class.java),
            "Production Deep Link contract 已接线",
        )
        LoveHouseCapabilityId.DeviceNotifications -> notificationReadiness()
        LoveHouseCapabilityId.DeviceContext -> runCatching { deviceContextProvider.getCurrentDeviceContext() }
            .fold(
                onSuccess = { CapabilityReadiness(SelfCheckStatus.PASS, "真实 Device Context provider 可读取") },
                onFailure = { CapabilityReadiness(SelfCheckStatus.FAIL, "Device Context production provider 读取失败", it::class.java.simpleName) },
            )
        else -> null
    }

    private fun photoPickerIntent(): Intent = if (Build.VERSION.SDK_INT >= 33) {
        Intent(MediaStore.ACTION_PICK_IMAGES)
    } else {
        Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("image/*")
    }

    private fun intentReadiness(intent: Intent, readyReason: String): CapabilityReadiness =
        staticIntentReadiness(
            isResolved = intent.resolveActivity(appContext.packageManager) != null,
            readyReason = readyReason,
        )

    private fun notificationReadiness(): CapabilityReadiness {
        if (!NotificationManagerCompat.from(appContext).areNotificationsEnabled()) {
            return CapabilityReadiness(SelfCheckStatus.WARN, "通知 wiring 正常；系统通知当前关闭")
        }
        val manager = appContext.getSystemService(NotificationManager::class.java)
        return if (manager == null) {
            CapabilityReadiness(SelfCheckStatus.FAIL, "Android NotificationManager 不可用")
        } else {
            CapabilityReadiness(SelfCheckStatus.PASS, "通知服务与当前授权状态可读取")
        }
    }
}

internal fun staticIntentReadiness(
    isResolved: Boolean,
    readyReason: String,
): CapabilityReadiness = if (isResolved) {
    CapabilityReadiness(SelfCheckStatus.PASS, "$readyReason；静态 handler probe 可解析")
} else {
    CapabilityReadiness(
        status = SelfCheckStatus.WARN,
        reason = "$readyReason；静态 handler probe 未解析，但不等同于运行时不可用",
    )
}

class NativeCapabilitySelfCheckContributor(
    private val registry: LoveHouseCapabilityRegistry,
    private val readinessProbe: CapabilityReadinessProbe,
) : SelfCheckContributor {
    override val contributorId: String = "app-global-capability"

    override suspend fun checks(): List<SelfCheckResult> {
        registry.refresh()
        val registered = registry.state.value.capabilities.associateBy { it.id }
        return LoveHouseCapabilityId.entries.map { id ->
            val capability = registered[id]
            if (capability == null) {
                SelfCheckResult(
                    id = "capability.${id.value}",
                    group = capabilityGroup(id),
                    name = id.value,
                    status = SelfCheckStatus.FAIL,
                    reason = "Production capability 未在 LoveHouseCapabilityRegistry 注册",
                )
            } else {
                capability.toSelfCheck(readinessProbe.readiness(id))
            }
        }
    }

    private fun LoveHouseCapability.toSelfCheck(readiness: CapabilityReadiness?): SelfCheckResult {
        val runtimeStatus = when {
            maturity == CapabilityMaturity.Partial -> SelfCheckStatus.PARTIAL
            maturity == CapabilityMaturity.NotBuilt -> SelfCheckStatus.NOT_BUILT
            availability == CapabilityAvailability.Partial -> SelfCheckStatus.PARTIAL
            availability == CapabilityAvailability.Unavailable && unavailableReason.orEmpty().contains("不支持") -> SelfCheckStatus.N_A
            availability == CapabilityAvailability.Unavailable -> SelfCheckStatus.WARN
            else -> readiness?.status ?: SelfCheckStatus.PASS
        }
        val reason = when {
            maturity == CapabilityMaturity.Partial -> unavailableReason ?: "能力按产品契约保持 Partial"
            maturity == CapabilityMaturity.NotBuilt -> unavailableReason ?: "能力尚未实现"
            availability == CapabilityAvailability.Unavailable -> unavailableReason ?: "当前设备条件不满足"
            readiness != null -> readiness.reason
            else -> "Registry 已注册；production 状态可读取"
        }
        return SelfCheckResult(
            id = "capability.${id.value}",
            group = if (kind == LoveHouseCapabilityKind.Attachment || kind == LoveHouseCapabilityKind.Media) {
                SelfCheckGroup.Attachment
            } else {
                SelfCheckGroup.NativeCapability
            },
            name = label,
            status = runtimeStatus,
            reason = reason,
            detail = readiness?.detail,
        )
    }

    private fun capabilityGroup(id: LoveHouseCapabilityId): SelfCheckGroup =
        if (id.value.startsWith("attachment.") || id.value.startsWith("media.")) {
            SelfCheckGroup.Attachment
        } else {
            SelfCheckGroup.NativeCapability
        }
}

class AttachmentContractSelfCheckContributor(
    private val registry: LoveHouseCapabilityRegistry,
    private val unsupportedProvider: AttachmentCapabilities = AttachmentCapabilities.Unsupported,
) : SelfCheckContributor {
    override val contributorId: String = "canonical-attachment"

    override suspend fun checks(): List<SelfCheckResult> {
        val attachment = ChatMediaAttachment(
            type = "photo",
            mimeType = "image/jpeg",
            sizeBytes = 0,
            name = "self-check.jpg",
            attachmentId = "self-check-attachment",
            lifecycle = ChatAttachmentLifecycle.LOCAL,
            availability = ChatAttachmentAvailability.AVAILABLE,
        )
        val draft = ChatAttachmentDraft().add(listOf(attachment))
        val providerRejection = unsupportedProvider.rejectionReason("", draft.attachments)
        val draftContractValid = draft.attachments.singleOrNull() === attachment &&
            draft.remove(attachment).attachments.isEmpty()
        val materializer = registry.capability(LoveHouseCapabilityId.MediaSecureMaterialize)
        val claudeConsumer = materializer?.providerConsumption?.get("claude")
        return listOf(
            SelfCheckResult(
                id = "attachment.canonical-draft",
                group = SelfCheckGroup.Attachment,
                name = "Canonical Attachment Draft",
                status = if (draftContractValid) SelfCheckStatus.PASS else SelfCheckStatus.FAIL,
                reason = if (draftContractValid) "临时 draft 可独立持有并移除 canonical attachment" else "Canonical draft contract 异常",
                detail = "无 Persona / Provider / Thread owner",
            ),
            SelfCheckResult(
                id = "attachment.virtual-window",
                group = SelfCheckGroup.Attachment,
                name = "Virtual Window Attachment",
                status = if (providerRejection != null && draftContractValid) SelfCheckStatus.PASS else SelfCheckStatus.FAIL,
                reason = if (providerRejection != null && draftContractValid) {
                    "无 Runtime 时附件仍存在；Provider 兼容性仅在 consume 时校验"
                } else {
                    "Provider gate 错误改变了附件存在性"
                },
            ),
            SelfCheckResult(
                id = "attachment.shared-materializer",
                group = SelfCheckGroup.Attachment,
                name = "Shared Secure Materializer",
                status = if (materializer != null && claudeConsumer == ProviderConsumption.Supported) SelfCheckStatus.PASS else SelfCheckStatus.FAIL,
                reason = if (materializer != null && claudeConsumer == ProviderConsumption.Supported) {
                    "全局 materialize capability 已注册；Claude consumer 正式声明支持"
                } else {
                    "共享 materializer 或 Claude consumer production wiring 缺失"
                },
            ),
        )
    }
}

data class ProductionWiringSnapshot(
    val permissionSourceProduction: Boolean,
    val settingsUsesGlobalRegistry: Boolean,
    val chatUsesGlobalRegistry: Boolean,
    val chatHistorySourceProduction: Boolean,
    val mediaSourceProduction: Boolean,
    val toolRegistrySourceProduction: Boolean,
    val toolConnectionSourceProduction: Boolean,
    val mcpSourceProduction: Boolean,
    val appIdentitySourceProduction: Boolean,
    val ownerSessionSourceProduction: Boolean,
)

class ProductionWiringSelfCheckContributor(
    private val wiring: ProductionWiringSnapshot,
) : SelfCheckContributor {
    override val contributorId: String = "production-wiring"

    override suspend fun checks(): List<SelfCheckResult> = listOf(
        wiringResult("wiring.settings", "Settings Native Source", wiring.permissionSourceProduction && wiring.settingsUsesGlobalRegistry, "Settings 使用真实权限源与全局 registry"),
        wiringResult("wiring.chat", "Chat Attachment Source", wiring.chatUsesGlobalRegistry && wiring.chatHistorySourceProduction && wiring.mediaSourceProduction, "Chat 使用 canonical attachment、SQLite 历史与真实媒体 client"),
        wiringResult("wiring.tool-center", "Tool Center Source", wiring.toolRegistrySourceProduction && wiring.toolConnectionSourceProduction && wiring.mcpSourceProduction, "Tool Center 使用 production registry / connection source", SelfCheckGroup.ToolCenter),
        wiringResult("wiring.account", "Account / Owner Session", wiring.appIdentitySourceProduction && wiring.ownerSessionSourceProduction, "App identity 与 Owner Session 使用 production provider", SelfCheckGroup.Account),
    )

    private fun wiringResult(
        id: String,
        name: String,
        passed: Boolean,
        passedReason: String,
        group: SelfCheckGroup = SelfCheckGroup.ProductionWiring,
    ) = SelfCheckResult(
        id = id,
        group = group,
        name = name,
        status = if (passed) SelfCheckStatus.PASS else SelfCheckStatus.FAIL,
        reason = if (passed) passedReason else "Production dependency 被 Mock/fake source 替换或 wiring 断开",
    )
}

class ToolCenterSelfCheckContributor(
    private val registry: CapabilityRegistry,
) : SelfCheckContributor {
    override val contributorId: String = "tool-center"

    override suspend fun checks(): List<SelfCheckResult> {
        val state = registry.state.value
        val status = when {
            state.error != null -> SelfCheckStatus.WARN
            state.loading -> SelfCheckStatus.WARN
            else -> SelfCheckStatus.PASS
        }
        val reason = when {
            state.error != null -> "Production Tool Registry 已接线；当前连接状态阻止读取"
            state.loading -> "Production Tool Registry 已接线；状态仍在刷新"
            else -> "Production Tool Registry 可读取；已发现 ${state.capabilities.size} 项"
        }
        return listOf(SelfCheckResult("tool-center.registry", SelfCheckGroup.ToolCenter, "Tool Registry", status, reason))
    }
}

class AccountSelfCheckContributor(
    private val appAccount: AppAccountRepository,
    private val ownerSession: OwnerSessionStore,
) : SelfCheckContributor {
    override val contributorId: String = "account-session"

    override suspend fun checks(): List<SelfCheckResult> {
        val account = appAccount.state.value
        val accountResult = when (account) {
            is AppAccountState.SignedIn -> SelfCheckResult("account.app", SelfCheckGroup.Account, "App Account", SelfCheckStatus.PASS, "Production App identity session 可读取")
            AppAccountState.SignedOut -> SelfCheckResult("account.app", SelfCheckGroup.Account, "App Account", SelfCheckStatus.WARN, "Production wiring 正常；当前未登录")
            AppAccountState.Checking -> SelfCheckResult("account.app", SelfCheckGroup.Account, "App Account", SelfCheckStatus.WARN, "Production wiring 正常；会话状态读取中")
            is AppAccountState.Error -> SelfCheckResult("account.app", SelfCheckGroup.Account, "App Account", SelfCheckStatus.WARN, "Production wiring 正常；当前会话读取失败")
        }
        val owner = ownerSession.state.value
        val ownerResult = SelfCheckResult(
            id = "account.owner-session",
            group = SelfCheckGroup.Account,
            name = "Owner Session",
            status = if (owner.status == OwnerSessionStatus.Active) SelfCheckStatus.PASS else SelfCheckStatus.WARN,
            reason = if (owner.status == OwnerSessionStatus.Active) "安全 Owner Session provider 可读取" else "Owner Session provider 正常；当前状态为 ${owner.status.name}",
        )
        return listOf(accountResult, ownerResult)
    }
}

class BuildIdentitySelfCheckContributor(
    private val build: BuildIdentity,
) : SelfCheckContributor {
    override val contributorId: String = "build-identity"

    override suspend fun checks(): List<SelfCheckResult> {
        val validSha = build.gitSha.matches(Regex("[0-9a-fA-F]{40}"))
        return listOf(
            SelfCheckResult(
                id = "build.identity",
                group = SelfCheckGroup.Build,
                name = "Installed Build Identity",
                status = if (validSha) SelfCheckStatus.PASS else SelfCheckStatus.FAIL,
                reason = if (validSha) "Version / build type / Git SHA 由构建阶段注入" else "构建未注入可信 Git SHA",
                detail = "${build.versionName} (${build.versionCode}) · ${build.buildType} · ${build.shortSha} · ${build.buildTime}",
            ),
        )
    }
}
