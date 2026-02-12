package com.phodal.routa.core.koog

import com.phodal.routa.core.RoutaFactory
import com.phodal.routa.core.model.AgentRole
import com.phodal.routa.core.model.Task
import com.phodal.routa.core.model.TaskStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/**
 * Tests for [TextBasedToolExecutor] with dynamic tool dispatch.
 *
 * Verifies that the executor can route text-based tool calls to:
 * 1. Built-in file tools (read_file, list_files)
 * 2. Dynamic agent coordination tools (list_agents, create_agent, etc.)
 */
class TextBasedToolExecutorTest {

    private val json = Json { ignoreUnknownKeys = true }

    private fun withExecutor(
        block: suspend (TextBasedToolExecutor, com.phodal.routa.core.RoutaSystem, String) -> Unit
    ) {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val routa = RoutaFactory.createInMemory(scope)
        val routaId = runBlocking { routa.coordinator.initialize("test-workspace") }

        val coordinationTools = RoutaToolRegistry.createToolsList(routa.tools, "test-workspace")
        val executor = TextBasedToolExecutor(
            cwd = System.getProperty("user.dir") ?: ".",
            additionalTools = coordinationTools,
        )

        try {
            runBlocking { block(executor, routa, routaId) }
        } finally {
            routa.coordinator.shutdown()
        }
    }

    // ── Built-in tools still work ────────────────────────────────────────

    @Test
    fun `built-in list_files tool works`() = withExecutor { executor, _, _ ->
        val result = executor.execute(
            ToolCallExtractor.ToolCall(
                name = "list_files",
                arguments = mapOf("path" to "."),
            )
        )
        assertTrue("list_files should succeed", result.success)
        assertTrue("Should list files", result.output.isNotBlank())
    }

    @Test
    fun `write_file tool is still blocked`() = withExecutor { executor, _, _ ->
        val result = executor.execute(
            ToolCallExtractor.ToolCall(
                name = "write_file",
                arguments = mapOf("path" to "test.txt", "content" to "hello"),
            )
        )
        assertFalse("write_file should be blocked", result.success)
        assertTrue("Should mention Coordinator", result.output.contains("Coordinator"))
    }

    // ── Dynamic agent coordination tools ────────────────────────────────

    @Test
    fun `list_agents tool works via dynamic dispatch`() = withExecutor { executor, _, _ ->
        val result = executor.execute(
            ToolCallExtractor.ToolCall(
                name = "list_agents",
                arguments = mapOf("workspaceId" to "test-workspace"),
            )
        )
        assertTrue("list_agents should succeed: ${result.output}", result.success)
        assertTrue("Should contain routa-main", result.output.contains("routa-main"))
        assertTrue("Should contain ROUTA", result.output.contains("ROUTA"))
    }

    @Test
    fun `create_agent tool works via dynamic dispatch`() = withExecutor { executor, _, routaId ->
        val result = executor.execute(
            ToolCallExtractor.ToolCall(
                name = "create_agent",
                arguments = mapOf(
                    "name" to "test-crafter",
                    "role" to "CRAFTER",
                    "parentId" to routaId,
                ),
            )
        )
        assertTrue("create_agent should succeed: ${result.output}", result.success)
        assertTrue("Should contain test-crafter", result.output.contains("test-crafter"))
        assertTrue("Should contain CRAFTER", result.output.contains("CRAFTER"))
    }

    @Test
    fun `get_agent_status tool works via dynamic dispatch`() = withExecutor { executor, _, routaId ->
        val result = executor.execute(
            ToolCallExtractor.ToolCall(
                name = "get_agent_status",
                arguments = mapOf("agentId" to routaId),
            )
        )
        assertTrue("get_agent_status should succeed: ${result.output}", result.success)
        assertTrue("Should contain ROUTA", result.output.contains("ROUTA"))
        assertTrue("Should contain ACTIVE", result.output.contains("ACTIVE"))
    }

    @Test
    fun `get_agent_summary tool works via dynamic dispatch`() = withExecutor { executor, routa, routaId ->
        // Add a message so the summary has content
        routa.tools.messageAgent(routaId, routaId, "Test message for summary")

        val result = executor.execute(
            ToolCallExtractor.ToolCall(
                name = "get_agent_summary",
                arguments = mapOf("agentId" to routaId),
            )
        )
        assertTrue("get_agent_summary should succeed: ${result.output}", result.success)
        assertTrue("Should contain Agent Summary", result.output.contains("Agent Summary"))
        assertTrue("Should contain routa-main", result.output.contains("routa-main"))
    }

    @Test
    fun `send_message_to_agent tool works via dynamic dispatch`() = withExecutor { executor, _, routaId ->
        val result = executor.execute(
            ToolCallExtractor.ToolCall(
                name = "send_message_to_agent",
                arguments = mapOf(
                    "fromAgentId" to routaId,
                    "toAgentId" to routaId,
                    "message" to "Hello from text-based tool call",
                ),
            )
        )
        assertTrue("send_message_to_agent should succeed: ${result.output}", result.success)
        assertTrue("Should contain sent", result.output.contains("sent"))
    }

    @Test
    fun `read_agent_conversation tool works via dynamic dispatch`() = withExecutor { executor, routa, routaId ->
        // Send a message first
        routa.tools.messageAgent(routaId, routaId, "Test conversation message")

        val result = executor.execute(
            ToolCallExtractor.ToolCall(
                name = "read_agent_conversation",
                arguments = mapOf("agentId" to routaId),
            )
        )
        assertTrue("read_agent_conversation should succeed: ${result.output}", result.success)
        assertTrue("Should contain message", result.output.contains("Test conversation message"))
    }

