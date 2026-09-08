package fyi.b612.lovehouse.app

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import fyi.b612.lovehouse.BuildConfig
import fyi.b612.lovehouse.core.auth.AndroidOwnerSessionStore
import fyi.b612.lovehouse.core.auth.OwnerSessionStore
import fyi.b612.lovehouse.core.auth.SupabaseOwnerSessionRefresher
import fyi.b612.lovehouse.core.permissions.AndroidPermissionStatusProvider
import fyi.b612.lovehouse.core.permissions.PermissionStatusProvider
import fyi.b612.lovehouse.core.status.DefaultSystemStatusProvider
import fyi.b612.lovehouse.core.status.SystemStatusProvider
import fyi.b612.lovehouse.core.storage.DataStoreLocalStorage
import fyi.b612.lovehouse.core.storage.LocalStorage
import fyi.b612.lovehouse.feature.chat.LocalChatMessageRepository
import fyi.b612.lovehouse.feature.chat.SQLiteLocalChatMessageRepository
import fyi.b612.lovehouse.feature.chat.MediaAttachmentClient
import fyi.b612.lovehouse.feature.chat.HttpMediaAttachmentClient
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
    val ownerSession: OwnerSessionStore,
    val mediaAttachments: MediaAttachmentClient,
    val toolCenter: ToolCenterRepository,
    val toolProfiles: ToolProfilePreferenceStore,
    val capabilityRegistry: CapabilityRegistry,
    val toolConnections: ToolConnectionStore,
    val toolConnectionProbe: ToolConnectionProbe,
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
    return AppDependencies(
        permissions = permissions,
        localStorage = DataStoreLocalStorage(appContext),
        systemStatus = DefaultSystemStatusProvider(permissions),
        chatMessages = SQLiteLocalChatMessageRepository(appContext),
        ownerSession = ownerSession,
        mediaAttachments = HttpMediaAttachmentClient(appContext, ownerSession),
        toolCenter = toolCenter,
        toolProfiles = toolProfiles,
        capabilityRegistry = AndroidCapabilityRegistry(toolCenter, toolProfiles, "codex", stableCodexThreadId()),
        toolConnections = AndroidToolConnectionStore(appContext),
        toolConnectionProbe = HttpToolConnectionProbe(),
    )
}

@Composable
fun rememberAppDependencies(): AppDependencies {
    val context = LocalContext.current
    return remember(context.applicationContext) { createAppDependencies(context) }
}
