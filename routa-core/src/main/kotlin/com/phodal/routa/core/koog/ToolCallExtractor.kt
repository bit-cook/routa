package com.phodal.routa.core.koog

import kotlinx.serialization.json.*

/**
 * Extracts tool calls from agent LLM text responses.
 *
 * Supports multiple formats (inspired by Intent's tool-call-extractor.ts):
 * - XML format: `<tool_call>{"name": "...", "arguments": {...}}</tool_call>`
 * - Markdown JSON blocks: ````json {"name": "...", "arguments": {...}} ````
 *
 * The XML `<tool_call>` format is preferred and most reliable. The system prompt
 * instructs the LLM to use this format exclusively.
 *
 * ## Example
 *
 * ```
 * val response = "Let me read the file.\n<tool_call>{\"name\": \"read_file\", \"arguments\": {\"path\": \"src/main.kt\"}}</tool_call>"
 * val calls = ToolCallExtractor.extractToolCalls(response)
 * // calls = [ToolCall(name="read_file", arguments={"path": "src/main.kt"})]
 * ```
 */
object ToolCallExtractor {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * A parsed tool call from an LLM response.
     */
    data class ToolCall(
        val name: String,
        val arguments: Map<String, String>,
    )

    /**
     * Extract all tool calls from the given response text.
     *
     * Tries XML `<tool_call>` tags first, then falls back to markdown code blocks.
     */
    fun extractToolCalls(response: String): List<ToolCall> {
        if (response.isBlank()) return emptyList()

        val toolCalls = mutableListOf<ToolCall>()

        // 1. Try XML format: <tool_call>{"name":"...", "arguments":{...}}</tool_call>
        val xmlRegex = Regex("""<tool_call>\s*([\s\S]*?)\s*</tool_call>""")
        for (match in xmlRegex.findAll(response)) {
            val parsed = parseToolCallJson(match.groupValues[1].trim())
            if (parsed != null) {
                toolCalls.add(parsed)
            }
        }

        if (toolCalls.isNotEmpty()) return toolCalls

        // 2. Try markdown code block format: ```json {...} ```
        val markdownRegex = Regex("""```(?:json)?\s*([\s\S]*?)```""")
        for (match in markdownRegex.findAll(response)) {
            val content = match.groupValues[1].trim()
            val parsed = parseToolCallJson(content)
            if (parsed != null && !toolCalls.any { it.name == parsed.name }) {
                toolCalls.add(parsed)
            }
        }

        return toolCalls
    }

    /**
     * Check if a response contains any tool calls.
     */
    fun hasToolCalls(response: String): Boolean {
        return response.contains("<tool_call>") ||
            extractToolCalls(response).isNotEmpty()
    }

    /**
     * Remove tool call blocks from response text for clean display.
     */
    fun removeToolCalls(response: String): String {
        var cleaned = response
        cleaned = cleaned.replace(Regex("""<tool_call>\s*[\s\S]*?\s*</tool_call>"""), "")
        return cleaned.trim()
    }

    /**
     * Parse a JSON string into a ToolCall.
     *
     * Expected format: `{"name": "tool_name", "arguments": {"key": "value"}}`
     */
    private fun parseToolCallJson(jsonStr: String): ToolCall? {
        return try {
            val element = json.parseToJsonElement(jsonStr)
            if (element !is JsonObject) return null

            val name = element["name"]?.jsonPrimitive?.content ?: return null
            val argsElement = element["arguments"]
            if (argsElement == null || argsElement !is JsonObject) return null

            val arguments = mutableMapOf<String, String>()
            for ((key, value) in argsElement) {
                arguments[key] = when (value) {
                    is JsonPrimitive -> value.content
                    else -> value.toString()
                }
            }

            ToolCall(name = name, arguments = arguments)
        } catch (e: Exception) {
            null
        }
    }
}
