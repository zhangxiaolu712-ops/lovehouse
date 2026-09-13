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
import fyi.b612.lovehouse.core.permissions.AndroidPermissionStatusProvider
import fyi.b612.lovehouse.core.permissions.PermissionStatusProvider
import fyi.b612.lovehouse.core.status.DefaultSystemStatusProvider
import fyi.b612.lovehouse.core.status.SystemStatusProvider
import fyi.b612.lovehouse.core.storage.DataStoreLocalStorage
import fyi.b612.lovehouse.core.storage.LocalStorage
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
import fyi.b612.lovehouse.feature.chat.stableCodexThreadId

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
)

fun createAppDependencies(context: Context): AppDependencies {
    val appContext = context.applicationContext
    val permissions = AndroidPermissionStatusProvider(appContext)
    val ownerSession = AndroidOwnerSessionStore(
        context = appContext,
        refresher = SupabaseOwnerSessionRefresher(
            baseUrl = BuildConfig.LOVEHOUSE_SUPABASE_URL,
            publishableKey = BuildConfig.LOVEHOUSE_SUPABASE_PUBLISHABLE_KEY,
        ),
        debugBootstrapToken = BuildConfig.LOVEHOUSE_OWNER_TOKEN.takeIf { BuildConfig.DEBUG },
    )
    val toolCenter = HttpToolCenterRepository(ownerSession = ownerSession)
    val toolProfiles = AndroidToolProfilePreferenceStore(appContext)
    val chatMessages = SQLiteLocalChatMessageRepository(appContext)
    val baseCapabilities = AndroidLoveHouseCapabilityRegistry(
        context = appContext,
        permissions = permissions,
        providerProfiles = listOf(
            CodexRuntime.toProviderCapabilityProfile(),
            ClaudeRuntime.toProviderCapabilityProfile(),
        ),
    )
    return AppDependencies(
        permissions = permissions,
        localStorage = DataStoreLocalStorage(appContext),
        systemStatus = DefaultSystemStatusProvider(permissions),
        chatMessages = chatMessages,
        claudeWebHistoryImporter = ClaudeWebHistoryImporter(appContext, chatMessages),
        ownerSession = ownerSession,
        mediaAttachments = HttpMediaAttachmentClient(appContext, ownerSession),
        toolCenter = toolCenter,
        toolProfiles = toolProfiles,
        capabilityRegistry = AndroidCapabilityRegistry(toolCenter, toolProfiles, "codex", stableCodexThreadId()),
        baseCapabilities = baseCapabilities,
        toolConnections = AndroidToolConnectionStore(appContext),
        toolConnectionProbe = HttpToolConnectionProbe(),
        chatConnections = AndroidChatConnectionStore(appContext),
        chatConnectionProbe = HttpChatConnectionProbe(),
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
