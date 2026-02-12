package com.phodal.routa.example.a2a.planner

import ai.koog.a2a.client.A2AClient
import ai.koog.a2a.client.UrlAgentCardResolver
import ai.koog.a2a.model.*
import ai.koog.a2a.server.agent.AgentExecutor
import ai.koog.a2a.server.session.RequestContext
import ai.koog.a2a.server.session.SessionEventProcessor
import ai.koog.a2a.transport.Request
import ai.koog.a2a.transport.client.jsonrpc.http.HttpJSONRPCClientTransport
import ai.koog.agents.a2a.core.toKoogMessage
import com.phodal.routa.core.config.RoutaConfigLoader
import com.phodal.routa.core.koog.RoutaAgentFactory
import com.phodal.routa.example.a2a.hub.HUB_CARD_PATH
import com.phodal.routa.example.a2a.hub.HUB_PATH
import com.phodal.routa.example.a2a.hub.HUB_PORT
import kotlinx.serialization.json.*
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * A2A AgentExecutor for the Planner agent.
 *
 * The Planner receives user requirements via A2A, breaks them into tasks,
 * and delegates work to the Agent Hub (which manages worker agents).
 *
 * It uses a local LLM (via Koog prompt executor, e.g., Ollama) to generate plans,
 * then communicates with the Agent Hub A2A server to:
 * 1. Create worker agents
 * 2. Create tasks
 * 3. Delegate tasks to workers
 * 4. Monitor progress
 * 5. Collect reports
 *
 * ## Flow
 * ```
 * User → Planner (A2A) → Agent Hub (A2A) → Worker Agents
 *                         ↕ RoutaSystem
 * ```
 */
class PlannerAgentExecutor : AgentExecutor {

    @OptIn(ExperimentalUuidApi::class)
    override suspend fun execute(
        context: RequestContext<MessageSendParams>,
        eventProcessor: SessionEventProcessor
    ) {
        val userMessage = context.params.message
        val userText = userMessage.parts
            .filterIsInstance<TextPart>()
            .joinToString("\n") { it.text }

        val steps = mutableListOf<String>()

        var hubTransport: HttpJSONRPCClientTransport? = null

        try {
            // Step 1: Connect to Agent Hub
            steps.add("Connecting to Agent Hub at localhost:$HUB_PORT...")
            val (client, transport) = createHubClient()
            hubTransport = transport
            client.connect()
            val hubCard = client.cachedAgentCard()
            val hubClient = client
            steps.add("✓ Connected to: ${hubCard.name}")

            // Step 2: Initialize workspace
            steps.add("\nInitializing workspace...")
            val initResult = sendHubCommand(hubClient, context.contextId, buildJsonObject {
                put("command", "initialize")
            })
            val initJson = Json.parseToJsonElement(initResult).jsonObject
            val routaId = initJson["routaAgentId"]?.jsonPrimitive?.content ?: "unknown"
            steps.add("✓ Workspace initialized (routa: ${routaId.take(8)}...)")

            // Step 3: Use LLM to plan tasks (or use simple heuristic for demo)
            steps.add("\nPlanning tasks for: $userText")
            val tasks = planTasks(userText)
            steps.add("✓ Planned ${tasks.size} task(s)")

            // Step 4: Create worker agents and tasks via Hub
            for ((index, task) in tasks.withIndex()) {
                val workerName = "worker-${index + 1}"
                steps.add("\n--- Task ${index + 1}: ${task.first} ---")

                // Create worker agent
                val createResult = sendHubCommand(hubClient, context.contextId, buildJsonObject {
                    put("command", "create_agent")
                    put("name", workerName)
                    put("role", "CRAFTER")
                    put("parentId", routaId)
                })
                val agentJson = Json.parseToJsonElement(createResult).jsonObject
                val workerId = agentJson["id"]?.jsonPrimitive?.content ?: "unknown"
                steps.add("  ✓ Created agent: $workerName ($workerId)")

                // Create task
                val taskId = "task-${index + 1}-${System.currentTimeMillis()}"
                sendHubCommand(hubClient, context.contextId, buildJsonObject {
                    put("command", "create_task")
                    put("taskId", taskId)
                    put("title", task.first)
                    put("objective", task.second)
                })
                steps.add("  ✓ Created task: $taskId")

                // Delegate task to worker
                sendHubCommand(hubClient, context.contextId, buildJsonObject {
                    put("command", "delegate_task")
                    put("agentId", workerId)
                    put("taskId", taskId)
                    put("callerAgentId", routaId)
                })
                steps.add("  ✓ Delegated task to $workerName")

                // Simulate work completion by reporting
                sendHubCommand(hubClient, context.contextId, buildJsonObject {
                    put("command", "report_to_parent")
                    put("agentId", workerId)
                    put("taskId", taskId)
                    put("summary", "Completed: ${task.first}")
                    put("success", true)
                    putJsonArray("filesModified") { add("example.kt") }
                })
                steps.add("  ✓ Worker reported completion")
            }

            // Step 5: List all agents to verify
            steps.add("\n--- Final Agent Roster ---")
            val listResult = sendHubCommand(hubClient, context.contextId, buildJsonObject {
                put("command", "list_agents")
            })
            steps.add(listResult)

            steps.add("\n✅ Planning and delegation complete!")

        } catch (e: Exception) {
            steps.add("\n✗ Error: ${e.message}")
        } finally {
            hubTransport?.close()
        }

        // Send consolidated result
        val responseMessage = Message(
            messageId = Uuid.random().toString(),
            role = Role.Agent,
            parts = listOf(TextPart(steps.joinToString("\n"))),
            contextId = context.contextId,
        )
        eventProcessor.sendMessage(responseMessage)
    }

