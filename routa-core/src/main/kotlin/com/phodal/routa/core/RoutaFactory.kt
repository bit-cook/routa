package com.phodal.routa.core

import com.phodal.routa.core.config.AcpAgentConfig
import com.phodal.routa.core.config.NamedModelConfig
import com.phodal.routa.core.coordinator.AgentExecutionContext
import com.phodal.routa.core.coordinator.RoutaCoordinator
import com.phodal.routa.core.event.EventBus
import com.phodal.routa.core.provider.*
import com.phodal.routa.core.store.*
import kotlinx.coroutines.CoroutineScope

/**
 * Factory for creating a fully wired Routa multi-agent system.
 *
 * Provides default in-memory implementations that work out of the box.
 * For production use, replace stores with persistent implementations.
 *
 * ## Usage (Basic — in-memory stores)
 * ```kotlin
 * val routa = RoutaFactory.createInMemory()
 * val routaAgentId = routa.coordinator.initialize("my-workspace")
 * ```
 *
 * ## Usage (With providers — capability-based routing)
 * ```kotlin
 * val routa = RoutaFactory.createInMemory()
 * val provider = RoutaFactory.createProvider(
 *     system = routa,
 *     workspaceId = "my-workspace",
 *     cwd = "/path/to/project",
 *     acpConfig = AcpAgentConfig(command = "codex", args = listOf("--full-auto")),
 * )
 * val orchestrator = RoutaOrchestrator(routa, provider, "my-workspace")
 * ```
 */
object RoutaFactory {

    /**
     * Create a Routa system with in-memory stores.
     *
     * Suitable for testing and single-process scenarios.
     */
    fun createInMemory(scope: CoroutineScope? = null): RoutaSystem {
        val eventBus = EventBus()
        val context = AgentExecutionContext(
            agentStore = InMemoryAgentStore(),
            conversationStore = InMemoryConversationStore(),
            taskStore = InMemoryTaskStore(),
            eventBus = eventBus,
        )
        val coordinator = if (scope != null) {
            RoutaCoordinator(context, scope)
        } else {
            RoutaCoordinator(context)
        }
        return RoutaSystem(context, coordinator)
    }

    /**
     * Create a Routa system with custom stores.
     *
     * Use this for production deployments with file-based or database storage.
     */
    fun create(
        agentStore: AgentStore,
        conversationStore: ConversationStore,
        taskStore: TaskStore,
        scope: CoroutineScope? = null,
    ): RoutaSystem {
        val eventBus = EventBus()
        val context = AgentExecutionContext(
            agentStore = agentStore,
            conversationStore = conversationStore,
            taskStore = taskStore,
            eventBus = eventBus,
        )
        val coordinator = if (scope != null) {
            RoutaCoordinator(context, scope)
        } else {
            RoutaCoordinator(context)
        }
        return RoutaSystem(context, coordinator)
    }

    /**
     * Create a capability-based provider with automatic routing.
     *
     * Sets up:
     * - [KoogAgentProvider] for ROUTA (planning via LLM with tool calling)
     * - [AcpAgentProvider] for CRAFTER/GATE (real coding agent via ACP)
     * - [ResilientAgentProvider] wrapper with circuit breaker + error recovery
     * - [CapabilityBasedRouter] for dynamic role → provider routing
     *
     * @param system The Routa system (stores, coordinator, tools).
     * @param workspaceId The workspace to operate in.
     * @param cwd Working directory for ACP/Claude agents.
     * @param acpConfig ACP agent configuration. If null, Koog is used for all roles.
     * @param acpAgentKey ACP agent key (e.g., "codex", "claude-code").
     * @param claudePath Path to Claude CLI binary. If provided, adds Claude as a provider.
     * @param modelConfig Optional Koog model configuration override.
     * @param resilient Whether to wrap providers with circuit breaker + recovery.
     */
    fun createProvider(
        system: RoutaSystem,
        workspaceId: String,
        cwd: String = ".",
        acpConfig: AcpAgentConfig? = null,
        acpAgentKey: String = "codex",
        claudePath: String? = null,
        modelConfig: NamedModelConfig? = null,
        resilient: Boolean = true,
    ): AgentProvider {
        val providers = mutableListOf<AgentProvider>()

        // Koog for planning (ROUTA role)
        val koog: AgentProvider = KoogAgentProvider(
            agentTools = system.tools,
            workspaceId = workspaceId,
            modelConfig = modelConfig,
        )
        providers.add(if (resilient) {
            ResilientAgentProvider(koog, system.context.conversationStore)
        } else koog)

        // ACP for implementation (CRAFTER/GATE roles)
        if (acpConfig != null) {
            val acp: AgentProvider = AcpAgentProvider(
                agentKey = acpAgentKey,
                config = acpConfig,
                cwd = cwd,
            )
            providers.add(if (resilient) {
                ResilientAgentProvider(acp, system.context.conversationStore)
            } else acp)
        }

        // Claude CLI as an alternative implementation provider
        if (claudePath != null) {
            val claude: AgentProvider = ClaudeAgentProvider(
                claudePath = claudePath,
                cwd = cwd,
            )
            providers.add(if (resilient) {
                ResilientAgentProvider(claude, system.context.conversationStore)
            } else claude)
        }

        return CapabilityBasedRouter(*providers.toTypedArray())
    }
}

/**
 * A fully wired Routa multi-agent system.
 */
data class RoutaSystem(
    /** The execution context containing all stores and tools. */
    val context: AgentExecutionContext,

    /** The main coordinator that orchestrates the Routa→Crafter→Gate workflow. */
    val coordinator: RoutaCoordinator,
) {
    /** Shortcut to the agent tools. */
    val tools get() = context.agentTools

    /** Shortcut to the event bus. */
    val eventBus get() = context.eventBus
}
