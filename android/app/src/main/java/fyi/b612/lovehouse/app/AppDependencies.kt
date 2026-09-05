package fyi.b612.lovehouse.app

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import fyi.b612.lovehouse.core.permissions.AndroidPermissionStatusProvider
import fyi.b612.lovehouse.core.permissions.PermissionStatusProvider
import fyi.b612.lovehouse.core.status.DefaultSystemStatusProvider
import fyi.b612.lovehouse.core.status.SystemStatusProvider
import fyi.b612.lovehouse.core.storage.DataStoreLocalStorage
import fyi.b612.lovehouse.core.storage.LocalStorage
import fyi.b612.lovehouse.feature.chat.LocalChatMessageRepository
import fyi.b612.lovehouse.feature.chat.SQLiteLocalChatMessageRepository

data class AppDependencies(
    val permissions: PermissionStatusProvider,
    val localStorage: LocalStorage,
    val systemStatus: SystemStatusProvider,
    val chatMessages: LocalChatMessageRepository,
)

fun createAppDependencies(context: Context): AppDependencies {
    val appContext = context.applicationContext
    val permissions = AndroidPermissionStatusProvider(appContext)
    return AppDependencies(
        permissions = permissions,
        localStorage = DataStoreLocalStorage(appContext),
        systemStatus = DefaultSystemStatusProvider(permissions),
        chatMessages = SQLiteLocalChatMessageRepository(appContext),
    )
}

@Composable
fun rememberAppDependencies(): AppDependencies {
    val context = LocalContext.current
    return remember(context.applicationContext) { createAppDependencies(context) }
}
