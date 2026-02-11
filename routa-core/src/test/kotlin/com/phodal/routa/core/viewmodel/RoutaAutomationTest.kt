package com.phodal.routa.core.viewmodel

import com.phodal.routa.core.model.AgentRole
import com.phodal.routa.core.model.AgentStatus
import com.phodal.routa.core.provider.AgentProvider
import com.phodal.routa.core.provider.ProviderCapabilities
import com.phodal.routa.core.provider.StreamChunk
import com.phodal.routa.core.runner.OrchestratorPhase
import com.phodal.routa.core.runner.OrchestratorResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

/**
 * Automation tests demonstrating how to use [RoutaViewModel] for scripted
 * testing of the full ROUTA → CRAFTER → GATE pipeline.
 *
 * These tests show how to:
 * - Observe all tasks planned by ROUTA (keyed by taskId)
 * - Track each CRAFTER's lifecycle (PENDING → ACTIVE → COMPLETED)
 * - Verify the debug log for traceability
 * - Assert task ordering and execution flow
 * - Use the ViewModel as a headless automation engine
 *
 * ## Usage Pattern (for your own automation scripts)
 * ```kotlin
 * val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
 * val vm = RoutaViewModel(scope)
 * vm.useEnhancedRoutaPrompt = false  // or true for production
 *
 * val provider = YourCustomProvider(...)
 * vm.initialize(provider, "workspace-id")
 *
 * // Observe state reactively
 * scope.launch {
 *     vm.crafterStates.collectLatest { states ->
 *         states.forEach { (taskId, state) ->
 *             println("[${state.status}] ${state.taskTitle} (task=$taskId, agent=${state.agentId})")
 *         }
 *     }
 * }
 *
 * // Execute and assert
 * val result = vm.execute("Your request here")
 * assert(result is OrchestratorResult.Success)
 *
 * // Check debug log
 * vm.debugLog.entries.forEach { println(it) }
 *
 * vm.dispose()
 * scope.cancel()
 * ```
 */
class RoutaAutomationTest {

    // ── Scriptable Provider ─────────────────────────────────────────────

    /**
     * A provider that lets you script different responses per task.
     * Useful for testing specific scenarios (e.g., task 2 fails, task 3 is slow).
     */
    private class ScriptableProvider(
        private val planOutput: String,
        private val crafterResponses: Map<Int, String> = emptyMap(),
        private val gateOutput: String = GATE_APPROVED,
    ) : AgentProvider {
        private var crafterCallIndex = 0

        /** Log of all (role, agentId, promptPreview) calls. */
        val callLog = mutableListOf<Triple<AgentRole, String, String>>()

        override suspend fun run(role: AgentRole, agentId: String, prompt: String): String {
            callLog.add(Triple(role, agentId, prompt.take(100)))
            return when (role) {
                AgentRole.ROUTA -> planOutput
                AgentRole.CRAFTER -> {
                    val index = crafterCallIndex++
                    crafterResponses[index] ?: CRAFTER_SUCCESS
                }
                AgentRole.GATE -> gateOutput
            }
        }

        override suspend fun runStreaming(
            role: AgentRole,
            agentId: String,
            prompt: String,
            onChunk: (StreamChunk) -> Unit,
        ): String {
            val output = run(role, agentId, prompt)
            // Simulate streaming in small chunks
            output.chunked(50).forEach { part ->
                onChunk(StreamChunk.Text(part))
            }
            onChunk(StreamChunk.Completed("end_turn"))
            return output
        }

        override fun capabilities() = ProviderCapabilities(
            name = "ScriptableProvider",
            supportsStreaming = true,
            supportsFileEditing = true,
            supportsTerminal = true,
            supportsToolCalling = true,
        )
    }

    // ── Automation Test: Full Lifecycle ──────────────────────────────────

