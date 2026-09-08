package fyi.b612.lovehouse.feature.settings

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class CapabilityRegistryState(
    val capabilities: List<ToolCapability> = emptyList(),
    val enabledToolIds: Set<String> = emptySet(),
    val loading: Boolean = true,
    val error: String? = null,
) {
    val enabledCapabilities: List<ToolCapability>
        get() = capabilities.filter { it.availability == ToolAvailability.Available && it.toolId in enabledToolIds }
}

interface CapabilityRegistry {
    val state: StateFlow<CapabilityRegistryState>
    fun refresh()
    fun setEnabled(toolId: String, enabled: Boolean)
    suspend fun test(toolId: String): ToolTestResult
    fun requestedToolIds(): Set<String>
}

class AndroidCapabilityRegistry(
    private val repository: ToolCenterRepository,
    private val profiles: ToolProfilePreferenceStore,
    private val personaId: String,
    private val threadId: String,
    private val scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
) : CapabilityRegistry {
    private val mutableState = MutableStateFlow(
        CapabilityRegistryState(enabledToolIds = profiles.profile(personaId, threadId).preferredToolIds),
    )
    override val state: StateFlow<CapabilityRegistryState> = mutableState.asStateFlow()

    init { refresh() }

    override fun refresh() {
        mutableState.value = mutableState.value.copy(loading = true, error = null)
        scope.launch {
            runCatching { repository.capabilities() }
                .onSuccess { tools ->
                    mutableState.value = CapabilityRegistryState(
                        capabilities = tools,
                        enabledToolIds = profiles.profile(personaId, threadId).preferredToolIds,
                        loading = false,
                    )
                }
                .onFailure { error -> mutableState.value = mutableState.value.copy(loading = false, error = error.message ?: "能力状态读取失败") }
        }
    }

    override fun setEnabled(toolId: String, enabled: Boolean) {
        profiles.setPreferred(personaId, threadId, toolId, enabled)
        mutableState.value = mutableState.value.copy(
            enabledToolIds = profiles.profile(personaId, threadId).preferredToolIds,
        )
    }

    override suspend fun test(toolId: String): ToolTestResult = withContext(Dispatchers.IO) { repository.testTool(toolId) }

    override fun requestedToolIds(): Set<String> = mutableState.value.enabledCapabilities.mapTo(linkedSetOf()) { it.toolId }
}

internal fun resolveEnabledCapabilities(
    capabilities: List<ToolCapability>,
    enabledToolIds: Set<String>,
): List<ToolCapability> = capabilities.filter {
    it.availability == ToolAvailability.Available && it.toolId in enabledToolIds
}
