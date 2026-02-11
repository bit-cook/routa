package com.phodal.routa.core.provider

import com.agentclientprotocol.common.Event
import com.agentclientprotocol.model.SessionUpdate
import com.phodal.routa.core.acp.AcpProcessManager
import com.phodal.routa.core.acp.RoutaAcpClient
import com.phodal.routa.core.config.AcpAgentConfig
import com.phodal.routa.core.model.AgentRole
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.io.asSink
import kotlinx.io.asSource
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * ACP-based agent provider with full lifecycle management.
 *
 * Compared to the legacy [com.phodal.routa.core.runner.AcpAgentRunner], this adds:
 * - **Health check**: monitors whether the ACP process is alive
 * - **Interrupt**: can kill a running agent process
 * - **Streaming**: delivers [StreamChunk] events in real-time during execution
 * - **Session tracking**: maintains per-agent connection state for cleanup
 *
 * Modeled after Intent's `ACPProvider` which manages child processes, handles
 * session recovery, and provides health monitoring.
 *
 * ## Process Lifecycle
 * ```
 * run()/runStreaming() → spawn process → ACP connect → create session → prompt → collect events → disconnect
 * interrupt()         → kill process
 * isHealthy()        → check process.isAlive
 * cleanup()          → terminate process, clear state
 * ```
 */
class AcpAgentProvider(
    private val agentKey: String,
    private val config: AcpAgentConfig,
    private val cwd: String,
) : AgentProvider {

    private val processManager = AcpProcessManager.getInstance()

    // Track active connections per agentId for health checks and cleanup
    private val activeClients = ConcurrentHashMap<String, ActiveAcpSession>()

    // Track last heartbeat per agentId
    private val lastHeartbeats = ConcurrentHashMap<String, Instant>()

    private data class ActiveAcpSession(
        val processKey: String,
        val client: RoutaAcpClient,
        val scope: CoroutineScope,
        val startedAt: Instant = Instant.now(),
    )

    // ── AgentRunner (backward compat) ────────────────────────────────

    override suspend fun run(role: AgentRole, agentId: String, prompt: String): String {
        return runStreaming(role, agentId, prompt) { /* discard chunks */ }
    }

    // ── AgentProvider: Streaming ─────────────────────────────────────

    override suspend fun runStreaming(
        role: AgentRole,
        agentId: String,
        prompt: String,
        onChunk: (StreamChunk) -> Unit,
    ): String {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val processKey = "$agentKey-$agentId"

        // Spawn (or reuse) the agent process
        val managed = processManager.getOrCreateProcess(
            agentKey = processKey,
            command = config.getCommandLine(),
            cwd = cwd,
            env = config.env,
        )

        // Connect via ACP protocol
        val client = RoutaAcpClient(
            coroutineScope = scope,
            input = managed.inputStream.asSource(),
            output = managed.outputStream.asSink(),
            cwd = cwd,
            agentName = agentKey,
        )

        // Track this session
        activeClients[agentId] = ActiveAcpSession(processKey, client, scope)
        updateHeartbeat(agentId)

        val resultBuilder = StringBuilder()

        // Wire up streaming callbacks
        client.onSessionUpdate = { update ->
            updateHeartbeat(agentId)
            when (update) {
                is SessionUpdate.AgentMessageChunk -> {
                    val text = RoutaAcpClient.extractText(update.content)
                    resultBuilder.append(text)
                    onChunk(StreamChunk.Text(text))
                }
                is SessionUpdate.ToolCallUpdate -> {
                    val toolName = update.title ?: "tool"
                    val statusStr = update.status.toString().lowercase()
                    val status = when {
                        statusStr.contains("start") -> ToolCallStatus.STARTED
                        statusStr.contains("complet") -> ToolCallStatus.COMPLETED
                        statusStr.contains("fail") || statusStr.contains("error") -> ToolCallStatus.FAILED
                        else -> ToolCallStatus.IN_PROGRESS
                    }
                    onChunk(StreamChunk.ToolCall(toolName, status))
                }
                else -> {
                    // Plan updates, mode changes, thought chunks, etc.
                    // Emit heartbeat so coordinator knows agent is alive
                    onChunk(StreamChunk.Heartbeat())
                }
            }
        }

        try {
            client.connect()
            onChunk(StreamChunk.Heartbeat()) // Connected

            // Send the task prompt
            client.prompt(prompt).collect { event ->
                updateHeartbeat(agentId)
                when (event) {
                    is Event.PromptResponseEvent -> {
                        val stopReason = event.response.stopReason
                        onChunk(StreamChunk.Completed(stopReason.toString()))
                    }
                    is Event.SessionUpdateEvent -> {
                        // Already handled by onSessionUpdate callback
                    }
                }
            }
        } catch (e: Exception) {
            onChunk(StreamChunk.Error(e.message ?: "ACP error", recoverable = true))
            throw e
        } finally {
            client.disconnect()
            processManager.terminateProcess(processKey)
            activeClients.remove(agentId)
            scope.coroutineContext[Job]?.cancel()
        }

        return resultBuilder.toString().ifEmpty {
            "[ACP agent completed without text output]"
        }
    }

    // ── AgentProvider: Health Check ──────────────────────────────────

    override fun isHealthy(agentId: String): Boolean {
        val session = activeClients[agentId] ?: return false
        // Check 1: Is the OS process alive?
        if (!processManager.isRunning(session.processKey)) return false
        // Check 2: Have we received a heartbeat recently? (5 min staleness threshold)
        val lastBeat = lastHeartbeats[agentId] ?: return false
        val staleThresholdMs = 5 * 60 * 1000L
        return Instant.now().toEpochMilli() - lastBeat.toEpochMilli() < staleThresholdMs
    }

    // ── AgentProvider: Interrupt ─────────────────────────────────────

    override suspend fun interrupt(agentId: String) {
        val session = activeClients.remove(agentId)
        if (session != null) {
            try {
                session.client.disconnect()
            } catch (_: Exception) { }
            processManager.terminateProcess(session.processKey)
            session.scope.coroutineContext[Job]?.cancel()
        }
        lastHeartbeats.remove(agentId)
    }

    // ── AgentProvider: Capabilities ──────────────────────────────────

    override fun capabilities(): ProviderCapabilities = ProviderCapabilities(
        name = "ACP ($agentKey)",
        supportsStreaming = true,
        supportsInterrupt = true,
        supportsHealthCheck = true,
        supportsFileEditing = true,
        supportsTerminal = true,
        supportsToolCalling = false, // ACP agents handle tools internally
        maxConcurrentAgents = 5,
        priority = 10,
    )

    // ── AgentProvider: Cleanup ───────────────────────────────────────

    override suspend fun cleanup(agentId: String) {
        interrupt(agentId) // Reuse interrupt for cleanup
    }

    override suspend fun shutdown() {
        val agents = activeClients.keys.toList()
        for (agentId in agents) {
            interrupt(agentId)
        }
    }

    // ── Internal ─────────────────────────────────────────────────────

    private fun updateHeartbeat(agentId: String) {
        lastHeartbeats[agentId] = Instant.now()
    }
}
