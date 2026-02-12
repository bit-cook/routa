package com.phodal.routa.example.a2a.planner

import ai.koog.a2a.model.*
import ai.koog.a2a.server.A2AServer
import ai.koog.a2a.transport.server.jsonrpc.http.HttpJSONRPCServerTransport
import io.ktor.server.cio.*

/**
 * A2A Server for the Planner agent.
 *
 * The Planner is a Koog-based agent that receives user requirements,
 * breaks them into tasks, and delegates to workers via the Agent Hub.
 *
 * ## Prerequisites
 * - Agent Hub A2A Server must be running on port 9100
 * - Optional: Ollama running locally for LLM-based planning
 *
 * ## Usage
 * ```bash
 * # Terminal 1: Start the Agent Hub
 * ./gradlew :examples:agent-hub-a2a-example:runHubServer
 *
 * # Terminal 2: Start the Planner
 * ./gradlew :examples:agent-hub-a2a-example:runPlannerServer
 * ```
 */
const val PLANNER_PORT = 9101
const val PLANNER_PATH = "/planner-agent"
const val PLANNER_CARD_PATH = "$PLANNER_PATH/agent-card.json"

suspend fun main() {
    println("╔══════════════════════════════════════════════════╗")
    println("║    Planner Agent — A2A Server                    ║")
    println("║    Port: $PLANNER_PORT                                      ║")
    println("║    Hub:  localhost:9100                           ║")
    println("╚══════════════════════════════════════════════════╝")
    println()

    val agentCard = AgentCard(
        protocolVersion = "0.3.0",
        name = "Routa Task Planner",
        description = "AI-powered task planner that breaks requirements into tasks " +
            "and delegates to worker agents via the Agent Hub.",
        version = "0.1.0",
        url = "http://localhost:$PLANNER_PORT$PLANNER_PATH",
        preferredTransport = TransportProtocol.JSONRPC,
        additionalInterfaces = listOf(
            AgentInterface(
                url = "http://localhost:$PLANNER_PORT$PLANNER_PATH",
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
                id = "task_planning",
                name = "Task Planning & Delegation",
                description = "Analyzes user requirements, creates a task plan, " +
                    "spawns worker agents, and delegates work via the Agent Hub.",
                examples = listOf(
                    "Create a REST API with user authentication",
                    "Refactor the database layer to use connection pooling",
                    "Add unit tests for the payment module",
                ),
                tags = listOf("planning", "delegation", "multi-agent")
            )
        ),
        supportsAuthenticatedExtendedCard = false,
    )

    val agentExecutor = PlannerAgentExecutor()

    val a2aServer = A2AServer(
        agentExecutor = agentExecutor,
        agentCard = agentCard,
    )

    val transport = HttpJSONRPCServerTransport(a2aServer)

    println("✓ Planner Agent A2A Server starting on http://localhost:$PLANNER_PORT$PLANNER_PATH")
    println("  Agent Card: http://localhost:$PLANNER_PORT$PLANNER_CARD_PATH")
    println()

    transport.start(
        engineFactory = CIO,
        port = PLANNER_PORT,
        path = PLANNER_PATH,
        wait = true,
        agentCard = agentCard,
        agentCardPath = PLANNER_CARD_PATH,
    )
}
