package com.phodal.routa.core.koog

import ai.koog.agents.core.agent.AIAgent
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import ai.koog.prompt.llm.LLModel
import com.phodal.routa.core.config.LLMProviderType
import com.phodal.routa.core.config.NamedModelConfig
import com.phodal.routa.core.config.RoutaConfigLoader
import com.phodal.routa.core.llm.ExecutorFactory
import com.phodal.routa.core.llm.ModelRegistry
import com.phodal.routa.core.model.AgentRole
import com.phodal.routa.core.role.RouteDefinitions
import com.phodal.routa.core.tool.AgentTools

/**
 * Holds executor and model for streaming LLM calls.
 */
data class LLMComponents(
    val executor: SingleLLMPromptExecutor,
    val model: LLModel,
    val systemPrompt: String,
)

/**
 * Factory for creating Koog [AIAgent] instances configured for Routa roles.
 *
 * Reads LLM config from `~/.autodev/config.yaml` (xiuper-compatible),
 * wires Routa coordination tools into a [ai.koog.agents.core.tools.ToolRegistry],
 * and creates agents with the appropriate system prompts.
 *
 * Delegates to [ModelRegistry] for model definitions and [ExecutorFactory] for
 * executor creation.
 *
 * Usage:
 * ```kotlin
 * val factory = RoutaAgentFactory(routa.tools, "my-workspace")
 * val routaAgent = factory.createAgent(AgentRole.ROUTA)
 * val result = routaAgent.run("Implement user authentication for the API")
 * ```
 *
 * For streaming, use [createLLMComponents] instead:
 * ```kotlin
 * val components = factory.createLLMComponents(AgentRole.ROUTA)
 * components.executor.executeStreaming(prompt, components.model).collect { frame -> ... }
 * ```
 */
class RoutaAgentFactory(
    private val agentTools: AgentTools,
    private val workspaceId: String,
) {

    /**
     * Create a Koog AIAgent for the given role, using config from `~/.autodev/config.yaml`.
     *
     * @param role The agent role (ROUTA, CRAFTER, or GATE).
     * @param modelConfig Optional explicit model config (overrides config.yaml).
     * @return A configured Koog AIAgent<String, String>.
     * @throws IllegalStateException if no valid config is found.
     */
    fun createAgent(
        role: AgentRole,
        modelConfig: NamedModelConfig? = null,
        maxIterations: Int = 15,
        systemPromptOverride: String? = null,
    ): AIAgent<String, String> {
        val config = modelConfig ?: RoutaConfigLoader.getActiveModelConfig()
            ?: throw IllegalStateException(
                "No active model config found. Please configure ~/.autodev/config.yaml " +
                    "(path: ${RoutaConfigLoader.getConfigPath()})"
            )

        val executor = createExecutor(config)
        val model = createModel(config)
        val toolRegistry = RoutaToolRegistry.create(agentTools, workspaceId)
        val roleDefinition = RouteDefinitions.forRole(role)

        return AIAgent(
            promptExecutor = executor,
            llmModel = model,
            systemPrompt = systemPromptOverride ?: roleDefinition.systemPrompt,
            toolRegistry = toolRegistry,
            maxIterations = maxIterations,
        )
    }

    /**
     * Create LLM components for streaming execution.
     *
     * @param role The agent role (for selecting system prompt).
     * @param modelConfig Optional explicit model config (overrides config.yaml).
     * @return LLMComponents containing executor, model, and system prompt.
     */
    fun createLLMComponents(
        role: AgentRole,
        modelConfig: NamedModelConfig? = null,
        systemPromptOverride: String? = null,
    ): LLMComponents {
        val config = modelConfig ?: RoutaConfigLoader.getActiveModelConfig()
            ?: throw IllegalStateException(
                "No active model config found. Please configure ~/.autodev/config.yaml " +
                    "(path: ${RoutaConfigLoader.getConfigPath()})"
            )

        val executor = createExecutor(config)
        val model = createModel(config)
        val roleDefinition = RouteDefinitions.forRole(role)

        return LLMComponents(
            executor = executor,
            model = model,
            systemPrompt = systemPromptOverride ?: roleDefinition.systemPrompt,
        )
    }

    companion object {

        /**
         * Create an LLModel from a NamedModelConfig.
         *
         * Tries the predefined model definitions first (via [ModelRegistry]),
         * then falls back to a generic model with sensible defaults.
         */
        fun createModel(config: NamedModelConfig): LLModel {
            val provider = LLMProviderType.fromString(config.provider)
                ?: return ModelRegistry.createGenericModel(LLMProviderType.OPENAI, config.model)

            return ModelRegistry.createModel(provider, config.model)
                ?: ModelRegistry.createGenericModel(provider, config.model)
        }

        /**
         * Create a SingleLLMPromptExecutor from the config.
         *
         * Delegates to [ExecutorFactory] which handles all provider types
         * including registered extensible providers (e.g. GitHub Copilot).
         */
        fun createExecutor(config: NamedModelConfig): SingleLLMPromptExecutor {
            return ExecutorFactory.create(config)
        }

        /**
         * 获取指定 Provider 支持的所有模型名称列表
         */
        fun getAvailableModels(provider: LLMProviderType): List<String> {
            return ModelRegistry.getAvailableModels(provider)
        }

        /**
         * 获取指定 Provider 的默认 baseUrl
         */
        fun getDefaultBaseUrl(provider: LLMProviderType): String {
            return ModelRegistry.getDefaultBaseUrl(provider)
        }

        /**
         * 根据 Provider 和模型名称创建 LLModel 对象
         * Returns null if the model is not in the predefined model list.
         */
        fun createModelByProvider(provider: LLMProviderType, modelName: String): LLModel? {
            return ModelRegistry.createModel(provider, modelName)
        }

        /**
         * 创建通用模型（当模型不在预定义列表中时使用）
         */
        fun createGenericModel(
            provider: LLMProviderType,
            modelName: String,
            contextLength: Long = 128000L,
        ): LLModel {
            return ModelRegistry.createGenericModel(provider, modelName, contextLength)
        }
    }
}
