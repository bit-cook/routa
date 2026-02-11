package com.phodal.routa.core.viewmodel

import com.phodal.routa.core.model.AgentRole
import java.time.Instant

/**
 * Structured debug log entry for tracing the Routa orchestration flow.
 *
 * Captures key events: task parsing results, agent-to-role mappings,
 * prompts sent to agents, and phase transitions. Useful for diagnosing
 * issues like incorrect task ordering or missing agent interrupts.
 */
data class RoutaDebugEntry(
    val timestamp: Instant = Instant.now(),
    val category: DebugCategory,
    val message: String,
    val details: Map<String, String> = emptyMap(),
) {
    override fun toString(): String {
        val detailStr = if (details.isNotEmpty()) {
            " " + details.entries.joinToString(", ") { "${it.key}=${it.value}" }
        } else ""
        return "[$timestamp] [$category] $message$detailStr"
    }
}

enum class DebugCategory {
    /** ROUTA plan output and task parsing results. */
    PLAN,
    /** Task registration and ordering. */
    TASK,
    /** Agent creation and role assignment. */
    AGENT,
    /** Prompt sent to an agent. */
    PROMPT,
    /** Phase transitions. */
    PHASE,
    /** Stream chunk routing. */
    STREAM,
    /** Stop / interrupt actions. */
    STOP,
    /** Errors and warnings. */
    ERROR,
}

/**
 * Thread-safe debug log with bounded size.
 * Keeps the last [maxEntries] entries.
 */
class RoutaDebugLog(private val maxEntries: Int = 500) {

    private val _entries = mutableListOf<RoutaDebugEntry>()
    private val lock = Any()

    /** All current log entries (newest last). */
    val entries: List<RoutaDebugEntry>
        get() = synchronized(lock) { _entries.toList() }

    /** Number of entries. */
    val size: Int get() = synchronized(lock) { _entries.size }

    fun log(category: DebugCategory, message: String, details: Map<String, String> = emptyMap()) {
        synchronized(lock) {
            _entries.add(RoutaDebugEntry(category = category, message = message, details = details))
            if (_entries.size > maxEntries) {
                _entries.removeFirst()
            }
        }
    }

    fun clear() {
        synchronized(lock) { _entries.clear() }
    }

    /** Dump all entries as a multi-line string (for debugging). */
    fun dump(): String = entries.joinToString("\n") { it.toString() }
}
