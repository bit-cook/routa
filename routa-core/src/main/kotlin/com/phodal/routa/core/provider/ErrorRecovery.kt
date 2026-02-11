package com.phodal.routa.core.provider

import kotlinx.coroutines.delay
import kotlin.math.pow

/**
 * Error classification and recovery strategy system.
 *
 * Modeled after Intent's `ErrorHandler` which maps error categories
 * to recovery strategies (retry, fallback, fail). Each category has
 * sensible defaults that can be overridden per deployment.
 *
 * ## Error Flow
 * ```
 * Exception → classify() → AgentException
 *           → getStrategy(category) → RecoveryStrategy
 *           → executeWithRecovery(strategy, block) → Result<T>
 * ```
 *
 * ## Usage
 * ```kotlin
 * val recovery = ErrorRecoveryRegistry()
 * val result = recovery.executeWithRecovery(ErrorCategory.PROVIDER) {
 *     runner.run(role, agentId, prompt)
 * }
 * ```
 */

// ── Error Classification ────────────────────────────────────────────────

/**
 * Categories of errors in the agent system.
 *
 * Maps to Intent's `ErrorCategory` enum with additions specific
 * to multi-agent coordination.
 */
enum class ErrorCategory {
    /** Network connectivity issues (DNS, TCP, TLS). */
    NETWORK,

    /** Request or stream timed out. */
    TIMEOUT,

    /** LLM provider returned an error (API error, overloaded, etc.). */
    PROVIDER,

    /** Rate-limited by the LLM provider. */
    RATE_LIMIT,

    /** Streaming connection lost or stalled. */
    STREAMING,

    /** Session was lost or expired (ACP session not found). */
    SESSION,

    /** Agent process crashed or was killed. */
    PROCESS,

    /** Configuration error (invalid API key, missing model, etc.). */
    CONFIGURATION,

    /** Out of memory or resource exhaustion. */
    MEMORY,

    /** Permission denied. */
    PERMISSION,

    /** Unclassifiable error. */
    UNKNOWN,
}

/**
 * Severity levels for errors.
 */
enum class ErrorSeverity {
    /** System-wide impact, requires immediate attention. */
    CRITICAL,

    /** Significant impact, may affect multiple tasks. */
    HIGH,

    /** Single task affected, automatic recovery likely. */
    MEDIUM,

    /** Minor issue, informational. */
    LOW,
}

/**
 * Typed exception for the agent system with classification metadata.
 */
class AgentException(
    message: String,
    val category: ErrorCategory,
    val severity: ErrorSeverity = ErrorSeverity.MEDIUM,
    val recoverable: Boolean = true,
    val agentId: String? = null,
    cause: Throwable? = null,
) : Exception(message, cause) {

    override fun toString(): String =
        "AgentException[$category/$severity] $message" +
            (if (agentId != null) " (agent=$agentId)" else "")
}

// ── Error Classifier ────────────────────────────────────────────────────

/**
 * Classifies raw exceptions into [AgentException] with proper category/severity.
 *
 * Uses message-based heuristics (like Intent's `classifyError()`).
 * Can be extended with custom classifiers.
 */
object ErrorClassifier {

    private data class Classification(
        val category: ErrorCategory,
        val severity: ErrorSeverity,
        val recoverable: Boolean,
    )

    private val patterns: List<Pair<Regex, Classification>> = listOf(
        // Network
        Regex("(?i)(network|dns|connect|socket|econnrefused|unreachable)") to
            Classification(ErrorCategory.NETWORK, ErrorSeverity.HIGH, recoverable = true),

        // Timeout
        Regex("(?i)(timeout|timed.out|deadline)") to
            Classification(ErrorCategory.TIMEOUT, ErrorSeverity.MEDIUM, recoverable = true),

        // Rate limiting
        Regex("(?i)(rate.limit|429|too.many.requests|throttl)") to
            Classification(ErrorCategory.RATE_LIMIT, ErrorSeverity.MEDIUM, recoverable = true),

        // Session
        Regex("(?i)(session.not.found|session.expired|session.invalid|session.lost)") to
            Classification(ErrorCategory.SESSION, ErrorSeverity.HIGH, recoverable = true),

        // Provider / API
        Regex("(?i)(api.error|provider|500|502|503|service.unavailable|overloaded)") to
            Classification(ErrorCategory.PROVIDER, ErrorSeverity.HIGH, recoverable = true),

        // Memory
        Regex("(?i)(out.of.memory|heap|oom|memory)") to
            Classification(ErrorCategory.MEMORY, ErrorSeverity.CRITICAL, recoverable = false),

        // Permission
        Regex("(?i)(permission|denied|unauthorized|forbidden|401|403)") to
            Classification(ErrorCategory.PERMISSION, ErrorSeverity.HIGH, recoverable = false),

        // Configuration
        Regex("(?i)(config|invalid.key|missing.model|invalid.model|model.not.found)") to
            Classification(ErrorCategory.CONFIGURATION, ErrorSeverity.HIGH, recoverable = false),

        // Streaming
        Regex("(?i)(stream|stall|interrupted|chunk)") to
            Classification(ErrorCategory.STREAMING, ErrorSeverity.MEDIUM, recoverable = true),

        // Process
        Regex("(?i)(process|killed|exit.code|crashed|spawn)") to
            Classification(ErrorCategory.PROCESS, ErrorSeverity.HIGH, recoverable = true),
    )

    /**
     * Classify a raw exception into an [AgentException].
     */
    fun classify(error: Throwable, agentId: String? = null): AgentException {
        if (error is AgentException) return error

        val message = error.message ?: error.toString()
        val classification = patterns.firstOrNull { (regex, _) ->
            regex.containsMatchIn(message)
        }?.second

        return AgentException(
            message = message,
            category = classification?.category ?: ErrorCategory.UNKNOWN,
            severity = classification?.severity ?: ErrorSeverity.MEDIUM,
            recoverable = classification?.recoverable ?: false,
            agentId = agentId,
            cause = error,
        )
    }
}

