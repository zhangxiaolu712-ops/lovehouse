package fyi.b612.lovehouse.feature.chat

import fyi.b612.lovehouse.BuildConfig
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

data class CodexRuntimeEvidence(
    val runtime: String,
    val adapterId: String?,
    val threadId: String,
)

data class CodexChatResult(
    val text: String,
    val evidence: CodexRuntimeEvidence,
)

class CodexChatException(message: String) : Exception(message)

interface CodexChatClient {
    suspend fun streamMessage(
        threadId: String,
        message: String,
        onText: (String) -> Unit,
    ): CodexChatResult
}

class HttpCodexChatClient(
    private val endpoint: String = BuildConfig.LOVEHOUSE_CHAT_URL,
    private val ownerToken: String = BuildConfig.LOVEHOUSE_OWNER_TOKEN,
) : CodexChatClient {
    override suspend fun streamMessage(
        threadId: String,
        message: String,
        onText: (String) -> Unit,
    ): CodexChatResult {
        if (ownerToken.isBlank()) throw CodexChatException("未配置 Owner 登录凭据")
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
            setRequestProperty("Authorization", "Bearer $ownerToken")
        }
        val payload = """{"persona_id":"codex","thread_id":"$threadId","window_id":"android-codex-main","scene":"work","message":{"type":"text","text":"${jsonEscape(message)}"}}"""
        try {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(payload) }
            if (connection.responseCode !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
                throw CodexChatException(httpFailure(connection.responseCode, detail))
            }
            var event = "message"
            val data = StringBuilder()
            var text = ""
            var evidence: CodexRuntimeEvidence? = null
            var ended = false
            var succeeded = false
            var streamError: String? = null

            fun dispatch() {
                if (data.isEmpty()) return
                val json = data.toString()
                when (event) {
                    "message_start" -> evidence = CodexRuntimeEvidence(
                        runtime = jsonString(json, "runtime") ?: "",
                        adapterId = jsonString(json, "adapter_id"),
                        threadId = jsonString(json, "thread_id") ?: threadId,
                    )
                    "text_delta" -> jsonString(json, "delta")?.let { delta ->
                        text += delta
                        onText(text)
                    }
                    "error" -> streamError = jsonString(json, "message") ?: "Codex Runtime 返回错误"
                    "message_end" -> {
                        ended = true
                        succeeded = jsonBoolean(json, "ok") == true
                    }
                }
                event = "message"
                data.clear()
            }

            connection.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                lines.forEach { line ->
                    when {
                        line.isBlank() -> dispatch()
                        line.startsWith("event:") -> event = line.substringAfter(':').trim()
                        line.startsWith("data:") -> {
                            if (data.isNotEmpty()) data.append('\n')
                            data.append(line.substringAfter(':').trimStart())
                        }
                    }
                }
            }
            dispatch()
            if (!ended) throw CodexChatException("连接中断：Codex 数据流未正常结束")
            if (!succeeded) throw CodexChatException(streamError ?: "发送失败：Codex Runtime 未完成回复")
            if (text.isBlank()) throw CodexChatException("发送失败：Codex Runtime 没有返回文字")
            val runtimeEvidence = evidence
                ?: throw CodexChatException("连接失败：响应缺少 Runtime metadata")
            if (runtimeEvidence.runtime != "codex_cli" || runtimeEvidence.adapterId != "codex-cli-v1") {
                throw CodexChatException("连接失败：后端不是已批准的 Codex Runtime")
            }
            return CodexChatResult(text, runtimeEvidence)
        } finally {
            connection.disconnect()
        }
    }

    private fun httpFailure(status: Int, body: String): String = when (status) {
        401, 403 -> "鉴权失败：请重新登录后再试"
        else -> jsonString(body, "message") ?: "连接失败（HTTP $status）"
    }
}

internal fun jsonEscape(value: String): String = buildString {
    value.forEach { char ->
        append(when (char) {
            '\\' -> "\\\\"
            '"' -> "\\\""
            '\n' -> "\\n"
            '\r' -> "\\r"
            '\t' -> "\\t"
            else -> char
        })
    }
}

private fun jsonString(json: String, key: String): String? {
    val match = Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"\\\\])*)\\\"").find(json) ?: return null
    return match.groupValues[1]
        .replace("\\n", "\n").replace("\\r", "\r").replace("\\t", "\t")
        .replace("\\\"", "\"").replace("\\\\", "\\")
}

private fun jsonBoolean(json: String, key: String): Boolean? =
    Regex("\\\"${Regex.escape(key)}\\\"\\s*:\\s*(true|false)").find(json)?.groupValues?.get(1)?.toBooleanStrictOrNull()

internal fun stableCodexThreadId(): String = "7c814f9a-7588-4e35-b4b6-a216f172c012"
