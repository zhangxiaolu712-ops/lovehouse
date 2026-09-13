package fyi.b612.lovehouse.feature.chat

import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

interface ChatConnectionProbe {
    suspend fun test(draft: ChatConnectionDraft): ChatConnectionTestResult
}

class HttpChatConnectionProbe : ChatConnectionProbe {
    override suspend fun test(draft: ChatConnectionDraft): ChatConnectionTestResult = withContext(Dispatchers.IO) {
        validateChatConnectionDraft(draft)?.let { return@withContext ChatConnectionTestResult(false, it) }
        val statusUrl = codexStatusEndpoint(draft.endpoint)
        val http = runCatching {
            (URL(statusUrl).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 15_000
                readTimeout = 20_000
                setRequestProperty("Accept", "application/json")
                setRequestProperty("X-LoveHouse-Chat-Key", draft.credential)
            }
        }.getOrElse { return@withContext ChatConnectionTestResult(false, it.message ?: "连接测试失败") }
        try {
            val status = http.responseCode
            if (status in 200..299) {
                ChatConnectionTestResult(true, "连接成功 · HTTP $status")
            } else {
                val detail = http.errorStream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
                ChatConnectionTestResult(false, connectionHttpFailure(status, detail))
            }
        } catch (error: Exception) {
            ChatConnectionTestResult(false, error.message ?: "连接测试失败")
        } finally {
            http.disconnect()
        }
    }
}

internal fun normalizeChatConnectionEndpointInput(value: String): String =
    value.lineSequence().firstOrNull().orEmpty().trim()

internal fun codexStatusEndpoint(configuredUrl: String): String =
    configuredUrl.trim().trimEnd('/').let { endpoint ->
        if (endpoint.endsWith("/status")) endpoint else "$endpoint/status"
    }

private fun validateChatConnectionDraft(draft: ChatConnectionDraft): String? = when {
    draft.name.isBlank() -> "请填写连接名称"
    !draft.endpoint.isHttpEndpoint() -> "请输入有效的 HTTPS/HTTP URL"
    draft.credential.isBlank() -> "请填写 Key"
    else -> null
}

private fun String.isHttpEndpoint(): Boolean = runCatching { URL(trim()).protocol.lowercase() in setOf("http", "https") }.getOrDefault(false)

private fun connectionHttpFailure(status: Int, body: String): String =
    connectionJsonString(body, "message") ?: "连接失败（HTTP $status）"

private fun connectionJsonString(json: String, key: String): String? {
    val match = Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"").find(json) ?: return null
    return match.groupValues[1]
        .replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t")
        .replace("\\\"", "\"").replace("\\\\", "\\")
}