// ── Recovery Strategies ─────────────────────────────────────────────────

/**
 * How to recover from an error of a given category.
 */
sealed class RecoveryStrategy {
    /**
     * Retry with exponential backoff.
     *
     * @param maxAttempts Maximum number of retry attempts.
     * @param baseDelayMs Initial delay in milliseconds.
     * @param backoffMultiplier Multiplier for each subsequent delay.
     */
    data class Retry(
        val maxAttempts: Int = 3,
        val baseDelayMs: Long = 1000,
        val backoffMultiplier: Double = 1.5,
    ) : RecoveryStrategy()

    /**
     * Return a fallback value instead of failing.
     */
    data class Fallback(val fallbackValue: String) : RecoveryStrategy()

    /**
     * Fail immediately — error is non-recoverable.
     */
    data object Fail : RecoveryStrategy()
}

// ── Recovery Registry ───────────────────────────────────────────────────

/**
 * Registry of recovery strategies per error category.
 *
 * Mirrors Intent's `DEFAULT_RECOVERY_STRATEGIES` Map with
 * category-specific retry/fallback/fail behavior.
 */
class ErrorRecoveryRegistry {

    private val strategies = mutableMapOf<ErrorCategory, RecoveryStrategy>(
        ErrorCategory.NETWORK to RecoveryStrategy.Retry(maxAttempts = 3, baseDelayMs = 1000),
        ErrorCategory.TIMEOUT to RecoveryStrategy.Retry(maxAttempts = 2, baseDelayMs = 2000),
        ErrorCategory.RATE_LIMIT to RecoveryStrategy.Retry(maxAttempts = 3, baseDelayMs = 5000, backoffMultiplier = 2.0),
        ErrorCategory.STREAMING to RecoveryStrategy.Retry(maxAttempts = 2, baseDelayMs = 500),
        ErrorCategory.SESSION to RecoveryStrategy.Retry(maxAttempts = 2, baseDelayMs = 1000),
        ErrorCategory.PROCESS to RecoveryStrategy.Retry(maxAttempts = 2, baseDelayMs = 2000),
        ErrorCategory.PROVIDER to RecoveryStrategy.Retry(maxAttempts = 2, baseDelayMs = 1500),
        ErrorCategory.CONFIGURATION to RecoveryStrategy.Fail,
        ErrorCategory.MEMORY to RecoveryStrategy.Fail,
        ErrorCategory.PERMISSION to RecoveryStrategy.Fail,
        ErrorCategory.UNKNOWN to RecoveryStrategy.Retry(maxAttempts = 1, baseDelayMs = 1000),
    )

    /**
     * Override the strategy for a specific category.
     */
    fun setStrategy(category: ErrorCategory, strategy: RecoveryStrategy) {
        strategies[category] = strategy
    }

    /**
     * Get the strategy for a category.
     */
    fun getStrategy(category: ErrorCategory): RecoveryStrategy {
        return strategies[category] ?: RecoveryStrategy.Fail
    }

    /**
     * Execute a block with automatic error classification and recovery.
     *
     * 1. Runs [block]
     * 2. On exception: classifies it → looks up recovery strategy → applies it
     * 3. For [RecoveryStrategy.Retry]: retries with exponential backoff
     *
     * @param overrideCategory Force a specific category (skip classification).
     * @param agentId For logging/metrics context.
     * @param block The suspending operation to protect.
     * @return The result, or throws if all recovery attempts fail.
     */
    suspend fun <T> executeWithRecovery(
        overrideCategory: ErrorCategory? = null,
        agentId: String? = null,
        block: suspend () -> T,
    ): T {
        var lastException: AgentException? = null
        val strategy = if (overrideCategory != null) {
            getStrategy(overrideCategory)
        } else {
            null // Will be determined after first failure
        }

        when (val s = strategy) {
            is RecoveryStrategy.Retry -> {
                return retryWithBackoff(s, agentId, block)
            }
            is RecoveryStrategy.Fallback -> {
                return try {
                    block()
                } catch (e: Exception) {
                    @Suppress("UNCHECKED_CAST")
                    s.fallbackValue as T
                }
            }
            is RecoveryStrategy.Fail, null -> {
                // Run once, classify on failure, then apply dynamic strategy
                return try {
                    block()
                } catch (e: Exception) {
                    val classified = ErrorClassifier.classify(e, agentId)
                    val dynamicStrategy = getStrategy(classified.category)
                    when (dynamicStrategy) {
                        is RecoveryStrategy.Retry -> retryWithBackoff(dynamicStrategy, agentId, block)
                        is RecoveryStrategy.Fallback -> {
                            @Suppress("UNCHECKED_CAST")
                            dynamicStrategy.fallbackValue as T
                        }
                        is RecoveryStrategy.Fail -> throw classified
                    }
                }
            }
        }
    }

    private suspend fun <T> retryWithBackoff(
        strategy: RecoveryStrategy.Retry,
        agentId: String?,
        block: suspend () -> T,
    ): T {
        var lastException: Exception? = null
        repeat(strategy.maxAttempts) { attempt ->
            try {
                return block()
            } catch (e: Exception) {
                lastException = e
                if (attempt < strategy.maxAttempts - 1) {
                    val delayMs = (strategy.baseDelayMs * strategy.backoffMultiplier.pow(attempt.toDouble())).toLong()
                    delay(delayMs)
                }
            }
        }
        val classified = ErrorClassifier.classify(lastException!!, agentId)
        throw classified
    }
}
