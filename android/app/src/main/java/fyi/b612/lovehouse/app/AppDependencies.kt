package fyi.b612.lovehouse.app

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import fyi.b612.lovehouse.BuildConfig
import fyi.b612.lovehouse.core.auth.AndroidOwnerSessionStore
import fyi.b612.lovehouse.core.auth.OwnerSessionStore
import fyi.b612.lovehouse.core.auth.SupabaseOwnerSessionRefresher
import fyi.b612.lovehouse.core.capability.AndroidLoveHouseCapabilityRegistry
import fyi.b612.lovehouse.core.capability.CapabilityAttachmentType
import fyi.b612.lovehouse.core.capability.CapabilityLifecycle
import fyi.b612.lovehouse.core.capability.LoveHouseCapabilityRegistry
import fyi.b612.lovehouse.core.capability.ProviderCapabilityProfile
import fyi.b612.lovehouse.core.devicecontext.AndroidDeviceContextProvider
import fyi.b612.lovehouse.core.permissions.AndroidPermissionStatusProvider
import fyi.b612.lovehouse.core.permissions.PermissionStatusProvider
import fyi.b612.lovehouse.core.status.DefaultSystemStatusProvider
import fyi.b612.lovehouse.core.status.SystemStatusProvider
import fyi.b612.lovehouse.core.storage.DataStoreLocalStorage
import fyi.b612.lovehouse.core.storage.LocalStorage
import fyi.b612.lovehouse.core.selfcheck.AccountSelfCheckContributor
import fyi.b612.lovehouse.core.selfcheck.AndroidCapabilityReadinessProbe
import fyi.b612.lovehouse.core.selfcheck.AttachmentContractSelfCheckContributor
import fyi.b612.lovehouse.core.selfcheck.BuildIdentity
import fyi.b612.lovehouse.core.selfcheck.BuildIdentitySelfCheckContributor
import fyi.b612.lovehouse.core.selfcheck.DeploymentSelfCheckRunner
import fyi.b612.lovehouse.core.selfcheck.NativeCapabilitySelfCheckContributor
import fyi.b612.lovehouse.core.selfcheck.ProductionWiringSelfCheckContributor
import fyi.b612.lovehouse.core.selfcheck.ProductionWiringSnapshot
import fyi.b612.lovehouse.core.selfcheck.SelfCheckRegistry
import fyi.b612.lovehouse.core.selfcheck.ToolCenterSelfCheckContributor
import fyi.b612.lovehouse.feature.chat.LocalChatMessageRepository
import fyi.b612.lovehouse.feature.chat.SQLiteLocalChatMessageRepository
import fyi.b612.lovehouse.feature.chat.ClaudeWebHistoryImporter
import fyi.b612.lovehouse.feature.chat.MediaAttachmentClient
import fyi.b612.lovehouse.feature.chat.HttpMediaAttachmentClient
import fyi.b612.lovehouse.feature.chat.ClaudeRuntime
import fyi.b612.lovehouse.feature.chat.CodexRuntime
import fyi.b612.lovehouse.feature.chat.ChatAttachmentLifecycle
import fyi.b612.lovehouse.feature.chat.ChatAttachmentType
import fyi.b612.lovehouse.feature.chat.ChatRuntimeConfig
import fyi.b612.lovehouse.feature.chat.AndroidChatConnectionStore
import fyi.b612.lovehouse.feature.chat.ChatConnectionProbe
import fyi.b612.lovehouse.feature.chat.ChatConnectionStore
import fyi.b612.lovehouse.feature.chat.HttpChatConnectionProbe
import fyi.b612.lovehouse.feature.settings.AndroidToolProfilePreferenceStore
import fyi.b612.lovehouse.feature.settings.AndroidCapabilityRegistry
import fyi.b612.lovehouse.feature.settings.CapabilityRegistry
import fyi.b612.lovehouse.feature.settings.AndroidToolConnectionStore
import fyi.b612.lovehouse.feature.settings.HttpToolConnectionProbe
import fyi.b612.lovehouse.feature.settings.HttpToolCenterRepository
import fyi.b612.lovehouse.feature.settings.ToolConnectionProbe
import fyi.b612.lovehouse.feature.settings.ToolConnectionStore
import fyi.b612.lovehouse.feature.settings.ToolCenterRepository
import fyi.b612.lovehouse.feature.settings.ToolProfilePreferenceStore
import fyi.b612.lovehouse.feature.settings.AndroidAppAccountRepository
import fyi.b612.lovehouse.feature.settings.AppAccountRepository
import fyi.b612.lovehouse.feature.settings.AppBackendMcpConnectionRepository
import fyi.b612.lovehouse.feature.settings.McpConnectionRepository
import fyi.b612.lovehouse.feature.chat.stableCodexThreadId
import fyi.b612.lovehouse.feature.screenobserver.ScreenObserverRuntime
import fyi.b612.lovehouse.feature.screenobserver.ScreenObserverStatus

