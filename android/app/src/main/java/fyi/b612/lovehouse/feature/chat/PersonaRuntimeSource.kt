package fyi.b612.lovehouse.feature.chat

import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** A per-turn view; the App Backend Persona Profile remains the source of truth. */
class PersonaRuntimeSnapshot(
    val personaId: String,
    val personaVersion: Int,
    val instructions: String,
    val background: String,
    val connectionIds: Set<String>,
    val executionTicket: String?,
    val reanchorIntent: Boolean,
) {
    override fun toString(): String = "PersonaRuntimeSnapshot(personaId=$personaId, version=$personaVersion, connections=${connectionIds.size}, ticket=<redacted>, reanchor=$reanchorIntent)"
}

data class PersonaProfile(
    val personaId: String,
    val displayName: String,
    val avatar: String?,
    val instructions: String,
    val background: String,
    val version: Int,
    val providerNativeAnchorPreference: Boolean,
)

interface PersonaRuntimeSource {
    suspend fun resolve(personaId: String, requestedToolIds: Set<String>, reanchorIntent: Boolean): PersonaRuntimeSnapshot?
    suspend fun profiles(): List<PersonaProfile> = emptyList()
    suspend fun profile(personaId: String): PersonaProfile? = null
    suspend fun save(profile: PersonaProfile): PersonaProfile = error("Persona 档案尚未接入")
}

object NoOpPersonaRuntimeSource : PersonaRuntimeSource {
    override suspend fun resolve(personaId: String, requestedToolIds: Set<String>, reanchorIntent: Boolean): PersonaRuntimeSnapshot? = null
}

class AppBackendPersonaRuntimeSource(
    private val baseUrl: String,
    private val sessionCookie: () -> String?,
) : PersonaRuntimeSource {
    override suspend fun profiles(): List<PersonaProfile> = withContext(Dispatchers.IO) {
        val result = request("GET", "${baseUrl.trimEnd('/')}/api/personas") ?: return@withContext emptyList()
        val values = result.optJSONArray("personas") ?: return@withContext emptyList()
        buildList {
            for (index in 0 until values.length()) values.optJSONObject(index)?.let { add(it.toProfile()) }
        }
    }
    override suspend fun profile(personaId: String): PersonaProfile? = withContext(Dispatchers.IO) {
        val encodedId = URLEncoder.encode(personaId, Charsets.UTF_8.name()).replace("+", "%20")
        request("GET", "${baseUrl.trimEnd('/')}/api/personas/$encodedId")?.toProfile()
    }

    override suspend fun save(profile: PersonaProfile): PersonaProfile = withContext(Dispatchers.IO) {
        val encodedId = URLEncoder.encode(profile.personaId, Charsets.UTF_8.name()).replace("+", "%20")
        val body = JSONObject().put("display_name", profile.displayName)
            .put("avatar", profile.avatar ?: JSONObject.NULL)
            .put("instructions", profile.instructions)
            .put("background", profile.background)
            .put("provider_native_anchor_preference", profile.providerNativeAnchorPreference)
        requireNotNull(request("PUT", "${baseUrl.trimEnd('/')}/api/personas/$encodedId", body.toString())).toProfile()
    }
    override suspend fun resolve(
        personaId: String,
        requestedToolIds: Set<String>,
        reanchorIntent: Boolean,
    ): PersonaRuntimeSnapshot? = withContext(Dispatchers.IO) {
        if (sessionCookie() == null) {
            if (requestedToolIds.any { it.startsWith("mcp-connection:") }) error("请先登录 LoveHouse App Account 后使用 MCP Connection")
            return@withContext null
        }
        val encodedId = URLEncoder.encode(personaId, Charsets.UTF_8.name()).replace("+", "%20")
        val selectedConnectionIds = requestedToolIds.filterTo(linkedSetOf()) { it.startsWith("mcp-connection:") }
        val profile = runCatching { request("GET", "${baseUrl.trimEnd('/')}/api/personas/$encodedId/runtime") }
            .getOrElse { failure -> if (selectedConnectionIds.isEmpty()) null else throw failure }
            ?: if (selectedConnectionIds.isEmpty()) return@withContext null else error("当前 Persona 尚未建立 App Account 档案，MCP Connection 不可用")
        val connections = profile.optJSONArray("effective_connections")
        val connectionIds = buildSet {
            if (connections != null) for (index in 0 until connections.length()) {
                connections.optJSONObject(index)?.optString("connection_id")?.takeIf(String::isNotBlank)?.let(::add)
            }
        }
        if (selectedConnectionIds.any { it.removePrefix("mcp-connection:") !in connectionIds }) {
            error("所选 MCP Connection 已不属于当前 Persona 或已停用")
        }
        val grant = if (connectionIds.isNotEmpty()) {
            request("POST", "${baseUrl.trimEnd('/')}/api/personas/$encodedId/runtime-grant",
                JSONObject().toString())
                ?: error("Persona 工具授权不可用")
        } else null
        if (grant != null) {
            val granted = grant.optJSONArray("connection_ids") ?: error("App Backend 未返回 MCP Connection 范围")
            val grantedIds = (0 until granted.length()).mapTo(linkedSetOf()) { granted.optString(it) }
            check(grantedIds == connectionIds) { "MCP Connection 授权范围与 Persona 不一致" }
        }
        PersonaRuntimeSnapshot(
            personaId = profile.getString("persona_id"),
            personaVersion = profile.getInt("persona_version"),
            instructions = profile.optString("instructions"),
            background = profile.optString("background"),
            connectionIds = connectionIds,
            executionTicket = grant?.getString("ticket"),
            reanchorIntent = reanchorIntent,
        )
    }

    private fun request(method: String, endpoint: String, body: String? = null): JSONObject? {
        val cookie = sessionCookie() ?: error("请先登录 LoveHouse App Account")
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 15_000
            readTimeout = 30_000
            setRequestProperty("Accept", "application/json")
            setRequestProperty("Cookie", cookie)
            if (body != null) {
                doOutput = true
                setRequestProperty("Content-Type", "application/json; charset=utf-8")
            }
        }
        try {
            if (body != null) connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = connection.responseCode
            if (status == 404 && method == "GET") return null
            if (status == 401) error("App Account 会话已失效，请重新登录")
            if (status !in 200..299) error("Persona Runtime 请求失败（HTTP $status）")
            return JSONObject(connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally {
            connection.disconnect()
        }
    }

    private fun JSONObject.toProfile() = PersonaProfile(
        personaId = getString("persona_id"),
        displayName = getString("display_name"),
        avatar = if (isNull("avatar")) null else optString("avatar").takeIf(String::isNotBlank),
        instructions = optString("instructions"),
        background = optString("background"),
        version = getInt("version"),
        providerNativeAnchorPreference = optBoolean("provider_native_anchor_preference"),
    )
}
