package com.phodal.routa.core.provider

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.streaming.StreamFrame
import com.phodal.routa.core.config.NamedModelConfig
import com.phodal.routa.core.model.AgentRole
import com.phodal.routa.core.tool.AgentTools
import com.phodal.routa.core.config.RoutaConfigLoader
import ai.koog.prompt.executor.llms.SingleLLMPromptExecutor
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import com.phodal.routa.core.koog.RoutaAgentFactory
import com.phodal.routa.core.koog.TextBasedToolExecutor
import com.phodal.routa.core.koog.ToolCallExtractor
import kotlinx.coroutines.flow.cancellable
import java.util.concurrent.ConcurrentHashMap

/**
 * Workspace Agent provider — uses **text-based tool calling** (not native function calling).
 *
 * Instead of passing tool definitions to the LLM as function-calling parameters
 * (which many models handle poorly), this provider:
 *
 * 1. Embeds tool descriptions directly in the system prompt
 * 2. Instructs the LLM to emit tool calls as `<tool_call>` XML blocks
 * 3. Parses tool calls from the LLM's text response using [ToolCallExtractor]
 * 4. Executes tool calls using [TextBasedToolExecutor]
 * 5. Feeds results back as `<tool_result>` blocks in a follow-up user message
 * 6. Loops until the LLM stops emitting tool calls or max iterations is reached
 *
 * This approach is inspired by Intent by Augment's `agent-tool-executor.ts` and
 * `tool-call-extractor.ts`, where tool calls are extracted from text rather than
 * relying on the LLM provider's native tool/function-calling API.
 *
 * ## Why Text-Based Tool Calling?
 *
 * - **Better compatibility**: Works with any LLM, including those with poor
 *   function-calling support (many open-source models)
 * - **More reliable**: The LLM can see and reason about tool calls in context
 * - **Simpler debugging**: Tool calls are visible in the conversation text
 * - **Flexible parsing**: Supports XML, JSON, and markdown code block formats
 *
 * ## Tool Call Format
 *
 * The LLM generates tool calls in XML format:
 * ```xml
 * <tool_call>
 * {"name": "read_file", "arguments": {"path": "src/main.kt"}}
 * </tool_call>
 * ```
 *
 * Results are fed back as:
 * ```xml
 * <tool_result>
 * <tool_name>read_file</tool_name>
 * <status>success</status>
 * <output>
 * ... file contents ...
 * </output>
 * </tool_result>
 * ```
 *
 * @see ToolCallExtractor for parsing tool calls from text
 * @see TextBasedToolExecutor for executing parsed tool calls
 * @see KoogAgentProvider for the native function-calling approach
 */
