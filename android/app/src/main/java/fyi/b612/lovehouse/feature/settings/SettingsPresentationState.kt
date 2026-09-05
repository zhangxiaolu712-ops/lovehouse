package fyi.b612.lovehouse.feature.settings

import java.time.ZoneId

/** Presentation-only state. No remote connection or persistence is implied. */
internal data class GlobalPlace(val id: String, val name: String, val zoneId: String) {
    init {
        require(name.isNotBlank())
        ZoneId.of(zoneId)
    }
}

internal data class GlobalLocationState(
    val homes: List<GlobalPlace> = listOf(GlobalPlace("shanghai", "上海", "Asia/Shanghai")),
    val homeId: String = homes.first().id,
    val travel: GlobalPlace? = null,
) {
    init {
        require(homes.isNotEmpty() && homes.size <= 5)
        require(homes.map { it.id }.distinct().size == homes.size)
        require(homes.any { it.id == homeId })
    }
    val current: GlobalPlace get() = travel ?: homes.first { it.id == homeId }
    fun addHome(place: GlobalPlace): GlobalLocationState =
        if (homes.size >= 5 || homes.any { it.id == place.id }) this else copy(homes = homes + place)
    fun selectHome(id: String): GlobalLocationState =
        if (homes.any { it.id == id }) copy(homeId = id, travel = null) else this
    fun confirmTravel(place: GlobalPlace): GlobalLocationState = copy(travel = place)
    fun returnHome(): GlobalLocationState = copy(travel = null)
}

internal data class PersonaVoiceDraft(
    val provider: String = "",
    val voiceId: String = "",
    val enabled: Boolean = false,
)

internal enum class HouseHealth { Online, Offline, Degraded, Unknown }

internal data class HouseServiceStatus(
    val name: String,
    val category: String,
    val health: HouseHealth = HouseHealth.Unknown,
    val version: String? = null,
    val release: String? = null,
    val latencyMillis: Long? = null,
    val lastHeartbeat: String? = null,
    val runtime: String? = null,
    val model: String? = null,
)

internal val houseServicePlaceholders = listOf(
    HouseServiceStatus("Android", "小屋核心"),
    HouseServiceStatus("Web", "小屋核心"),
    HouseServiceStatus("Bridge", "小屋核心"),
    HouseServiceStatus("Memory V2", "小屋核心"),
    HouseServiceStatus("Codex Runtime", "AI Runtime"),
    HouseServiceStatus("Ollama", "AI Runtime"),
    HouseServiceStatus("VPS", "网络与设备"),
    HouseServiceStatus("Tailscale", "网络与设备"),
    HouseServiceStatus("Database / Supabase", "云服务"),
    HouseServiceStatus("GitHub", "工程服务"),
    HouseServiceStatus("MCP / Capability", "外部连接"),
)
