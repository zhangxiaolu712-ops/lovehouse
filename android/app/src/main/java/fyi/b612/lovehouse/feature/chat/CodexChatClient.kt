package fyi.b612.lovehouse.feature.chat

import fyi.b612.lovehouse.BuildConfig
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

data class CodexRuntimeEvidence(
    val runtime: String,
    val adapterId: String?,
    val threadId: String,
    val requestedToolIds: Set<String> = emptySet(),
    val toolCalls: List<CodexToolCallEvidence> = emptyList(),
)

data class ChatRuntimeConfig(
    val personaId: String,
    val threadId: String,
    val windowId: String,
    val expectedRuntime: String,
    val expectedAdapterId: String,
    val attachmentCapabilities: AttachmentCapabilities,
    val toolCenterEnabled: Boolean,
) {
    val attachmentsEnabled: Boolean
        get() = attachmentCapabilities.supported
}

data class CodexToolCallEvidence(
    val name: String,
    val status: String,
)

enum class ChatProcessKind { ReasoningStatus, Thinking, ToolCall, ToolResult, ToolError, WorkflowStatus }
enum class ChatProcessStatus { Running, Succeeded, Failed }

data class ChatProcessEvent(
    val id: String,
    val kind: ChatProcessKind,
    val title: String,
    val status: ChatProcessStatus,
    val detail: String? = null,
)

internal fun mergeThinkingText(
    current: String,
    summary: String?,
    delta: String?,
): String? = when {
    summary != null -> summary
    delta != null -> current + delta
    else -> null
}

data class CodexChatResult(
    val text: String,
    val evidence: CodexRuntimeEvidence,
)

class CodexChatException(message: String) : Exception(message)

interface CodexChatClient {
    suspend fun streamMessage(
        threadId: String,
        message: String,
        requestedToolIds: Set<String> = emptySet(),
        attachments: List<ChatAttachment> = emptyList(),
        onText: (String) -> Unit,
    ): CodexChatResult

    suspend fun streamMessageWithProcess(
        threadId: String,
        message: String,
        requestedToolIds: Set<String> = emptySet(),
        attachments: List<ChatAttachment> = emptyList(),
        onText: (String) -> Unit,
        onProcess: (ChatProcessEvent) -> Unit,
    ): CodexChatResult = streamMessage(threadId, message, requestedToolIds, attachments, onText)

    suspend fun streamRuntimeMessageWithProcess(
        config: ChatRuntimeConfig,
        message: String,
        requestedToolIds: Set<String> = emptySet(),
        attachments: List<ChatAttachment> = emptyList(),
        onText: (String) -> Unit,
        onProcess: (ChatProcessEvent) -> Unit,
    ): CodexChatResult = streamMessageWithProcess(
        config.threadId,
        message,
        requestedToolIds,
        attachments,
        onText,
        onProcess,
    )
}

