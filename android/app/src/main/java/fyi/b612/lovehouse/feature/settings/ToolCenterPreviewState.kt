package fyi.b612.lovehouse.feature.settings

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** 所有「还没接后端」的操作统一用这句话提示，避免让人以为已经生效。 */
internal const val ToolPreviewToast = "预览：这一项还没接后端，改动不会保存"

/**
 * 工具中心里「定稿 HTML 有、但现在没有数据来源」的那部分界面状态。
 * 只存在内存里，退出页面即丢失；它不是后端接口，也不对后端提出任何字段要求。
 * 真实数据（MCP 服务 / 账号 / API 连接 / 内置能力）一律走各自原有的仓库，不经过这里。
 */
@Stable
internal class ToolPreviewState {
    private val flags = mutableStateMapOf<String, Boolean>()
    private val texts = mutableStateMapOf<String, String>()

    fun flag(key: String, default: Boolean = true): Boolean = flags[key] ?: default
    fun setFlag(key: String, value: Boolean) {
        flags[key] = value
    }

    fun text(key: String): String? = texts[key]
    fun setText(key: String, value: String) {
        texts[key] = value
    }

    val storage = StoragePreview()
}

internal data class StorageAcc(val name: String, val meta: String, val on: Boolean = true)

internal data class StorageService(
    val id: String,
    val group: String,
    val letter: String,
    val name: String,
    val sub: String,
    val status: String,
    val idle: Boolean = false,
    val note: String = "",
    val accs: List<StorageAcc> = emptyList(),
)

internal data class StorageRoute(val label: String, val primaryId: String, val backupId: String)

/** 「存储」标签现在没有任何数据来源，先用定稿 HTML 里的示例内容撑起界面。 */
@Stable
internal class StoragePreview {
    var services by mutableStateOf(
        listOf(
            StorageService("srvD1", "自建与本地", "V", "VPS · SQLite", "自建", "已连接", accs = listOf(StorageAcc("App 账号库", "读写 · 存 App 账号 / 身份"))),
            StorageService("srvD3", "自建与本地", "L", "本地 · SQLite", "手机本地", "已就绪", accs = listOf(StorageAcc("聊天记录库", "读写 · 存 聊天记录正文"))),
            StorageService("srvD2", "托管数据库", "N", "Neon", "托管 Postgres", "已连接", accs = listOf(StorageAcc("生活记忆库", "读写 · 存 生活记忆 / 小客厅"))),
            StorageService("srvD4", "托管数据库", "R", "Railway", "托管 Postgres", "已连接", accs = listOf(StorageAcc("Engineering 库", "读写 · 存 Engineering"))),
            StorageService("srvD5", "托管数据库", "S", "Supabase", "托管 Postgres · 备用", "已连接", accs = listOf(StorageAcc("备用库", "备用 · 存 其他"))),
            StorageService("srvD6", "对象存储", "C", "Cloudflare R2", "对象存储", "已连接", accs = listOf(StorageAcc("附件桶", "读写 · 存 附件"))),
        ),
    )
    var routes by mutableStateOf(
        listOf(
            StorageRoute("App 账号 / 身份", "srvD1", ""),
            StorageRoute("生活记忆 / 小客厅", "srvD2", ""),
            StorageRoute("聊天记录正文", "srvD3", ""),
            StorageRoute("Engineering", "srvD4", ""),
            StorageRoute("其他", "srvD5", ""),
            StorageRoute("附件", "srvD6", ""),
        ),
    )

    fun nameOf(id: String): String = services.firstOrNull { it.id == id }?.name ?: "无"
}
