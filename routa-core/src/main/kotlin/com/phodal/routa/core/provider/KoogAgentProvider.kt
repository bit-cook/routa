package com.phodal.routa.core.provider

import com.phodal.routa.core.config.NamedModelConfig
import com.phodal.routa.core.koog.RoutaAgentFactory
import com.phodal.routa.core.model.AgentRole
import com.phodal.routa.core.tool.AgentTools
import java.util.concurrent.ConcurrentHashMap

/**
 * Koog-based agent provider for LLM planning and tool calling.
 *
 * Compared to the legacy [com.phodal.routa.core.runner.KoogAgentRunner], this adds:
 * - **Health check**: always true (stateless LLM calls)
 * - **Capabilities**: declares tool-calling support for ROUTA role
 * - **Agent tracking**: tracks running agents for observability
 *
 * Koog agents are stateless (each run creates a fresh AIAgent), so
 * interrupt/cleanup are lightweight.
 *
 * Best suited for:
 * - **ROUTA**: planning tasks via LLM with tool calls (create_agent, delegate, etc.)
 * - **GATE**: verification via LLM when no terminal access is needed
 *
 * @see AcpAgentProvider for implementation tasks requiring file editing.
 */
class KoogAgentProvider(
    private val agentTools: AgentTools,
    private val workspaceId: String,
    private val modelConfig: NamedModelConfig? = null,
) : AgentProvider {

    private val factory by lazy {
        RoutaAgentFactory(agentTools, workspaceId)
    }

    // Track active agents for isHealthy / interrupt
    private val activeAgents = ConcurrentHashMap<String, RunningAgent>()

    private data class RunningAgent(
        val role: AgentRole,
        @Volatile var cancelled: Boolean = false,
    )

    // ── AgentRunner (backward compat) ────────────────────────────────

    override suspend fun run(role: AgentRole, agentId: String, prompt: String): String {
        val maxIterations = when (role) {
            AgentRole.ROUTA -> 5
            AgentRole.CRAFTER -> 10
            AgentRole.GATE -> 10
        }

        val agent = factory.createAgent(
            role = role,
            modelConfig = modelConfig,
            maxIterations = maxIterations,
        )

        activeAgents[agentId] = RunningAgent(role)

        return try {
            agent.run(prompt)
        } catch (e: Exception) {
            // If agent hits max iterations, return partial output message
            if (e.message?.contains("maxAgentIterations", ignoreCase = true) == true ||
                e.message?.contains("number of steps", ignoreCase = true) == true
            ) {
                "[Agent reached max iterations. Partial output may be available.]"
            } else {
                throw e
            }
        } finally {
            agent.close()
            activeAgents.remove(agentId)
        }
    }

    // ── AgentProvider: Health Check ──────────────────────────────────

    override fun isHealthy(agentId: String): Boolean {
        // Koog agents are stateless — if they're in activeAgents, they're running.
        // If not, they've either completed or were never started.
        val agent = activeAgents[agentId] ?: return true // Not running = "healthy" (idle)
        return !agent.cancelled
    }

    // ── AgentProvider: Interrupt ─────────────────────────────────────

    override suspend fun interrupt(agentId: String) {
        // Koog AIAgent doesn't support mid-run cancellation natively.
        // Mark as cancelled so isHealthy returns false, which the coordinator
        // can use to avoid waiting for this agent.
        activeAgents[agentId]?.cancelled = true
    }

    // ── AgentProvider: Capabilities ──────────────────────────────────

    override fun capabilities(): ProviderCapabilities = ProviderCapabilities(
        name = "Koog LLM",
        supportsStreaming = false,
        supportsInterrupt = false, // Koog doesn't support mid-run cancel
        supportsHealthCheck = false,
        supportsFileEditing = false, // LLM can't edit files directly
        supportsTerminal = false,
        supportsToolCalling = true, // Koog handles function calling natively
        maxConcurrentAgents = 10, // LLM calls are stateless, can run many
        priority = 5,
    )

    // ── AgentProvider: Cleanup ───────────────────────────────────────

    override suspend fun cleanup(agentId: String) {
        activeAgents.remove(agentId)
    }

    override suspend fun shutdown() {
        activeAgents.clear()
    }
}
