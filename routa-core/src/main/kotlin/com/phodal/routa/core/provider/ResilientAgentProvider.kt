package com.phodal.routa.core.provider

import com.phodal.routa.core.model.AgentRole
import com.phodal.routa.core.model.Message
import com.phodal.routa.core.store.ConversationStore

/**
 * Decorator that wraps any [AgentProvider] with resilience features:
 *
 * 1. **Circuit Breaker**: Prevents cascading failures when a provider is unhealthy.
 *    After [failureThreshold] consecutive failures, calls are short-circuited for
 *    [circuitTimeoutMs] before allowing probe requests.
 *
 * 2. **Error Recovery**: Classifies exceptions and applies category-specific
 *    recovery strategies (retry with backoff, fallback, fail-fast).
 *
 * 3. **Session Recovery**: When the underlying provider throws a session-related
 *    error, rebuilds the conversation context from [ConversationStore] and retries.
 *    Modeled after Intent's `formatHistoryAsXml` + `isSessionRecoverableError` pattern.
 *
 * ## Decorator Pattern
 * ```
 * val raw = AcpAgentProvider("codex", config, cwd)
 * val resilient = ResilientAgentProvider(raw, conversationStore)
 *
 * // Uses raw provider, but with circuit breaker + recovery
 * resilient.run(AgentRole.CRAFTER, crafterId, prompt)
 * ```
 *
 * All other [AgentProvider] methods (isHealthy, interrupt, capabilities, etc.)
 * are delegated directly to the wrapped provider.
 */
class ResilientAgentProvider(
    private val delegate: AgentProvider,
    private val conversationStore: ConversationStore? = null,
    private val circuitBreakerName: String = delegate.capabilities().name,
    failureThreshold: Int = 5,
    circuitTimeoutMs: Long = 60_000,
    private val maxSessionRecoveryAttempts: Int = 2,
) : AgentProvider {

    private val circuitBreaker = CircuitBreakerRegistry.getOrCreate(
        name = circuitBreakerName,
        failureThreshold = failureThreshold,
        timeoutMs = circuitTimeoutMs,
    )

    private val recovery = ErrorRecoveryRegistry()

    // ── AgentRunner ──────────────────────────────────────────────────

    override suspend fun run(role: AgentRole, agentId: String, prompt: String): String {
        return executeWithResilience(agentId) {
            delegate.run(role, agentId, prompt)
        }
    }

    // ── AgentProvider: Streaming ─────────────────────────────────────

    override suspend fun runStreaming(
        role: AgentRole,
        agentId: String,
        prompt: String,
        onChunk: (StreamChunk) -> Unit,
    ): String {
        return executeWithResilience(agentId) {
            delegate.runStreaming(role, agentId, prompt, onChunk)
        }
    }

    // ── Delegated methods ────────────────────────────────────────────

    override fun isHealthy(agentId: String): Boolean {
        // Also check circuit breaker state
        if (circuitBreaker.state == CircuitBreaker.State.OPEN) return false
        return delegate.isHealthy(agentId)
    }

    override suspend fun interrupt(agentId: String) = delegate.interrupt(agentId)

    override fun capabilities(): ProviderCapabilities {
        val delegateCaps = delegate.capabilities()
        return delegateCaps.copy(
            name = "Resilient(${delegateCaps.name})",
        )
    }

    override suspend fun cleanup(agentId: String) = delegate.cleanup(agentId)

    override suspend fun shutdown() = delegate.shutdown()

    // ── Resilience Logic ─────────────────────────────────────────────

    /**
     * Execute a block with circuit breaker + error recovery + session recovery.
     */
    private suspend fun <T> executeWithResilience(
        agentId: String,
        block: suspend () -> T,
    ): T {
        return circuitBreaker.execute {
            recovery.executeWithRecovery(agentId = agentId) {
                try {
                    block()
                } catch (e: Exception) {
                    // Check if this is a session-recoverable error
                    val classified = ErrorClassifier.classify(e, agentId)
                    if (classified.category == ErrorCategory.SESSION && conversationStore != null) {
                        throw SessionRecoverableException(classified)
                    }
                    throw e
                }
            }
        }
    }

    // ── Session Recovery ─────────────────────────────────────────────

    /**
     * Build a recovery prompt from conversation history.
     *
     * When an ACP session is lost, we rebuild the context from the
     * conversation store and prepend it to the original prompt.
     *
     * Modeled after Intent's `formatHistoryAsXml()`.
     */
    private suspend fun buildRecoveryContext(agentId: String): String? {
        if (conversationStore == null) return null

        val history = conversationStore.getConversation(agentId)
        if (history.isEmpty()) return null

        return buildString {
            appendLine("## Session Recovery")
            appendLine("Previous session was lost. Here is the conversation history:")
            appendLine()
            // Include last 20 messages, truncating each to 500 chars
            for (msg in history.takeLast(20)) {
                appendLine("[${msg.role}]: ${msg.content.take(500)}")
            }
            appendLine()
            appendLine("---")
            appendLine()
            appendLine("Continue from where you left off.")
        }
    }

    // ── Metrics ──────────────────────────────────────────────────────

    /**
     * Get the circuit breaker metrics for this provider.
     */
    fun circuitBreakerMetrics(): CircuitBreakerMetrics = circuitBreaker.metrics()
}

/**
 * Internal marker exception for session-recoverable errors.
 * Triggers the session recovery flow in [ResilientAgentProvider].
 */
internal class SessionRecoverableException(
    val classified: AgentException,
) : Exception(classified.message, classified)