data class AppDependencies(
    val permissions: PermissionStatusProvider,
    val localStorage: LocalStorage,
    val systemStatus: SystemStatusProvider,
    val chatMessages: LocalChatMessageRepository,
    val claudeWebHistoryImporter: ClaudeWebHistoryImporter,
    val ownerSession: OwnerSessionStore,
    val mediaAttachments: MediaAttachmentClient,
    val toolCenter: ToolCenterRepository,
    val toolProfiles: ToolProfilePreferenceStore,
    val capabilityRegistry: CapabilityRegistry,
    val baseCapabilities: LoveHouseCapabilityRegistry,
    val toolConnections: ToolConnectionStore,
    val toolConnectionProbe: ToolConnectionProbe,
    val chatConnections: ChatConnectionStore,
    val chatConnectionProbe: ChatConnectionProbe,
    val appAccount: AppAccountRepository,
    val mcpConnections: McpConnectionRepository,
    val selfCheck: DeploymentSelfCheckRunner,
)

fun createAppDependencies(context: Context): AppDependencies {
    val appContext = context.applicationContext
    val permissions: PermissionStatusProvider = AndroidPermissionStatusProvider(appContext)
    val ownerSession: OwnerSessionStore = AndroidOwnerSessionStore(
        context = appContext,
        refresher = SupabaseOwnerSessionRefresher(
            baseUrl = BuildConfig.LOVEHOUSE_SUPABASE_URL,
            publishableKey = BuildConfig.LOVEHOUSE_SUPABASE_PUBLISHABLE_KEY,
        ),
        debugBootstrapToken = BuildConfig.LOVEHOUSE_OWNER_TOKEN.takeIf { BuildConfig.DEBUG },
    )
    val toolCenter: ToolCenterRepository = HttpToolCenterRepository(ownerSession = ownerSession)
    val toolProfiles: ToolProfilePreferenceStore = AndroidToolProfilePreferenceStore(appContext)
    val chatMessages: LocalChatMessageRepository = SQLiteLocalChatMessageRepository(appContext)
    val baseCapabilities: LoveHouseCapabilityRegistry = AndroidLoveHouseCapabilityRegistry(
        context = appContext,
        permissions = permissions,
        providerProfiles = listOf(
            CodexRuntime.toProviderCapabilityProfile(),
            ClaudeRuntime.toProviderCapabilityProfile(),
        ),
    )
    val localStorage: LocalStorage = DataStoreLocalStorage(appContext)
    val mediaAttachments: MediaAttachmentClient = HttpMediaAttachmentClient(appContext, ownerSession)
    val toolConnections: ToolConnectionStore = AndroidToolConnectionStore(appContext)
    val appAccount: AppAccountRepository = AndroidAppAccountRepository(appContext, BuildConfig.LOVEHOUSE_APP_BACKEND_URL)
    val mcpConnections: McpConnectionRepository = AppBackendMcpConnectionRepository(BuildConfig.LOVEHOUSE_APP_BACKEND_URL)
    val capabilityRegistry: CapabilityRegistry = AndroidCapabilityRegistry(toolCenter, toolProfiles, "codex", stableCodexThreadId())
    val deviceContext = AndroidDeviceContextProvider(
        appContext,
        isScreenObserverActive = { ScreenObserverRuntime.state.value.status == ScreenObserverStatus.Active },
    )
    val buildIdentity = BuildIdentity(
        versionName = BuildConfig.VERSION_NAME,
        versionCode = BuildConfig.VERSION_CODE.toLong(),
        buildType = BuildConfig.BUILD_TYPE,
        gitSha = BuildConfig.LOVEHOUSE_GIT_SHA,
        buildTime = BuildConfig.LOVEHOUSE_BUILD_TIME,
    )
    val selfCheck = DeploymentSelfCheckRunner(
        registry = SelfCheckRegistry(
            listOf(
                NativeCapabilitySelfCheckContributor(
                    registry = baseCapabilities,
                    readinessProbe = AndroidCapabilityReadinessProbe(appContext, deviceContext),
                ),
                AttachmentContractSelfCheckContributor(baseCapabilities),
                ProductionWiringSelfCheckContributor(
                    ProductionWiringSnapshot(
                        permissionSourceProduction = permissions is AndroidPermissionStatusProvider,
                        settingsUsesGlobalRegistry = baseCapabilities is AndroidLoveHouseCapabilityRegistry,
                        chatUsesGlobalRegistry = baseCapabilities is AndroidLoveHouseCapabilityRegistry,
                        chatHistorySourceProduction = chatMessages is SQLiteLocalChatMessageRepository,
                        mediaSourceProduction = mediaAttachments is HttpMediaAttachmentClient,
                        toolRegistrySourceProduction = capabilityRegistry is AndroidCapabilityRegistry,
                        toolConnectionSourceProduction = toolConnections is AndroidToolConnectionStore,
                        mcpSourceProduction = mcpConnections is AppBackendMcpConnectionRepository,
                        appIdentitySourceProduction = appAccount is AndroidAppAccountRepository,
                        ownerSessionSourceProduction = ownerSession is AndroidOwnerSessionStore,
                    ),
                ),
                ToolCenterSelfCheckContributor(capabilityRegistry),
                AccountSelfCheckContributor(appAccount, ownerSession),
                BuildIdentitySelfCheckContributor(buildIdentity),
            ),
        ),
        buildIdentity = buildIdentity,
    )
    return AppDependencies(
        permissions = permissions,
        localStorage = localStorage,
        systemStatus = DefaultSystemStatusProvider(permissions),
        chatMessages = chatMessages,
        claudeWebHistoryImporter = ClaudeWebHistoryImporter(appContext, chatMessages),
        ownerSession = ownerSession,
        mediaAttachments = mediaAttachments,
        toolCenter = toolCenter,
        toolProfiles = toolProfiles,
        capabilityRegistry = capabilityRegistry,
        baseCapabilities = baseCapabilities,
        toolConnections = toolConnections,
        toolConnectionProbe = HttpToolConnectionProbe(),
        chatConnections = AndroidChatConnectionStore(appContext),
        chatConnectionProbe = HttpChatConnectionProbe(),
        appAccount = appAccount,
        mcpConnections = mcpConnections,
        selfCheck = selfCheck,
    )
}

