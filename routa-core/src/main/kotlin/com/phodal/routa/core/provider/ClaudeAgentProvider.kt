package com.phodal.routa.core.provider

import com.phodal.routa.core.model.AgentRole
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Claude Code CLI agent provider with process lifecycle management.
 *
 * Compared to the legacy [com.phodal.routa.core.runner.ClaudeAgentRunner], this adds:
 * - **Health check**: monitors whether the Claude process is alive
 * - **Interrupt**: can kill a running Claude process
 * - **Streaming**: delivers stdout as [StreamChunk.Text] in real-time
 * - **Process tracking**: maintains per-agent process references
 *
 * Claude Code runs in non-interactive print mode (`claude -p`) with
 * full tool access (file editing, bash commands, etc.).
 */
class ClaudeAgentProvider(
    private val claudePath: String = "claude",
    private val cwd: String = ".",
    private val allowedTools: List<String> = listOf("Bash", "Read", "Edit", "Write", "Glob", "Grep"),
    private val timeoutMinutes: Long = 5,
) : AgentProvider {

    // Track active processes per agentId
    private val activeProcesses = ConcurrentHashMap<String, Process>()

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
    ): String = withContext(Dispatchers.IO) {
        val cmdList = mutableListOf(claudePath, "-p", "--output-format", "text")

        if (allowedTools.isNotEmpty()) {
            cmdList.add("--allowedTools")
            cmdList.addAll(allowedTools)
        }

        val pb = ProcessBuilder(cmdList).apply {
            directory(File(cwd))
            redirectErrorStream(false)
        }

        val process = pb.start()
        activeProcesses[agentId] = process

        onChunk(StreamChunk.Heartbeat())

        // Write prompt to stdin and close it
        process.outputStream.bufferedWriter().use { writer ->
            writer.write(prompt)
            writer.flush()
        }

        // Read stdout with streaming
        val stdoutBuilder = StringBuilder()
        val stdoutThread = Thread {
            try {
                process.inputStream.bufferedReader().use { reader ->
                    val buffer = CharArray(4096)
                    var n: Int
                    while (reader.read(buffer).also { n = it } != -1) {
                        val text = String(buffer, 0, n)
                        stdoutBuilder.append(text)
                        onChunk(StreamChunk.Text(text))
                    }
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; start() }

        // Read stderr in background
        val stderrBuilder = StringBuilder()
        val stderrThread = Thread {
            try {
                process.errorStream.bufferedReader().use { reader ->
                    reader.forEachLine { line ->
                        stderrBuilder.appendLine(line)
                    }
                }
            } catch (_: Exception) {
            }
        }.apply { isDaemon = true; start() }

        // Wait for completion
        val finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES)
        if (!finished) {
            process.destroyForcibly()
            onChunk(StreamChunk.Error("Timeout after ${timeoutMinutes}m", recoverable = false))
        }

        stdoutThread.join(5000)
        stderrThread.join(5000)

        activeProcesses.remove(agentId)

        val exitCode = if (finished) process.exitValue() else -1
        val output = stdoutBuilder.toString()

        if (stderrBuilder.isNotBlank()) {
            onChunk(StreamChunk.Error("stderr: ${stderrBuilder.toString().take(500)}", recoverable = true))
        }

        onChunk(StreamChunk.Completed("exit_code=$exitCode"))

        output.ifEmpty {
            "[Claude exited with code $exitCode. stderr: ${stderrBuilder.toString().take(500)}]"
        }
    }

    // ── AgentProvider: Health Check ──────────────────────────────────

    override fun isHealthy(agentId: String): Boolean {
        val process = activeProcesses[agentId] ?: return true // Not running = idle = healthy
        return process.isAlive
    }

    // ── AgentProvider: Interrupt ─────────────────────────────────────

    override suspend fun interrupt(agentId: String) {
        val process = activeProcesses.remove(agentId) ?: return
        if (process.isAlive) {
            process.destroy()
            val exited = process.waitFor(5, TimeUnit.SECONDS)
            if (!exited) {
                process.destroyForcibly()
            }
        }
    }

    // ── AgentProvider: Capabilities ──────────────────────────────────

    override fun capabilities(): ProviderCapabilities = ProviderCapabilities(
        name = "Claude CLI",
        supportsStreaming = true,
        supportsInterrupt = true,
        supportsHealthCheck = true,
        supportsFileEditing = true,
        supportsTerminal = true,
        supportsToolCalling = false, // Claude CLI handles tools internally
        maxConcurrentAgents = 3,
        priority = 8,
    )

    // ── AgentProvider: Cleanup ───────────────────────────────────────

    override suspend fun cleanup(agentId: String) {
        interrupt(agentId)
    }

    override suspend fun shutdown() {
        val agents = activeProcesses.keys.toList()
        for (agentId in agents) {
            interrupt(agentId)
        }
    }
}
