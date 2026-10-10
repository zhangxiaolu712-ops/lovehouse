package fyi.b612.lovehouse.core.navigation

import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navDeepLink
import androidx.navigation.navArgument
import fyi.b612.lovehouse.app.AppDependencies
import fyi.b612.lovehouse.core.designsystem.LoveHouseAppShell
import fyi.b612.lovehouse.feature.schedule.ScheduleScreen
import fyi.b612.lovehouse.feature.chat.ChatListScreen
import fyi.b612.lovehouse.feature.chat.ChatSessionStore
import fyi.b612.lovehouse.feature.chat.ChatShellScreen
import fyi.b612.lovehouse.feature.chat.HttpCodexChatClient
import fyi.b612.lovehouse.feature.chat.stableCodexThreadId
import fyi.b612.lovehouse.feature.home.HomeScreen
import fyi.b612.lovehouse.feature.lab.LabHubScreen
import fyi.b612.lovehouse.feature.nativelab.NativeLabScreen
import fyi.b612.lovehouse.feature.settings.ConnectionControlScreen
import fyi.b612.lovehouse.feature.settings.SettingsScreen
import fyi.b612.lovehouse.feature.settings.ToolCenterLabScreen
import fyi.b612.lovehouse.feature.settings.McpOAuthResultScreen
import fyi.b612.lovehouse.feature.shell.NavGlyph
import fyi.b612.lovehouse.feature.shell.PlaceholderScreen
import fyi.b612.lovehouse.feature.events.NotificationEventTargetResolver
import fyi.b612.lovehouse.feature.events.NotificationIntentHandoff
import fyi.b612.lovehouse.feature.events.notificationEventTargetRoute
import fyi.b612.lovehouse.feature.events.ServerEventPresentationState
import fyi.b612.lovehouse.feature.events.ServerEventDetailScreen
import fyi.b612.lovehouse.feature.events.ServerEventsScreen
import kotlinx.coroutines.launch
import android.net.Uri

@Composable
fun LoveHouseShell(
    dependencies: AppDependencies,
    modifier: Modifier = Modifier,
    navController: NavHostController = rememberNavController(),
) {
    LoveHouseAppShell(localStorage = dependencies.localStorage, modifier = modifier.fillMaxSize()) {
        LoveHouseContent(navController, dependencies, Modifier.fillMaxSize())
    }
}

