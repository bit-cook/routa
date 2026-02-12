package com.phodal.routa.core.koog

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolRegistry
import com.phodal.routa.core.tool.AgentTools

/**
 * Factory for building a Koog [ToolRegistry] containing all Routa coordination tools.
 *
 * Usage with a Koog AIAgent:
 * ```kotlin
 * val toolRegistry = RoutaToolRegistry.create(routa.tools, "my-workspace")
 *
 * val agent = AIAgent(
 *     promptExecutor = executor,
 *     systemPrompt = RouteDefinitions.ROUTA.systemPrompt,
 *     llmModel = model,
 *     toolRegistry = toolRegistry
 * )
 * ```
 */
object RoutaToolRegistry {

    /**
     * Create a [ToolRegistry] with all 12 Routa coordination tools.
     *
     * @param agentTools The underlying AgentTools implementation.
     * @param workspaceId Default workspace ID for tools that need it.
     */
    fun create(agentTools: AgentTools, workspaceId: String): ToolRegistry {
        return ToolRegistry {
            for (tool in createToolsList(agentTools, workspaceId)) {
                tool(tool)
            }
        }
    }

    /**
     * Create a list of all 12 Routa coordination tools as [SimpleTool] instances.
     *
     * Unlike [create], this returns raw tool instances that can be used
     * outside a [ToolRegistry], e.g., for schema generation or text-based execution
     * via [TextBasedToolExecutor].
     *
     * @param agentTools The underlying AgentTools implementation.
     * @param workspaceId Default workspace ID for tools that need it.
     */
    fun createToolsList(agentTools: AgentTools, workspaceId: String): List<SimpleTool<*>> {
        return listOf(
            // Core coordination tools
            ListAgentsTool(agentTools, workspaceId),
            ReadAgentConversationTool(agentTools),
            CreateAgentTool(agentTools, workspaceId),
            DelegateTaskTool(agentTools),
            MessageAgentTool(agentTools),
            ReportToParentTool(agentTools),
            // Task-agent lifecycle tools
            WakeOrCreateTaskAgentTool(agentTools, workspaceId),
            SendMessageToTaskAgentTool(agentTools),
            GetAgentStatusTool(agentTools),
            GetAgentSummaryTool(agentTools),
            // Event subscription tools
            SubscribeToEventsTool(agentTools),
            UnsubscribeFromEventsTool(agentTools),
        )
    }
}
