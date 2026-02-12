package com.phodal.routa.core.llm

import ai.koog.prompt.executor.clients.deepseek.DeepSeekLLMClient
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import ai.koog.prompt.executor.llms.all.*
import com.phodal.routa.core.config.LLMProviderType
import com.phodal.routa.core.config.NamedModelConfig
import com.phodal.routa.core.llm.clients.CustomOpenAILLMClient
import com.phodal.routa.core.llm.provider.LLMClientRegistry

/**
 * Executor 工厂 - 负责根据配置创建合适的 LLM Executor
 *
 * Adapted from xiuper's ExecutorFactory.
 *
 * 职责：
 * 1. 根据 Provider 类型创建对应的 Executor
 * 2. 处理不同 Provider 的初始化逻辑
 * 3. 统一 Executor 创建接口
 * 4. 支持通过 LLMClientRegistry 注册的扩展 Provider
 */
object ExecutorFactory {

    /**
     * 根据模型配置创建 Executor (blocking)
     */
    fun create(config: NamedModelConfig): SingleLLMPromptExecutor {
        val provider = LLMProviderType.fromString(config.provider)
            ?: throw IllegalArgumentException("Unknown provider: ${config.provider}")

        // Check registry first for extensible providers (e.g. GitHub Copilot)
        val registryProvider = LLMClientRegistry.getProvider(provider)
        if (registryProvider != null) {
            return kotlinx.coroutines.runBlocking { registryProvider.createExecutor(config) }
                ?: throw IllegalStateException(
                    "Failed to create executor for ${provider.displayName}. " +
                        "Provider is registered but returned null."
                )
        }

        return when (provider) {
            LLMProviderType.OPENAI -> simpleOpenAIExecutor(config.apiKey)
            LLMProviderType.ANTHROPIC -> simpleAnthropicExecutor(config.apiKey)
            LLMProviderType.GOOGLE -> simpleGoogleAIExecutor(config.apiKey)
            LLMProviderType.DEEPSEEK -> SingleLLMPromptExecutor(DeepSeekLLMClient(config.apiKey))
            LLMProviderType.OLLAMA -> simpleOllamaAIExecutor(
                baseUrl = config.baseUrl.ifEmpty { "http://localhost:11434" }
            )
            LLMProviderType.OPENROUTER -> simpleOpenRouterExecutor(config.apiKey)
            LLMProviderType.GLM -> createCustomOpenAI(config, LLMProviderType.GLM)
            LLMProviderType.QWEN -> createCustomOpenAI(config, LLMProviderType.QWEN)
            LLMProviderType.KIMI -> createCustomOpenAI(config, LLMProviderType.KIMI)
            LLMProviderType.MINIMAX -> createCustomOpenAI(config, LLMProviderType.MINIMAX)
            LLMProviderType.CUSTOM_OPENAI_BASE -> {
                require(config.baseUrl.isNotEmpty()) { "baseUrl is required for custom OpenAI provider" }
                SingleLLMPromptExecutor(
                    CustomOpenAILLMClient(apiKey = config.apiKey, baseUrl = config.baseUrl)
                )
            }
            LLMProviderType.GITHUB_COPILOT -> throw IllegalStateException(
                "GitHub Copilot is not available. Register GithubCopilotClientProvider first: " +
                    "LLMClientRegistry.register(GithubCopilotClientProvider())"
            )
        }
    }

    /**
     * 根据模型配置异步创建 Executor
     */
    suspend fun createAsync(config: NamedModelConfig): SingleLLMPromptExecutor {
        val provider = LLMProviderType.fromString(config.provider)
            ?: throw IllegalArgumentException("Unknown provider: ${config.provider}")

        // Check registry first
        val registryExecutor = LLMClientRegistry.createExecutor(provider, config)
        if (registryExecutor != null) {
            return registryExecutor
        }

        // Fall back to built-in providers
        return create(config)
    }

    /**
     * Check if a provider is available (either built-in or registered)
     */
    fun isProviderAvailable(providerType: LLMProviderType): Boolean {
        if (LLMClientRegistry.isProviderAvailable(providerType)) {
            return true
        }
        return providerType != LLMProviderType.GITHUB_COPILOT
    }

    private fun createCustomOpenAI(config: NamedModelConfig, provider: LLMProviderType): SingleLLMPromptExecutor {
        val baseUrl = config.baseUrl.ifEmpty { ModelRegistry.getDefaultBaseUrl(provider) }
        return SingleLLMPromptExecutor(
            CustomOpenAILLMClient(apiKey = config.apiKey, baseUrl = baseUrl)
        )
    }
}