@Composable
private fun LoveHouseContent(
    navController: NavHostController,
    dependencies: AppDependencies,
    modifier: Modifier = Modifier,
) {
    val lifecycleOwner = LocalLifecycleOwner.current
    val applicationScope = rememberCoroutineScope()
    val chatStore = remember(dependencies.chatMessages, dependencies.capabilityRegistry, dependencies.claudeWebHistoryImporter) {
        ChatSessionStore(
            codexClient = HttpCodexChatClient(
                allowedToolIdsFor = dependencies.effectiveTools::cachedAllowedToolIds,
                appAccountSessionCookie = dependencies.appAccountSessionCookie,
            ),
            messageRepository = dependencies.chatMessages,
            conversationPersonas = dependencies.conversationPersonas,
            personaRuntimeSource = dependencies.personaRuntimeSource,
            claudeWebHistoryImporter = dependencies.claudeWebHistoryImporter,
        )
    }
    val notificationIntentTarget by NotificationIntentHandoff.pending.collectAsState()
    LaunchedEffect(notificationIntentTarget?.deliveryId) {
        val target = notificationIntentTarget ?: return@LaunchedEffect
        navController.navigate(notificationEventTargetRoute(target.eventId)) {
            launchSingleTop = true
        }
        NotificationIntentHandoff.consume(target.deliveryId)
    }
    LaunchedEffect(chatStore) {
        chatStore.recoverPendingExecutions()
        runCatching { chatStore.refreshPersonaProfiles() }
    }
    LaunchedEffect(dependencies.serverEvents) { dependencies.serverEvents.refresh() }
    LaunchedEffect(dependencies.remoteEventPush) {
        runCatching { dependencies.remoteEventPush.refreshRegistration() }
    }
    DisposableEffect(lifecycleOwner, dependencies.serverEvents) {
        ServerEventPresentationState.setAppForeground(
            lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED),
        )
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_START -> {
                    ServerEventPresentationState.setAppForeground(true)
                    applicationScope.launch { dependencies.serverEvents.refresh() }
                }
                Lifecycle.Event.ON_STOP -> ServerEventPresentationState.setAppForeground(false)
                else -> Unit
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            ServerEventPresentationState.setAppForeground(false)
        }
    }
    NavHost(
        navController = navController,
        startDestination = AppDestination.Home.route,
        modifier = modifier.fillMaxSize(),
    ) {
        composable(
            route = AppDestination.Home.route,
            deepLinks = listOf(navDeepLink { uriPattern = AppDestination.Home.deepLink }),
        ) {
            HomeScreen(
                onOpenChat = { navController.navigate(AppDestination.Chat.route) },
                onOpenSettings = { navController.navigate(AppDestination.Settings.route) },
                onOpenLab = { navController.navigate(AppDestination.Lab.route) },
                onOpenSchedule = { navController.navigate(AppDestination.Schedule.route) },
                localStorage = dependencies.localStorage,
            )
        }

        composable(
            route = AppDestination.Schedule.route,
            deepLinks = listOf(navDeepLink { uriPattern = AppDestination.Schedule.deepLink }),
        ) {
            ScheduleScreen()
        }

        composable(
            route = AppDestination.Chat.route,
            deepLinks = listOf(navDeepLink { uriPattern = AppDestination.Chat.deepLink }),
        ) {
            ChatListScreen(store = chatStore, onOpenThread = { thread ->
                navController.navigate("chat/thread/${thread.threadId}")
            })
        }

        composable(
            route = AppDestination.ChatThread.route,
            arguments = listOf(navArgument("threadId") { type = NavType.StringType }),
            deepLinks = listOf(navDeepLink { uriPattern = AppDestination.ChatThread.deepLink }),
        ) { entry ->
            val threadId = entry.arguments?.getString("threadId").orEmpty()
            ChatShellScreen(
                threadId = threadId,
                store = chatStore,
                localStorage = dependencies.localStorage,
                effectiveToolResolver = dependencies.effectiveTools,
                baseCapabilities = dependencies.baseCapabilities,
                mediaAttachments = dependencies.mediaAttachments,
                chatConnections = dependencies.chatConnections,
                chatConnectionProbe = dependencies.chatConnectionProbe,
                serverEvents = dependencies.serverEvents,
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = AppDestination.Memory.route,
            deepLinks = listOf(navDeepLink { uriPattern = AppDestination.Memory.deepLink }),
        ) {
            PlaceholderScreen(
                eyebrow = "记忆房间",
                title = "记忆",
                message = "Memory V2 仍留在服务端，这里只是为后续阶段准备的原生入口。",
                status = "后端未改动",
            )
        }

        composable(
            route = AppDestination.Engineering.route,
            deepLinks = listOf(navDeepLink { uriPattern = AppDestination.Engineering.deepLink }),
        ) {
            PlaceholderScreen(
                eyebrow = "工程工作台",
                title = "工程",
                message = "工程主题、修订和来源以后会通过稳定客户端契约接入，而不是复制一份网页。",
                status = "仅有原生壳",
            )
        }

        composable(
            route = AppDestination.Settings.route,
            deepLinks = listOf(navDeepLink { uriPattern = AppDestination.Settings.deepLink }),
        ) {
            SettingsScreen(
                localStorage = dependencies.localStorage,
                permissionStatusProvider = dependencies.permissions,
                ownerSession = dependencies.ownerSession,
                capabilityRegistry = dependencies.capabilityRegistry,
                baseCapabilities = dependencies.baseCapabilities,
                apiConnections = dependencies.apiConnections,
                appAccount = dependencies.appAccount,
                mcpConnections = dependencies.mcpConnections,
                secretVault = dependencies.secretVault,
                personaRuntimeSource = dependencies.personaRuntimeSource,
                selfCheck = dependencies.selfCheck,
                serverEvents = dependencies.serverEvents,
                onOpenConnectionControl = { navController.navigate(AppDestination.ConnectionControl.route) },
                onOpenEvents = { navController.navigate(AppDestination.Events.route) },
            )
        }

        composable(
            route = AppDestination.Events.route,
            deepLinks = listOf(navDeepLink { uriPattern = AppDestination.Events.deepLink }),
        ) {
            ServerEventsScreen(
                repository = dependencies.serverEvents,
                onOpenEvent = { eventId -> navController.navigate("events/${Uri.encode(eventId)}") },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = AppDestination.EventDetail.route,
            arguments = listOf(
                navArgument("eventId") { type = NavType.StringType },
                navArgument("openTarget") { type = NavType.BoolType; defaultValue = false },
            ),
            deepLinks = listOf(
                navDeepLink { uriPattern = AppDestination.EventDetail.deepLink },
                navDeepLink { uriPattern = "lovehouse://event/{eventId}?open_target={openTarget}" },
            ),
        ) { entry ->
            val eventId = entry.arguments?.getString("eventId").orEmpty()
            val openTarget = entry.arguments?.getBoolean("openTarget") ?: false
            if (openTarget) {
                NotificationEventTargetResolver(
                    eventId = eventId,
                    repository = dependencies.serverEvents,
                    resolveLocalThreadId = chatStore::localThreadIdForCanonicalThread,
                    rehydrateThread = { localThreadId, canonicalThreadId ->
                        chatStore.recoverPendingExecutionsOnce()
                        chatStore.rehydrateThreadFromPersistence(localThreadId, canonicalThreadId)
                    },
                    onOpenChat = { localThreadId ->
                        navController.navigate("chat/thread/${Uri.encode(localThreadId)}") {
                            popUpTo(navController.graph.findStartDestination().id)
                            launchSingleTop = true
                        }
                    },
                    onOpenConfirmation = { confirmationEventId ->
                        navController.navigate("events/${Uri.encode(confirmationEventId)}") {
                            popUpTo(navController.graph.findStartDestination().id)
                            launchSingleTop = true
                        }
                    },
                    onFallback = {
                        navController.navigate("events/${Uri.encode(eventId)}") {
                            popUpTo(navController.graph.findStartDestination().id)
                            launchSingleTop = true
                        }
                    },
                )
            } else {
                ServerEventDetailScreen(
                    eventId = eventId,
                    repository = dependencies.serverEvents,
                    onOpenThread = { canonicalThreadId ->
                        eventChatRoute(
                            canonicalThreadId = canonicalThreadId,
                            resolveLocalThreadId = chatStore::localThreadIdForCanonicalThread,
                            encodeRouteSegment = Uri::encode,
                        )?.let { route ->
                            navController.navigate(route)
                            true
                        } ?: false
                    },
                    onBack = { navController.popBackStack() },
                )
            }
        }

        composable(
            route = AppDestination.Lab.route,
            deepLinks = listOf(navDeepLink { uriPattern = AppDestination.Lab.deepLink }),
        ) {
            LabHubScreen(
                onOpenConnectionControl = { navController.navigate(AppDestination.ConnectionControl.route) },
                onOpenToolCenter = { navController.navigate(AppDestination.ToolCenterLab.route) },
                onOpenNativeLab = { navController.navigate(AppDestination.NativeLab.route) },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = AppDestination.ConnectionControl.route,
            deepLinks = listOf(navDeepLink { uriPattern = AppDestination.ConnectionControl.deepLink }),
        ) {
            ConnectionControlScreen(
                ownerSession = dependencies.ownerSession,
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = AppDestination.ToolCenterLab.route,
        ) {
            ToolCenterLabScreen(
                repository = dependencies.toolCenter,
                profiles = dependencies.toolProfiles,
                personaId = "codex",
                threadId = stableCodexThreadId(),
                onBack = { navController.popBackStack() },
                onReconnect = { navController.navigate(AppDestination.ConnectionControl.route) },
            )
        }

        composable(
            route = AppDestination.McpOAuthCallback.route,
            arguments = listOf(
                navArgument("connectionId") { type = NavType.StringType; defaultValue = "" },
                navArgument("status") { type = NavType.StringType; defaultValue = "" },
            ),
            deepLinks = listOf(navDeepLink { uriPattern = AppDestination.McpOAuthCallback.deepLink }),
        ) { entry ->
            McpOAuthResultScreen(
                repository = dependencies.mcpConnections,
                personaRuntimeSource = dependencies.personaRuntimeSource,
                capabilityRegistry = dependencies.capabilityRegistry,
                apiConnections = dependencies.apiConnections,
                secretVault = dependencies.secretVault,
                connectionId = entry.arguments?.getString("connectionId").orEmpty(),
                callbackStatus = entry.arguments?.getString("status").orEmpty(),
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route = AppDestination.NativeLab.route,
            deepLinks = listOf(navDeepLink { uriPattern = AppDestination.NativeLab.deepLink }),
        ) {
            NativeLabScreen(
                systemStatusProvider = dependencies.systemStatus,
                baseCapabilities = dependencies.baseCapabilities,
                onBack = { navController.popBackStack() },
            )
        }
    }
}

@Composable
private fun LoveHouseNavigationBar(
    selected: AppDestination?,
    onNavigate: (AppDestination) -> Unit,
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.98f),
        tonalElevation = 3.dp,
    ) {
        AppDestination.primary.forEach { destination ->
            NavigationBarItem(
                selected = destination == selected,
                onClick = { onNavigate(destination) },
                icon = { NavGlyph(destination.glyph, destination == selected) },
                label = {
                    Text(
                        text = destination.label,
                        maxLines = 1,
                        softWrap = false,
                        style = MaterialTheme.typography.labelMedium,
                    )
                },
                alwaysShowLabel = true,
                colors = NavigationBarItemDefaults.colors(
                    indicatorColor = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.35f),
                ),
            )
        }
    }
}

@Composable
private fun LoveHouseNavigationRail(
    selected: AppDestination?,
    onNavigate: (AppDestination) -> Unit,
) {
    NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
        AppDestination.primary.forEach { destination ->
            NavigationRailItem(
                selected = destination == selected,
                onClick = { onNavigate(destination) },
                icon = { NavGlyph(destination.glyph, destination == selected) },
                label = { Text(destination.label) },
                alwaysShowLabel = true,
            )
        }
    }
}

private fun NavHostController.openPrimary(destination: AppDestination) {
    navigate(destination.route) {
        popUpTo(graph.findStartDestination().id) {
            saveState = destination != AppDestination.Chat
        }
        launchSingleTop = true
        restoreState = destination != AppDestination.Chat
    }
}
