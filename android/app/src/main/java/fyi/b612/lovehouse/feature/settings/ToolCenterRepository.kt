package fyi.b612.lovehouse.feature.settings

import fyi.b612.lovehouse.BuildConfig
import java.net.HttpURLConnection
import java.net.URL
import org.json.JSONObject

interface ToolCenterRepository {
    suspend fun capabilities(): List<ToolCapability>
    suspend fun testTool(toolId: String): ToolTestResult
}

class HttpToolCenterRepository(
    chatEndpoint: String = BuildConfig.LOVEHOUSE_CHAT_URL,
    private val ownerToken: String = BuildConfig.LOVEHOUSE_OWNER_TOKEN,
) : ToolCenterRepository {
    private val apiBase = chatEndpoint.substringBeforeLast("/chat")

    override suspend fun capabilities(): List<ToolCapability> {
        val payload = request("GET", "$apiBase/tools/capabilities")
        val tools = payload.getJSONArray("tools")
        return buildList {
            for (index in 0 until tools.length()) add(toCapability(tools.getJSONObject(index)))
        }
    }

    override suspend fun testTool(toolId: String): ToolTestResult {
        val payload = request(
            "POST",
            "$apiBase/tools/test",
            JSONObject().put("tool_id", toolId).toString(),
            acceptConflict = true,
        )
        return ToolTestResult(
            toolId = toolId,
            succeeded = payload.optBoolean("ok", false),
            message = payload.optString("result_summary").ifBlank {
                payload.optString("detail").ifBlank { "工具测试失败" }
            },
        )
    }

    private fun request(method: String, endpoint: String, body: String? = null, acceptConflict: Boolean = false): JSONObject {
        if (ownerToken.isBlank()) error("缺少 Owner 登录凭据")
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Authorization", "Bearer $ownerToken")
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
            }
        }
        return try {
            if (body != null) connection.outputStream.bufferedWriter(Charsets.UTF_8).use { it.write(body) }
            val accepted = connection.responseCode in 200..299 || (acceptConflict && connection.responseCode == 409)
            val text = (if (accepted) connection.inputStream else connection.errorStream)
                ?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (!accepted) error(JSONObject(text.ifBlank { "{}" }).optJSONObject("error")?.optString("message").orEmpty().ifBlank { "连接失败（HTTP ${connection.responseCode}）" })
            JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }

    private fun toCapability(value: JSONObject): ToolCapability = ToolCapability(
        toolId = value.getString("tool_id"),
        group = value.getString("group"),
        groupLabel = value.getString("group_label"),
        displayName = value.getString("display_name"),
        summary = value.getString("summary"),
        availability = when (value.getString("status")) {
            "available" -> ToolAvailability.Available
            "no_permission" -> ToolAvailability.NoPermission
            "connection_failed" -> ToolAvailability.ConnectionFailed
            else -> ToolAvailability.Unconfigured
        },
        detail = value.optString("detail"),
        riskLevel = when (value.optString("risk_level")) {
            "high" -> ToolRiskLevel.High
            "medium" -> ToolRiskLevel.Medium
            else -> ToolRiskLevel.Low
        },
        capabilityKind = when (value.optString("capability_kind")) {
            "write" -> ToolCapabilityKind.Write
            "execute" -> ToolCapabilityKind.Execute
            "admin" -> ToolCapabilityKind.Admin
            else -> ToolCapabilityKind.Read
        },
        requiresApproval = value.optBoolean("requires_approval", false),
        scope = value.optJSONArray("scope")?.let { array ->
            buildList { for (index in 0 until array.length()) add(array.getString(index)) }
        }.orEmpty(),
    )
}