    @Test
    fun `wake_or_create_task_agent tool works via dynamic dispatch`() = withExecutor { executor, routa, routaId ->
        // Create a task first
        val task = Task(
            id = "task-exec-1",
            title = "Test Task for Executor",
            objective = "Test dynamic dispatch",
            workspaceId = "test-workspace",
            createdAt = Instant.now().toString(),
            updatedAt = Instant.now().toString(),
        )
        routa.context.taskStore.save(task)

        val result = executor.execute(
            ToolCallExtractor.ToolCall(
                name = "wake_or_create_task_agent",
                arguments = mapOf(
                    "taskId" to "task-exec-1",
                    "contextMessage" to "Start working on test task",
                    "callerAgentId" to routaId,
                ),
            )
        )
        assertTrue("wake_or_create_task_agent should succeed: ${result.output}", result.success)
        assertTrue("Should contain created_new", result.output.contains("created_new"))
    }

    @Test
    fun `send_message_to_task_agent tool works via dynamic dispatch`() = withExecutor { executor, routa, routaId ->
        // Create a crafter and assign to a task
        val crafterResult = routa.tools.createAgent(
            name = "test-crafter",
            role = AgentRole.CRAFTER,
            workspaceId = "test-workspace",
            parentId = routaId,
        )
        val crafterId = json.parseToJsonElement(crafterResult.data)
            .jsonObject["id"]!!.jsonPrimitive.content

        val task = Task(
            id = "task-exec-2",
            title = "Test Task 2",
            objective = "Test",
            assignedTo = crafterId,
            workspaceId = "test-workspace",
            createdAt = Instant.now().toString(),
            updatedAt = Instant.now().toString(),
        )
        routa.context.taskStore.save(task)

        val result = executor.execute(
            ToolCallExtractor.ToolCall(
                name = "send_message_to_task_agent",
                arguments = mapOf(
                    "taskId" to "task-exec-2",
                    "message" to "Fix the tests",
                    "callerAgentId" to routaId,
                ),
            )
        )
        assertTrue("send_message_to_task_agent should succeed: ${result.output}", result.success)
        assertTrue("Should contain sent", result.output.contains("sent"))
    }

    @Test
    fun `subscribe_to_events tool works with list arguments`() = withExecutor { executor, _, routaId ->
        val result = executor.execute(
            ToolCallExtractor.ToolCall(
                name = "subscribe_to_events",
                arguments = mapOf(
                    "agentId" to routaId,
                    "agentName" to "routa-main",
                    "eventTypes" to """["agent:*", "task:*"]""",
                ),
            )
        )
        assertTrue("subscribe_to_events should succeed: ${result.output}", result.success)
        assertTrue("Should contain subscriptionId", result.output.contains("subscriptionId"))
    }

    // ── Error cases ─────────────────────────────────────────────────────

    @Test
    fun `unknown tool returns error listing all available tools`() = withExecutor { executor, _, _ ->
        val result = executor.execute(
            ToolCallExtractor.ToolCall(
                name = "nonexistent_tool",
                arguments = emptyMap(),
            )
        )
        assertFalse("Should fail for unknown tool", result.success)
        assertTrue("Should mention available tools", result.output.contains("list_agents"))
        assertTrue("Should mention file tools", result.output.contains("read_file"))
    }

    @Test
    fun `create_agent with invalid role returns error`() = withExecutor { executor, _, _ ->
        val result = executor.execute(
            ToolCallExtractor.ToolCall(
                name = "create_agent",
                arguments = mapOf("name" to "bad-agent", "role" to "INVALID"),
            )
        )
        assertTrue("create_agent should succeed (error is in tool output)", result.success)
        assertTrue("Output should mention invalid role", result.output.contains("Invalid role"))
    }

    @Test
    fun `get_agent_status for nonexistent agent returns error`() = withExecutor { executor, _, _ ->
        val result = executor.execute(
            ToolCallExtractor.ToolCall(
                name = "get_agent_status",
                arguments = mapOf("agentId" to "nonexistent-id"),
            )
        )
        assertTrue("Tool execution itself should succeed", result.success)
        assertTrue("Output should contain error", result.output.contains("not found"))
    }

    // ── RoutaToolRegistry.createToolsList ────────────────────────────────

    @Test
    fun `createToolsList returns 12 coordination tools`() {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val routa = RoutaFactory.createInMemory(scope)
        try {
            val tools = RoutaToolRegistry.createToolsList(routa.tools, "test-workspace")
            assertEquals("Should have 12 coordination tools", 12, tools.size)

            val names = tools.map { it.descriptor.name }.toSet()
            assertTrue("Should include list_agents", "list_agents" in names)
            assertTrue("Should include create_agent", "create_agent" in names)
            assertTrue("Should include delegate_task", "delegate_task" in names)
            assertTrue("Should include report_to_parent", "report_to_parent" in names)
            assertTrue("Should include wake_or_create_task_agent", "wake_or_create_task_agent" in names)
            assertTrue("Should include get_agent_status", "get_agent_status" in names)
            assertTrue("Should include get_agent_summary", "get_agent_summary" in names)
            assertTrue("Should include subscribe_to_events", "subscribe_to_events" in names)
            assertTrue("Should include unsubscribe_from_events", "unsubscribe_from_events" in names)
        } finally {
            routa.coordinator.shutdown()
        }
    }
}
