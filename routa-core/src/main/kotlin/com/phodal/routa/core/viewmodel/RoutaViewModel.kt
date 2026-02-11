package com.phodal.routa.core.viewmodel

import com.phodal.routa.core.RoutaFactory
import com.phodal.routa.core.RoutaSystem
import com.phodal.routa.core.coordinator.CoordinationState
import com.phodal.routa.core.event.AgentEvent
import com.phodal.routa.core.model.AgentRole
import com.phodal.routa.core.model.AgentStatus
import com.phodal.routa.core.provider.AgentProvider
import com.phodal.routa.core.provider.StreamChunk
import com.phodal.routa.core.role.RouteDefinitions
import com.phodal.routa.core.runner.OrchestratorPhase
import com.phodal.routa.core.runner.OrchestratorResult
import com.phodal.routa.core.runner.RoutaOrchestrator
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/**
 * Platform-agnostic ViewModel for the Routa multi-agent orchestrator.
 *
 * Encapsulates all orchestration state and business logic, providing observable
 * [StateFlow] / [SharedFlow] properties for UI binding. Shared between:
 * - **CLI** — [com.phodal.routa.core.cli.RoutaCli]
 * - **IntelliJ Plugin** — `IdeaRoutaService` / `DispatcherPanel`
 * - **Tests** — unit & integration tests
 *
 * ## Key Design Decision: TaskId-keyed State
 * [crafterStates] is keyed by **taskId** (not agentId). This allows the UI to
 * show all planned tasks as tabs immediately after ROUTA planning, even before
 * agents are assigned. Agent IDs are stored inside each [CrafterStreamState].
 *
 * ## Typical Usage
 * ```kotlin
 * val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
 * val vm = RoutaViewModel(scope)
 *
 * // Build a provider (e.g., via RoutaFactory.createProvider)
 * val provider = RoutaFactory.createProvider(...)
 * vm.initialize(provider, "my-workspace")
 *
 * // Observe state
 * launch { vm.phase.collect { phase -> println("Phase: $phase") } }
 * launch { vm.crafterStates.collect { states -> updateUI(states) } }
 *
 * // Execute
 * val result = vm.execute("Add user authentication")
 * ```
 *
 * @param scope The coroutine scope for background work. The caller owns the scope's lifecycle.
 */
