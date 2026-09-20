package fyi.b612.lovehouse.core.selfcheck

enum class SelfCheckStatus {
    PASS,
    WARN,
    FAIL,
    PARTIAL,
    N_A,
    NOT_BUILT,
}

enum class SelfCheckGroup(val label: String) {
    NativeCapability("Native Capability"),
    Attachment("Attachment"),
    ProductionWiring("Production Wiring"),
    ToolCenter("Tool Center"),
    Account("Account"),
    Build("Build"),
}

data class SelfCheckResult(
    val id: String,
    val group: SelfCheckGroup,
    val name: String,
    val status: SelfCheckStatus,
    val reason: String,
    val detail: String? = null,
)

data class SelfCheckSummary(
    val checked: Int,
    val counts: Map<SelfCheckStatus, Int>,
    val overall: SelfCheckStatus,
)

data class BuildIdentity(
    val versionName: String,
    val versionCode: Long,
    val buildType: String,
    val gitSha: String,
    val buildTime: String,
) {
    val shortSha: String get() = gitSha.take(8)
}

data class DeploymentSelfCheckReport(
    val build: BuildIdentity,
    val results: List<SelfCheckResult>,
) {
    val summary: SelfCheckSummary = summarizeSelfChecks(results)
}

interface SelfCheckContributor {
    val contributorId: String
    suspend fun checks(): List<SelfCheckResult>
}

class SelfCheckRegistry(contributors: Collection<SelfCheckContributor>) {
    private val contributors = contributors.toList().also { values ->
        require(values.map { it.contributorId }.distinct().size == values.size) {
            "Self-Check contributor id must be unique"
        }
    }

    suspend fun run(): List<SelfCheckResult> = contributors.flatMap { contributor ->
        runCatching { contributor.checks() }.getOrElse { error ->
            listOf(
                SelfCheckResult(
                    id = "contributor.${contributor.contributorId}",
                    group = SelfCheckGroup.ProductionWiring,
                    name = contributor.contributorId,
                    status = SelfCheckStatus.FAIL,
                    reason = "自检 contributor 执行失败",
                    detail = error::class.java.simpleName,
                ),
            )
        }
    }.also { results ->
        require(results.map { it.id }.distinct().size == results.size) {
            "Self-Check result id must be unique"
        }
    }
}

class DeploymentSelfCheckRunner(
    private val registry: SelfCheckRegistry,
    val buildIdentity: BuildIdentity,
) {
    suspend fun run(): DeploymentSelfCheckReport = DeploymentSelfCheckReport(
        build = buildIdentity,
        results = registry.run(),
    )
}

fun summarizeSelfChecks(results: List<SelfCheckResult>): SelfCheckSummary {
    val counts = SelfCheckStatus.entries.associateWith { status -> results.count { it.status == status } }
    val overall = when {
        counts.getValue(SelfCheckStatus.FAIL) > 0 -> SelfCheckStatus.FAIL
        counts.getValue(SelfCheckStatus.WARN) > 0 -> SelfCheckStatus.WARN
        counts.getValue(SelfCheckStatus.PARTIAL) > 0 -> SelfCheckStatus.PARTIAL
        else -> SelfCheckStatus.PASS
    }
    return SelfCheckSummary(results.size, counts, overall)
}

fun DeploymentSelfCheckReport.safeText(): String {
    val lines = buildList {
        add("LoveHouse Deployment Self-Check")
        add("build=${build.versionName} (${build.versionCode}) ${build.buildType}")
        add("git=${build.gitSha}")
        add("built=${build.buildTime}")
        add("overall=${summary.overall} checked=${summary.checked}")
        SelfCheckGroup.entries.forEach { group ->
            val groupResults = results.filter { it.group == group }
            if (groupResults.isNotEmpty()) {
                add("[${group.label}]")
                groupResults.forEach { result ->
                    add("${result.status} ${result.name}: ${result.reason}")
                    result.detail?.takeIf(String::isNotBlank)?.let { add("  $it") }
                }
            }
        }
    }
    return sanitizeSelfCheckReport(lines.joinToString("\n"))
}

internal fun sanitizeSelfCheckReport(value: String): String = value
    .replace(Regex("(?i)\\bauthorization\\s*[:=]\\s*(?:bearer\\s+)?[^\\s]+"), "Authorization=[REDACTED]")
    .replace(Regex("(?i)\\b(?:access_token|refresh_token|api[_ -]?key|cookie)\\s*[:=]\\s*[^\\s]+")) { match ->
        match.value.substringBefore(':').substringBefore('=').trim() + "=[REDACTED]"
    }
    .replace(Regex("\\beyJ[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+\\b"), "[REDACTED_JWT]")
    .replace(Regex("(https?://[^\\s?]+)\\?[^\\s]+"), "$1?[REDACTED_QUERY]")
    .replace(Regex("(?i)\\b(?:latitude|longitude|lat|lng)\\s*[:=]\\s*-?\\d+(?:\\.\\d+)?")) { match ->
        match.value.substringBefore(':').substringBefore('=').trim() + "=[REDACTED_LOCATION]"
    }
