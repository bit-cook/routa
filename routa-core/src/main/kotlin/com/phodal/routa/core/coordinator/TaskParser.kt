package com.phodal.routa.core.coordinator

import com.phodal.routa.core.model.Task
import com.phodal.routa.core.model.TaskStatus
import java.time.Instant
import java.util.UUID

/**
 * Parses `@@@task` blocks from Routa's planning output into [Task] objects.
 *
 * The `@@@task` block format:
 * ```
 * @@@task
 * # Task Title
 *
 * ## Objective
 * Clear statement
 *
 * ## Scope
 * - file1.kt
 * - file2.kt
 *
 * ## Definition of Done
 * - Acceptance criteria 1
 * - Acceptance criteria 2
 *
 * ## Verification
 * - ./gradlew test
 * @@@
 * ```
 *
 * Supports both English and Chinese section headers:
 * - Objective / 目标
 * - Scope / 范围
 * - Definition of Done / 完成标准 / 验收标准
 * - Verification / 验证
 */
object TaskParser {

    private val TASK_BLOCK_REGEX = Regex(
        """@@@task\s*\n(.*?)@@@""",
        setOf(RegexOption.DOT_MATCHES_ALL),
    )

    /**
     * Section name aliases: maps each canonical section to all recognized header names.
     * Supports English and Chinese headers that LLMs commonly produce.
     */
    private val SECTION_ALIASES = mapOf(
        "Objective" to listOf("Objective", "目标", "Goal", "目的"),
        "Scope" to listOf("Scope", "范围", "作用域"),
        "Definition of Done" to listOf(
            "Definition of Done", "完成标准", "验收标准",
            "Acceptance Criteria", "Done Criteria", "完成条件",
        ),
        "Verification" to listOf("Verification", "验证", "Verify", "验证方法", "测试验证"),
    )

    /**
     * Parse all `@@@task` blocks from the given text.
     *
     * If the LLM places multiple tasks inside a single `@@@task` block
     * (identified by multiple `# ` level-1 headers), they are automatically
     * split into separate tasks.
     *
     * @param text The Routa output containing task blocks.
     * @param workspaceId The workspace these tasks belong to.
     * @return List of parsed tasks.
     */
    fun parse(text: String, workspaceId: String): List<Task> {
        val blocks = TASK_BLOCK_REGEX.findAll(text).map { it.groupValues[1].trim() }.toList()

        if (blocks.isEmpty()) return emptyList()

        return blocks.flatMap { block ->
            splitMultiTaskBlock(block).map { subBlock ->
                parseTaskBlock(subBlock, workspaceId)
            }
        }
    }

    /**
     * Split a single block that may contain multiple tasks (multiple `# ` headers)
     * into separate sub-blocks — one per task.
     *
     * If the block contains only one `# ` header (or none), it is returned as-is.
     *
     * **Important:** Lines inside markdown code fences (``` ... ```) are ignored
     * when scanning for `# ` title headers. This prevents bash comments like
     * `# Check if file exists` inside verification code blocks from being
     * mistaken for task titles.
     */
    internal fun splitMultiTaskBlock(block: String): List<String> {
        val lines = block.lines()

        // Build a set of line indices that are inside code fences
        val insideCodeFence = BooleanArray(lines.size)
        var inFence = false
        for (i in lines.indices) {
            val trimmed = lines[i].trim()
            if (trimmed.startsWith("```")) {
                inFence = !inFence
                insideCodeFence[i] = true // The fence line itself is "inside"
            } else {
                insideCodeFence[i] = inFence
            }
        }

        // Find `# ` title lines that are NOT inside code fences
        val titleIndices = lines.indices.filter { i ->
            !insideCodeFence[i] &&
                lines[i].startsWith("# ") &&
                !lines[i].startsWith("## ")
        }

        // 0 or 1 title → single task block
        if (titleIndices.size <= 1) return listOf(block)

        // Multiple titles → split at each `# ` boundary
        val subBlocks = mutableListOf<String>()
        for (i in titleIndices.indices) {
            val start = titleIndices[i]
            val end = if (i + 1 < titleIndices.size) titleIndices[i + 1] else lines.size
            val subBlock = lines.subList(start, end).joinToString("\n").trim()
            if (subBlock.isNotEmpty()) {
                subBlocks.add(subBlock)
            }
        }
        return subBlocks
    }

    internal fun parseTaskBlock(block: String, workspaceId: String): Task {
        val lines = block.lines()

        // Find the title — must be outside code fences
        val title = findTitleOutsideCodeFences(lines)

        val objective = extractSectionWithAliases(lines, "Objective")
        val scope = extractListSectionWithAliases(lines, "Scope")
        val acceptanceCriteria = extractListSectionWithAliases(lines, "Definition of Done")
        val verificationCommands = extractListSectionWithAliases(lines, "Verification")

        val now = Instant.now().toString()
        return Task(
            id = UUID.randomUUID().toString(),
            title = title,
            objective = objective,
            scope = scope,
            acceptanceCriteria = acceptanceCriteria,
            verificationCommands = verificationCommands,
            status = TaskStatus.PENDING,
            workspaceId = workspaceId,
            createdAt = now,
            updatedAt = now,
        )
    }

    /**
     * Find the first `# ` title line that is not inside a code fence.
     */
    private fun findTitleOutsideCodeFences(lines: List<String>): String {
        var inFence = false
        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.startsWith("```")) {
                inFence = !inFence
                continue
            }
            if (!inFence && line.startsWith("# ") && !line.startsWith("## ")) {
                return line.removePrefix("# ").trim()
            }
        }
        return "Untitled Task"
    }

    /**
     * Extract a text section, trying all known aliases for the given section name.
     */
    private fun extractSectionWithAliases(lines: List<String>, canonicalName: String): String {
        val aliases = SECTION_ALIASES[canonicalName] ?: listOf(canonicalName)
        for (alias in aliases) {
            val result = extractSection(lines, alias)
            if (result.isNotEmpty()) return result
        }
        return ""
    }

    /**
     * Extract list items, trying all known aliases for the given section name.
     */
    private fun extractListSectionWithAliases(lines: List<String>, canonicalName: String): List<String> {
        val aliases = SECTION_ALIASES[canonicalName] ?: listOf(canonicalName)
        for (alias in aliases) {
            val result = extractListSection(lines, alias)
            if (result.isNotEmpty()) return result
        }
        return emptyList()
    }

    /**
     * Extract a text section between `## SectionName` and the next `##` or end.
     */
    private fun extractSection(lines: List<String>, sectionName: String): String {
        val startIdx = lines.indexOfFirst { it.trim().startsWith("## $sectionName") }
        if (startIdx == -1) return ""

        val contentLines = mutableListOf<String>()
        for (i in (startIdx + 1) until lines.size) {
            val line = lines[i]
            if (line.trim().startsWith("## ")) break
            contentLines.add(line)
        }
        return contentLines.joinToString("\n").trim()
    }

    /**
     * Extract list items (lines starting with `-`) from a section.
     */
    private fun extractListSection(lines: List<String>, sectionName: String): List<String> {
        val section = extractSection(lines, sectionName)
        return section.lines()
            .filter { it.trim().startsWith("-") }
            .map { it.trim().removePrefix("-").trim() }
            .filter { it.isNotEmpty() }
    }
}