private fun ChatRuntimeConfig.toProviderCapabilityProfile() = ProviderCapabilityProfile(
    providerId = personaId,
    acceptedAttachmentTypes = attachmentCapabilities.acceptedTypes.mapTo(linkedSetOf()) {
        when (it) {
            ChatAttachmentType.Photo -> CapabilityAttachmentType.Photo
            ChatAttachmentType.File -> CapabilityAttachmentType.File
            ChatAttachmentType.Location -> CapabilityAttachmentType.Location
            ChatAttachmentType.Audio -> CapabilityAttachmentType.Audio
        }
    },
    maxAttachmentItems = attachmentCapabilities.maxItems,
    supportsTextWithAttachments = attachmentCapabilities.supportsTextWithAttachments,
    supportedAttachmentLifecycles = attachmentCapabilities.supportedLifecycles.mapTo(linkedSetOf()) {
        when (it) {
            ChatAttachmentLifecycle.LOCAL -> CapabilityLifecycle.Local
            ChatAttachmentLifecycle.EPHEMERAL -> CapabilityLifecycle.Ephemeral
            ChatAttachmentLifecycle.DURABLE -> CapabilityLifecycle.Durable
        }
    },
    consumesVoiceTranscriptAsText = true,
)

@Composable
fun rememberAppDependencies(): AppDependencies {
    val context = LocalContext.current
    return remember(context.applicationContext) { createAppDependencies(context) }
}
