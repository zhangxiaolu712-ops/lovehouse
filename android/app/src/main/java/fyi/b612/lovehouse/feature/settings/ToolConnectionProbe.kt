package fyi.b612.lovehouse.feature.settings

import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

data class ToolConnectionProbeResult(
    val succeeded: Boolean,
    val message: String,
    val tools: List<String> = emptyList(),
)

interface ToolConnectionProbe {
    fun test(draft: ToolConnectionDraft): ToolConnectionProbeResult
    fun discover(draft: ToolConnectionDraft): ToolConnectionProbeResult
}

class HttpToolConnectionProbe : ToolConnectionProbe {
    override fun test(draft: ToolConnectionDraft): ToolConnectionProbeResult = when {
        !draft.endpoint.isHttpEndpoint() -> ToolConnectionProbeResult(false, "请输入有效的 HTTPS/HTTP Endpoint")
        draft.kind == ToolConnectionKind.Mcp -> discover(draft)
        else -> request(draft, "GET", null).let { response ->
            ToolConnectionProbeResult(response.code in 200..399, response.summary())
        }
    }

    override fun discover(draft: ToolConnectionDraft): ToolConnectionProbeResult {
        if (draft.kind != ToolConnectionKind.Mcp) return ToolConnectionProbeResult(false, "只有 MCP 连接支持发现工具")
        if (!draft.endpoint.isHttpEndpoint()) return ToolConnectionProbeResult(false, "请输入有效的 MCP HTTP Endpoint")
        val initialize = JSONObject()
            .put("jsonrpc", "2.0").put("id", 1).put("method", "initialize")
            .put("params", JSONObject().put("protocolVersion", "2025-06-18").put("capabilities", JSONObject())
                .put("clientInfo", JSONObject().put("name", "LoveHouse Android").put("version", "1")))
        val initialized = request(draft, "POST", initialize.toString())
        if (initialized.code !in 200..299) return ToolConnectionProbeResult(false, initialized.summary())
        val initializedJson = initialized.json() ?: return ToolConnectionProbeResult(false, "MCP initialize 未返回可解析的 JSON-RPC 数据")
        initializedJson.optJSONObject("error")?.let { return ToolConnectionProbeResult(false, it.optString("message", "MCP initialize 失败")) }

        request(
            draft,
            "POST",
            JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized").toString(),
            initialized.sessionId,
        )
        val listed = request(
            draft,
            "POST",
            JSONObject().put("jsonrpc", "2.0").put("id", 2).put("method", "tools/list").put("params", JSONObject()).toString(),
            initialized.sessionId,
        )
        if (listed.code !in 200..299) return ToolConnectionProbeResult(false, listed.summary())
        val payload = listed.json() ?: return ToolConnectionProbeResult(false, "MCP tools/list 未返回可解析的数据")
        payload.optJSONObject("error")?.let { return ToolConnectionProbeResult(false, it.optString("message", "MCP discovery 失败")) }
        val tools = payload.optJSONObject("result")?.optJSONArray("tools")?.let { array ->
            buildList { for (index in 0 until array.length()) add(array.getJSONObject(index).optString("name")) }
                .filter(String::isNotBlank)
        }.orEmpty()
        return ToolConnectionProbeResult(true, "发现 ${tools.size} 个真实工具", tools)
    }

    private fun request(draft: ToolConnectionDraft, method: String, body: String?, sessionId: String? = null): Response {
        val connection = (URL(draft.endpoint.trim()).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 12_000
            readTimeout = 20_000
            setRequestProperty("Accept", "application/json, text/event-stream")
            setRequestProperty("Origin", "https://android.lovehouse.local")
            sessionId?.let { setRequestProperty("Mcp-Session-Id", it) }
            when (draft.auth) {
                ToolConnectionAuth.ApiKey -> if (draft.credential.isNotBlank()) setRequestProperty("X-API-Key", draft.credential)
                ToolConnectionAuth.BearerToken -> if (draft.credential.isNotBlank()) setRequestProperty("Authorization", "Bearer ${draft.credential}")
                ToolConnectionAuth.None -> Unit
            }
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        return try {
            if (body != null) connection.outputStream.bufferedWriter().use { it.write(body) }
            val code = connection.responseCode
            val text = (if (code in 200..399) connection.inputStream else connection.errorStream)
                ?.bufferedReader()?.use { it.readText() }.orEmpty()
            Response(code, text, connection.getHeaderField("Mcp-Session-Id"))
        } catch (error: Exception) {
            Response(-1, error.message?.take(160).orEmpty(), null)
        } finally {
            connection.disconnect()
        }
    }
}

private data class Response(val code: Int, val body: String, val sessionId: String?) {
    fun json(): JSONObject? = runCatching {
        val content = if (body.trimStart().startsWith("data:")) {
            body.lineSequence().firstOrNull { it.startsWith("data:") }?.removePrefix("data:")?.trim().orEmpty()
        } else body
        JSONObject(content)
    }.getOrNull()

    fun summary(): String = when {
        code == -1 -> "连接失败：${body.ifBlank { "网络不可达" }}"
        code in 200..399 -> "连接成功 · HTTP $code"
        code == 401 || code == 403 -> "认证失败 · HTTP $code"
        else -> "连接失败 · HTTP $code"
    }
}

private fun String.isHttpEndpoint(): Boolean = runCatching {
    URL(trim()).protocol.lowercase() in setOf("http", "https")
}.getOrDefault(false)
