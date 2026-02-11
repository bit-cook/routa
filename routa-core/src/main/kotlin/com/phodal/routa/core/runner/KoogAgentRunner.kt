package com.phodal.routa.core.runner

import com.phodal.routa.core.config.NamedModelConfig
import com.phodal.routa.core.koog.RoutaAgentFactory
import com.phodal.routa.core.model.AgentRole
import com.phodal.routa.core.tool.AgentTools

/**
 * Agent runner backed by JetBrains Koog AIAgent framework.
 *
 * Creates a Koog AIAgent for each run, using the LLM config from
 * `~/.autodev/config.yaml` or an explicit [modelConfig].
 *
 * Tool calls are handled natively by Koog — when the LLM generates
 * a tool call (e.g., `report_to_parent`), Koog dispatches it to our
 * [SimpleTool] implementations which update the stores.
 */
class KoogAgentRunner(
    private val agentTools: AgentTools,
    private val workspaceId: String,
    private val modelConfig: NamedModelConfig? = null,
) : AgentRunner {

    private val factory by lazy {
        RoutaAgentFactory(agentTools, workspaceId)
    }

    override suspend fun run(role: AgentRole, agentId: String, prompt: String): String {
        // ROUTA only produces text (plans), no tool calls needed — use lower iterations
        // CRAFTER/GATE need tool calls (report_to_parent) — use moderate iterations
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

        return try {
            agent.run(prompt)
        } catch (e: Exception) {
            // If agent hits max iterations, return whatever it produced
            if (e.message?.contains("maxAgentIterations", ignoreCase = true) == true ||
                e.message?.contains("number of steps", ignoreCase = true) == true
            ) {
                "[Agent reached max iterations. Partial output may be available.]"
            } else {
                throw e
            }
        } finally {
            agent.close()
        }
    }
}
