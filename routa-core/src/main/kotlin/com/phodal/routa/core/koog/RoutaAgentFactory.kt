package com.phodal.routa.core.koog

import ai.koog.agents.core.agent.AIAgent
import ai.koog.prompt.executor.clients.deepseek.DeepSeekLLMClient
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import ai.koog.prompt.executor.llms.all.*
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import com.phodal.routa.core.config.LLMProviderType
import com.phodal.routa.core.config.NamedModelConfig
import com.phodal.routa.core.config.RoutaConfigLoader
import com.phodal.routa.core.model.AgentRole
import com.phodal.routa.core.role.RouteDefinitions
import com.phodal.routa.core.tool.AgentTools

/**
 * Factory for creating Koog [AIAgent] instances configured for Routa roles.
 *
 * Reads LLM config from `~/.autodev/config.yaml` (xiuper-compatible),
 * wires Routa coordination tools into a [ai.koog.agents.core.tools.ToolRegistry],
 * and creates agents with the appropriate system prompts.
 *
 * Usage:
 * ```kotlin
 * val factory = RoutaAgentFactory(routa.tools, "my-workspace")
 * val routaAgent = factory.createAgent(AgentRole.ROUTA)
 * val result = routaAgent.run("Implement user authentication for the API")
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
     * Create a SingleLLMPromptExecutor from the config.
     */
    private fun createExecutor(config: NamedModelConfig): SingleLLMPromptExecutor {
        val provider = LLMProviderType.fromString(config.provider)
            ?: throw IllegalArgumentException("Unknown provider: ${config.provider}")

        return when (provider) {
            LLMProviderType.OPENAI -> simpleOpenAIExecutor(config.apiKey)
            LLMProviderType.ANTHROPIC -> simpleAnthropicExecutor(config.apiKey)
            LLMProviderType.GOOGLE -> simpleGoogleAIExecutor(config.apiKey)
            LLMProviderType.DEEPSEEK -> SingleLLMPromptExecutor(DeepSeekLLMClient(config.apiKey))
            LLMProviderType.OLLAMA -> simpleOllamaAIExecutor(
                baseUrl = config.baseUrl.ifEmpty { "http://localhost:11434" }
            )
            LLMProviderType.OPENROUTER -> simpleOpenRouterExecutor(config.apiKey)
        }
    }

    /**
     * Create an LLModel from the config.
     */
    private fun createModel(config: NamedModelConfig): LLModel {
        val provider = LLMProviderType.fromString(config.provider)
            ?: LLMProviderType.OPENAI

        val llmProvider = when (provider) {
            LLMProviderType.OPENAI -> LLMProvider.OpenAI
            LLMProviderType.ANTHROPIC -> LLMProvider.Anthropic
            LLMProviderType.GOOGLE -> LLMProvider.Google
            LLMProviderType.DEEPSEEK -> LLMProvider.DeepSeek
            LLMProviderType.OLLAMA -> LLMProvider.Ollama
            LLMProviderType.OPENROUTER -> LLMProvider.OpenRouter
        }

        // Context/output lengths per provider
        val (contextLength, maxOutputTokens) = when (provider) {
            LLMProviderType.DEEPSEEK -> when {
                config.model.contains("reasoner") -> 64_000L to 64_000L
                else -> 64_000L to 8_000L
            }
            LLMProviderType.ANTHROPIC -> 200_000L to 8_192L
            LLMProviderType.GOOGLE -> 1_000_000L to 8_192L
            else -> config.maxTokens.toLong() to 4_096L
        }

        return LLModel(
            provider = llmProvider,
            id = config.model,
            capabilities = listOf(
                LLMCapability.Completion,
                LLMCapability.Temperature,
                LLMCapability.Tools,
                LLMCapability.ToolChoice,
            ),
            contextLength = contextLength,
            maxOutputTokens = maxOutputTokens,
        )
    }
}