class RoutaViewModel(
    private val scope: CoroutineScope,
) {

    // ── Observable State ────────────────────────────────────────────────

    private val _phase = MutableStateFlow<OrchestratorPhase>(OrchestratorPhase.Initializing)
    /** Current orchestration phase (ROUTA planning, CRAFTER running, GATE verifying, etc.). */
    val phase: StateFlow<OrchestratorPhase> = _phase.asStateFlow()

    private val _routaChunks = MutableSharedFlow<StreamChunk>(extraBufferCapacity = 512)
    /** Streaming chunks from the ROUTA (planning) agent. */
    val routaChunks: SharedFlow<StreamChunk> = _routaChunks.asSharedFlow()

    private val _gateChunks = MutableSharedFlow<StreamChunk>(extraBufferCapacity = 512)
    /** Streaming chunks from the GATE (verification) agent. */
    val gateChunks: SharedFlow<StreamChunk> = _gateChunks.asSharedFlow()

    private val _crafterChunks = MutableSharedFlow<Pair<String, StreamChunk>>(extraBufferCapacity = 512)
    /**
     * Streaming chunks from CRAFTER agents, keyed by **taskId**.
     *
     * The first element of the pair is the taskId (not agentId).
     * This matches the key used in [crafterStates].
     */
    val crafterChunks: SharedFlow<Pair<String, StreamChunk>> = _crafterChunks.asSharedFlow()

    private val _crafterStates = MutableStateFlow<Map<String, CrafterStreamState>>(emptyMap())
    /**
     * Per-task CRAFTER streaming state for UI rendering.
     *
     * **Keyed by taskId** — all planned tasks appear immediately after ROUTA planning,
     * with PENDING status. Status updates to ACTIVE when an agent starts, COMPLETED when done.
     * This allows the UI to show all task tabs from the start.
     */
    val crafterStates: StateFlow<Map<String, CrafterStreamState>> = _crafterStates.asStateFlow()

    private val _events = MutableSharedFlow<AgentEvent>(extraBufferCapacity = 256)
    /** All agent events from the event bus (for detailed logging / debugging). */
    val events: SharedFlow<AgentEvent> = _events.asSharedFlow()

    private val _isRunning = MutableStateFlow(false)
    /** Whether an orchestration is currently running. */
    val isRunning: StateFlow<Boolean> = _isRunning.asStateFlow()

    private val _result = MutableStateFlow<OrchestratorResult?>(null)
    /** The result of the last orchestration (null if none has completed). */
    val result: StateFlow<OrchestratorResult?> = _result.asStateFlow()

    // ── Debug Log ───────────────────────────────────────────────────────

    /** Structured debug log for tracing orchestration flow. */
    val debugLog = RoutaDebugLog()

    // ── Internal State ──────────────────────────────────────────────────

    private var routaSystem: RoutaSystem? = null
    private var orchestrator: RoutaOrchestrator? = null
    private var provider: AgentProvider? = null
    private var eventListenerJob: Job? = null

    /** The currently running execution job — used for cancellation on stop. */
    private var executionJob: Job? = null

    /** Track agentId → role for routing stream chunks AND for stop-all. */
    private val agentRoleMap = mutableMapOf<String, AgentRole>()

    /** Track agentId → taskId mapping (CRAFTER agents only). */
    private val crafterTaskMap = mutableMapOf<String, String>()

    /** Track taskId → task title. */
    private val taskTitleMap = mutableMapOf<String, String>()

    /** Lock for thread-safe updates to _crafterStates. */
    private val crafterStateLock = Any()

    // ── Configuration ───────────────────────────────────────────────────

    /**
     * Whether to prepend ROUTA system prompt to user requests.
     *
     * When `true` (default), the ROUTA coordinator instructions from [RouteDefinitions.ROUTA]
     * are prepended to user input, providing context for ACP-based ROUTA agents.
     * Set to `false` when using Koog (which injects system prompt via its own mechanism)
     * or when the prompt is already enhanced.
     */
    var useEnhancedRoutaPrompt: Boolean = true

    // ── Public API ──────────────────────────────────────────────────────

    /**
     * Get the underlying [RoutaSystem] for advanced usage (e.g., accessing stores, event bus).
     * Returns `null` if not initialized.
     */
    val system: RoutaSystem? get() = routaSystem

    /**
     * Get the coordination state from the underlying [com.phodal.routa.core.coordinator.RoutaCoordinator].
     *
     * Useful for observing the low-level coordination phase (IDLE, PLANNING, EXECUTING, etc.)
     * and tracking which agents are active.
     */
    val coordinationState: StateFlow<CoordinationState>
        get() = routaSystem?.coordinator?.coordinationState ?: MutableStateFlow(CoordinationState())

    /**
     * Check if the ViewModel has been initialized with a provider.
     */
    fun isInitialized(): Boolean = orchestrator != null

    /**
     * Initialize the ViewModel with a provider and workspace.
     *
     * Creates the underlying [RoutaSystem] and [RoutaOrchestrator], and starts
     * listening for events from the event bus.
     *
     * If a [RoutaSystem] is provided, it is used directly (useful when the provider
     * needs to share the same system, e.g., [com.phodal.routa.core.provider.KoogAgentProvider]
     * which requires access to agent tools from the same stores). Otherwise, a fresh
     * in-memory system is created.
     *
     * @param provider The [AgentProvider] to use for execution (ACP, Koog, Claude, mock, etc.)
     * @param workspaceId The workspace identifier for this orchestration session.
     * @param system Optional pre-created [RoutaSystem]. If null, a new in-memory system is created.
     */
    fun initialize(
        provider: AgentProvider,
        workspaceId: String,
        system: RoutaSystem? = null,
    ) {
        // Clean up any previous session
        resetInternal()

        this.provider = provider

        val routaSys = system ?: RoutaFactory.createInMemory(scope)
        routaSystem = routaSys

        orchestrator = RoutaOrchestrator(
            routa = routaSys,
            runner = provider,
            workspaceId = workspaceId,
            onPhaseChange = { phase -> handlePhaseChange(phase) },
            onStreamChunk = { agentId, chunk -> handleStreamChunk(agentId, chunk) },
        )

        // Listen to events from the event bus
        eventListenerJob = scope.launch {
            routaSys.eventBus.events.collect { event ->
                handleEvent(event)
                _events.tryEmit(event)
            }
        }

        debugLog.log(DebugCategory.PHASE, "ViewModel initialized", mapOf(
            "workspaceId" to workspaceId,
            "provider" to provider.capabilities().name,
            "systemProvided" to (system != null).toString(),
        ))
    }

    /**
     * Execute a user request through the full ROUTA → CRAFTER → GATE pipeline.
     *
     * This is a suspending function that runs the complete orchestration flow:
     * 1. ROUTA plans tasks from the user request
     * 2. CRAFTERs execute the planned tasks
     * 3. GATE verifies all completed work
     * 4. If not approved, CRAFTERs retry (up to max waves)
     *
     * State changes are observable via [phase], [crafterStates], [routaChunks], etc.
     *
     * @param userRequest The user's task description / requirement.
     * @return The [OrchestratorResult] indicating success, failure, or other outcomes.
     * @throws IllegalStateException if not initialized.
     */
    suspend fun execute(userRequest: String): OrchestratorResult {
        val orch = orchestrator
            ?: throw IllegalStateException("ViewModel not initialized. Call initialize() first.")

        _isRunning.value = true
        _result.value = null
        _crafterStates.value = emptyMap()
        agentRoleMap.clear()
        crafterTaskMap.clear()
        taskTitleMap.clear()

        val request = if (useEnhancedRoutaPrompt) {
            buildRoutaEnhancedPrompt(userRequest)
        } else {
            userRequest
        }

        debugLog.log(DebugCategory.PHASE, "Execution starting", mapOf(
            "userRequest" to userRequest.take(200),
            "enhancedPrompt" to useEnhancedRoutaPrompt.toString(),
        ))

        return try {
            // Store the execution as a job so stopExecution() can cancel it
            val result = coroutineScope {
                val job = async { orch.execute(request) }
                executionJob = job
                job.await()
            }

            debugLog.log(DebugCategory.PHASE, "Execution completed", mapOf(
                "result" to result::class.simpleName.orEmpty(),
            ))

            _result.value = result
            result
        } catch (e: CancellationException) {
            debugLog.log(DebugCategory.STOP, "Execution cancelled by user")
            val cancelledResult = OrchestratorResult.Failed("Execution cancelled by user")
            _result.value = cancelledResult
            cancelledResult
        } catch (e: Exception) {
            debugLog.log(DebugCategory.ERROR, "Execution failed: ${e.message}")
            val failedResult = OrchestratorResult.Failed(e.message ?: "Unknown error")
            _result.value = failedResult
            failedResult
        } finally {
            executionJob = null
            _isRunning.value = false
        }
    }

    /**
     * Stop all running agents and cancel the current execution.
     *
     * This method:
     * 1. Cancels the running execution coroutine (stops the orchestrator loop)
     * 2. Interrupts ALL known agents (ROUTA, CRAFTERs, GATE — not just active CRAFTERs)
     * 3. Updates crafter states to CANCELLED
     */
    fun stopExecution() {
        if (!_isRunning.value) return

        debugLog.log(DebugCategory.STOP, "Stop requested", mapOf(
            "knownAgents" to agentRoleMap.size.toString(),
            "activeCrafters" to _crafterStates.value.count { it.value.status == AgentStatus.ACTIVE }.toString(),
        ))

        // 1. Cancel the execution coroutine FIRST (stops the orchestrator loop)
        executionJob?.cancel()
        executionJob = null

        // 2. Interrupt ALL known agents (not just crafters with ACTIVE status)
        scope.launch {
            val currentProvider = provider ?: return@launch

            for ((agentId, role) in agentRoleMap.toMap()) {
                try {
                    currentProvider.interrupt(agentId)
                    debugLog.log(DebugCategory.STOP, "Interrupted agent", mapOf(
                        "agentId" to agentId.take(8),
                        "role" to role.name,
                    ))
                } catch (e: Exception) {
                    debugLog.log(DebugCategory.STOP, "Failed to interrupt agent: ${e.message}", mapOf(
                        "agentId" to agentId.take(8),
                        "role" to role.name,
                    ))
                }
            }

            // 3. Mark all non-completed tasks as CANCELLED
            val currentStates = _crafterStates.value
            for ((taskId, state) in currentStates) {
                if (state.status == AgentStatus.ACTIVE || state.status == AgentStatus.PENDING) {
                    updateCrafterState(taskId) { current ->
                        current.copy(status = AgentStatus.CANCELLED)
                    }
                }
            }

            _isRunning.value = false
        }
    }

    /**
     * Stop a specific CRAFTER agent by its agent ID.
     *
     * @param agentId The agent ID to interrupt (looked up from [CrafterStreamState.agentId]).
     */
    fun stopCrafter(agentId: String) {
        val taskId = crafterTaskMap[agentId]
        debugLog.log(DebugCategory.STOP, "Stopping single crafter", mapOf(
            "agentId" to agentId.take(8),
            "taskId" to (taskId?.take(8) ?: "unknown"),
        ))
        scope.launch {
            try {
                provider?.interrupt(agentId)
                if (taskId != null) {
                    updateCrafterState(taskId) { current ->
                        current.copy(status = AgentStatus.CANCELLED)
                    }
                }
            } catch (_: Exception) {
                // Best effort
            }
        }
    }

    /**
     * Reset the ViewModel, cleaning up all internal resources.
     *
     * Does NOT shut down the provider — the provider lifecycle is managed externally.
     * After reset, [isInitialized] returns `false` and a new [initialize] call is needed.
     */
    fun reset() {
        resetInternal()
    }

    /**
     * Dispose the ViewModel. Call when the owning component is destroyed.
     * Equivalent to [reset].
     */
    fun dispose() {
        resetInternal()
    }

    // ── Internal reset ──────────────────────────────────────────────────

    private fun resetInternal() {
        // Cancel any running execution
        executionJob?.cancel()
        executionJob = null

        eventListenerJob?.cancel()
        eventListenerJob = null

        routaSystem?.coordinator?.reset()
        routaSystem = null
        orchestrator = null
        // Don't shutdown provider — it's externally managed
        provider = null

        _phase.value = OrchestratorPhase.Initializing
        _crafterStates.value = emptyMap()
        _isRunning.value = false
        _result.value = null
        agentRoleMap.clear()
        crafterTaskMap.clear()
        taskTitleMap.clear()
    }

    // ── Event Handlers ──────────────────────────────────────────────────

    private suspend fun handlePhaseChange(phase: OrchestratorPhase) {
        _phase.value = phase

        when (phase) {
            is OrchestratorPhase.Initializing -> {
                debugLog.log(DebugCategory.PHASE, "Phase: Initializing")
            }

            is OrchestratorPhase.Planning -> {
                debugLog.log(DebugCategory.PHASE, "Phase: Planning (ROUTA starting)")
            }

            is OrchestratorPhase.PlanReady -> {
                debugLog.log(DebugCategory.PLAN, "ROUTA plan output", mapOf(
                    "outputLength" to phase.planOutput.length.toString(),
                    "preview" to phase.planOutput.take(500),
                ))
            }

            is OrchestratorPhase.TasksRegistered -> {
                debugLog.log(DebugCategory.TASK, "Tasks registered", mapOf(
                    "count" to phase.count.toString(),
                ))

                // ── Pre-populate all tasks as PENDING tabs ──
                // This is the key: by populating crafterStates here, the UI can
                // show all planned tasks immediately, not just the currently running one.
                val workspaceId = routaSystem?.coordinator?.coordinationState?.value?.workspaceId ?: ""
                val allTasks = routaSystem?.context?.taskStore?.listByWorkspace(workspaceId) ?: emptyList()

                val states = mutableMapOf<String, CrafterStreamState>()
                for ((index, task) in allTasks.withIndex()) {
                    states[task.id] = CrafterStreamState(
                        agentId = "",  // no agent assigned yet
                        taskId = task.id,
                        taskTitle = task.title,
                        status = AgentStatus.PENDING,
                    )
                    taskTitleMap[task.id] = task.title

                    debugLog.log(DebugCategory.TASK, "Task #${index + 1} planned", mapOf(
                        "taskId" to task.id.take(8),
                        "title" to task.title,
                        "objective" to task.objective.take(200),
                    ))
                }
                _crafterStates.value = states
            }

            is OrchestratorPhase.WaveStarting -> {
                debugLog.log(DebugCategory.PHASE, "Wave starting", mapOf(
                    "wave" to phase.wave.toString(),
                ))
            }

            is OrchestratorPhase.CrafterRunning -> {
                val taskId = phase.taskId
                agentRoleMap[phase.crafterId] = AgentRole.CRAFTER
                crafterTaskMap[phase.crafterId] = taskId

                // Resolve task title (should already be in taskTitleMap from TasksRegistered)
                val title = taskTitleMap[taskId] ?: run {
                    val task = routaSystem?.context?.taskStore?.get(taskId)
                    val resolved = task?.title?.takeIf { it.isNotBlank() }
                        ?: "Task ${taskId.take(8)}..."
                    taskTitleMap[taskId] = resolved
                    resolved
                }

                debugLog.log(DebugCategory.AGENT, "CRAFTER running", mapOf(
                    "crafterId" to phase.crafterId.take(8),
                    "taskId" to taskId.take(8),
                    "taskTitle" to title,
                ))

                // Update the PENDING entry to ACTIVE with the assigned agentId
                updateCrafterState(taskId) { current ->
                    current.copy(
                        agentId = phase.crafterId,
                        taskTitle = title,
                        status = AgentStatus.ACTIVE,
                    )
                }
            }

            is OrchestratorPhase.CrafterCompleted -> {
                val taskId = phase.taskId

                debugLog.log(DebugCategory.AGENT, "CRAFTER completed", mapOf(
                    "crafterId" to phase.crafterId.take(8),
                    "taskId" to taskId.take(8),
                ))

                updateCrafterState(taskId) { current ->
                    current.copy(status = AgentStatus.COMPLETED)
                }
            }

            is OrchestratorPhase.VerificationStarting -> {
                debugLog.log(DebugCategory.PHASE, "GATE verification starting", mapOf(
                    "wave" to phase.wave.toString(),
                ))
            }

            is OrchestratorPhase.VerificationCompleted -> {
                debugLog.log(DebugCategory.PHASE, "GATE verification completed", mapOf(
                    "gateId" to phase.gateId.take(8),
                    "outputPreview" to phase.output.take(300),
                ))
            }

            is OrchestratorPhase.NeedsFix -> {
                debugLog.log(DebugCategory.PHASE, "Wave needs fix, retrying", mapOf(
                    "wave" to phase.wave.toString(),
                ))
            }

            is OrchestratorPhase.Completed -> {
                debugLog.log(DebugCategory.PHASE, "Orchestration completed successfully")
            }

            is OrchestratorPhase.MaxWavesReached -> {
                debugLog.log(DebugCategory.PHASE, "Max waves reached", mapOf(
                    "waves" to phase.waves.toString(),
                ))
            }
        }
    }

    private fun handleStreamChunk(agentId: String, chunk: StreamChunk) {
        // Determine the agent's role for routing
        val role = agentRoleMap[agentId] ?: run {
            val state = routaSystem?.coordinator?.coordinationState?.value
            when {
                state?.routaAgentId == agentId -> {
                    agentRoleMap[agentId] = AgentRole.ROUTA
                    debugLog.log(DebugCategory.AGENT, "Identified agent as ROUTA", mapOf(
                        "agentId" to agentId.take(8),
                    ))
                    AgentRole.ROUTA
                }
                else -> null
            }
        }

        when (role) {
            AgentRole.ROUTA -> _routaChunks.tryEmit(chunk)
            AgentRole.GATE -> _gateChunks.tryEmit(chunk)
            AgentRole.CRAFTER -> {
                // Look up taskId for this agent
                val taskId = crafterTaskMap[agentId]
                if (taskId == null) {
                    debugLog.log(DebugCategory.STREAM, "CRAFTER chunk but no taskId mapping", mapOf(
                        "agentId" to agentId.take(8),
                    ))
                    return
                }

                // Emit chunk keyed by taskId for UI
                _crafterChunks.tryEmit(taskId to chunk)

                // Also update accumulated state
                updateCrafterState(taskId) { current ->
                    val newOutput = when (chunk) {
                        is StreamChunk.Text -> current.outputText + chunk.content
                        is StreamChunk.ToolCall -> current.outputText + "\n[${chunk.status}] ${chunk.name}"
                        is StreamChunk.Error -> current.outputText + "\n[ERROR] ${chunk.message}"
                        else -> current.outputText
                    }
                    current.copy(
                        chunks = current.chunks + chunk,
                        outputText = newOutput,
                    )
                }
            }

            null -> {
                debugLog.log(DebugCategory.STREAM, "Chunk for unknown agent, skipping", mapOf(
                    "agentId" to agentId.take(8),
                ))
            }
        }
    }

    private suspend fun handleEvent(event: AgentEvent) {
        when (event) {
            is AgentEvent.AgentCreated -> {
                val agent = routaSystem?.context?.agentStore?.get(event.agentId)
                if (agent != null) {
                    agentRoleMap[agent.id] = agent.role
                    debugLog.log(DebugCategory.AGENT, "Agent created", mapOf(
                        "agentId" to agent.id.take(8),
                        "role" to agent.role.name,
                        "name" to agent.name,
                    ))
                }
            }

            is AgentEvent.AgentStatusChanged -> {
                debugLog.log(DebugCategory.AGENT, "Agent status changed", mapOf(
                    "agentId" to event.agentId.take(8),
                    "newStatus" to event.newStatus.name,
                ))

                if (event.newStatus == AgentStatus.ERROR) {
                    val role = agentRoleMap[event.agentId]
                    if (role == AgentRole.CRAFTER) {
                        val taskId = crafterTaskMap[event.agentId]
                        if (taskId != null) {
                            updateCrafterState(taskId) { current ->
                                current.copy(status = AgentStatus.ERROR)
                            }
                        }
                    }
                }
            }

            is AgentEvent.TaskDelegated -> {
                agentRoleMap[event.agentId] = AgentRole.CRAFTER
                crafterTaskMap[event.agentId] = event.taskId

                debugLog.log(DebugCategory.TASK, "Task delegated to agent", mapOf(
                    "agentId" to event.agentId.take(8),
                    "taskId" to event.taskId.take(8),
                ))
            }

            is AgentEvent.AgentCompleted -> {
                val role = agentRoleMap[event.agentId]

                debugLog.log(DebugCategory.AGENT, "Agent completed", mapOf(
                    "agentId" to event.agentId.take(8),
                    "role" to (role?.name ?: "unknown"),
                    "taskId" to event.report.taskId.take(8),
                    "success" to event.report.success.toString(),
                ))

                if (role == AgentRole.CRAFTER) {
                    val report = event.report
                    val taskId = crafterTaskMap[event.agentId] ?: report.taskId
                    val completionChunk = StreamChunk.CompletionReport(
                        agentId = event.agentId,
                        taskId = taskId,
                        summary = report.summary,
                        filesModified = report.filesModified,
                        success = report.success,
                    )
                    _crafterChunks.tryEmit(taskId to completionChunk)
                }
            }

            else -> {
                // Other events are observable via the events SharedFlow
            }
        }
    }

    /**
     * Update a crafter state entry by **taskId**.
     * If no entry exists yet, creates a default PENDING one.
     */
    private fun updateCrafterState(taskId: String, updater: (CrafterStreamState) -> CrafterStreamState) {
        synchronized(crafterStateLock) {
            val current = _crafterStates.value.toMutableMap()
            val existing = current[taskId] ?: CrafterStreamState(
                agentId = "",
                taskId = taskId,
                taskTitle = taskTitleMap[taskId] ?: "",
            )
            current[taskId] = updater(existing)
            _crafterStates.value = current
        }
    }

    // ── Prompt Enhancement ──────────────────────────────────────────────

    private fun buildRoutaEnhancedPrompt(userRequest: String): String {
        val routaSystemPrompt = RouteDefinitions.ROUTA.systemPrompt
        val routaRoleReminder = RouteDefinitions.ROUTA.roleReminder

        return buildString {
            appendLine("# ROUTA Coordinator Instructions")
            appendLine()
            appendLine(routaSystemPrompt)
            appendLine()
            appendLine("---")
            appendLine()
            appendLine("**Role Reminder:** $routaRoleReminder")
            appendLine()
            appendLine("---")
            appendLine()
            appendLine("# User Request")
            appendLine()
            appendLine(userRequest)
        }
    }
}
