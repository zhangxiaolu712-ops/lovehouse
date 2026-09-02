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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
import fyi.b612.lovehouse.feature.chat.ChatListScreen
import fyi.b612.lovehouse.feature.chat.ChatSessionStore
import fyi.b612.lovehouse.feature.chat.ChatShellScreen
import fyi.b612.lovehouse.feature.home.HomeScreen
import fyi.b612.lovehouse.feature.nativelab.NativeLabScreen
import fyi.b612.lovehouse.feature.settings.SettingsScreen
import fyi.b612.lovehouse.feature.shell.NavGlyph
import fyi.b612.lovehouse.feature.shell.PlaceholderScreen

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
    val chatStore = remember { ChatSessionStore() }
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
                localStorage = dependencies.localStorage,
            )
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
            ChatShellScreen(threadId = threadId, store = chatStore, onBack = { navController.popBackStack() })
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
            SettingsScreen(onOpenNativeLab = { navController.navigate(AppDestination.NativeLab.route) })
        }

        composable(
            route = AppDestination.NativeLab.route,
            deepLinks = listOf(navDeepLink { uriPattern = AppDestination.NativeLab.deepLink }),
        ) {
            NativeLabScreen(
                systemStatusProvider = dependencies.systemStatus,
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
