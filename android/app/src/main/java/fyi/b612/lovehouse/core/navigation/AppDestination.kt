package fyi.b612.lovehouse.core.navigation

enum class AppDestination(
    val route: String,
    val label: String,
    val glyph: String,
    val deepLink: String,
    val isPrimary: Boolean,
) {
    Home("home", "Home", "H", "lovehouse://home", true),
    Chat("chat", "Chat", "C", "lovehouse://chat", true),
    Memory("memory", "Memory", "M", "lovehouse://memory", true),
    Engineering("engineering", "Engineering", "E", "lovehouse://engineering", true),
    Settings("settings", "Settings", "S", "lovehouse://settings", true),
    NativeLab("settings/native-lab", "Native Lab", "NL", "lovehouse://settings/native-lab", false),
    ;

    companion object {
        val primary = entries.filter(AppDestination::isPrimary)

        fun selectedForRoute(route: String?): AppDestination? = when {
            route == null -> null
            route.startsWith(Settings.route) -> Settings
            else -> primary.firstOrNull { it.route == route }
        }
    }
}
