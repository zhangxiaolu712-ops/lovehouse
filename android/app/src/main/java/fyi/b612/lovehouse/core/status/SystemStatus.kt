package fyi.b612.lovehouse.core.status

import fyi.b612.lovehouse.BuildConfig
import fyi.b612.lovehouse.core.permissions.CapabilityPermissionStatus
import fyi.b612.lovehouse.core.permissions.PermissionStatusProvider
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class SystemReadiness(val label: String) {
    Ready("Ready"),
    Planned("Planned"),
    Unavailable("Unavailable"),
}

data class SystemStatus(
    val appVersion: String,
    val navigation: SystemReadiness,
    val localStorage: SystemReadiness,
    val backend: SystemReadiness,
    val permissions: List<CapabilityPermissionStatus>,
)

interface SystemStatusProvider {
    val status: StateFlow<SystemStatus>
    fun refresh()
}

class DefaultSystemStatusProvider(
    private val permissionStatusProvider: PermissionStatusProvider,
) : SystemStatusProvider {
    private val mutableStatus = MutableStateFlow(snapshot())
    override val status: StateFlow<SystemStatus> = mutableStatus.asStateFlow()

    override fun refresh() {
        permissionStatusProvider.refresh()
        mutableStatus.value = snapshot()
    }

    private fun snapshot() = SystemStatus(
        appVersion = BuildConfig.VERSION_NAME,
        navigation = SystemReadiness.Ready,
        localStorage = SystemReadiness.Ready,
        backend = SystemReadiness.Planned,
        permissions = permissionStatusProvider.statuses.value,
    )
}
