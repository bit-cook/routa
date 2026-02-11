package com.phodal.routa.core.provider

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

/**
 * Circuit breaker for protecting agent provider calls.
 *
 * Implements the standard three-state circuit breaker pattern
 * (CLOSED → OPEN → HALF_OPEN) modeled after Intent's `CircuitBreaker`
 * in `error-handler.ts`.
 *
 * ## States
 * - **CLOSED**: Normal operation. Failures are counted.
 * - **OPEN**: Calls are rejected immediately. Transitions to HALF_OPEN after [timeoutMs].
 * - **HALF_OPEN**: A limited number of calls are allowed to test recovery.
 *   If they succeed, the breaker closes. If they fail, it reopens.
 *
 * ## Usage
 * ```kotlin
 * val breaker = CircuitBreaker("llm-provider")
 * val result = breaker.execute { runner.run(role, agentId, prompt) }
 * ```
 *
 * ## Thread Safety
 * All state transitions are protected by a [Mutex].
 * The breaker is safe for concurrent use from multiple coroutines.
 */
class CircuitBreaker(
    val name: String,
    private val failureThreshold: Int = 5,
    private val successThreshold: Int = 2,
    private val timeoutMs: Long = 60_000,
    private val volumeThreshold: Int = 10,
) {

    enum class State { CLOSED, OPEN, HALF_OPEN }

    // ── Internal state (guarded by mutex) ────────────────────────────

    private val mutex = Mutex()

    @Volatile
    var state: State = State.CLOSED
        private set

    private var failures = 0
    private var successes = 0
    private var lastFailureTime: Long = 0
    private var totalRequests: Long = 0
    private var totalFailures: Long = 0

    // ── Public API ───────────────────────────────────────────────────

    /**
     * Execute a suspending block with circuit breaker protection.
     *
     * @throws CircuitOpenException if the circuit is OPEN and timeout hasn't elapsed.
     */
    suspend fun <T> execute(block: suspend () -> T): T {
        checkState()

        return try {
            val result = block()
            onSuccess()
            result
        } catch (e: Exception) {
            onFailure()
            throw e
        }
    }

    /**
     * Get current metrics snapshot.
     */
    fun metrics(): CircuitBreakerMetrics = CircuitBreakerMetrics(
        name = name,
        state = state,
        failures = failures,
        totalRequests = totalRequests,
        totalFailures = totalFailures,
        errorRate = if (totalRequests > 0) totalFailures.toDouble() / totalRequests else 0.0,
    )

    /**
     * Reset the circuit breaker to its initial state.
     */
    suspend fun reset() {
        mutex.withLock {
            state = State.CLOSED
            failures = 0
            successes = 0
            totalRequests = 0
            totalFailures = 0
            lastFailureTime = 0
        }
    }

    // ── State transitions ────────────────────────────────────────────

    private suspend fun checkState() {
        mutex.withLock {
            when (state) {
                State.OPEN -> {
                    val elapsed = System.currentTimeMillis() - lastFailureTime
                    if (elapsed >= timeoutMs) {
                        // Transition to HALF_OPEN: allow a probe request
                        state = State.HALF_OPEN
                        successes = 0
                        failures = 0
                    } else {
                        throw CircuitOpenException(
                            "Circuit breaker [$name] is OPEN. " +
                                "Will retry in ${(timeoutMs - elapsed) / 1000}s."
                        )
                    }
                }
                else -> { /* CLOSED or HALF_OPEN: allow the call */ }
            }
        }
    }

    private suspend fun onSuccess() {
        mutex.withLock {
            totalRequests++
            when (state) {
                State.HALF_OPEN -> {
                    successes++
                    if (successes >= successThreshold) {
                        state = State.CLOSED
                        failures = 0
                        successes = 0
                    }
                }
                State.CLOSED -> {
                    // Decay failures on success
                    failures = maxOf(0, failures - 1)
                }
                State.OPEN -> { /* shouldn't happen */ }
            }
        }
    }

    private suspend fun onFailure() {
        mutex.withLock {
            totalRequests++
            totalFailures++
            failures++
            lastFailureTime = System.currentTimeMillis()

            when (state) {
                State.HALF_OPEN -> {
                    // Probe failed: reopen
                    state = State.OPEN
                }
                State.CLOSED -> {
                    if (shouldOpen()) {
                        state = State.OPEN
                        successes = 0
                    }
                }
                State.OPEN -> { /* already open */ }
            }
        }
    }

    private fun shouldOpen(): Boolean {
        // Don't open until we have enough data
        if (totalRequests < volumeThreshold) return false
        return failures >= failureThreshold
    }
}

// ── Metrics ─────────────────────────────────────────────────────────────

data class CircuitBreakerMetrics(
    val name: String,
    val state: CircuitBreaker.State,
    val failures: Int,
    val totalRequests: Long,
    val totalFailures: Long,
    val errorRate: Double,
)

// ── Exceptions ──────────────────────────────────────────────────────────

class CircuitOpenException(message: String) : Exception(message)

// ── Registry ────────────────────────────────────────────────────────────

/**
 * Global registry of circuit breakers, keyed by provider name.
 *
 * Allows sharing breakers across provider instances and inspecting
 * their state for observability/degradation decisions.
 */
object CircuitBreakerRegistry {
    private val breakers = ConcurrentHashMap<String, CircuitBreaker>()

    fun getOrCreate(
        name: String,
        failureThreshold: Int = 5,
        successThreshold: Int = 2,
        timeoutMs: Long = 60_000,
    ): CircuitBreaker {
        return breakers.computeIfAbsent(name) {
            CircuitBreaker(name, failureThreshold, successThreshold, timeoutMs)
        }
    }

    fun get(name: String): CircuitBreaker? = breakers[name]

    fun allMetrics(): List<CircuitBreakerMetrics> = breakers.values.map { it.metrics() }

    fun clear() {
        breakers.clear()
    }
}
