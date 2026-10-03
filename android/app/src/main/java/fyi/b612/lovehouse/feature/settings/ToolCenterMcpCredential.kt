package fyi.b612.lovehouse.feature.settings

import androidx.compose.runtime.Composable
import kotlin.coroutines.cancellation.CancellationException

internal enum class McpAuthChoice(val label: String) {
    OAuth("OAuth"),
    None("无鉴权"),
    Bearer("Bearer Token"),
    ApiKey("API Key"),
}

/**
 * In-memory credential form state. Secrets live only here while the user is typing,
 * are cleared after a successful submit, and are never restored from the backend.
 */
internal class McpCredentialDraft private constructor(
    val choice: McpAuthChoice,
    val headerName: String,
    val token: String,
    val apiKey: String,
) {
    fun withChoice(value: McpAuthChoice) = McpCredentialDraft(value, headerName, token, apiKey)
    fun withHeaderName(value: String) = McpCredentialDraft(choice, value, token, apiKey)
    fun withToken(value: String) = McpCredentialDraft(choice, headerName, value, apiKey)
    fun withApiKey(value: String) = McpCredentialDraft(choice, headerName, token, value)
    fun cleared() = McpCredentialDraft(choice, headerName, "", "")

    val hasSecret: Boolean get() = token.isNotEmpty() || apiKey.isNotEmpty()

    /** Null means "omit credential" (OAuth-capable create); None is an explicit no-auth request. */
    fun toInput(): McpCredentialInput? = when (choice) {
        McpAuthChoice.OAuth -> null
        McpAuthChoice.None -> McpCredentialInput.None
        McpAuthChoice.Bearer -> McpCredentialInput.Bearer(token)
        McpAuthChoice.ApiKey -> McpCredentialInput.ApiKey(headerName.trim(), apiKey)
    }

    fun validationError(): String? = when (choice) {
        McpAuthChoice.Bearer -> if (token.isBlank()) "请输入 Token" else null
        McpAuthChoice.ApiKey -> when {
            headerName.isBlank() -> "请输入 Header Name"
            !isPlausibleApiKeyHeaderName(headerName.trim()) -> "Header Name 格式不正确"
            apiKey.isBlank() -> "请输入 API Key"
            else -> null
        }
        McpAuthChoice.OAuth, McpAuthChoice.None -> null
    }

    override fun toString(): String = "McpCredentialDraft(choice=$choice, headerName=$headerName, secret=<redacted>)"

    companion object {
        fun forNew() = McpCredentialDraft(McpAuthChoice.OAuth, "", "", "")

        /** Rebuilt only from public metadata; no secret is ever read back. */
        fun from(connection: McpBackendConnection?): McpCredentialDraft = McpCredentialDraft(
            choice = connection?.authType?.toChoice() ?: McpAuthChoice.OAuth,
            headerName = connection?.apiKeyHeaderName.orEmpty(),
            token = "",
            apiKey = "",
        )
    }
}

internal fun McpAuthType.toChoice(): McpAuthChoice = when (this) {
    McpAuthType.None -> McpAuthChoice.None
    McpAuthType.Bearer -> McpAuthChoice.Bearer
    McpAuthType.ApiKey -> McpAuthChoice.ApiKey
    McpAuthType.OAuth -> McpAuthChoice.OAuth
}

/** Public-metadata summary; never shows or fabricates any part of a secret. */
internal fun mcpCredentialSummary(connection: McpBackendConnection): String? {
    val type = connection.authType ?: return null
    val state = when (type) {
        McpAuthType.None -> when (connection.credentialStatus) {
            McpCredentialStatus.Configured -> "可用"
            else -> null
        }
        McpAuthType.OAuth -> when (connection.credentialStatus) {
            McpCredentialStatus.Configured -> "已授权"
            McpCredentialStatus.Missing -> "尚未授权"
            McpCredentialStatus.ReauthorizationRequired -> "需要重新授权"
            null -> null
        }
        McpAuthType.Bearer, McpAuthType.ApiKey -> when (connection.credentialStatus) {
            McpCredentialStatus.Configured -> "凭证已配置"
            McpCredentialStatus.Missing -> "缺少凭证"
            McpCredentialStatus.ReauthorizationRequired -> "需要重新配置凭证"
            null -> null
        }
    }
    val header = connection.apiKeyHeaderName?.takeIf { type == McpAuthType.ApiKey }
    val updated = connection.credentialUpdatedAt?.take(10)?.let { "更新于 $it" }
    return listOfNotNull(type.toChoice().label, header, state, updated).joinToString(" · ")
}

/** What the single "save" must do with the credential, derived from dirty form state. */
internal sealed interface McpCredentialPlan {
    data object NoChange : McpCredentialPlan
    data object Remove : McpCredentialPlan
    class Put(val input: McpCredentialInput) : McpCredentialPlan {
        override fun toString(): String = "Put($input)"
    }
    data class Invalid(val message: String) : McpCredentialPlan
}

