package fyi.b612.lovehouse.feature.settings

import java.util.concurrent.ConcurrentHashMap

data class EffectiveToolSet(
    val personaId: String,
    val threadId: String,
    val tools: List<ToolCapability>,
) {
    val allowedToolIds: Set<String> = tools.mapTo(linkedSetOf(), ToolCapability::toolId)

    companion object {
        fun empty(personaId: String, threadId: String) = EffectiveToolSet(personaId, threadId, emptyList())
    }
}

interface EffectiveToolResolver {
    suspend fun resolve(personaId: String, threadId: String): EffectiveToolSet
    fun cachedAllowedToolIds(personaId: String, threadId: String): Set<String>
}

class AppEffectiveToolResolver(
    private val builtInRepository: ToolCenterRepository,
    private val profiles: ToolProfilePreferenceStore,
    private val mcpConnections: McpConnectionRepository,
) : EffectiveToolResolver {
    private val cache = ConcurrentHashMap<String, EffectiveToolSet>()

    override suspend fun resolve(personaId: String, threadId: String): EffectiveToolSet {
        val builtIns = runCatching { builtInRepository.capabilities() }
            .getOrDefault(emptyList())
            .filter { capability ->
                capability.availability == ToolAvailability.Available &&
                    capability.toolId in profiles.profile(personaId, threadId).preferredToolIds
            }
        val authoritative = mcpConnections.effectiveConnections(personaId)
        val mcpTools = if (authoritative != null) {
            authoritative.map { it.toCapability(personaId) }
        } else {
            // Compatibility for repository implementations without the App Backend runtime contract.
            val connected = runCatching { mcpConnections.connections() }.getOrDefault(emptyList())
            val registered = runCatching { mcpConnections.registry() }.getOrDefault(emptyList())
            val registeredById = registered.associateBy(McpBackendConnection::id)
            connected.asSequence()
                .map { connection -> connection.mergeRegistry(registeredById[connection.id]) }
                .filter { connection ->
                    connection.status == McpBackendConnectionStatus.Connected &&
                        connection.enabled &&
                        personaId in connection.boundIdentityIds
                }
                .map { connection -> connection.toCapability(personaId) }
                .distinctBy(ToolCapability::toolId)
                .toList()
        }
        return EffectiveToolSet(
            personaId = personaId,
            threadId = threadId,
            tools = (builtIns + mcpTools).distinctBy(ToolCapability::toolId),
        ).also { cache[key(personaId, threadId)] = it }
    }

    override fun cachedAllowedToolIds(personaId: String, threadId: String): Set<String> =
        cache[key(personaId, threadId)]?.allowedToolIds.orEmpty()

    private fun key(personaId: String, threadId: String) = "$personaId::$threadId"
}

internal fun mcpConnectionCapabilityId(connectionId: String): String = "mcp-connection:$connectionId"

private fun McpBackendConnection.mergeRegistry(registered: McpBackendConnection?): McpBackendConnection =
    if (registered == null) this else copy(
        name = registered.name ?: name,
        description = registered.description ?: description,
        toolCount = maxOf(toolCount, registered.toolCount),
        tools = if (registered.tools.isNotEmpty()) registered.tools else tools,
    )

private fun McpBackendConnection.toCapability(personaId: String): ToolCapability {
    val connectionLabel = name?.takeIf(String::isNotBlank)
        ?: serverUrl.substringAfter("://").substringBefore('/').ifBlank { "MCP" }
    return ToolCapability(
        toolId = mcpConnectionCapabilityId(id),
        group = mcpConnectionCapabilityId(id),
        groupLabel = connectionLabel.replace(" ", "_"),
        displayName = connectionLabel,
        summary = "$toolCount 个已发现工具；作为完整 MCP Connection 提供给当前 Persona",
        availability = ToolAvailability.Available,
        detail = "MCP connection $id",
        riskLevel = ToolRiskLevel.Unknown,
        capabilityKind = ToolCapabilityKind.Unknown,
        requiresApproval = false,
        scope = listOf("persona:$personaId", "connection:$id"),
    )
}

private fun McpEffectiveConnection.toCapability(personaId: String) = ToolCapability(
    toolId = mcpConnectionCapabilityId(connectionId),
    group = mcpConnectionCapabilityId(connectionId),
    groupLabel = (name ?: "MCP").replace(" ", "_"),
    displayName = name ?: "MCP Connection",
    summary = "$toolCount 个已发现工具；作为完整 MCP Connection 提供给当前 Persona",
    availability = ToolAvailability.Available,
    detail = "MCP connection $connectionId",
    riskLevel = ToolRiskLevel.Unknown,
    capabilityKind = ToolCapabilityKind.Unknown,
    requiresApproval = false,
    scope = listOf("persona:$personaId", "connection:$connectionId"),
)