class HttpCodexChatClient(
    private val endpoint: String = BuildConfig.LOVEHOUSE_CHAT_URL,
    private val allowedToolIdsFor: (String) -> Set<String> = { emptySet() },
) : CodexChatClient {
    override suspend fun streamMessage(
        threadId: String,
        message: String,
        requestedToolIds: Set<String>,
        attachments: List<ChatAttachment>,
        onText: (String) -> Unit,
    ): CodexChatResult = streamInternal(CodexRuntime.copy(threadId = threadId), message, requestedToolIds, attachments, onText) {}

    override suspend fun streamMessageWithProcess(
        threadId: String,
        message: String,
        requestedToolIds: Set<String>,
        attachments: List<ChatAttachment>,
        onText: (String) -> Unit,
        onProcess: (ChatProcessEvent) -> Unit,
    ): CodexChatResult = streamInternal(CodexRuntime.copy(threadId = threadId), message, requestedToolIds, attachments, onText, onProcess)

    override suspend fun streamRuntimeMessageWithProcess(
        config: ChatRuntimeConfig,
        message: String,
        requestedToolIds: Set<String>,
        attachments: List<ChatAttachment>,
        onText: (String) -> Unit,
        onProcess: (ChatProcessEvent) -> Unit,
    ): CodexChatResult = streamInternal(config, message, requestedToolIds, attachments, onText, onProcess)

    private suspend fun streamInternal(
        config: ChatRuntimeConfig,
        message: String,
        requestedToolIds: Set<String>,
        attachments: List<ChatAttachment>,
        onText: (String) -> Unit,
        onProcess: (ChatProcessEvent) -> Unit,
    ): CodexChatResult {
        require(config.attachmentsEnabled || attachments.isEmpty()) { "${config.personaId} Runtime 尚未启用附件" }
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 15_000
            readTimeout = 120_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Accept", "text/event-stream")
        }
        val allowedToolIds = if (config.toolCenterEnabled) {
            allowedToolIdsFor(config.threadId).intersect(requestedToolIds)
        } else {
            emptySet()
        }
        val payload = buildChatPayload(
            config,
            toolDirectedMessage(message, allowedToolIds),
            allowedToolIds,
            attachments,
        )
        try {
            connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(payload) }
            if (connection.responseCode !in 200..299) {
                val detail = connection.errorStream?.bufferedReader()?.use(BufferedReader::readText).orEmpty()
                throw CodexChatException(httpFailure(connection.responseCode, detail))
            }
            var event = "message"
            val data = StringBuilder()
            var text = ""
            var thinkingText = ""
            var evidence: CodexRuntimeEvidence? = null
            var ended = false
            var succeeded = false
            var streamError: String? = null
            val toolCalls = linkedMapOf<String, CodexToolCallEvidence>()

            fun dispatch() {
                if (data.isEmpty()) return
                val json = data.toString()
                when (event) {
                    "message_start" -> evidence = CodexRuntimeEvidence(
                        runtime = jsonString(json, "runtime") ?: "",
                        adapterId = jsonString(json, "adapter_id"),
                        threadId = jsonString(json, "thread_id") ?: config.threadId,
                    )
                    "text_delta" -> jsonString(json, "delta")?.let { delta ->
                        text += delta
                        onText(text)
                    }
                    "tool_call" -> jsonString(json, "name")?.let { name ->
                        toolCalls[name] = CodexToolCallEvidence(name, "running")
                        onProcess(ChatProcessEvent("tool:$name", ChatProcessKind.ToolCall, toolTitle(name), ChatProcessStatus.Running, jsonString(json, "summary")))
                    }
                    "tool_result" -> jsonString(json, "name")?.let { name ->
                        toolCalls[name] = CodexToolCallEvidence(name, "success")
                        onProcess(ChatProcessEvent("tool:$name", ChatProcessKind.ToolResult, toolTitle(name), ChatProcessStatus.Succeeded, jsonString(json, "summary")))
                    }
                    "tool_error" -> jsonString(json, "name")?.let { name ->
                        toolCalls[name] = CodexToolCallEvidence(name, "rejected")
                        onProcess(ChatProcessEvent("tool:$name", ChatProcessKind.ToolError, toolTitle(name), ChatProcessStatus.Failed, jsonString(json, "message")))
                    }
                    "reasoning_status" -> jsonString(json, "summary")?.takeIf(String::isNotBlank)?.let { summary ->
                        onProcess(ChatProcessEvent("reasoning", ChatProcessKind.ReasoningStatus, "思考状态", ChatProcessStatus.Running, summary))
                    }
                    "thinking" -> mergeThinkingText(
                        current = thinkingText,
                        summary = jsonString(json, "summary"),
                        delta = jsonString(json, "thinking"),
                    )?.let { updated ->
                        thinkingText = updated
                        updated.takeIf(String::isNotBlank)?.let { detail ->
                            onProcess(ChatProcessEvent("thinking", ChatProcessKind.Thinking, "Thinking", ChatProcessStatus.Running, detail))
                        }
                    }
                    "workflow_status" -> jsonString(json, "summary")?.takeIf(String::isNotBlank)?.let { summary ->
                        onProcess(ChatProcessEvent("workflow", ChatProcessKind.WorkflowStatus, "执行状态", ChatProcessStatus.Running, summary))
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
            if (runtimeEvidence.runtime != config.expectedRuntime || runtimeEvidence.adapterId != config.expectedAdapterId) {
                throw CodexChatException("连接失败：后端不是已批准的 ${config.personaId} Runtime")
            }
            if (allowedToolIds.isNotEmpty() && toolCalls.values.none { it.status == "success" }) {
                throw CodexChatException("工具调用未完成：Runtime 没有返回真实 MCP tools/call 成功事件")
            }
            return CodexChatResult(
                text,
                runtimeEvidence.copy(requestedToolIds = allowedToolIds, toolCalls = toolCalls.values.toList()),
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun httpFailure(status: Int, body: String): String = when (status) {
        401, 403 -> "聊天服务暂时不可用（HTTP $status）"
        else -> jsonString(body, "message") ?: "连接失败（HTTP $status）"
    }
}

private fun toolTitle(name: String): String = when {
    name.contains("engineering", ignoreCase = true) -> "读取 Engineering"
    name.contains("livingroom", ignoreCase = true) || name.contains("living_room", ignoreCase = true) -> "读取 LivingRoom"
    else -> "调用 $name"
}

internal fun toolDirectedMessage(message: String, toolIds: Set<String>): String {
    if (toolIds.isEmpty()) return message
    val instructions = buildList {
        if ("builtin.engineering.read_current" in toolIds || "builtin.engineering.open" in toolIds) {
            add("Use the LoveHouse MCP Engineering tool for the requested Engineering data; do not substitute shell, process inspection, or a guess.")
        }
        if ("builtin.livingroom.read" in toolIds) {
            add("Use the LoveHouse MCP LivingRoom read tool for the requested recent messages; do not substitute a guess.")
        }
    }
    return if (instructions.isEmpty()) message else "$message\n\n[Selected LoveHouse tool requirement: ${instructions.joinToString(" ")}]"
}

internal fun buildCodexChatPayload(
    threadId: String,
    message: String,
    allowedToolIds: Set<String>,
    attachments: List<ChatAttachment> = emptyList(),
): String = buildChatPayload(
    config = CodexRuntime.copy(threadId = threadId),
    message = message,
    allowedToolIds = allowedToolIds,
    attachments = attachments,
)

internal fun buildChatPayload(
    config: ChatRuntimeConfig,
    message: String,
    allowedToolIds: Set<String>,
    attachments: List<ChatAttachment> = emptyList(),
): String {
    val tools = allowedToolIds.sorted().joinToString(",") { "\"${jsonEscape(it)}\"" }
    val toolField = if (config.toolCenterEnabled) "\"allowed_tool_ids\":[$tools]," else ""
    val attachmentField = if (config.attachmentsEnabled) ",\"attachments\":${chatAttachmentsJson(attachments)}" else ""
    return """{"persona_id":"${config.personaId}","thread_id":"${config.threadId}","window_id":"${config.windowId}","scene":"work",$toolField"message":{"type":"text","text":"${jsonEscape(message)}"$attachmentField}}"""
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

internal val CodexRuntime = ChatRuntimeConfig(
    personaId = "codex",
    threadId = stableCodexThreadId(),
    windowId = "android-codex-main",
    expectedRuntime = "codex_cli",
    expectedAdapterId = "codex-cli-v1",
    attachmentCapabilities = CodexAttachmentCapabilities,
    toolCenterEnabled = true,
)

internal val ClaudeRuntime = ChatRuntimeConfig(
    personaId = "claude",
    threadId = "1f75f3d4-3840-46fe-83b9-fbbbafeb8bba",
    windowId = "cf9e3810-2ae7-4e58-b773-f559db4a0001",
    expectedRuntime = "claude_cli",
    expectedAdapterId = "claude-cli-v1",
    attachmentCapabilities = ClaudeAttachmentCapabilities,
    toolCenterEnabled = false,
)