    /**
     * Plan tasks from user input.
     *
     * In a real scenario, this would use a Koog agent with LLM to generate a plan.
     * For the demo, we use simple heuristic parsing.
     *
     * Returns list of (title, objective) pairs.
     */
    private suspend fun planTasks(userInput: String): List<Pair<String, String>> {
        // Try to use LLM if config is available
        val config = RoutaConfigLoader.getActiveModelConfig()
        if (config != null) {
            return planWithLLM(userInput, config)
        }

        // Fallback: simple heuristic
        return listOf(
            "Analyze requirements" to "Analyze the user requirement: $userInput",
            "Implement solution" to "Implement the solution for: $userInput",
            "Verify results" to "Verify that the implementation meets: $userInput",
        )
    }

    /**
     * Use Koog LLM to generate a task plan.
     */
    private suspend fun planWithLLM(
        userInput: String,
        config: com.phodal.routa.core.config.NamedModelConfig
    ): List<Pair<String, String>> {
        return try {
            val executor = RoutaAgentFactory.createExecutor(config)
            val model = RoutaAgentFactory.createModel(config)

            val prompt = ai.koog.prompt.dsl.prompt("task-planning") {
                system {
                    +"""You are a task planner. Break the user's requirement into 2-4 concrete tasks.
                       |Respond with a JSON array of objects with "title" and "objective" fields.
                       |Example: [{"title": "Setup database", "objective": "Create PostgreSQL schema"}]
                       |Only output the JSON array, nothing else.""".trimMargin()
                }
                user {
                    +userInput
                }
            }

            val result = executor.execute(prompt, model)
            val responseText = result.firstOrNull()?.content ?: return fallbackPlan(userInput)

            // Parse LLM output as JSON
            val jsonArray = Json.parseToJsonElement(responseText).jsonArray
            jsonArray.map { element ->
                val obj = element.jsonObject
                val title = obj["title"]?.jsonPrimitive?.content ?: "Task"
                val objective = obj["objective"]?.jsonPrimitive?.content ?: userInput
                title to objective
            }
        } catch (e: Exception) {
            println("  ⚠ LLM planning failed (${e.message}), using heuristic")
            fallbackPlan(userInput)
        }
    }

    private fun fallbackPlan(userInput: String): List<Pair<String, String>> {
        return listOf(
            "Analyze requirements" to "Analyze the user requirement: $userInput",
            "Implement solution" to "Implement the solution for: $userInput",
            "Verify results" to "Verify that the implementation meets: $userInput",
        )
    }

    private fun createHubClient(): Pair<A2AClient, HttpJSONRPCClientTransport> {
        val transport = HttpJSONRPCClientTransport(
            url = "http://localhost:$HUB_PORT$HUB_PATH"
        )
        val resolver = UrlAgentCardResolver(
            baseUrl = "http://localhost:$HUB_PORT",
            path = HUB_CARD_PATH,
        )
        return A2AClient(
            transport = transport,
            agentCardResolver = resolver,
        ) to transport
    }

    @OptIn(ExperimentalUuidApi::class)
    private suspend fun sendHubCommand(
        client: A2AClient,
        contextId: String,
        command: JsonObject
    ): String {
        val message = Message(
            messageId = Uuid.random().toString(),
            role = Role.User,
            parts = listOf(TextPart(command.toString())),
            contextId = contextId,
        )
        val response = client.sendMessage(Request(MessageSendParams(message = message)))
        val reply = response.data as? Message ?: return "Error: unexpected response type"
        return reply.parts.filterIsInstance<TextPart>().joinToString("") { it.text }
    }
}
