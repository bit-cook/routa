package com.phodal.routa.core.koog

import ai.koog.agents.core.tools.SimpleTool
import ai.koog.agents.core.tools.ToolParameterType
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.*
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * Executes tool calls extracted from LLM text responses.
 *
 * Inspired by Intent's `agent-tool-executor.ts`. Instead of going through Koog's
 * native tool registry (which requires LLM function-calling parameters), this
 * executor works with text-based tool calls parsed by [ToolCallExtractor].
 *
 * ## Built-in tools (workspace file operations):
 * - `read_file` — read a file's contents
 * - `list_files` — list directory contents
 * - `write_file` — intentionally disabled (Coordinator is read-only)
 *
 * ## Dynamic tools (via [additionalTools]):
 * Any [SimpleTool] instance can be registered. The executor will:
 * 1. Match the tool call name to `tool.descriptor.name`
 * 2. Rebuild a typed [JsonObject] from the string arguments using the tool's descriptor
 * 3. Deserialize to the tool's typed args using its `argsSerializer`
 * 4. Call `tool.execute(args)` and return the result
 *
 * This enables the Workspace Agent to call agent coordination tools
 * (list_agents, create_agent, delegate, etc.) via text-based `<tool_call>` blocks.
 *
 * ## Usage
 *
 * ```kotlin
 * val agentToolInstances = RoutaToolRegistry.createToolsList(agentTools, workspaceId)
 * val executor = TextBasedToolExecutor(
 *     cwd = "/path/to/project",
 *     additionalTools = agentToolInstances,
 * )
 * val toolCalls = ToolCallExtractor.extractToolCalls(llmResponse)
 * val results = executor.executeAll(toolCalls)
 * val feedback = executor.formatResults(results)
 * ```
 */
