package com.phodal.routa.core.llm.provider

import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import com.phodal.routa.core.config.LLMProviderType
import com.phodal.routa.core.config.NamedModelConfig

/**
 * Interface for extensible LLM client providers.
 *
 * Adapted from xiuper's LLMClientProvider.
 *
 * Allows platform-specific or third-party LLM integrations to be registered
 * and used without modifying the core ExecutorFactory.
 *
 * Example usage:
 * ```kotlin
 * LLMClientRegistry.register(GithubCopilotClientProvider())
 * ```
 */
interface LLMClientProvider {
    /**
     * The provider type this provider handles
     */
    val providerType: LLMProviderType

    /**
     * Check if this provider is available on the current platform
     */
    fun isAvailable(): Boolean

    /**
     * Create an executor for the given model config
     *
     * @return SingleLLMPromptExecutor or null if creation fails
     */
    suspend fun createExecutor(config: NamedModelConfig): SingleLLMPromptExecutor?

    /**
     * Get cached available models for this provider (synchronous)
     */
    fun getAvailableModels(): List<String> = emptyList()

    /**
     * Fetch available models from the provider API (asynchronous)
     */
    suspend fun fetchAvailableModelsAsync(forceRefresh: Boolean = false): List<String> = getAvailableModels()

    /**
     * Get the default base URL for this provider
     */
    fun getDefaultBaseUrl(): String = ""
}
