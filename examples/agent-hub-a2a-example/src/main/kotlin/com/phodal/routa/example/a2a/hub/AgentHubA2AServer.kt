package com.phodal.routa.example.a2a.hub

import ai.koog.a2a.model.*
import ai.koog.a2a.server.A2AServer
import ai.koog.a2a.transport.server.jsonrpc.http.HttpJSONRPCServerTransport
import com.phodal.routa.core.RoutaFactory
import com.phodal.routa.core.RoutaSystem
import io.ktor.server.cio.*
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * A2A Server that exposes routa-agent-hub functionality via the Agent-to-Agent protocol.
 *
 * This server wraps [AgentHubExecutor] to provide agent lifecycle management
 * (create, list, delegate, report, message, subscribe) through A2A.
 *
 * Any A2A-compatible client can connect and manage agents through this server.
 *
 * ## Architecture
 * ```
 * A2A Client (Planner/Worker/CLI)
 *   ↕ A2A Protocol (JSON-RPC over HTTP)
 * AgentHubA2AServer
 *   ↕ AgentHubExecutor
 * RoutaSystem (AgentTools, Stores, Coordinator)
 * ```
 *
 * ## Usage
 * ```bash
 * ./gradlew :examples:agent-hub-a2a-example:runHubServer
 * ```
 */
const val HUB_PORT = 9100
const val HUB_PATH = "/agent-hub"
const val HUB_CARD_PATH = "$HUB_PATH/agent-card.json"

fun createAgentCard(port: Int = HUB_PORT): AgentCard {
    return AgentCard(
        protocolVersion = "0.3.0",
        name = "Routa Agent Hub",
        description = "Agent lifecycle management hub — create, coordinate, and monitor agents " +
            "in a multi-agent workspace via JSON commands over A2A.",
        version = "0.1.0",
        url = "http://localhost:$port$HUB_PATH",
        preferredTransport = TransportProtocol.JSONRPC,
        additionalInterfaces = listOf(
            AgentInterface(
                url = "http://localhost:$port$HUB_PATH",
                transport = TransportProtocol.JSONRPC,
            )
        ),
        capabilities = AgentCapabilities(
            streaming = false,
            pushNotifications = false,
            stateTransitionHistory = false,
        ),
        defaultInputModes = listOf("text"),
        defaultOutputModes = listOf("text"),
        skills = listOf(
            AgentSkill(
                id = "agent_lifecycle",
                name = "Agent Lifecycle Management",
                description = "Create, list, query status, and manage agents in a workspace",
                examples = listOf(
                    """{"command": "initialize"}""",
                    """{"command": "create_agent", "name": "worker-1", "role": "CRAFTER"}""",
                    """{"command": "list_agents"}""",
                ),
                tags = listOf("agent", "management", "lifecycle")
            ),
            AgentSkill(
                id = "task_delegation",
                name = "Task Delegation & Coordination",
                description = "Delegate tasks to agents, send messages, and report completion",
                examples = listOf(
                    """{"command": "delegate_task", "agentId": "...", "taskId": "...", "callerAgentId": "..."}""",
                    """{"command": "report_to_parent", "agentId": "...", "taskId": "...", "summary": "Done"}""",
                ),
                tags = listOf("task", "delegation", "coordination")
            ),
            AgentSkill(
                id = "event_subscription",
                name = "Event Subscription",
                description = "Subscribe to and monitor workspace events",
                examples = listOf(
                    """{"command": "subscribe_to_events", "agentId": "...", "eventTypes": ["agent:*"]}""",
                ),
                tags = listOf("events", "subscription", "monitoring")
            )
        ),
        supportsAuthenticatedExtendedCard = false,
    )
}

/**
 * Create an A2AServer backed by a RoutaSystem.
 */
fun createHubA2AServer(
    system: RoutaSystem? = null,
    workspaceId: String = "a2a-workspace",
    port: Int = HUB_PORT,
): Pair<A2AServer, RoutaSystem> {
    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    val routa = system ?: RoutaFactory.createInMemory(scope)
    val executor = AgentHubExecutor(routa, workspaceId)
    val agentCard = createAgentCard(port)

    val a2aServer = A2AServer(
        agentExecutor = executor,
        agentCard = agentCard,
    )

    return a2aServer to routa
}

/**
 * Standalone entry point: starts the Agent Hub A2A server on port [HUB_PORT].
 */
suspend fun main() {
    println("╔══════════════════════════════════════════════════╗")
    println("║    Routa Agent Hub — A2A Server                  ║")
    println("║    Port: $HUB_PORT                                      ║")
    println("║    Path: $HUB_PATH                           ║")
    println("╚══════════════════════════════════════════════════╝")
    println()

    val (a2aServer, routa) = createHubA2AServer()

    // Initialize the workspace
    val routaId = routa.coordinator.initialize("a2a-workspace")
    println("✓ Workspace initialized (routa agent: $routaId)")

    val agentCard = createAgentCard()
    val transport = HttpJSONRPCServerTransport(a2aServer)

    println("✓ Agent Hub A2A Server starting on http://localhost:$HUB_PORT$HUB_PATH")
    println("  Agent Card: http://localhost:$HUB_PORT$HUB_CARD_PATH")
    println()
    println("Send JSON commands via A2A, e.g.:")
    println("""  {"command": "list_agents"}""")
    println("""  {"command": "create_agent", "name": "worker-1", "role": "CRAFTER", "parentId": "$routaId"}""")
    println()

    transport.start(
        engineFactory = CIO,
        port = HUB_PORT,
        path = HUB_PATH,
        wait = true,
        agentCard = agentCard,
        agentCardPath = HUB_CARD_PATH,
    )
}
