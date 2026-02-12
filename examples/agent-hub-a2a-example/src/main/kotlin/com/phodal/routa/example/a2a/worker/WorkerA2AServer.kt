package com.phodal.routa.example.a2a.worker

import ai.koog.a2a.model.*
import ai.koog.a2a.server.A2AServer
import ai.koog.a2a.transport.server.jsonrpc.http.HttpJSONRPCServerTransport
import io.ktor.server.cio.*

/**
 * A2A Server for Worker agents.
 *
 * Workers receive task instructions, execute them (optionally with LLM),
 * and report results back via the Agent Hub.
 *
 * ## Prerequisites
 * - Agent Hub A2A Server must be running on port 9100 (for reporting)
 * - Optional: Ollama running locally for LLM-based work execution
 *
 * ## Usage
 * ```bash
 * # Terminal 1: Start the Agent Hub
 * ./gradlew :examples:agent-hub-a2a-example:runHubServer
 *
 * # Terminal 2: Start a Worker
 * ./gradlew :examples:agent-hub-a2a-example:runWorkerServer
 * ```
 */
const val WORKER_PORT = 9102
const val WORKER_PATH = "/worker-agent"
const val WORKER_CARD_PATH = "$WORKER_PATH/agent-card.json"

suspend fun main() {
    println("╔══════════════════════════════════════════════════╗")
    println("║    Worker Agent — A2A Server                     ║")
    println("║    Port: $WORKER_PORT                                      ║")
    println("║    Hub:  localhost:9100                           ║")
    println("╚══════════════════════════════════════════════════╝")
    println()

    val agentCard = AgentCard(
        protocolVersion = "0.3.0",
        name = "Routa Task Worker",
        description = "Worker agent that receives tasks, executes them, " +
            "and reports results back to the Agent Hub.",
        version = "0.1.0",
        url = "http://localhost:$WORKER_PORT$WORKER_PATH",
        preferredTransport = TransportProtocol.JSONRPC,
        additionalInterfaces = listOf(
            AgentInterface(
                url = "http://localhost:$WORKER_PORT$WORKER_PATH",
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
                id = "task_execution",
                name = "Task Execution",
                description = "Receives task instructions, executes them, and reports completion. " +
                    "Can handle code implementation, analysis, and verification tasks.",
                examples = listOf(
                    """{"taskId": "t1", "agentId": "a1", "instruction": "Implement user login"}""",
                    "Analyze the codebase for potential security issues",
                    "Write unit tests for the payment module",
                ),
                tags = listOf("execution", "implementation", "worker")
            )
        ),
        supportsAuthenticatedExtendedCard = false,
    )

    val agentExecutor = WorkerAgentExecutor()

    val a2aServer = A2AServer(
        agentExecutor = agentExecutor,
        agentCard = agentCard,
    )

    val transport = HttpJSONRPCServerTransport(a2aServer)

    println("✓ Worker Agent A2A Server starting on http://localhost:$WORKER_PORT$WORKER_PATH")
    println("  Agent Card: http://localhost:$WORKER_PORT$WORKER_CARD_PATH")
    println()

    transport.start(
        engineFactory = CIO,
        port = WORKER_PORT,
        path = WORKER_PATH,
        wait = true,
        agentCard = agentCard,
        agentCardPath = WORKER_CARD_PATH,
    )
}
