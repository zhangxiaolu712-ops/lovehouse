package fyi.b612.lovehouse.core.selfcheck

/** Structural checks only; they never claim that an undeployed backend or provider smoke passed. */
class PersonaRuntimeSelfCheckContributor(
    private val accountSourceProduction: Boolean,
    private val profileSourceProduction: Boolean,
    private val conversationStoreProduction: Boolean,
    private val capabilityResolverProduction: Boolean,
    private val providerPayloadWired: Boolean,
) : SelfCheckContributor {
    override val contributorId: String = CONTRIBUTOR_ID

    override suspend fun checks(): List<SelfCheckResult> = listOf(
        check("persona.account", "App Account ownership", accountSourceProduction),
        check("persona.profile", "Persona Profile source", profileSourceProduction),
        check("persona.conversation", "Conversation persona_id", conversationStoreProduction),
        check("persona.capabilities", "Persona capability resolver", capabilityResolverProduction),
        check("persona.provider", "Provider Persona payload", providerPayloadWired),
        SelfCheckResult("persona.executor", SelfCheckGroup.ProductionWiring, "App Backend MCP Executor",
            SelfCheckStatus.WARN, "本地 contract 已接线；生产部署与真实工具调用仍待验收"),
    )

    private fun check(id: String, name: String, wired: Boolean) = SelfCheckResult(
        id, SelfCheckGroup.ProductionWiring, name,
        if (wired) SelfCheckStatus.PASS else SelfCheckStatus.FAIL,
        if (wired) "Production source 已注册" else "Persona Runtime production wiring 缺失",
    )

    companion object { const val CONTRIBUTOR_ID = "persona-runtime" }
}