    @Test
    fun `automation - observe full task lifecycle from PENDING to COMPLETED`() {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val vm = RoutaViewModel(scope)
        vm.useEnhancedRoutaPrompt = false

        // ── Execute ──
        val provider = ScriptableProvider(planOutput = PLAN_THREE_TASKS)
        vm.initialize(provider, "auto-test-workspace")

        val result = runBlocking { vm.execute("Create user management") }

        // ── Assertions ──

        // 1. Should succeed
        assertTrue("Expected Success but got: $result", result is OrchestratorResult.Success)

        // 2. Final state: all 3 tasks completed with titles and agentIds
        val finalStates = vm.crafterStates.value
        assertEquals("Final state should have 3 tasks", 3, finalStates.size)
        for ((taskId, state) in finalStates) {
            assertEquals("Task $taskId should be COMPLETED", AgentStatus.COMPLETED, state.status)
            assertTrue("Task $taskId should have a title", state.taskTitle.isNotBlank())
            assertTrue("Task $taskId should have an agentId", state.agentId.isNotBlank())
        }

        // 3. Execution order: ROUTA → 3 CRAFTERs → GATE
        val roles = provider.callLog.map { it.first }
        assertEquals(AgentRole.ROUTA, roles[0])
        assertEquals(AgentRole.CRAFTER, roles[1])
        assertEquals(AgentRole.CRAFTER, roles[2])
        assertEquals(AgentRole.CRAFTER, roles[3])
        assertEquals(AgentRole.GATE, roles[4])

        // 4. Debug log captures the full lifecycle (not conflated like StateFlow)
        //    Use the debug log to verify PENDING → ACTIVE → COMPLETED transitions
        val taskPlannedEntries = vm.debugLog.entries.filter {
            it.category == DebugCategory.TASK && it.message.contains("planned")
        }
        assertEquals("Should log 3 planned tasks", 3, taskPlannedEntries.size)

        val crafterRunningEntries = vm.debugLog.entries.filter {
            it.message.contains("CRAFTER running")
        }
        assertEquals("Should log 3 CRAFTER running events", 3, crafterRunningEntries.size)

        val crafterCompletedEntries = vm.debugLog.entries.filter {
            it.message.contains("CRAFTER completed")
        }
        assertEquals("Should log 3 CRAFTER completed events", 3, crafterCompletedEntries.size)

        // 5. Task titles are captured in debug log
        val titles = taskPlannedEntries.mapNotNull { it.details["title"] }
        assertTrue("Should have 'Create User Model'", titles.any { it.contains("User Model") })
        assertTrue("Should have 'Implement User API'", titles.any { it.contains("User API") })
        assertTrue("Should have 'Add Authentication'", titles.any { it.contains("Authentication") })

        vm.dispose()
        scope.cancel()
    }

    // ── Automation Test: Debug Log Traceability ─────────────────────────

    @Test
    fun `automation - debug log traces full execution for troubleshooting`() {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val vm = RoutaViewModel(scope)
        vm.useEnhancedRoutaPrompt = false

        val provider = ScriptableProvider(planOutput = PLAN_THREE_TASKS)
        vm.initialize(provider, "debug-test-workspace")

        runBlocking { vm.execute("Build API endpoints") }

        // ── Verify debug log completeness ──

        val log = vm.debugLog

        // Phase transitions
        val phaseEntries = log.entries.filter { it.category == DebugCategory.PHASE }
        assertTrue("Should log phase transitions", phaseEntries.size >= 5)

        // Task parsing
        val taskEntries = log.entries.filter { it.category == DebugCategory.TASK && it.message.contains("planned") }
        assertEquals("Should log 3 planned tasks", 3, taskEntries.size)

        // Each task should have title in the log
        for (entry in taskEntries) {
            assertTrue("Task entry should have title: ${entry.details}",
                entry.details.containsKey("title"))
            assertTrue("Task title should not be empty",
                entry.details["title"]!!.isNotBlank())
        }

        // CRAFTER agent starts
        val crafterStarts = log.entries.filter { it.message.contains("CRAFTER running") }
        assertEquals("Should log 3 CRAFTER starts", 3, crafterStarts.size)

        // Can dump full log as string for debugging
        val dump = log.dump()
        assertTrue("Dump should contain multiple lines", dump.lines().size > 10)

        vm.dispose()
        scope.cancel()
    }

    // ── Automation Test: Streaming Observation ──────────────────────────