class WorkspaceAgentProvider(
    private val agentTools: AgentTools,
    private val workspaceId: String,
    private val cwd: String,
    private val modelConfig: NamedModelConfig? = null,
    private val maxIterations: Int = 20,
) : AgentProvider {

    // Track active agents for isHealthy / interrupt
    private val activeAgents = ConcurrentHashMap<String, RunningAgent>()

    private data class RunningAgent(
        val role: AgentRole,
        @Volatile var cancelled: Boolean = false,
    )

    // ── System Prompt ────────────────────────────────────────────────────

    companion object {
        /**
         * Build the workspace agent system prompt with tool descriptions embedded.
         *
         * This prompt teaches the LLM to use `<tool_call>` XML format for invoking
         * tools, instead of relying on native function-calling parameters.
         */
        fun buildSystemPrompt(cwd: String): String = """
            |You are a workspace agent that can directly implement tasks and manage files.
            |You have access to tools for reading, writing, and listing files in the project.
            |
            |## Working Directory
            |
            |Your workspace root is: $cwd
            |All file paths are relative to this directory.
            |
            |## Available Tools
            |
            |You have the following tools available. To use a tool, emit a `<tool_call>` block
            |with a JSON object containing `name` and `arguments`:
            |
            |### read_file
            |Read the contents of a file.
            |
            |Parameters:
            |- `path` (required): File path relative to workspace root
            |
            |Example:
            |<tool_call>
            |{"name": "read_file", "arguments": {"path": "src/main.kt"}}
            |</tool_call>
            |
            |### write_file
            |Write content to a file. Creates parent directories automatically.
            |
            |Parameters:
            |- `path` (required): File path relative to workspace root
            |- `content` (required): The full content to write to the file
            |
            |Example:
            |<tool_call>
            |{"name": "write_file", "arguments": {"path": "src/hello.kt", "content": "fun main() {\n    println(\"Hello\")\n}"}}
            |</tool_call>
            |
            |### list_files
            |List files and directories in a path.
            |
            |Parameters:
            |- `path` (optional, defaults to "."): Directory path relative to workspace root
            |
            |Example:
            |<tool_call>
            |{"name": "list_files", "arguments": {"path": "src"}}
            |</tool_call>
            |
            |## Tool Call Rules
            |
            |1. **One tool call per block**: Each `<tool_call>` block should contain exactly one tool invocation.
            |2. **Multiple calls allowed**: You can include multiple `<tool_call>` blocks in a single response.
            |3. **Wait for results**: After emitting tool calls, I will execute them and return the results
            |   in `<tool_result>` blocks. Use these results to continue your work.
            |4. **Read before write**: Always read a file before modifying it to understand its current state.
            |5. **JSON format**: The content inside `<tool_call>` must be valid JSON with `name` and `arguments` fields.
            |
            |## Workflow
            |
            |1. **Understand** the request — ask clarifying questions if needed
            |2. **Explore** — use `list_files` and `read_file` to understand the codebase
            |3. **Plan** — describe what changes you'll make
            |4. **Implement** — use `read_file` then `write_file` to make changes
            |5. **Verify** — read back modified files to confirm correctness
            |6. **Summarize** — explain what was done
            |
            |## Important Notes
            |
            |- When you're done and have no more tool calls to make, just provide your final summary.
            |- Make minimal, targeted changes — don't rewrite entire files unnecessarily.
            |- If a task is too complex, break it down into steps and work through them one at a time.
            |- Always provide your reasoning before making tool calls.
        """.trimMargin()
    }

    // ── AgentProvider: Run (with tool call loop) ────────────────────────

    override suspend fun run(role: AgentRole, agentId: String, prompt: String): String {
        val components = createLLMComponents()
        val toolExecutor = TextBasedToolExecutor(cwd)

        activeAgents[agentId] = RunningAgent(role)

        return try {
            runAgentLoop(
                executor = components.executor,
                model = components.model,
                systemPrompt = components.systemPrompt,
                userPrompt = prompt,
                toolExecutor = toolExecutor,
                agentId = agentId,
            )
        } catch (e: Exception) {
            "Error: ${e.message}"
        } finally {
            activeAgents.remove(agentId)
        }
    }

    /**
     * The core agent loop:
     * 1. Send prompt to LLM (without native tool definitions)
     * 2. Check response for `<tool_call>` blocks
     * 3. If found: execute tools, format results, add to conversation, repeat
     * 4. If not found: return the final response
     */
    private suspend fun runAgentLoop(
        executor: SingleLLMPromptExecutor,
        model: LLModel,
        systemPrompt: String,
        userPrompt: String,
        toolExecutor: TextBasedToolExecutor,
        agentId: String,
    ): String {
        // Build conversation history as a list of messages
        val conversationMessages = mutableListOf<Pair<String, String>>() // role -> content
        conversationMessages.add("user" to userPrompt)

        var lastResponse = ""

        for (iteration in 1..maxIterations) {
            // Check for cancellation
            if (activeAgents[agentId]?.cancelled == true) {
                return lastResponse.ifEmpty { "[Agent cancelled]" }
            }

            // Build prompt with full conversation history
            val llmPrompt = prompt(id = "workspace-$agentId-iter$iteration") {
                system(systemPrompt)
                for ((role, content) in conversationMessages) {
                    when (role) {
                        "user" -> user(content)
                        "assistant" -> assistant(content)
                    }
                }
            }

            // Execute LLM call WITHOUT tool definitions (text-based approach)
            val responses = executor.execute(llmPrompt, model, tools = emptyList())
            val responseText = responses
                .filterIsInstance<Message.Response>()
                .joinToString("") { it.content }

            lastResponse = responseText

            // Check for tool calls in the response
            val toolCalls = ToolCallExtractor.extractToolCalls(responseText)

            if (toolCalls.isEmpty()) {
                // No tool calls — LLM is done, return the final response
                return responseText
            }

            // Add assistant's response to conversation
            conversationMessages.add("assistant" to responseText)

            // Execute the tool calls
            val results = toolExecutor.executeAll(toolCalls)
            val resultMessage = toolExecutor.formatResults(results)

            // Add tool results as a user message (the LLM will see these)
            conversationMessages.add("user" to resultMessage)
        }

        // Max iterations reached
        return lastResponse.ifEmpty { "[Agent reached max iterations ($maxIterations)]" }
    }

    // ── AgentProvider: Streaming ─────────────────────────────────────────

    override suspend fun runStreaming(
        role: AgentRole,
        agentId: String,
        prompt: String,
        onChunk: (StreamChunk) -> Unit,
    ): String {
        val components = createLLMComponents()
        val toolExecutor = TextBasedToolExecutor(cwd)

        activeAgents[agentId] = RunningAgent(role)
        onChunk(StreamChunk.Heartbeat())

        return try {
            runStreamingAgentLoop(
                executor = components.executor,
                model = components.model,
                systemPrompt = components.systemPrompt,
                userPrompt = prompt,
                toolExecutor = toolExecutor,
                agentId = agentId,
                onChunk = onChunk,
            )
        } catch (e: Exception) {
            onChunk(StreamChunk.Error(e.message ?: "Unknown error", recoverable = false))
            "Error: ${e.message}"
        } finally {
            activeAgents.remove(agentId)
        }
    }

    /**
     * Streaming version of the agent loop.
     *
     * Streams text chunks to the caller while also extracting tool calls
     * from the accumulated response.
     */
    private suspend fun runStreamingAgentLoop(
        executor: SingleLLMPromptExecutor,
        model: LLModel,
        systemPrompt: String,
        userPrompt: String,
        toolExecutor: TextBasedToolExecutor,
        agentId: String,
        onChunk: (StreamChunk) -> Unit,
    ): String {
        val conversationMessages = mutableListOf<Pair<String, String>>()
        conversationMessages.add("user" to userPrompt)

        val fullOutput = StringBuilder()
        var lastResponse = ""

        for (iteration in 1..maxIterations) {
            if (activeAgents[agentId]?.cancelled == true) {
                return fullOutput.toString().ifEmpty { "[Agent cancelled]" }
            }

            val llmPrompt = prompt(id = "workspace-$agentId-iter$iteration") {
                system(systemPrompt)
                for ((role, content) in conversationMessages) {
                    when (role) {
                        "user" -> user(content)
                        "assistant" -> assistant(content)
                    }
                }
            }

            // Stream the LLM response
            val responseBuilder = StringBuilder()
            executor.executeStreaming(llmPrompt, model, tools = emptyList())
                .cancellable()
                .collect { frame ->
                    when (frame) {
                        is StreamFrame.Append -> {
                            responseBuilder.append(frame.text)
                            onChunk(StreamChunk.Text(frame.text))
                        }
                        is StreamFrame.End -> {
                            // Don't emit completed yet — we may have more iterations
                        }
                        is StreamFrame.ToolCall -> {
                            // Native tool calls shouldn't happen (we passed empty tools)
                            // but handle gracefully
                            onChunk(StreamChunk.ToolCall(frame.name, ToolCallStatus.IN_PROGRESS))
                        }
                    }
                }

            lastResponse = responseBuilder.toString()
            fullOutput.append(lastResponse)

            // Check for tool calls
            val toolCalls = ToolCallExtractor.extractToolCalls(lastResponse)

            if (toolCalls.isEmpty()) {
                onChunk(StreamChunk.Completed("end"))
                return fullOutput.toString()
            }

            // Notify about tool executions
            for (tc in toolCalls) {
                onChunk(StreamChunk.ToolCall(tc.name, ToolCallStatus.STARTED, tc.arguments.toString()))
            }

            // Add assistant response to conversation
            conversationMessages.add("assistant" to lastResponse)

            // Execute tools
            val results = toolExecutor.executeAll(toolCalls)

            // Notify completion of each tool
            for (result in results) {
                val status = if (result.success) ToolCallStatus.COMPLETED else ToolCallStatus.FAILED
                onChunk(StreamChunk.ToolCall(result.toolName, status, result = result.output.take(200)))
            }

            // Format results and add to conversation
            val resultMessage = toolExecutor.formatResults(results)
            conversationMessages.add("user" to resultMessage)

            // Add a visual separator in the stream
            onChunk(StreamChunk.Text("\n\n"))
            fullOutput.append("\n\n")
        }

        onChunk(StreamChunk.Completed("max_iterations"))
        return fullOutput.toString()
    }

    // ── AgentProvider: Health Check ──────────────────────────────────────

    override fun isHealthy(agentId: String): Boolean {
        val agent = activeAgents[agentId] ?: return true
        return !agent.cancelled
    }

    // ── AgentProvider: Interrupt ─────────────────────────────────────────

    override suspend fun interrupt(agentId: String) {
        activeAgents[agentId]?.cancelled = true
    }

    // ── AgentProvider: Capabilities ──────────────────────────────────────

    override fun capabilities(): ProviderCapabilities = ProviderCapabilities(
        name = "Workspace Agent (Text-Based Tool Calling)",
        supportsStreaming = true,
        supportsInterrupt = true,
        supportsHealthCheck = true,
        supportsFileEditing = true,
        supportsTerminal = false,
        supportsToolCalling = true,
        maxConcurrentAgents = 5,
        priority = 8,
    )

    // ── AgentProvider: Cleanup ───────────────────────────────────────────

    override suspend fun cleanup(agentId: String) {
        activeAgents.remove(agentId)
    }

    override suspend fun shutdown() {
        activeAgents.clear()
    }

    // ── Internal: LLM creation ──────────────────────────────────────────

    private data class LLMComponents(
        val executor: SingleLLMPromptExecutor,
        val model: LLModel,
        val systemPrompt: String,
    )

    private fun createLLMComponents(): LLMComponents {
        val config = modelConfig ?: RoutaConfigLoader.getActiveModelConfig()
            ?: throw IllegalStateException(
                "No active model config found. Please configure ~/.autodev/config.yaml"
            )

        return LLMComponents(
            executor = RoutaAgentFactory.createExecutor(config),
            model = RoutaAgentFactory.createModel(config),
            systemPrompt = buildSystemPrompt(cwd),
        )
    }
}
