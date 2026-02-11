package com.phodal.routa.core.provider

import com.phodal.routa.core.model.AgentRole

/**
 * Routes agent execution to the best provider based on capabilities.
 *
 * Replaces the hardcoded `CompositeAgentRunner` which maps roles to runners
 * via `when(role)`. Instead, each provider declares its [ProviderCapabilities],
 * and the router selects the best match at runtime.
 *
 * Inspired by Intent's `resolveProviderCapabilities()` pattern and the
 * provider registry with lazy loading.
 *
 * ## Selection Algorithm
 * 1. Filter providers that satisfy the role's [ProviderRequirements]
 * 2. Among matching providers, pick the one with highest [ProviderCapabilities.priority]
 * 3. If no provider matches, throw [NoSuitableProviderException]
 *
 * ## Usage
 * ```kotlin
 * val router = CapabilityBasedRouter(
 *     KoogAgentProvider(tools, workspaceId),
 *     AcpAgentProvider("codex", config, cwd),
 *     ClaudeAgentProvider(),
 * )
 *
 * // Automatically selects: Koog for ROUTA, ACP/Claude for CRAFTER
 * router.run(AgentRole.CRAFTER, crafterId, prompt)
 * ```
 *
 * ## Dynamic Registration
 * Providers can be added/removed at runtime:
 * ```kotlin
 * router.register(newProvider)
 * router.unregister("Koog LLM")
 * ```
 */
class CapabilityBasedRouter(
    vararg initialProviders: AgentProvider,
) : AgentProvider {

    private val providers = mutableListOf<AgentProvider>().apply {
        addAll(initialProviders)
    }

    // ── Provider Management ──────────────────────────────────────────

    /**
     * Register a new provider. It will be considered for future routing decisions.
     */
    fun register(provider: AgentProvider) {
        providers.add(provider)
    }

    /**
     * Unregister a provider by name.
     */
    fun unregister(providerName: String) {
        providers.removeAll { it.capabilities().name == providerName }
    }

    /**
     * List all registered providers and their capabilities.
     */
    fun listProviders(): List<ProviderCapabilities> {
        return providers.map { it.capabilities() }
    }

    // ── Routing ──────────────────────────────────────────────────────

    /**
     * Select the best provider for a given role.
     *
     * @throws NoSuitableProviderException if no provider satisfies the role's requirements.
     */
    fun selectProvider(role: AgentRole): AgentProvider {
        val requirements = role.requirements()
        val candidates = providers.filter { it.capabilities().satisfies(requirements) }

        if (candidates.isEmpty()) {
            throw NoSuitableProviderException(
                role = role,
                requirements = requirements,
                available = providers.map { it.capabilities() },
            )
        }

        // Pick the provider with the highest priority
        return candidates.maxByOrNull { it.capabilities().priority }!!
    }

    // ── AgentRunner ──────────────────────────────────────────────────

    override suspend fun run(role: AgentRole, agentId: String, prompt: String): String {
        return selectProvider(role).run(role, agentId, prompt)
    }

    // ── AgentProvider ────────────────────────────────────────────────

    override suspend fun runStreaming(
        role: AgentRole,
        agentId: String,
        prompt: String,
        onChunk: (StreamChunk) -> Unit,
    ): String {
        return selectProvider(role).runStreaming(role, agentId, prompt, onChunk)
    }

    override fun isHealthy(agentId: String): Boolean {
        // Check all providers — agent could be running on any of them
        return providers.all { it.isHealthy(agentId) }
    }

    override suspend fun interrupt(agentId: String) {
        // Broadcast interrupt to all providers
        providers.forEach { it.interrupt(agentId) }
    }

    override fun capabilities(): ProviderCapabilities {
        // The router's capabilities are the union of all providers
        return ProviderCapabilities(
            name = "CapabilityBasedRouter (${providers.size} providers)",
            supportsStreaming = providers.any { it.capabilities().supportsStreaming },
            supportsInterrupt = providers.any { it.capabilities().supportsInterrupt },
            supportsHealthCheck = providers.any { it.capabilities().supportsHealthCheck },
            supportsFileEditing = providers.any { it.capabilities().supportsFileEditing },
            supportsTerminal = providers.any { it.capabilities().supportsTerminal },
            supportsToolCalling = providers.any { it.capabilities().supportsToolCalling },
            maxConcurrentAgents = providers.sumOf { it.capabilities().maxConcurrentAgents },
            priority = providers.maxOfOrNull { it.capabilities().priority } ?: 0,
        )
    }

    override suspend fun cleanup(agentId: String) {
        providers.forEach { it.cleanup(agentId) }
    }

    override suspend fun shutdown() {
        providers.forEach { it.shutdown() }
    }
}

// ── Exceptions ──────────────────────────────────────────────────────────

class NoSuitableProviderException(
    val role: AgentRole,
    val requirements: ProviderRequirements,
    val available: List<ProviderCapabilities>,
) : Exception(
    "No provider satisfies requirements for role $role: $requirements. " +
        "Available providers: ${available.map { "${it.name} (${describeGaps(it, requirements)})" }}"
)

private fun describeGaps(caps: ProviderCapabilities, reqs: ProviderRequirements): String {
    val gaps = mutableListOf<String>()
    if (reqs.needsFileEditing && !caps.supportsFileEditing) gaps.add("no file editing")
    if (reqs.needsTerminal && !caps.supportsTerminal) gaps.add("no terminal")
    if (reqs.needsToolCalling && !caps.supportsToolCalling) gaps.add("no tool calling")
    if (reqs.needsStreaming && !caps.supportsStreaming) gaps.add("no streaming")
    return if (gaps.isEmpty()) "OK" else gaps.joinToString(", ")
}