class TextBasedToolExecutor(
    private val cwd: String,
    private val additionalTools: List<SimpleTool<*>> = emptyList(),
) {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true; coerceInputValues = true }

    /**
     * Result of a single tool execution.
     */
    data class ToolResult(
        val toolName: String,
        val success: Boolean,
        val output: String,
    )

    /**
     * Execute all tool calls and return results.
     */
    suspend fun executeAll(toolCalls: List<ToolCallExtractor.ToolCall>): List<ToolResult> {
        return toolCalls.map { execute(it) }
    }

    /**
     * Execute a single tool call.
     *
     * Resolution order:
     * 1. Built-in file tools (read_file, list_files, write_file)
     * 2. Dynamic tools from [additionalTools] (agent coordination tools, etc.)
     * 3. Error if no matching tool found
     */
    suspend fun execute(toolCall: ToolCallExtractor.ToolCall): ToolResult {
        return try {
            when (toolCall.name) {
                "read_file" -> executeReadFile(toolCall.arguments)
                "list_files" -> executeListFiles(toolCall.arguments)
                "write_file" -> ToolResult(
                    toolName = "write_file",
                    success = false,
                    output = "Error: write_file is not available. As a Coordinator, you cannot edit files directly. " +
                            "Create an @@@task block to delegate implementation to an Implementor agent.",
                )
                else -> {
                    // Try additional tools (agent coordination tools, etc.)
                    val tool = additionalTools.find { it.descriptor.name == toolCall.name }
                    if (tool != null) {
                        executeDynamicTool(tool, toolCall.arguments)
                    } else {
                        val availableTools = buildList {
                            addAll(listOf("read_file", "list_files"))
                            addAll(additionalTools.map { it.descriptor.name })
                        }
                        ToolResult(
                            toolName = toolCall.name,
                            success = false,
                            output = "Error: Unknown tool '${toolCall.name}'. Available tools: ${availableTools.joinToString(", ")}. " +
                                    "Note: write_file is not available — delegate to Implementor agents via @@@task blocks.",
                        )
                    }
                }
            }
        } catch (e: Exception) {
            ToolResult(
                toolName = toolCall.name,
                success = false,
                output = "Error executing ${toolCall.name}: ${e.message}",
            )
        }
    }

    /**
     * Format tool results into a message to feed back to the LLM.
     *
     * Each result is wrapped in `<tool_result>` XML for clear parsing by the LLM.
     */
    fun formatResults(results: List<ToolResult>): String {
        if (results.isEmpty()) return ""

        val parts = results.map { result ->
            val status = if (result.success) "success" else "error"
            buildString {
                appendLine("<tool_result>")
                appendLine("<tool_name>${result.toolName}</tool_name>")
                appendLine("<status>$status</status>")
                appendLine("<output>")
                appendLine(result.output)
                appendLine("</output>")
                appendLine("</tool_result>")
            }
        }

        return parts.joinToString("\n")
    }

    // ── Dynamic tool execution ──────────────────────────────────────────

    /**
     * Execute a dynamic [SimpleTool] by rebuilding typed JSON from the string arguments
     * and deserializing using the tool's own serializer.
     *
     * The tool's [ai.koog.agents.core.tools.ToolDescriptor] provides parameter type
     * information, allowing us to correctly convert string values to JSON booleans,
     * integers, arrays, etc. before deserialization.
     */
    @Suppress("UNCHECKED_CAST")
    private suspend fun executeDynamicTool(
        tool: SimpleTool<*>,
        args: Map<String, String>,
    ): ToolResult {
        return try {
            val jsonObj = rebuildJsonFromDescriptor(tool, args)
            val argsSerializer = tool.argsSerializer as KSerializer<Any>
            val typedArgs = json.decodeFromJsonElement(argsSerializer, jsonObj)
            val typedTool = tool as SimpleTool<Any>
            val result = typedTool.execute(typedArgs)
            ToolResult(toolName = tool.descriptor.name, success = true, output = result)
        } catch (e: Exception) {
            ToolResult(
                toolName = tool.descriptor.name,
                success = false,
                output = "Error executing ${tool.descriptor.name}: ${e.message}",
            )
        }
    }

    /**
     * Rebuild a [JsonObject] from string arguments using the tool's descriptor
     * for type information.
     *
     * This bridges the gap between [ToolCallExtractor] (which stores all args as strings)
     * and kotlinx.serialization (which needs correctly typed JSON values).
     *
     * Type mapping:
     * - [ToolParameterType.Boolean] → JSON boolean
     * - [ToolParameterType.Integer] → JSON number (long)
     * - [ToolParameterType.Float] → JSON number (double)
     * - [ToolParameterType.List] / [ToolParameterType.Object] → parsed JSON element
     * - Everything else → JSON string
     */
    private fun rebuildJsonFromDescriptor(
        tool: SimpleTool<*>,
        args: Map<String, String>,
    ): JsonObject {
        val allParams = tool.descriptor.requiredParameters + tool.descriptor.optionalParameters
        val paramTypes = allParams.associate { it.name to it.type }

        return buildJsonObject {
            for ((key, value) in args) {
                when (val paramType = paramTypes[key]) {
                    is ToolParameterType.Boolean ->
                        put(key, value.toBooleanStrictOrNull() ?: value.equals("true", ignoreCase = true))
                    is ToolParameterType.Integer ->
                        put(key, value.toLongOrNull() ?: 0L)
                    is ToolParameterType.Float ->
                        put(key, value.toDoubleOrNull() ?: 0.0)
                    is ToolParameterType.List -> {
                        try {
                            put(key, json.parseToJsonElement(value))
                        } catch (_: Exception) {
                            // If parsing fails, treat as a single-element array
                            put(key, buildJsonArray { add(JsonPrimitive(value)) })
                        }
                    }
                    is ToolParameterType.Object -> {
                        try {
                            put(key, json.parseToJsonElement(value))
                        } catch (_: Exception) {
                            put(key, JsonPrimitive(value))
                        }
                    }
                    else -> put(key, value) // String, Enum, unknown → JSON string
                }
            }
        }
    }

    // ── Built-in tool implementations ───────────────────────────────────

    private fun executeReadFile(args: Map<String, String>): ToolResult {
        val filePath = args["path"]
            ?: return ToolResult("read_file", false, "Error: 'path' argument is required")

        val resolved = resolveSafely(filePath)
            ?: return ToolResult("read_file", false, "Error: Access denied — path outside workspace")

        val file = resolved.toFile()
        if (!file.exists()) {
            return ToolResult("read_file", false, "Error: File not found: $filePath")
        }
        if (!file.isFile) {
            return ToolResult("read_file", false, "Error: Not a file: $filePath")
        }

        val content = file.readText()
        return ToolResult("read_file", true, content)
    }

    private fun executeListFiles(args: Map<String, String>): ToolResult {
        val dirPath = args["path"]?.ifBlank { "." } ?: "."

        val resolved = resolveSafely(dirPath)
            ?: return ToolResult("list_files", false, "Error: Access denied — path outside workspace")

        val dir = resolved.toFile()
        if (!dir.exists()) {
            return ToolResult("list_files", false, "Error: Directory not found: $dirPath")
        }
        if (!dir.isDirectory) {
            return ToolResult("list_files", false, "Error: Not a directory: $dirPath")
        }

        val entries = dir.listFiles()
            ?.sortedBy { it.name }
            ?.joinToString("\n") { entry ->
                val type = if (entry.isDirectory) "[dir]" else "[file]"
                "$type ${entry.name}"
            }
            ?: "(empty)"

        return ToolResult("list_files", true, entries)
    }

    // ── Path safety ─────────────────────────────────────────────────────

    private fun resolveSafely(relativePath: String): Path? {
        val base = Paths.get(cwd).toAbsolutePath().normalize()
        val resolved = base.resolve(relativePath).normalize()
        return if (resolved.startsWith(base)) resolved else null
    }
}
