package com.phodal.routa.core.llm.provider

import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import com.phodal.routa.core.config.LLMProviderType
import com.phodal.routa.core.config.NamedModelConfig

/**
 * Registry for extensible LLM client providers.
 *
 * Adapted from xiuper's LLMClientRegistry.
 *
 * Allows platform-specific providers (like GitHub Copilot) to be registered
 * without requiring changes to the core ExecutorFactory.
 *
 * Usage:
 * ```kotlin
 * LLMClientRegistry.register(GithubCopilotClientProvider())
 *
 * val isAvailable = LLMClientRegistry.isProviderAvailable(LLMProviderType.GITHUB_COPILOT)
 *
 * val executor = LLMClientRegistry.createExecutor(providerType, config)
 * ```
 */
object LLMClientRegistry {
    private val providers = mutableMapOf<LLMProviderType, LLMClientProvider>()

    /**
     * Register a provider for a specific type.
     * Only registers if the provider reports itself as available.
     */
    fun register(provider: LLMClientProvider) {
        if (provider.isAvailable()) {
            providers[provider.providerType] = provider
        }
    }

    /**
     * Unregister a provider
     */
    fun unregister(providerType: LLMProviderType) {
        providers.remove(providerType)
    }

    /**
     * Check if a provider is registered and available
     */
    fun isProviderAvailable(providerType: LLMProviderType): Boolean {
        return providers[providerType]?.isAvailable() == true
    }

    /**
     * Get a registered provider
     */
    fun getProvider(providerType: LLMProviderType): LLMClientProvider? {
        return providers[providerType]
    }

    /**
     * Create an executor using a registered provider
     */
    suspend fun createExecutor(providerType: LLMProviderType, config: NamedModelConfig): SingleLLMPromptExecutor? {
        return providers[providerType]?.createExecutor(config)
    }

    /**
     * Get all registered provider types
     */
    fun getRegisteredProviderTypes(): Set<LLMProviderType> {
        return providers.keys.toSet()
    }

    /**
     * Get cached available models for a provider (synchronous)
     */
    fun getAvailableModels(providerType: LLMProviderType): List<String> {
        return providers[providerType]?.getAvailableModels() ?: emptyList()
    }

    /**
     * Fetch available models from provider API (asynchronous)
     */
    suspend fun fetchAvailableModelsAsync(providerType: LLMProviderType, forceRefresh: Boolean = false): List<String> {
        return providers[providerType]?.fetchAvailableModelsAsync(forceRefresh) ?: emptyList()
    }

    /**
     * Clear all registered providers (useful for testing)
     */
    fun clear() {
        providers.clear()
    }
}
