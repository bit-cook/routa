package com.phodal.routa.core.cli

import com.phodal.routa.core.RoutaFactory
import com.phodal.routa.core.config.RoutaConfigLoader
import com.phodal.routa.core.model.AgentRole
import com.phodal.routa.core.provider.StreamChunk
import com.phodal.routa.core.provider.ToolCallStatus
import com.phodal.routa.core.provider.WorkspaceAgentProvider
import kotlinx.coroutines.runBlocking

/**
 * CLI for testing the Workspace Agent with text-based tool calling.
 *
 * This CLI verifies that the workspace agent can:
 * 1. Receive user prompts
 * 2. Generate `<tool_call>` blocks in its text response
 * 3. Have those tool calls extracted and executed (read_file, write_file, list_files)
 * 4. Receive tool results and continue working
 * 5. Complete multi-step tasks (e.g., "list files, read a file, summarize it")
 *
 * ## Usage
 *
 * ```bash
 * # Interactive mode (default)
 * ./gradlew :routa-core:run -PmainClass=com.phodal.routa.core.cli.WorkspaceAgentCliKt
 *
 * # With custom working directory
 * ./gradlew :routa-core:run -PmainClass=com.phodal.routa.core.cli.WorkspaceAgentCliKt --args="--cwd /path/to/project"
 *
 * # One-shot mode (execute a single prompt)
 * ./gradlew :routa-core:run -PmainClass=com.phodal.routa.core.cli.WorkspaceAgentCliKt --args="--prompt 'List all files in src/'"
 * ```
 */
fun main(args: Array<String>) {
    println("╔══════════════════════════════════════════════════╗")
    println("║   Workspace Agent CLI (Text-Based Tool Calling)  ║")
    println("╠══════════════════════════════════════════════════╣")
    println("║  Tools: read_file, write_file, list_files        ║")
    println("║  Format: <tool_call> XML blocks in LLM response  ║")
    println("╚══════════════════════════════════════════════════╝")
    println()

    // Check config
    if (!RoutaConfigLoader.hasConfig()) {
        println("Error: No config found at ${RoutaConfigLoader.getConfigPath()}")
        println()
        println("Please create ~/.autodev/config.yaml with:")
        println("  active: default")
        println("  configs:")
        println("    - name: default")
        println("      provider: deepseek")
        println("      apiKey: sk-...")
        println("      model: deepseek-chat")
        return
    }

    val activeConfig = RoutaConfigLoader.getActiveModelConfig()!!
    println("LLM: ${activeConfig.provider} / ${activeConfig.model}")

    // Parse args
    var cwd = System.getProperty("user.dir") ?: "."
    var oneShot: String? = null
    var useStreaming = true
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--cwd" -> { i++; if (i < args.size) cwd = args[i] }
            "--prompt" -> { i++; if (i < args.size) oneShot = args[i] }
            "--no-stream" -> useStreaming = false
        }
        i++
    }
    println("CWD: $cwd")
    println("Mode: ${if (useStreaming) "streaming" else "non-streaming"}")
    println()

    // Create the workspace agent provider
    val routa = RoutaFactory.createInMemory()
    val provider = WorkspaceAgentProvider(
        agentTools = routa.tools,
        workspaceId = "cli-workspace",
        cwd = cwd,
        modelConfig = activeConfig,
        maxIterations = 20,
    )

    println("Capabilities: ${provider.capabilities()}")
    println()

    if (oneShot != null) {
        // One-shot mode
        println("Prompt: $oneShot")
        println("─".repeat(60))
        executePrompt(provider, oneShot, useStreaming)
        return
    }

    // Interactive mode
    println("Enter your request (or 'quit' to exit):")
    println("─".repeat(60))

    while (true) {
        print("\n> ")
        val input = readlnOrNull()?.trim() ?: break
        if (input.equals("quit", ignoreCase = true) || input.equals("exit", ignoreCase = true)) break
        if (input.isEmpty()) continue

        executePrompt(provider, input, useStreaming)
    }

    runBlocking { provider.shutdown() }
    println("\nGoodbye!")
}

private fun executePrompt(provider: WorkspaceAgentProvider, prompt: String, streaming: Boolean) {
    val agentId = "workspace-${System.currentTimeMillis()}"

    try {
        if (streaming) {
            println()
            val result = runBlocking {
                provider.runStreaming(
                    role = AgentRole.ROUTA,
                    agentId = agentId,
                    prompt = prompt,
                    onChunk = { chunk -> printChunk(chunk) },
                )
            }
            println()
            println("─".repeat(60))
            println("Done. (${result.length} chars)")
        } else {
            println()
            println("Processing (non-streaming)...")
            val result = runBlocking {
                provider.run(AgentRole.ROUTA, agentId, prompt)
            }
            println()
            println(result)
            println()
            println("─".repeat(60))
            println("Done. (${result.length} chars)")
        }
    } catch (e: Exception) {
        println()
        println("Error: ${e.message}")
        e.printStackTrace()
    }
}

private fun printChunk(chunk: StreamChunk) {
    when (chunk) {
        is StreamChunk.Text -> print(chunk.content)
        is StreamChunk.ToolCall -> {
            val icon = when (chunk.status) {
                ToolCallStatus.STARTED -> ">>>"
                ToolCallStatus.IN_PROGRESS -> "..."
                ToolCallStatus.COMPLETED -> "<<<"
                ToolCallStatus.FAILED -> "!!!"
            }
            println("\n    [$icon ${chunk.name}] ${chunk.arguments ?: ""}")
            if (chunk.result != null) {
                println("    Result: ${chunk.result}")
            }
        }
        is StreamChunk.Error -> println("\n    [ERROR] ${chunk.message}")
        is StreamChunk.Completed -> println("\n    [${chunk.stopReason}]")
        is StreamChunk.Heartbeat -> { /* silent */ }
        is StreamChunk.Thinking -> print(chunk.content)
        is StreamChunk.CompletionReport -> {
            println("\n    [Report] ${chunk.summary.take(200)}")
        }
    }
}