internal fun planMcpCredentialChange(draft: McpCredentialDraft, current: McpBackendConnection): McpCredentialPlan {
    val currentType = current.authType
    return when (draft.choice) {
        McpAuthChoice.OAuth -> McpCredentialPlan.NoChange
        McpAuthChoice.None -> if (currentType == McpAuthType.None) McpCredentialPlan.NoChange else McpCredentialPlan.Remove
        McpAuthChoice.Bearer -> when {
            draft.token.isNotBlank() -> McpCredentialPlan.Put(McpCredentialInput.Bearer(draft.token))
            currentType == McpAuthType.Bearer -> McpCredentialPlan.NoChange
            else -> McpCredentialPlan.Invalid("请输入 Token")
        }
        McpAuthChoice.ApiKey -> {
            val header = draft.headerName.trim()
            val headerChanged = header != current.apiKeyHeaderName.orEmpty()
            when {
                draft.apiKey.isBlank() && currentType == McpAuthType.ApiKey && !headerChanged -> McpCredentialPlan.NoChange
                header.isEmpty() -> McpCredentialPlan.Invalid("请输入 Header Name")
                !isPlausibleApiKeyHeaderName(header) -> McpCredentialPlan.Invalid("Header Name 格式不正确")
                draft.apiKey.isBlank() && currentType == McpAuthType.ApiKey -> McpCredentialPlan.Invalid("修改 Header Name 需要同时重新输入 API Key")
                draft.apiKey.isBlank() -> McpCredentialPlan.Invalid("请输入 API Key")
                else -> McpCredentialPlan.Put(McpCredentialInput.ApiKey(header, draft.apiKey))
            }
        }
    }
}

/**
 * The single save for an existing Connection: details first, then the credential mutation
 * the dirty state needs, then one authoritative GET that the UI converges to.
 */
internal suspend fun saveMcpConnectionEdits(
    repository: McpConnectionRepository,
    connectionId: String,
    plan: McpCredentialPlan,
    saveDetails: suspend () -> Unit,
): McpBackendConnection {
    if (plan is McpCredentialPlan.Invalid) throw McpSafeException(plan.message)
    saveDetails()
    try {
        when (plan) {
            is McpCredentialPlan.Put -> repository.updateCredential(connectionId, plan.input)
            McpCredentialPlan.Remove -> repository.removeCredential(connectionId)
            McpCredentialPlan.NoChange, is McpCredentialPlan.Invalid -> Unit
        }
    } catch (error: CancellationException) {
        throw error
    } catch (error: Exception) {
        throw McpSafeException("服务和账号信息已保存，但凭证未更新：${error.mcpSafeText()}")
    }
    return try {
        repository.connection(connectionId)
    } catch (error: CancellationException) {
        throw error
    } catch (ignored: Exception) {
        throw McpSafeException("已保存，但重新读取连接状态失败，请刷新")
    }
}

/**
 * Runs one credential mutation, then re-reads the authoritative Connection.
 * The UI converges to the GET result, never to the local request.
 */
internal suspend fun runMcpCredentialMutation(
    repository: McpConnectionRepository,
    connectionId: String,
    mutation: suspend McpConnectionRepository.() -> Unit,
): McpBackendConnection {
    repository.mutation()
    return try {
        repository.connection(connectionId)
    } catch (error: CancellationException) {
        throw error
    } catch (ignored: Exception) {
        throw McpSafeException("凭证已提交，但重新读取连接状态失败，请刷新")
    }
}

@Composable
internal fun McpCredentialInputs(
    draft: McpCredentialDraft,
    existing: McpBackendConnection?,
    onChange: (McpCredentialDraft) -> Unit,
) {
    TcField("接入方式") {
        TcPills(McpAuthChoice.entries.map { it.label }, setOf(draft.choice.ordinal)) {
            onChange(draft.withChoice(McpAuthChoice.entries[it]))
        }
        existing?.let(::mcpCredentialSummary)?.let { TcHint("当前：$it") }
        when {
            existing == null && draft.choice == McpAuthChoice.OAuth ->
                TcHint("不附带凭证创建；远端要求 OAuth 时会跳转到官方授权页面。")
            existing != null && draft.choice == McpAuthChoice.OAuth ->
                TcHint("OAuth 需要在官方页面完成授权，不随「保存」提交。")
            existing != null && draft.choice == McpAuthChoice.None && existing.authType != McpAuthType.None ->
                TcHint("保存后将删除后端保存的凭证，改为无鉴权。")
        }
    }
    val configured = existing?.credentialStatus == McpCredentialStatus.Configured
    when (draft.choice) {
        McpAuthChoice.Bearer -> TcField("Token") {
            TcInput(
                draft.token,
                { onChange(draft.withToken(it)) },
                placeholder = if (configured && existing?.authType == McpAuthType.Bearer) "凭证已配置，输入新 Token 可替换" else "",
                secret = true,
            )
            TcHint("只提交给后端保存，前端不留存，也不会明文回显。")
        }
        McpAuthChoice.ApiKey -> {
            TcField("Header Name") {
                TcInput(draft.headerName, { onChange(draft.withHeaderName(it)) }, placeholder = "X-API-Key")
            }
            TcField("API Key") {
                TcInput(
                    draft.apiKey,
                    { onChange(draft.withApiKey(it)) },
                    placeholder = if (configured && existing?.authType == McpAuthType.ApiKey) "凭证已配置，输入新 API Key 可替换" else "",
                    secret = true,
                )
                TcHint("只提交给后端保存，前端不留存，也不会明文回显。")
            }
        }
        McpAuthChoice.OAuth, McpAuthChoice.None -> Unit
    }
}