    @Test
    fun `automation - observe streaming chunks per task`() {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val vm = RoutaViewModel(scope)
        vm.useEnhancedRoutaPrompt = false

        // Track chunks by taskId
        val chunksByTask = mutableMapOf<String, MutableList<StreamChunk>>()
        val chunkJob = scope.launch {
            vm.crafterChunks.collect { (taskId, chunk) ->
                chunksByTask.getOrPut(taskId) { mutableListOf() }.add(chunk)
            }
        }

        val provider = ScriptableProvider(
            planOutput = PLAN_TWO_TASKS,
            crafterResponses = mapOf(
                0 to "Implemented login endpoint with JWT.",
                1 to "Added user registration with email validation.",
            ),
        )
        vm.initialize(provider, "stream-test-workspace")

        runBlocking { vm.execute("Add auth") }
        runBlocking { delay(100) }
        chunkJob.cancel()

        // Each task should have received streaming chunks
        assertEquals("Should have chunks for 2 tasks", 2, chunksByTask.size)

        for ((taskId, chunks) in chunksByTask) {
            assertTrue("Task $taskId should have chunks", chunks.isNotEmpty())
            // Should have at least Text and Completed chunks
            assertTrue("Task $taskId should have Text chunks",
                chunks.any { it is StreamChunk.Text })
            assertTrue("Task $taskId should have Completed chunk",
                chunks.any { it is StreamChunk.Completed })
        }

        vm.dispose()
        scope.cancel()
    }

    // ── Automation Test: Pre-populated PENDING State ─────────────────────

    @Test
    fun `automation - all tasks appear as PENDING immediately after planning`() {
        val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
        val vm = RoutaViewModel(scope)
        vm.useEnhancedRoutaPrompt = false

        // Capture the first non-empty crafterStates snapshot
        var firstSnapshot: Map<String, CrafterStreamState>? = null
        val snapshotJob = scope.launch {
            vm.crafterStates.filter { it.isNotEmpty() }.first().let { states ->
                firstSnapshot = states.toMap()
            }
        }

        val provider = ScriptableProvider(planOutput = PLAN_THREE_TASKS)
        vm.initialize(provider, "prepop-test-workspace")

        runBlocking { vm.execute("Build things") }
        runBlocking { delay(100) }
        snapshotJob.cancel()

        // The first snapshot should have ALL 3 tasks in PENDING status
        assertNotNull("Should capture first snapshot", firstSnapshot)
        assertEquals("First snapshot should have 3 tasks", 3, firstSnapshot!!.size)

        for ((taskId, state) in firstSnapshot!!) {
            assertEquals("Task $taskId should initially be PENDING", AgentStatus.PENDING, state.status)
            assertTrue("Task $taskId should have a title", state.taskTitle.isNotBlank())
            assertEquals("Task $taskId agentId should be empty initially", "", state.agentId)
        }

        vm.dispose()
        scope.cancel()
    }

    // ── Test Data ───────────────────────────────────────────────────────

    companion object {
        val PLAN_TWO_TASKS = """
            @@@task
            # Implement Login API
            ## Objective
            Create a POST /api/login endpoint with JWT authentication
            ## Scope
            - src/auth/LoginController.kt
            ## Definition of Done
            - POST /api/login accepts email + password
            ## Verification
            - ./gradlew test --tests LoginControllerTest
            @@@

            @@@task
            # Add User Registration
            ## Objective
            Create a POST /api/register endpoint
            ## Scope
            - src/user/RegisterController.kt
            ## Definition of Done
            - POST /api/register creates a user
            ## Verification
            - ./gradlew test --tests RegisterControllerTest
            @@@
        """.trimIndent()

        val PLAN_THREE_TASKS = """
            @@@task
            # Create User Model
            ## Objective
            Define the User entity and repository
            ## Scope
            - src/model/User.kt
            - src/repository/UserRepository.kt
            ## Definition of Done
            - User entity created with id, name, email
            - Repository interface defined
            ## Verification
            - ./gradlew test --tests UserModelTest
            @@@

            @@@task
            # Implement User API
            ## Objective
            Create REST endpoints for user CRUD
            ## Scope
            - src/api/UserController.kt
            ## Definition of Done
            - GET /users, POST /users, GET /users/{id}
            ## Verification
            - ./gradlew test --tests UserControllerTest
            @@@

            @@@task
            # Add Authentication
            ## Objective
            Add JWT-based authentication middleware
            ## Scope
            - src/auth/JwtFilter.kt
            - src/auth/AuthConfig.kt
            ## Definition of Done
            - JWT validation on protected routes
            - Login endpoint returns token
            ## Verification
            - ./gradlew test --tests AuthTest
            @@@
        """.trimIndent()

        val CRAFTER_SUCCESS = """
            I've implemented the task as requested.
            Changes made:
            - Created the required files
            - Added tests
            All tests pass.
        """.trimIndent()

        val GATE_APPROVED = """
            ### Verification Summary
            - Verdict: ✅ APPROVED
            - Confidence: High
            All acceptance criteria verified.
        """.trimIndent()
    }
}
