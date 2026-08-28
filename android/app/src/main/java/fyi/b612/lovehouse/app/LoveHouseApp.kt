package fyi.b612.lovehouse.app

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import fyi.b612.lovehouse.core.designsystem.LoveHouseTheme
import fyi.b612.lovehouse.core.navigation.LoveHouseShell
import fyi.b612.lovehouse.feature.shell.LoveHouseLaunchScreen
import kotlinx.coroutines.delay

@Composable
fun LoveHouseApp(
    dependencies: AppDependencies = rememberAppDependencies(),
) {
    var showingLaunch by rememberSaveable { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        delay(650)
        showingLaunch = false
    }

    LoveHouseTheme {
        if (showingLaunch) {
            LoveHouseLaunchScreen()
        } else {
            LoveHouseShell(dependencies)
        }
    }
}
