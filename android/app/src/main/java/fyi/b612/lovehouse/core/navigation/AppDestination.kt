package fyi.b612.lovehouse.core.navigation

enum class AppDestination(
    val route: String,
    val label: String,
    val glyph: String,
    val deepLink: String,
    val isPrimary: Boolean,
) {
    Home("home", "首页", "家", "lovehouse://home", true),
    Chat("chat", "聊天", "聊", "lovehouse://chat", true),
    Memory("memory", "记忆", "忆", "lovehouse://memory", true),
    Engineering("engineering", "工程", "工", "lovehouse://engineering", true),
    Settings("settings", "设置", "设", "lovehouse://settings", true),
    ChatThread("chat/thread/{threadId}", "聊天详情", "聊", "lovehouse://chat/thread/{threadId}", false),
    Lab("lab", "Lab", "验", "lovehouse://lab", false),
    ConnectionControl("lab/connection-control", "连接与工程控制", "连", "lovehouse://lab/connection-control", false),
    NativeLab("lab/native", "原生能力测试", "验", "lovehouse://lab/native", false),
    ToolCenterLab("lab/tool-center", "MCP Tools Lab", "验", "lovehouse://lab/tool-center", false),
    ;

    companion object {
        val primary = entries.filter(AppDestination::isPrimary)

        fun selectedForRoute(route: String?): AppDestination? = when {
            route == null -> null
            route.startsWith(Chat.route) -> Chat
            route.startsWith(Settings.route) -> Settings
            else -> primary.firstOrNull { it.route == route }
        }
    }
}
