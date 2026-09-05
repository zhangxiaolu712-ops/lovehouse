package fyi.b612.lovehouse.feature.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class ToolCenterViewModel(
    private val repository: ToolCenterRepository,
    private val profiles: ToolProfilePreferenceStore,
    private val personaId: String,
    private val threadId: String,
) : ViewModel() {
    private val mutableState = MutableStateFlow<ToolCenterUiState>(ToolCenterUiState.Loading)
    val state: StateFlow<ToolCenterUiState> = mutableState.asStateFlow()
    private val mutablePreferred = MutableStateFlow(profiles.profile(personaId, threadId).preferredToolIds)
    val preferred: StateFlow<Set<String>> = mutablePreferred.asStateFlow()
    private val mutableTests = MutableStateFlow<Map<String, ToolTestResult>>(emptyMap())
    val tests: StateFlow<Map<String, ToolTestResult>> = mutableTests.asStateFlow()

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        mutableState.value = ToolCenterUiState.Loading
        mutableState.value = runCatching { withContext(Dispatchers.IO) { repository.capabilities() } }
            .fold({ ToolCenterUiState.Ready(it) }, { ToolCenterUiState.Error(it.message ?: "Tool Center 连接失败") })
    }

    fun setEnabled(toolId: String, enabled: Boolean) {
        profiles.setPreferred(personaId, threadId, toolId, enabled)
        mutablePreferred.value = profiles.profile(personaId, threadId).preferredToolIds
    }

    fun test(toolId: String) = viewModelScope.launch {
        mutableTests.value = mutableTests.value - toolId
        val result = runCatching { withContext(Dispatchers.IO) { repository.testTool(toolId) } }
            .getOrElse { ToolTestResult(toolId, false, it.message ?: "工具测试失败") }
        mutableTests.value = mutableTests.value + (toolId to result)
    }

    companion object {
        fun factory(repository: ToolCenterRepository, profiles: ToolProfilePreferenceStore, personaId: String, threadId: String) =
            object : ViewModelProvider.Factory {
                @Suppress("UNCHECKED_CAST")
                override fun <T : ViewModel> create(modelClass: Class<T>): T =
                    ToolCenterViewModel(repository, profiles, personaId, threadId) as T
            }
    }
}
